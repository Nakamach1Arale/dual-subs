package io.github.dualsubs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var sonioxKey: EditText
    private lateinit var sonioxModel: EditText
    private lateinit var engine: RadioGroup
    private lateinit var claudeKey: EditText
    private lateinit var claudeModel: EditText
    private lateinit var oaiBaseUrl: EditText
    private lateinit var oaiKey: EditText
    private lateinit var oaiModel: EditText
    private lateinit var targetLang: Spinner
    private lateinit var source: RadioGroup
    private lateinit var showOriginal: CheckBox
    private lateinit var furigana: CheckBox
    private lateinit var sourceLang: Spinner
    private lateinit var modelList: LinearLayout
    private var selectedModel: String = ModelCatalog.all.first().id
    private val modelRows = mutableMapOf<String, ModelRow>()

    private class ModelRow(val radio: RadioButton, val info: TextView, val action: Button)
    private lateinit var stt: RadioGroup
    private lateinit var lines: RadioGroup
    private lateinit var holdSeconds: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sonioxKey = findViewById(R.id.sonioxKey)
        sonioxModel = findViewById(R.id.sonioxModel)
        engine = findViewById(R.id.engine)
        claudeKey = findViewById(R.id.claudeKey)
        claudeModel = findViewById(R.id.claudeModel)
        oaiBaseUrl = findViewById(R.id.oaiBaseUrl)
        oaiKey = findViewById(R.id.oaiKey)
        oaiModel = findViewById(R.id.oaiModel)
        targetLang = findViewById(R.id.targetLang)
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TargetLanguages.all)
        source = findViewById(R.id.source)
        showOriginal = findViewById(R.id.showOriginal)
        furigana = findViewById(R.id.furigana)
        sourceLang = findViewById(R.id.sourceLang)
        sourceLang.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SourceLanguages.all.map { it.label },
        )
        modelList = findViewById(R.id.modelList)
        buildModelList()
        stt = findViewById(R.id.stt)
        lines = findViewById(R.id.lines)
        holdSeconds = findViewById(R.id.holdSeconds)
        status = findViewById(R.id.status)

        fillForm(Prefs.load(this))
        engine.setOnCheckedChangeListener { _, _ -> updateEngineGroups() }
        stt.setOnCheckedChangeListener { _, _ -> updateEngineGroups() }

        findViewById<Button>(R.id.start).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, SubtitleService::class.java).setAction(SubtitleService.ACTION_STOP))
            status.text = "已停止"
        }
    }

    override fun onResume() {
        super.onResume()
        if (SubtitleService.isRunning) status.text = "字幕运行中。切换到视频或直播即可。"
        ModelDownloader.listener = { id, _ -> refreshModelRow(id) }
        ModelCatalog.all.forEach { refreshModelRow(it.id) }
    }

    override fun onPause() {
        super.onPause()
        ModelDownloader.listener = null
        Prefs.save(this, readForm())
    }

    private fun fillForm(s: Settings) {
        stt.check(if (s.sttEngine == SttKind.SONIOX) R.id.sttSoniox else R.id.sttLocal)
        selectModel(ModelCatalog.byId(s.localModel).id)
        sourceLang.setSelection(SourceLanguages.all.indexOfFirst { it.code == s.sourceLang }.coerceAtLeast(0))
        sonioxKey.setText(s.sonioxKey)
        sonioxModel.setText(s.sonioxModel)
        engine.check(when (s.engine) {
            TranslateEngine.CLAUDE -> R.id.engineClaude
            TranslateEngine.OPENAI_COMPAT -> R.id.engineOai
            TranslateEngine.NONE -> R.id.engineNone
        })
        claudeKey.setText(s.claudeKey)
        claudeModel.setText(s.claudeModel)
        oaiBaseUrl.setText(s.oaiBaseUrl)
        oaiKey.setText(s.oaiKey)
        oaiModel.setText(s.oaiModel)
        targetLang.setSelection(TargetLanguages.all.indexOf(s.targetLang).coerceAtLeast(0))
        source.check(if (s.audioSource == AudioSource.PLAYBACK) R.id.sourcePlayback else R.id.sourceMic)
        showOriginal.isChecked = s.showOriginal
        furigana.isChecked = s.furigana
        lines.check(when (s.displayLines) { 1 -> R.id.lines1; 3 -> R.id.lines3; else -> R.id.lines2 })
        holdSeconds.setText(s.holdSeconds.toString())
        updateEngineGroups()
    }

    private fun readForm(): Settings {
        val old = Prefs.load(this)
        return Settings(
            sttEngine = if (stt.checkedRadioButtonId == R.id.sttSoniox) SttKind.SONIOX else SttKind.LOCAL,
            sourceLang = SourceLanguages.all.getOrNull(sourceLang.selectedItemPosition)?.code ?: "",
            localModel = selectedModel,
            sonioxKey = sonioxKey.text.toString(),
            sonioxModel = sonioxModel.text.toString().ifBlank { "stt-rt-v6" },
            engine = when (engine.checkedRadioButtonId) {
                R.id.engineOai -> TranslateEngine.OPENAI_COMPAT
                R.id.engineNone -> TranslateEngine.NONE
                else -> TranslateEngine.CLAUDE
            },
            claudeKey = claudeKey.text.toString(),
            claudeModel = claudeModel.text.toString().ifBlank { "claude-opus-5-5" },
            oaiBaseUrl = oaiBaseUrl.text.toString().ifBlank { "https://api.deepseek.com" },
            oaiKey = oaiKey.text.toString(),
            oaiModel = oaiModel.text.toString().ifBlank { "deepseek-chat" },
            targetLang = TargetLanguages.all.getOrNull(targetLang.selectedItemPosition) ?: TargetLanguages.all.first(),
            audioSource = if (source.checkedRadioButtonId == R.id.sourceMic) AudioSource.MIC else AudioSource.PLAYBACK,
            showOriginal = showOriginal.isChecked,
            furigana = furigana.isChecked,
            displayLines = when (lines.checkedRadioButtonId) { R.id.lines1 -> 1; R.id.lines3 -> 3; else -> 2 },
            holdSeconds = holdSeconds.text.toString().trim().toIntOrNull() ?: 15,
            fontScale = old.fontScale,
        )
    }

    // ---- offline model list

    private fun buildModelList() {
        modelList.removeAllViews()
        modelRows.clear()
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (m in ModelCatalog.all) {
            val radio = RadioButton(this).apply {
                text = m.title
                setOnClickListener { selectModel(m.id) }
            }
            val info = TextView(this).apply {
                textSize = 12f
                setPadding(pad * 4, 0, 0, 0)
            }
            val action = Button(this).apply {
                setOnClickListener { onModelAction(m) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                addView(radio)
                addView(info)
                addView(action, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = pad * 4 })
            }
            modelList.addView(row)
            modelRows[m.id] = ModelRow(radio, info, action)
            refreshModelRow(m.id)
        }
    }

    private fun selectModel(id: String) {
        selectedModel = id
        modelRows.forEach { (rowId, row) -> row.radio.isChecked = rowId == id }
    }

    private fun onModelAction(m: SttModel) {
        when {
            ModelDownloader.isRunning(m.id) -> ModelDownloader.cancel(m.id)
            ModelCatalog.isInstalled(this, m) -> {
                if (SubtitleService.isRunning) { status.text = "请先停止字幕，再删除模型"; return }
                ModelCatalog.delete(this, m)
            }
            else -> {
                ModelDownloader.start(this, m)
                selectModel(m.id)
            }
        }
        refreshModelRow(m.id)
    }

    private fun refreshModelRow(id: String) {
        val row = modelRows[id] ?: return
        val m = ModelCatalog.byId(id)
        val sizeMb = m.totalBytes / 1_000_000
        val p = ModelDownloader.lastProgress(id)
        when {
            ModelDownloader.isRunning(id) -> {
                val pct = if (p != null && p.total > 0) (p.done * 100 / p.total) else 0
                row.info.text = "${m.description} · 下载中 $pct%（共 $sizeMb MB）"
                row.action.text = "取消"
            }
            ModelCatalog.isInstalled(this, m) -> {
                row.info.text = "${m.description} · 已下载（$sizeMb MB）"
                row.action.text = "删除"
            }
            else -> {
                val err = p?.error?.let { " · $it" } ?: ""
                row.info.text = "${m.description} · 未下载（$sizeMb MB）$err"
                row.action.text = "下载"
            }
        }
    }

    private fun updateEngineGroups() {
        val soniox = stt.checkedRadioButtonId == R.id.sttSoniox
        findViewById<View>(R.id.sonioxGroup).visibility = if (soniox) View.VISIBLE else View.GONE
        findViewById<View>(R.id.localGroup).visibility = if (soniox) View.GONE else View.VISIBLE
        val id = engine.checkedRadioButtonId
        findViewById<View>(R.id.claudeGroup).visibility = if (id == R.id.engineClaude) View.VISIBLE else View.GONE
        findViewById<View>(R.id.oaiGroup).visibility = if (id == R.id.engineOai) View.VISIBLE else View.GONE
    }

    // ---- start flow: validate -> overlay permission -> runtime permissions -> projection consent -> service

    private fun onStartClicked() {
        val s = readForm()
        Prefs.save(this, s)

        val problem = when {
            s.sttEngine == SttKind.SONIOX && s.sonioxKey.isBlank() -> "请填写 Soniox API key"
            s.sttEngine == SttKind.LOCAL && !ModelCatalog.isInstalled(this, ModelCatalog.byId(s.localModel)) ->
                "请先下载识别模型“${ModelCatalog.byId(s.localModel).title}”"
            s.sttEngine == SttKind.LOCAL && s.sourceLang !in ModelCatalog.byId(s.localModel).languages ->
                "“${ModelCatalog.byId(s.localModel).title}”不支持${SourceLanguages.byCode(s.sourceLang).label}，" +
                    "请换个模型，或把识别语言改成“自动识别”"
            s.engine == TranslateEngine.CLAUDE && s.claudeKey.isBlank() -> "请填写 Claude API key"
            s.engine == TranslateEngine.OPENAI_COMPAT && s.oaiKey.isBlank() -> "请填写翻译接口的 API key"
            else -> null
        }
        if (problem != null) { status.text = problem; return }
        if (SubtitleService.isRunning) { status.text = "字幕已经在运行"; return }
        continueStart()
    }

    private fun continueStart() {
        if (!AndroidSettings.canDrawOverlays(this)) {
            status.text = "请允许“显示在其他应用上层”，然后返回再点开始"
            startActivityForResult(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY,
            )
            return
        }

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return
        }

        if (Prefs.load(this).audioSource == AudioSource.PLAYBACK) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } else {
            launchService(null, RESULT_OK)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "需要“录音”权限才能捕获声音（机内音源也需要这个权限）"
            return
        }
        continueStart()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> if (AndroidSettings.canDrawOverlays(this)) continueStart()
            REQ_PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    status.text = "已取消屏幕共享授权（本应用只录制声音，不录制画面）"
                    return
                }
                launchService(data, resultCode)
            }
        }
    }

    private fun launchService(data: Intent?, resultCode: Int) {
        val intent = Intent(this, SubtitleService::class.java)
            .putExtra(SubtitleService.EXTRA_RESULT_CODE, resultCode)
        if (data != null) intent.putExtra(SubtitleService.EXTRA_RESULT_DATA, data)
        startForegroundService(intent)
        status.text = "字幕已启动。切换到视频或直播即可。"
    }

    companion object {
        private const val REQ_OVERLAY = 1
        private const val REQ_PERMS = 2
        private const val REQ_PROJECTION = 3
    }
}
