package com.alnoor.autobot

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * v4.2 TTS — Auto Bot speaker se bol kar poochhta hai.
 * v4.8: Jarvis mode ke liye force-speak + "busy" tracking (taake Hey Bot apni hi awaz na sune).
 * Pref: autobot → tts_enabled (default true). Band karne: "speaker band karo"
 */
object TtsBox {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpokenAt = 0L
    @Volatile private var speaking = false
    @Volatile private var lastDoneAt = 0L

    private fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).getBoolean("tts_enabled", true)

    /** true = abhi bol raha hai (ya abhi abhi chup hua) — mic ko is waqt ignore karo */
    fun busy(): Boolean {
        val now = System.currentTimeMillis()
        if (speaking && now - lastSpokenAt < 30_000) return true
        return now - lastDoneAt < 1200
    }

    fun ensure(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                try {
                    // Roman Urdu/Urdu clear bolne ke liye: ur-PK available to wo, warna US English
                    val r = tts?.setLanguage(Locale("ur", "PK"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.US
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { speaking = true }
                        override fun onDone(utteranceId: String?) { speaking = false; lastDoneAt = System.currentTimeMillis() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { speaking = false; lastDoneAt = System.currentTimeMillis() }
                    })
                } catch (_: Exception) {
                    tts?.language = Locale.US
                }
            }
        } catch (_: Exception) { tts = null }
    }

    /** Speaker se bolo. FAIL-safe: TTS na ho to chup — app kabhi crash nahi hogi.
     *  force=true => tts_enabled ignore (Jarvis mode apna toggle khud dekhta hai) */
    fun speak(ctx: Context, text: String, force: Boolean = false) {
        try {
            if ((!force && !enabled(ctx)) || text.isBlank()) return
            ensure(ctx)
            val t = tts ?: return
            if (!ready) {
                // pehli baar init hone mein thora waqt lag sakta hai — ek hi try
                Thread.sleep(700)
                if (!ready) return
            }
            val now = System.currentTimeMillis()
            lastSpokenAt = now
            speaking = true
            t.setSpeechRate(0.95f)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "autobot_" + now)
        } catch (_: Exception) { speaking = false }
    }

    fun stopNow() {
        try { tts?.stop() } catch (_: Exception) {}
        speaking = false; lastDoneAt = System.currentTimeMillis()
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false; speaking = false
    }
}
