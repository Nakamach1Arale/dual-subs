package io.github.dualsubs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A draggable floating subtitle box drawn over every app (TYPE_APPLICATION_OVERLAY).
 *
 * Retention: up to [displayLines] sentences are shown. A sentence is never pushed out
 * before its translation has arrived and been on screen for [MIN_READ_MS], and it
 * disappears [holdSeconds] after its translation arrived (0 = only when pushed out).
 *
 * All methods must be called on the main thread.
 */
class SubtitleOverlay(
    private val ctx: Context,
    private var showOriginal: Boolean,
    private val displayLines: Int,
    private val holdSeconds: Int,
    private var fontScale: Float,
    /** Kana readings above kanji in the original text (Japanese only). */
    private var furigana: Boolean,
    /** Spoken-language code; furigana is only offered for "ja" and auto-detect (""). */
    private val sourceLang: String,
    private val onStopRequested: () -> Unit,
) {
    class Line(val original: String) {
        var zh: String? = null
            internal set
        /** When the translation arrived (elapsedRealtime), or 0 while still translating. */
        internal var settledAt = 0L
        /** [original] with furigana spans, built once the dictionary is loaded. */
        internal var annotated: CharSequence? = null
    }

    private val furiganaAvailable = sourceLang == "ja" || sourceLang == ""

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<Line>()
    private var liveText = ""
    private var status: String? = null
    private var everShowedSpeech = false

    private val root = LinearLayout(ctx)
    private val body = LinearLayout(ctx)
    private val toolbar = LinearLayout(ctx)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(48)
    }
    private var attached = false

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            if (prune()) render()
            main.postDelayed(this, 1000)
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(4), dp(12), dp(8))
        root.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(170, 0, 0, 0))
        }

        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.END
        toolbar.addView(toolButton("A−") { setFontScale(fontScale - 0.1f) })
        toolbar.addView(toolButton("A+") { setFontScale(fontScale + 0.1f) })
        toolbar.addView(toolButton("原文") { showOriginal = !showOriginal; render() })
        if (furiganaAvailable) {
            toolbar.addView(toolButton("注音") {
                furigana = !furigana
                Prefs.saveFurigana(ctx, furigana)
                if (furigana) loadFurigana()
                render()
            })
        }
        toolbar.addView(toolButton("清空") { lines.clear(); liveText = ""; render() })
        toolbar.addView(toolButton("✕") { onStopRequested() })
        toolbar.visibility = View.GONE

        body.orientation = LinearLayout.VERTICAL
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        installDragAndTap()
    }

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
        if (furigana && furiganaAvailable) loadFurigana()
        render()
        main.postDelayed(tick, 1000)
    }

    /** Loads the furigana dictionary in the background and redraws once it is ready. */
    private fun loadFurigana() {
        Furigana.warmUp { main.post { render() } }
    }

    /** The original text, with furigana when it is on and the text is Japanese. */
    private fun original(text: String, line: Line? = null): CharSequence {
        if (!furigana || !furiganaAvailable) return text
        if (sourceLang == "" && !Furigana.containsKana(text)) return text  // auto-detect: skip Chinese
        line?.annotated?.let { return it }
        val annotated = Furigana.annotate(text) ?: return text  // dictionary still loading
        line?.annotated = annotated
        return annotated
    }

    fun hide() {
        if (!attached) return
        main.removeCallbacks(tick)
        runCatching { wm.removeView(root) }
        attached = false
    }

    fun setStatus(text: String?) {
        if (status == text) return
        status = text
        render()
    }

    fun setLive(committed: String, tentative: String) {
        val t = committed + tentative
        if (t == liveText) return
        liveText = t
        if (t.isNotBlank()) everShowedSpeech = true
        render()
    }

    fun addLine(original: String): Line {
        val line = Line(original)
        lines.addLast(line)
        everShowedSpeech = true
        prune()
        render()
        return line
    }

    fun updateLine(line: Line, zh: String) {
        line.zh = zh
        line.settledAt = SystemClock.elapsedRealtime()
        prune()
        render()
    }

    /** Drops sentences that have been read or have expired. Returns true if anything changed. */
    private fun prune(): Boolean {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        fun readEnough(l: Line) = l.zh != null && now - l.settledAt >= MIN_READ_MS

        // Too many lines: drop the oldest ones once they have been readable for a while.
        while (lines.size > displayLines && readEnough(lines.first())) {
            lines.removeFirst(); changed = true
        }
        // Hard cap so a stalled translator can't fill the screen.
        while (lines.size > displayLines + 2) {
            lines.removeFirst(); changed = true
        }
        // Expiry after the hold time.
        if (holdSeconds > 0) {
            val holdMs = holdSeconds * 1000L
            while (lines.isNotEmpty()) {
                val l = lines.first()
                if (l.zh != null && now - l.settledAt >= holdMs) { lines.removeFirst(); changed = true }
                else break
            }
        }
        return changed
    }

    private fun setFontScale(scale: Float) {
        fontScale = scale.coerceIn(0.6f, 2.0f)
        Prefs.saveFontScale(ctx, fontScale)
        render()
    }

    private fun render() {
        if (!attached) return
        body.removeAllViews()

        status?.let { body.addView(text(it, 13f, Color.rgb(255, 170, 120))) }

        for (line in lines) {
            if (showOriginal) body.addView(text(original(line.original, line), 15f, Color.WHITE))
            val zh = line.zh
            when {
                zh == null -> body.addView(text("…", 18f, YELLOW))
                zh.isNotEmpty() -> body.addView(text(zh, 18f, YELLOW, bold = true))
            }
        }

        if (liveText.isNotBlank()) {
            val sb = SpannableStringBuilder("▸ ").append(original(liveText))
            sb.setSpan(ForegroundColorSpan(Color.rgb(170, 170, 170)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.addView(text(sb, 14f, Color.LTGRAY))
        }

        val empty = body.childCount == 0
        if (empty && !everShowedSpeech) {
            body.addView(text("等待语音…（点按显示菜单，拖动移动位置）", 13f, Color.LTGRAY))
        }
        // Once subtitles have started, an empty box would just cover the video: hide it.
        root.visibility =
            if (empty && everShowedSpeech && toolbar.visibility != View.VISIBLE) View.INVISIBLE else View.VISIBLE
    }

    private fun text(s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontScale)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(1), 0, dp(1))
    }

    private fun toolButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    /** Drag the whole box vertically; a tap toggles the toolbar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installDragAndTap() {
        var downY = 0f
        var startY = 0
        var moved = false
        body.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (abs(dy) > dp(6)) moved = true
                    if (moved && attached) {
                        // Gravity is BOTTOM, so dragging down means a smaller y offset.
                        params.y = (startY - dy).toInt().coerceAtLeast(0)
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        render()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        /** A translated sentence stays at least this long, even if newer ones arrive. */
        private const val MIN_READ_MS = 4000L
        private val YELLOW = Color.rgb(255, 228, 92)
    }
}
