package io.github.dualsubs

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Translates one subtitle line, given a few previous lines as context. Blocking. */
interface Translator {
    fun translate(line: String, context: List<String>): String
    fun close() {}
}

private fun systemPrompt(target: String, source: String) = """
You translate live speech from videos and live streams (news, shows, talks, games, dramas) into $target subtitles.
${if (source.isNotEmpty()) "The speech is in $source." else "Detect the spoken language yourself."}
The text comes from automatic speech recognition, so it may contain misrecognized words, missing
punctuation, or a sentence cut in the middle. Use the earlier lines to infer the intended meaning.
Rules:
- Output only the $target translation of the line marked [NEW]. No notes, quotes, romanization, or the original text.
- Write natural, concise subtitle-style $target. If the line is a fragment, translate the fragment naturally.
- Use the standard $target names for people, places, and organizations when they are well known.
- If the line is already in $target, output it unchanged (fixing obvious recognition errors).
- If the line is only filler or noise (e.g. "uh", "um", えー, 嗯), output an empty line.
""".trim()

private fun userPrompt(line: String, context: List<String>): String = buildString {
    if (context.isNotEmpty()) {
        append("Earlier lines (context only, do not translate):\n")
        context.forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
    append("[NEW]\n").append(line)
}

/** Claude via the official Anthropic Java SDK. */
class ClaudeTranslator(
    apiKey: String,
    private val model: String,
    private val target: String,
    /** English name of the spoken language, "" if unknown. */
    private val source: String = "",
) : Translator {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .maxRetries(1)
        .build()

    override fun translate(line: String, context: List<String>): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2000L)
            .system(systemPrompt(target, source))
            .addUserMessage(userPrompt(line, context))
            // Subtitles must keep up with speech: low effort keeps latency down.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // If a safety classifier declines (e.g. a news story about an attack),
            // let the server retry on a suitable fallback model instead of failing.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        try {
            val msg = client.beta().messages().create(params)
            if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) return "（翻译被拒绝）"
            return msg.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("")
                .trim()
        } catch (e: UnauthorizedException) {
            throw TranslateException("Claude API key 无效", e)
        } catch (e: RateLimitException) {
            throw TranslateException("Claude 请求过于频繁 (429)", e)
        } catch (e: AnthropicServiceException) {
            throw TranslateException("Claude 错误 ${e.statusCode()}: ${e.message}", e)
        }
    }

    override fun close() = client.close()
}

/** Any OpenAI-compatible chat completions endpoint (DeepSeek, OpenAI, Qwen, local servers, ...). */
class OpenAiCompatTranslator(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val target: String,
    /** English name of the spoken language, "" if unknown. */
    private val source: String = "",
) : Translator {
    private val url = baseUrl.trimEnd('/') + "/chat/completions"
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun translate(line: String, context: List<String>): String {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(target, source)))
                .put(JSONObject().put("role", "user").put("content", userPrompt(line, context))))
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw TranslateException("翻译接口错误 HTTP ${resp.code}: ${text.take(200)}")
            }
            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
                .trim()
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

class TranslateException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun createTranslator(s: Settings): Translator? = when (s.engine) {
    TranslateEngine.CLAUDE -> ClaudeTranslator(
        s.claudeKey, s.claudeModel, s.targetLang, SourceLanguages.byCode(s.sourceLang).englishName,
    )
    TranslateEngine.OPENAI_COMPAT -> OpenAiCompatTranslator(
        s.oaiBaseUrl, s.oaiKey, s.oaiModel, s.targetLang, SourceLanguages.byCode(s.sourceLang).englishName,
    )
    TranslateEngine.NONE -> null
}
