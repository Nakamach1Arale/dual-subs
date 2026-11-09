package io.github.dualsubs

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws [ruby] (a kana reading) in small type centred above the spanned text.
 * TextView has no <ruby> support, so the span reserves the extra height itself by
 * raising the line's ascent. Colour, shadow and bold come from the TextView's paint.
 */
class RubySpan(private val ruby: String) : ReplacementSpan() {

    private fun rubyPaint(paint: Paint): TextPaint =
        TextPaint(paint).apply { textSize = paint.textSize * RUBY_SCALE }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val rp = rubyPaint(paint)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val r = rp.fontMetricsInt
            val rubyHeight = r.descent - r.ascent
            fm.ascent -= rubyHeight
            fm.top -= rubyHeight
        }
        return ceil(max(paint.measureText(text, start, end), rp.measureText(ruby))).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val rp = rubyPaint(paint)
        val baseWidth = paint.measureText(text, start, end)
        val rubyWidth = rp.measureText(ruby)
        val width = max(baseWidth, rubyWidth)

        canvas.drawText(text, start, end, x + (width - baseWidth) / 2, y.toFloat(), paint)
        // Sit the reading just above the top of the kanji.
        val rubyBaseline = y + paint.fontMetrics.ascent - rp.fontMetrics.descent
        canvas.drawText(ruby, x + (width - rubyWidth) / 2, rubyBaseline, rp)
    }

    private companion object {
        const val RUBY_SCALE = 0.5f
    }
}
