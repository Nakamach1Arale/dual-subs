package io.github.dualsubs

import android.content.Context

/** A speech-to-text backend fed with 16 kHz mono PCM16 audio. */
interface SttEngine {
    fun start()
    /** Called from the audio thread; must not block. */
    fun sendAudio(buf: ByteArray, len: Int)
    fun stop()
}

interface SttListener {
    /** Live, not-yet-final text. [committed] is stable, [tentative] may still change. */
    fun onPartial(committed: String, tentative: String)
    /** A finished sentence / speech segment, ready to translate. */
    fun onSegment(text: String)
    /** A transient status line for the overlay (null clears it). */
    fun onStatus(message: String?)
    fun onError(message: String)
    /** The engine stopped by itself (e.g. network drop). The owner may restart it. */
    fun onClosed()
}

fun createSttEngine(ctx: Context, s: Settings, listener: SttListener): SttEngine = when (s.sttEngine) {
    SttKind.LOCAL -> LocalSttEngine(ctx.applicationContext, ModelCatalog.byId(s.localModel), s.sourceLang, listener)
    SttKind.SONIOX -> SonioxClient(s.sonioxKey, s.sonioxModel, s.sourceLang, listener)
}
