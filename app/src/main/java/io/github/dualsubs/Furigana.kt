package io.github.dualsubs

import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Kana readings above kanji (furigana) for Japanese text, fully offline.
 *
 * Kuromoji splits the sentence into words and gives each word's reading from IPADIC;
 * the okurigana (kana already written in the word) is then matched out, so only the
 * kanji parts get a reading: 食べる → 食(た)べる, 取り扱い → 取(と)り扱(あつか)い.
 * Words the dictionary doesn't know are left without a reading.
 */
object Furigana {
    private const val TAG = "Furigana"

    @Volatile private var tokenizer: Tokenizer? = null
    private var loading = false

    /**
     * Loads the dictionary (about a second, tens of MB) on a background thread.
     * [onReady] runs on that thread once it is usable; nothing happens if it fails.
     */
    fun warmUp(onReady: () -> Unit) {
        if (tokenizer != null) { onReady(); return }
        synchronized(this) {
            if (loading) return
            loading = true
        }
        Thread({
            try {
                tokenizer = Tokenizer()
                onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "dictionary load failed", t)
            } finally {
                synchronized(this) { loading = false }
            }
        }, "furigana-init").start()
    }

    /** True if [s] has hiragana or katakana — i.e. it is Japanese, not Chinese. */
    fun containsKana(s: CharSequence): Boolean =
        s.any { it in 'ぁ'..'ゟ' || it in 'ァ'..'ヺ' }

    /** [text] with a [RubySpan] over each kanji run, or null while the dictionary is still loading. */
    fun annotate(text: String): CharSequence? {
        val tk = tokenizer ?: return null
        if (text.none(::isKanji)) return text
        val out = SpannableString(text)
        val tokens = try {
            tk.tokenize(text)
        } catch (t: Throwable) {
            Log.w(TAG, "tokenize failed", t)
            return text
        }
        for (token in tokens) {
            val surface = token.surface
            val reading = token.reading
            if (surface.none(::isKanji) || reading.isNullOrEmpty() || reading == "*") continue
            val base = token.position
            if (base < 0 || base + surface.length > text.length) continue
            for (r in align(surface, toHiragana(reading))) {
                out.setSpan(
                    RubySpan(r.reading),
                    base + r.offset, base + r.offset + r.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return out
    }

    private class Ruby(val offset: Int, val length: Int, val reading: String)

    /**
     * Splits a word's [reading] (hiragana) over its kanji runs by matching the kana runs
     * of [surface] literally. If they don't line up, the whole reading goes over the whole word.
     */
    private fun align(surface: String, reading: String): List<Ruby> {
        val pattern = StringBuilder()
        val kanjiRuns = mutableListOf<IntRange>()
        var i = 0
        while (i < surface.length) {
            val kanji = isKanji(surface[i])
            var j = i
            while (j < surface.length && isKanji(surface[j]) == kanji) j++
            if (kanji) {
                pattern.append("(.+?)")
                kanjiRuns += i until j
            } else {
                pattern.append(Regex.escape(toHiragana(surface.substring(i, j))))
            }
            i = j
        }
        val m = Regex(pattern.toString()).matchEntire(reading)
            ?: return listOf(Ruby(0, surface.length, reading))
        return kanjiRuns.mapIndexed { n, run ->
            Ruby(run.first, run.last - run.first + 1, m.groupValues[n + 1])
        }
    }

    /** Kanji, plus the marks that are read like kanji (々 〆 ヵ ヶ as in 一ヶ月). */
    private fun isKanji(c: Char): Boolean =
        c == '々' || c == '〆' || c == 'ヵ' || c == 'ヶ' ||
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
