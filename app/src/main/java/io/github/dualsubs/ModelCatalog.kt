package io.github.dualsubs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class ModelKind { TRANSDUCER, SENSE_VOICE }

/** One file of a model: [name] is the local name, [remote] the file name in the HuggingFace repo. */
data class ModelFile(val name: String, val remote: String, val size: Long)

data class SttModel(
    val id: String,
    val title: String,
    val description: String,
    /** HuggingFace repository the files are downloaded from. */
    val repo: String,
    val kind: ModelKind,
    /** Language codes this model can recognize ("" = auto-detect is always allowed). */
    val languages: Set<String>,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** Offline speech-recognition models the app can download. Sizes are used to verify downloads. */
object ModelCatalog {
    val all = listOf(
        SttModel(
            id = "reazonspeech-ja",
            title = "ReazonSpeech 日语",
            description = "用日本电视节目音频训练，日语准确率高",
            repo = "reazon-research/reazonspeech-k2-v2",
            kind = ModelKind.TRANSDUCER,
            languages = setOf("", "ja"),
            files = listOf(
                ModelFile("encoder.int8.onnx", "encoder-epoch-99-avg-1.int8.onnx", 154_670_139),
                ModelFile("decoder.onnx", "decoder-epoch-99-avg-1.onnx", 11_767_836),
                ModelFile("joiner.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx", 2_696_970),
                ModelFile("tokens.txt", "tokens.txt", 45_754),
            ),
        ),
        SttModel(
            id = "reazonspeech-ja-en",
            title = "ReazonSpeech 日语+英语",
            description = "日英双语，可以混着说，体积更小",
            repo = "csukuangfj/reazonspeech-k2-v2-ja-en",
            kind = ModelKind.TRANSDUCER,
            languages = setOf("", "ja", "en"),
            files = listOf(
                ModelFile("encoder.int8.onnx", "encoder-epoch-35-avg-1.int8.onnx", 70_876_409),
                ModelFile("decoder.onnx", "decoder-epoch-35-avg-1.onnx", 5_165_084),
                ModelFile("joiner.int8.onnx", "joiner-epoch-35-avg-1.int8.onnx", 1_033_417),
                ModelFile("tokens.txt", "tokens.txt", 26_631),
            ),
        ),
        SttModel(
            id = "sensevoice-zh-en-ja-ko-yue",
            title = "SenseVoice 中/英/日/韩/粤",
            description = "多语言，自动判断语言，带标点",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            kind = ModelKind.SENSE_VOICE,
            languages = setOf("", "zh", "en", "ja", "ko", "yue"),
            files = listOf(
                ModelFile("model.int8.onnx", "model.int8.onnx", 237_115_547),
                ModelFile("tokens.txt", "tokens.txt", 315_894),
            ),
        ),
    )

    fun byId(id: String): SttModel = all.firstOrNull { it.id == id } ?: all.first()

    fun dir(ctx: Context, m: SttModel): File = File(ctx.filesDir, "models/${m.id}")

    fun isInstalled(ctx: Context, m: SttModel): Boolean {
        val d = dir(ctx, m)
        return m.files.all { File(d, it.name).length() == it.size }
    }

    fun delete(ctx: Context, m: SttModel) {
        dir(ctx, m).deleteRecursively()
    }
}

/**
 * Downloads model files with resume support, trying a mainland-China-friendly
 * HuggingFace mirror first and huggingface.co second.
 */
object ModelDownloader {
    data class Progress(val done: Long, val total: Long, val error: String? = null, val finished: Boolean = false)

    private const val TAG = "ModelDownloader"
    private val HOSTS = listOf("https://hf-mirror.com", "https://huggingface.co")

    private val main = Handler(Looper.getMainLooper())
    private val running = ConcurrentHashMap<String, Thread>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val last = ConcurrentHashMap<String, Progress>()

    /** Called on the main thread. */
    var listener: ((modelId: String, Progress) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isRunning(id: String) = running.containsKey(id)
    fun lastProgress(id: String): Progress? = last[id]

    fun cancel(id: String) {
        cancelled.add(id)
        running[id]?.interrupt()
    }

    fun start(ctx: Context, m: SttModel) {
        if (running.containsKey(m.id)) return
        cancelled.remove(m.id)
        val app = ctx.applicationContext
        running[m.id] = thread(name = "download-${m.id}") {
            try {
                download(app, m)
                post(m.id, Progress(m.totalBytes, m.totalBytes, finished = true))
            } catch (e: Exception) {
                val msg = if (m.id in cancelled) "已取消" else (e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "download ${m.id} failed", e)
                post(m.id, Progress(0, m.totalBytes, error = msg))
            } finally {
                running.remove(m.id)
            }
        }
    }

    private fun download(ctx: Context, m: SttModel) {
        val dir = ModelCatalog.dir(ctx, m).apply { mkdirs() }
        var doneBefore = 0L
        for (f in m.files) {
            val target = File(dir, f.name)
            if (target.length() == f.size) { doneBefore += f.size; continue }
            target.delete()
            val part = File(dir, f.name + ".part")
            var lastError: Exception? = null
            for (host in HOSTS) {
                try {
                    fetch("$host/${m.repo}/resolve/main/${f.remote}", part, f.size) { partLen ->
                        post(m.id, Progress(doneBefore + partLen, m.totalBytes))
                    }
                    lastError = null
                    break
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (m.id in cancelled) throw InterruptedException("cancelled")
                    Log.w(TAG, "fetch from $host failed", e)
                    lastError = e
                }
            }
            lastError?.let { throw Exception("下载失败（${f.name}）：${it.message}", it) }
            if (part.length() != f.size) throw Exception("文件大小不对（${f.name}），请重试")
            if (!part.renameTo(target)) throw Exception("无法保存 ${f.name}")
            doneBefore += f.size
        }
    }

    private fun fetch(url: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        if (part.length() > size) part.delete()
        val have = if (part.exists()) part.length() else 0L
        if (have == size) return
        val req = Request.Builder().url(url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        http.newCall(req).execute().use { resp ->
            val append = resp.code == 206
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("空响应")
            var written = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                val input = body.byteStream()
                var lastReport = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (written - lastReport >= 1_000_000) { lastReport = written; onProgress(written) }
                }
            }
            onProgress(written)
        }
    }

    private fun post(id: String, p: Progress) {
        last[id] = p
        main.post { listener?.invoke(id, p) }
    }
}
