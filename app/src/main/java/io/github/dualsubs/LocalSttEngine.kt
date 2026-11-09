package io.github.dualsubs

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Fully offline speech recognition on the phone:
 * Silero VAD cuts the audio into speech segments, and a downloaded sherpa-onnx
 * model (see [ModelCatalog]) decodes each one.
 *
 * While someone is still speaking, the audio so far is re-decoded about once a
 * second to show a live preview, so text appears before the sentence ends.
 */
class LocalSttEngine(
    private val ctx: Context,
    private val model: SttModel,
    /** Spoken language code, "" = auto-detect. Only multilingual models use it. */
    private val language: String,
    private val listener: SttListener,
) : SttEngine {

    private val queue = LinkedBlockingQueue<FloatArray>()
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun start() {
        running = true
        worker = thread(name = "local-stt", priority = Thread.NORM_PRIORITY + 1) { run() }
    }

    override fun sendAudio(buf: ByteArray, len: Int) {
        if (!running) return
        val n = len / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val s = (buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)
            out[i] = s.toShort() / 32768f
        }
        // If decoding falls far behind real time, drop the oldest audio rather than lag forever.
        while (queue.size > MAX_BACKLOG_CHUNKS) queue.poll()
        queue.offer(out)
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun run() {
        if (!ModelCatalog.isInstalled(ctx, model)) {
            listener.onError("识别模型“${model.title}”还没下载，请先在设置里下载")
            return
        }
        listener.onStatus("正在加载识别模型：${model.title}…")
        val recognizer: OfflineRecognizer
        val vad: Vad
        try {
            // Model files live in app storage (downloaded); the small VAD model ships in assets.
            recognizer = OfflineRecognizer(null, recognizerConfig())
            vad = Vad(ctx.assets, vadConfig())
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load models", e)
            listener.onError("本地识别模型加载失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        listener.onStatus(null)

        val window = FloatArray(VAD_WINDOW)
        var filled = 0
        val speech = FloatArrayBuilder()     // audio of the utterance currently in progress
        var lastPreviewAt = 0L
        var lastPreview = ""

        try {
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                var pos = 0
                while (pos < chunk.size) {
                    val take = minOf(VAD_WINDOW - filled, chunk.size - pos)
                    System.arraycopy(chunk, pos, window, filled, take)
                    filled += take
                    pos += take
                    if (filled < VAD_WINDOW) break

                    vad.acceptWaveform(window.copyOf())
                    filled = 0
                    if (vad.isSpeechDetected()) speech.append(window)

                    // Finished segments: decode and emit.
                    while (!vad.empty()) {
                        val seg = vad.front()
                        vad.pop()
                        speech.clear()
                        lastPreview = ""
                        val text = decode(recognizer, seg.samples)
                        if (text.isNotBlank()) listener.onSegment(text)
                        else listener.onPartial("", "")
                    }
                }

                // Live preview of the sentence that is still being spoken.
                val now = SystemClock.elapsedRealtime()
                if (speech.size >= MIN_PREVIEW_SAMPLES && now - lastPreviewAt >= PREVIEW_INTERVAL_MS) {
                    lastPreviewAt = now
                    val text = decode(recognizer, speech.toArray())
                    if (text != lastPreview) {
                        lastPreview = text
                        listener.onPartial("", text)
                    }
                    // A slow phone: back off so previews never starve the final decode.
                    val took = SystemClock.elapsedRealtime() - now
                    if (took > PREVIEW_INTERVAL_MS / 2) lastPreviewAt += took
                }
            }
        } catch (_: InterruptedException) {
            // stopping
        } finally {
            runCatching { vad.release() }
            runCatching { recognizer.release() }
        }
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun recognizerConfig(): OfflineRecognizerConfig {
        val dir = ModelCatalog.dir(ctx, model).absolutePath
        val modelConfig = when (model.kind) {
            ModelKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                modelType = "transducer",
                numThreads = 2,
            )
            ModelKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$dir/model.int8.onnx",
                    language = language,              // "" = auto-detect
                    useInverseTextNormalization = true,
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
            )
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    private fun vadConfig() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = "silero_vad.onnx",
            threshold = 0.5f,
            // Broadcast speech has short pauses; 0.4 s ends a sentence without cutting phrases.
            minSilenceDuration = 0.4f,
            minSpeechDuration = 0.25f,
            windowSize = VAD_WINDOW,
            // Force a cut in long monologues so subtitles never lag more than ~10 s.
            maxSpeechDuration = 10f,
        ),
        sampleRate = AudioCapture.SAMPLE_RATE,
        numThreads = 1,
    )

    /** Growable float buffer without boxing. */
    private class FloatArrayBuilder {
        private var data = FloatArray(16_000 * 4)
        var size = 0
            private set

        fun append(src: FloatArray) {
            if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
            System.arraycopy(src, 0, data, size, src.size)
            size += src.size
        }

        fun clear() { size = 0 }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "LocalStt"
        private const val VAD_WINDOW = 512
        private const val PREVIEW_INTERVAL_MS = 1000L
        private const val MIN_PREVIEW_SAMPLES = AudioCapture.SAMPLE_RATE // 1 s
        private const val MAX_BACKLOG_CHUNKS = 100                       // 10 s of audio
    }
}
