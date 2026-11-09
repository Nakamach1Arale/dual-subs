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
        val history = AudioHistory(HISTORY_SAMPLES)
        var prevEnd = 0L        // absolute sample where the last emitted sentence ended
        var liveStart = -1L     // absolute start of the sentence in progress, -1 = none
        var lastPreviewAt = 0L
        var lastPreview = ""

        // Silero only confirms speech once the probability has stayed above the threshold
        // for minSpeechDuration, and sherpa then starts the segment just ~26 ms before the
        // first loud window. Soft onsets (か, さ, breathy starts) and short openers like
        // 「あの、」 fall before that, so prepend up to PREROLL_SAMPLES from our own copy of
        // the audio — never reaching back into the previous sentence.
        fun withPreroll(start: Long, samples: FloatArray): FloatArray {
            val end = start + samples.size
            val from = maxOf(start - PREROLL_SAMPLES, prevEnd, history.oldest)
            prevEnd = maxOf(prevEnd, end)
            if (samples.isEmpty() || from >= start || end > history.total) return samples
            // Our numbering must match the VAD's; if it ever doesn't, use its audio as before.
            val aligned = history[start] == samples[0] &&
                history[start + samples.size / 2] == samples[samples.size / 2] &&
                history[end - 1] == samples[samples.size - 1]
            if (!aligned) {
                Log.w(TAG, "VAD segment not aligned with history; skipping pre-roll")
                return samples
            }
            return history.copy(from, end)
        }

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

                    val w = window.copyOf()
                    history.append(w)
                    vad.acceptWaveform(w)
                    filled = 0

                    // Finished segments: decode and emit.
                    while (!vad.empty()) {
                        val seg = vad.front()
                        vad.pop()
                        liveStart = -1
                        lastPreview = ""
                        val text = decode(recognizer, withPreroll(seg.start, seg.samples))
                        if (text.isNotBlank()) listener.onSegment(text)
                        else listener.onPartial("", "")
                    }

                    // A new sentence just started: begin where the final decode will begin.
                    if (vad.isSpeechDetected() && liveStart < 0) {
                        val vadStart = history.total - 2 * VAD_WINDOW - MIN_SPEECH_SAMPLES
                        liveStart = maxOf(vadStart - PREROLL_SAMPLES, prevEnd, history.oldest)
                    }
                }

                // Live preview of the sentence that is still being spoken.
                val now = SystemClock.elapsedRealtime()
                if (liveStart >= 0 && history.total - liveStart >= MIN_PREVIEW_SAMPLES &&
                    now - lastPreviewAt >= PREVIEW_INTERVAL_MS
                ) {
                    lastPreviewAt = now
                    val text = decode(recognizer, history.copy(liveStart, history.total))
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
            minSpeechDuration = MIN_SPEECH_SAMPLES.toFloat() / AudioCapture.SAMPLE_RATE,
            windowSize = VAD_WINDOW,
            // Force a cut in long monologues so subtitles never lag more than ~10 s.
            maxSpeechDuration = 10f,
        ),
        sampleRate = AudioCapture.SAMPLE_RATE,
        numThreads = 1,
    )

    /**
     * Ring buffer of the most recent audio fed to the VAD, addressed by absolute sample
     * index (the same numbering as [com.k2fsa.sherpa.onnx.SpeechSegment.start]).
     */
    private class AudioHistory(private val capacity: Int) {
        private val data = FloatArray(capacity)

        /** Samples appended so far = absolute index of the next sample. */
        var total = 0L
            private set

        /** Oldest absolute index still held. */
        val oldest: Long get() = maxOf(0L, total - capacity)

        fun append(src: FloatArray) {
            var i = 0
            while (i < src.size) {
                val idx = ((total + i) % capacity).toInt()
                val n = minOf(src.size - i, capacity - idx)
                System.arraycopy(src, i, data, idx, n)
                i += n
            }
            total += src.size
        }

        operator fun get(pos: Long): Float = data[(pos % capacity).toInt()]

        /** Samples [from, to), with [from] clamped to what is still held. */
        fun copy(from: Long, to: Long): FloatArray {
            val start = maxOf(from, oldest)
            val n = (to - start).toInt().coerceAtLeast(0)
            val out = FloatArray(n)
            var i = 0
            while (i < n) {
                val idx = ((start + i) % capacity).toInt()
                val len = minOf(n - i, capacity - idx)
                System.arraycopy(data, idx, out, i, len)
                i += len
            }
            return out
        }
    }

    companion object {
        private const val TAG = "LocalStt"
        private const val VAD_WINDOW = 512
        private const val PREVIEW_INTERVAL_MS = 1000L
        private const val MIN_PREVIEW_SAMPLES = AudioCapture.SAMPLE_RATE // 1 s
        private const val MAX_BACKLOG_CHUNKS = 100                       // 10 s of audio
        private const val MIN_SPEECH_SAMPLES = AudioCapture.SAMPLE_RATE / 4  // 0.25 s
        /** Extra audio kept in front of each sentence so its first sounds aren't clipped. */
        private const val PREROLL_SAMPLES = AudioCapture.SAMPLE_RATE * 2 / 5 // 0.4 s
        private const val HISTORY_SAMPLES = AudioCapture.SAMPLE_RATE * 30    // 30 s
    }
}
