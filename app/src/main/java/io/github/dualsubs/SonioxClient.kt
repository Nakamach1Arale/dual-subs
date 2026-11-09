package io.github.dualsubs

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Streams 16 kHz mono PCM16 audio to Soniox real-time STT and turns the token
 * stream into (a) live partial text and (b) finished sentence segments.
 *
 * Protocol: https://docs.soniox.com/api-reference/stt/websocket-api
 */
class SonioxClient(
    private val apiKey: String,
    private val model: String,
    /** Spoken language code, "" = let Soniox detect it. */
    private val language: String,
    private val listener: SttListener,
) : SttEngine {

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    private val committed = StringBuilder()

    override fun start() {
        val req = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildConfig().toString())
                ready = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                flushCommitted()
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                val code = response?.code
                Log.w(TAG, "websocket failure (http=$code)", t)
                listener.onError(
                    if (code == 401 || code == 403) "Soniox API key 无效 (HTTP $code)"
                    else "Soniox 连接失败: ${t.message ?: t.javaClass.simpleName}"
                )
                flushCommitted()
                listener.onClosed()
            }
        })
    }

    /** Send one chunk of raw PCM16 little-endian audio. Dropped while not connected. */
    override fun sendAudio(buf: ByteArray, len: Int) {
        if (!ready) return
        ws?.send(buf.toByteString(0, len))
    }

    /** Graceful end: an empty text frame tells Soniox to finish, then the socket is dropped. */
    override fun stop() {
        ready = false
        val socket = ws ?: return
        socket.send("")
        Thread {
            Thread.sleep(1500)
            socket.cancel()
            http.dispatcher.executorService.shutdown()
        }.start()
        ws = null
    }

    private fun buildConfig(): JSONObject = JSONObject().apply {
        put("model", model)
        put("audio_format", "pcm_s16le")
        put("sample_rate", AudioCapture.SAMPLE_RATE)
        put("num_channels", 1)
        // A hint improves accuracy; without one Soniox detects the language itself.
        // Soniox has no separate Cantonese code, so Cantonese is hinted as Chinese.
        if (language.isNotEmpty()) {
            put("language_hints", JSONArray().put(if (language == "yue") "zh" else language))
        }
        put("enable_endpoint_detection", true)
        put("max_endpoint_delay_ms", 1500)
        put("client_reference_id", "dual-subs")
    }

    private fun handleMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }

        if (msg.has("error_code") || msg.has("error_message")) {
            listener.onError("Soniox 错误 ${msg.optInt("error_code")}: ${msg.optString("error_message")}")
            return
        }

        val tentative = StringBuilder()
        val tokens = msg.optJSONArray("tokens") ?: JSONArray()
        for (i in 0 until tokens.length()) {
            val tok = tokens.optJSONObject(i) ?: continue
            val text = tok.optString("text")
            if (text == "<end>") {
                flushCommitted()
                continue
            }
            if (text.startsWith("<") && text.endsWith(">")) continue // other control tokens, e.g. <fin>
            if (tok.optString("translation_status") == "translation") continue
            if (tok.optBoolean("is_final")) committed.append(text) else tentative.append(text)
        }

        splitLongSentences()
        listener.onPartial(committed.toString(), tentative.toString())

        if (msg.optBoolean("finished")) flushCommitted()
    }

    /**
     * Broadcast speech often runs for a long time without a pause long enough for
     * endpoint detection, so we also cut on sentence-ending punctuation or length.
     */
    private fun splitLongSentences() {
        while (committed.length >= MIN_SPLIT) {
            var cut = -1
            for (i in committed.indices) {
                if (committed[i] in SENTENCE_END && i + 1 >= MIN_SPLIT) { cut = i + 1; break }
            }
            if (cut < 0 && committed.length >= MAX_SEGMENT) {
                // No sentence end: cut at the last comma, or hard-cut.
                val comma = committed.lastIndexOfAny(charArrayOf('、', '，', ','), MAX_SEGMENT - 1)
                cut = if (comma >= MIN_SPLIT) comma + 1 else MAX_SEGMENT
            }
            if (cut < 0) return
            val seg = committed.substring(0, cut).trim()
            committed.delete(0, cut)
            if (seg.isNotEmpty()) listener.onSegment(seg)
        }
    }

    private fun flushCommitted() {
        val seg = committed.toString().trim()
        committed.setLength(0)
        if (seg.isNotEmpty()) listener.onSegment(seg)
    }

    companion object {
        private const val TAG = "SonioxClient"
        private const val URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MIN_SPLIT = 12
        private const val MAX_SEGMENT = 70
        private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?')
    }
}
