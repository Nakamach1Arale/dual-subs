package io.github.dualsubs

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.concurrent.thread

/**
 * Records 16 kHz mono PCM16, either from what other apps are playing
 * (AudioPlaybackCapture, needs a MediaProjection) or from the microphone.
 */
class AudioCapture(
    private val projection: MediaProjection?,
    private val onChunk: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by MainActivity before starting.
    fun start() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)

        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
        if (projection != null) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(cfg)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }

        val rec = try {
            builder.build()
        } catch (e: Exception) {
            onError("无法创建录音: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("录音初始化失败")
            return
        }
        record = rec
        running = true
        rec.startRecording()

        worker = thread(name = "audio-capture") {
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf, n)
                else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    if (running) onError("读取音频失败 ($n)")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        /** 100 ms of 16 kHz mono PCM16. */
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
    }
}
