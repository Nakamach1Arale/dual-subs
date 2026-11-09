package io.github.dualsubs

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service: audio capture -> speech recognition (offline ReazonSpeech or Soniox)
 * -> LLM translation -> floating overlay.
 */
class SubtitleService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settings: Settings
    private var projection: MediaProjection? = null
    private var capture: AudioCapture? = null
    @Volatile private var stt: SttEngine? = null
    private var translator: Translator? = null
    private var overlay: SubtitleOverlay? = null

    /** Segments are translated strictly in order, one at a time. */
    private val translateQueue = Channel<Pair<SubtitleOverlay.Line, String>>(Channel.UNLIMITED)
    private val recentJa = ArrayDeque<String>()

    @Volatile private var stopping = false
    private var reconnectDelayMs = 1000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay != null) return START_NOT_STICKY // already running

        settings = Prefs.load(this)
        val usePlayback = settings.audioSource == AudioSource.PLAYBACK

        // Must be in the foreground (with the right type) before getMediaProjection().
        startForegroundCompat(usePlayback)

        if (usePlayback) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data: Intent? = intent?.let {
                if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (code != Activity.RESULT_OK || data == null) {
                toast("没有获得音频捕获授权")
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { stopSelf() } }
                }, main)
            }
        }

        overlay = SubtitleOverlay(
            ctx = this,
            showOriginal = settings.showOriginal,
            displayLines = settings.displayLines,
            holdSeconds = settings.holdSeconds,
            fontScale = settings.fontScale,
            onStopRequested = { stopSelf() },
        ).also { it.show() }

        translator = runCatching { createTranslator(settings) }
            .onFailure { setStatus("翻译初始化失败: ${it.message}") }
            .getOrNull()
        scope.launch { translateLoop() }

        connectStt()

        capture = AudioCapture(
            projection = projection,
            onChunk = { buf, n -> stt?.sendAudio(buf, n) },
            onError = { msg -> setStatus(msg) },
        ).also { it.start() }

        isRunning = true
        return START_NOT_STICKY
    }

    private fun connectStt() {
        if (stopping) return
        stt = createSttEngine(this, settings, object : SttListener {
            override fun onStatus(message: String?) = setStatus(message)

            override fun onPartial(committed: String, tentative: String) {
                main.post {
                    reconnectDelayMs = 1000L
                    overlay?.setStatus(null)
                    overlay?.setLive(committed, tentative)
                }
            }

            override fun onSegment(text: String) {
                main.post { emitSegment(text) }
            }

            override fun onError(message: String) = setStatus(message)

            override fun onClosed() {
                main.post {
                    if (stopping) return@post
                    // Soniox streams end after 300 min or on network errors: reconnect with backoff.
                    val delay = reconnectDelayMs
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
                    main.postDelayed({ connectStt() }, delay)
                }
            }
        }).also { it.start() }
    }

    /** Main thread only: show a finished segment and queue it for translation. */
    private fun emitSegment(text: String) {
        val ov = overlay ?: return
        ov.setLive("", "")
        val line = ov.addLine(text)
        if (translator == null) ov.updateLine(line, "") else translateQueue.trySend(line to text)
    }

    private suspend fun translateLoop() {
        for ((line, ja) in translateQueue) {
            val tr = translator ?: continue
            val context = synchronized(recentJa) { recentJa.toList() }
            val zh = try {
                tr.translate(ja, context)
            } catch (e: TranslateException) {
                setStatus(e.message)
                "（翻译失败）"
            } catch (e: Exception) {
                Log.w(TAG, "translate failed", e)
                setStatus("翻译失败: ${e.message ?: e.javaClass.simpleName}")
                "（翻译失败）"
            }
            synchronized(recentJa) {
                recentJa.addLast(ja)
                while (recentJa.size > CONTEXT_LINES) recentJa.removeFirst()
            }
            withContext(Dispatchers.Main) { overlay?.updateLine(line, zh) }
        }
    }

    private fun setStatus(msg: String?) {
        main.post { overlay?.setStatus(msg) }
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        main.removeCallbacksAndMessages(null)
        capture?.stop()
        stt?.stop()
        stt = null
        translateQueue.close()
        scope.cancel()
        translator?.let { t -> Thread { runCatching { t.close() } }.start() }
        projection?.stop()
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    private fun startForegroundCompat(usePlayback: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "字幕运行中", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SubtitleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("双语字幕运行中")
            .setContentText(if (usePlayback) "正在识别机内音源" else "正在识别外部音源（麦克风）")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .setOngoing(true)
            .build()
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val proj = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (!usePlayback) {
            startForeground(NOTIFICATION_ID, notification, mic)
            return
        }
        // Playback capture also goes through the RECORD_AUDIO app-op, which Android 11+
        // only grants in the background to a "microphone" foreground service.
        try {
            startForeground(NOTIFICATION_ID, notification, proj or mic)
        } catch (e: SecurityException) {
            Log.w(TAG, "microphone FGS type refused, falling back to mediaProjection only", e)
            startForeground(NOTIFICATION_ID, notification, proj)
        }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    companion object {
        private const val TAG = "SubtitleService"
        private const val CHANNEL_ID = "subtitles"
        private const val NOTIFICATION_ID = 1
        private const val CONTEXT_LINES = 4
        const val ACTION_STOP = "io.github.dualsubs.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var isRunning = false
            private set
    }
}
