package io.github.dualsubs

import android.content.Context

enum class AudioSource { PLAYBACK, MIC }
enum class TranslateEngine { CLAUDE, OPENAI_COMPAT, NONE }
enum class SttKind { LOCAL, SONIOX }

/** Spoken-language choices. "" = auto-detect. Codes follow SenseVoice / ISO 639. */
object SourceLanguages {
    data class Lang(val code: String, val label: String, val englishName: String)

    val all = listOf(
        Lang("", "自动识别", ""),
        Lang("ja", "日语", "Japanese"),
        Lang("zh", "中文（普通话）", "Mandarin Chinese"),
        Lang("en", "英语", "English"),
        Lang("ko", "韩语", "Korean"),
        Lang("yue", "粤语", "Cantonese"),
    )

    fun byCode(code: String): Lang = all.firstOrNull { it.code == code } ?: all.first()
}

/**
 * Translation targets. Each value is written in its own language and goes straight
 * into the translation prompt, so the LLM knows exactly which language/script to use.
 */
object TargetLanguages {
    val all = listOf(
        "简体中文",
        "繁體中文",
        "English",
        "日本語",
        "한국어",
        "Español",
        "Français",
        "Deutsch",
        "Português",
        "Русский",
        "Italiano",
        "Tiếng Việt",
        "ภาษาไทย",
        "Bahasa Indonesia",
        "العربية",
    )

    fun normalize(value: String): String = all.firstOrNull { it == value } ?: all.first()
}

/** All user settings, persisted in SharedPreferences. */
data class Settings(
    val sttEngine: SttKind,
    /** Spoken language code (see [SourceLanguages]); "" = auto-detect. */
    val sourceLang: String,
    /** [SttModel.id] of the offline model to use. */
    val localModel: String,
    val sonioxKey: String,
    val sonioxModel: String,
    val engine: TranslateEngine,
    val claudeKey: String,
    val claudeModel: String,
    val oaiBaseUrl: String,
    val oaiKey: String,
    val oaiModel: String,
    val targetLang: String,
    val audioSource: AudioSource,
    /** Show the recognized original-language line above the translation. */
    val showOriginal: Boolean,
    /** How many subtitle sentences are on screen at once (1–3). */
    val displayLines: Int,
    /** Seconds a sentence stays after its translation arrives; 0 = until pushed out. */
    val holdSeconds: Int,
    val fontScale: Float,
)

object Prefs {
    private const val FILE = "settings"

    fun load(ctx: Context): Settings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Settings(
            sttEngine = runCatching { SttKind.valueOf(p.getString("sttEngine", "LOCAL")!!) }
                .getOrDefault(SttKind.LOCAL),
            sourceLang = SourceLanguages.byCode(p.getString("sourceLang", "")!!).code,
            localModel = p.getString("localModel", ModelCatalog.all.first().id)!!,
            sonioxKey = p.getString("sonioxKey", "")!!,
            sonioxModel = p.getString("sonioxModel", "stt-rt-v6")!!,
            engine = runCatching { TranslateEngine.valueOf(p.getString("engine", "CLAUDE")!!) }
                .getOrDefault(TranslateEngine.CLAUDE),
            claudeKey = p.getString("claudeKey", "")!!,
            claudeModel = p.getString("claudeModel", "claude-opus-5-5")!!,
            oaiBaseUrl = p.getString("oaiBaseUrl", "https://api.deepseek.com")!!,
            oaiKey = p.getString("oaiKey", "")!!,
            oaiModel = p.getString("oaiModel", "deepseek-chat")!!,
            targetLang = TargetLanguages.normalize(p.getString("targetLang", "简体中文")!!),
            audioSource = runCatching { AudioSource.valueOf(p.getString("audioSource", "PLAYBACK")!!) }
                .getOrDefault(AudioSource.PLAYBACK),
            showOriginal = p.getBoolean("showOriginal", true),
            displayLines = p.getInt("displayLines", 2).coerceIn(1, 3),
            holdSeconds = p.getInt("holdSeconds", 15).coerceAtLeast(0),
            fontScale = p.getFloat("fontScale", 1.0f),
        )
    }

    fun save(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("sttEngine", s.sttEngine.name)
            .putString("sourceLang", s.sourceLang)
            .putString("localModel", s.localModel)
            .putString("sonioxKey", s.sonioxKey.trim())
            .putString("sonioxModel", s.sonioxModel.trim())
            .putString("engine", s.engine.name)
            .putString("claudeKey", s.claudeKey.trim())
            .putString("claudeModel", s.claudeModel.trim())
            .putString("oaiBaseUrl", s.oaiBaseUrl.trim().trimEnd('/'))
            .putString("oaiKey", s.oaiKey.trim())
            .putString("oaiModel", s.oaiModel.trim())
            .putString("targetLang", s.targetLang.trim())
            .putString("audioSource", s.audioSource.name)
            .putBoolean("showOriginal", s.showOriginal)
            .putInt("displayLines", s.displayLines.coerceIn(1, 3))
            .putInt("holdSeconds", s.holdSeconds.coerceAtLeast(0))
            .putFloat("fontScale", s.fontScale)
            .apply()
    }

    fun saveFontScale(ctx: Context, scale: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("fontScale", scale).apply()
    }
}
