package com.alnoor.autobot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * v4.8 JARVIS MODE
 *  - wake_on  : "Hey Bot" hamesha sunna (WakeService, foreground notification ke saath)
 *  - jarvis_on: Bot bol kar batata hai (kaam shuru / chal raha hai / ho gaya / sawal poochna)
 * Dono Settings → Offline Brain mein toggle hain, ya chat/voice: "hey bot on/off", "jarvis on/off".
 */
object Jarvis {
    @Volatile var activity: WeakReference<MainActivity>? = null
    private val speaker = Executors.newSingleThreadExecutor()

    private fun p(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)
    fun wakeOn(ctx: Context) = p(ctx).getBoolean("wake_on", false)
    fun voiceOn(ctx: Context) = p(ctx).getBoolean("jarvis_on", false)
    fun assistantName(ctx: Context): String = p(ctx).getString("assistant_name", "Auto Bot")?.trim().orEmpty().ifBlank { "Auto Bot" }
    fun setAssistantName(ctx: Context, name: String) { p(ctx).edit().putString("assistant_name", name.trim().ifBlank { "Auto Bot" }).apply() }
    fun overlayOn(ctx: Context) = p(ctx).getBoolean("assistant_overlay", false)
    fun setOverlayOn(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("assistant_overlay", on).apply() }

    fun setWake(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("wake_on", on).apply()
        if (on) WakeService.start(ctx.applicationContext) else WakeService.stop(ctx.applicationContext)
    }

    fun setVoice(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("jarvis_on", on).apply()
        if (on) speak(ctx, "Jarvis mode on. Main bol kar batata rahunga.")
        else TtsBox.stopNow()
    }

    fun status(ctx: Context): String =
        "🎙️ Hey Bot: " + (if (wakeOn(ctx)) "ON" else "OFF") + (if (WakeService.running) " (sun raha hai)" else "") +
        "\n🤖 Jarvis awaz: " + (if (voiceOn(ctx)) "ON" else "OFF") +
        "\nBadalne ke liye: hey bot on/off  •  jarvis on/off  (ya Settings → Offline Brain)"

    /** Wake phrase supports the built-in names plus the user's chosen assistant name. */
    fun matchWake(textLower: String, ctx: Context? = null): Pair<Boolean, String> {
        val custom = ctx?.let { assistantName(it) } ?: "Auto Bot"
        val names = linkedSetOf("auto bot", "bot", "jarvis", custom.lowercase())
        val escaped = names.map { Regex.escape(it) }.sortedByDescending { it.length }.joinToString("|")
        val en = Regex("\\b(?:(?:hey|hay|hi|hello|ok|okay)\\s+(?:$escaped|boat|bott|bought)|$escaped|jarvish)\\b", RegexOption.IGNORE_CASE)
        val hi = Regex("(?:हे|हेय|हाय|हैलो|ओके)\\s*(?:बोट|बॉट|ऑटो बॉट)|जार्विस|जारविस")
        val m = en.find(textLower) ?: hi.find(textLower) ?: return Pair(false, "")
        return Pair(true, textLower.substring(m.range.last + 1).trim().trim(',', '.', '!', '?'))
    }

    /** Jarvis awaz: reply ka chhota saaf hissa bolo (emoji/markdown hata kar). Step-lines (▶) skip. */
    fun onReply(ctx: Context, text: String) {
        if (!voiceOn(ctx)) return
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("▶") || t.startsWith("🎤") || t.startsWith("📱 Screen text")) return
        var clean = t.replace(Regex("\\p{So}|\\u200d|\\ufe0f"), " ")
            .replace(Regex("[*_`#>•→\\[\\]{}|]"), " ")
            .replace(Regex("\\s*\\n+\\s*"), ". ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return
        if (clean.length > 200) clean = clean.substring(0, 200).substringBeforeLast(' ') + "."
        speak(ctx, clean)
    }

    fun speak(ctx: Context, text: String) {
        val app = ctx.applicationContext
        speaker.execute { try { TtsBox.speak(app, text, force = true) } catch (_: Throwable) {} }
    }

    /** Prefer the live chat pipeline, otherwise use safe background device actions. */
    fun deliver(ctx: Context, cmd: String) {
        val app = ctx.applicationContext
        AssistantOverlayService.show(app, "Working…", 12000L)
        if (BackgroundCommandDispatcher.dispatchPriority(app, cmd) { message ->
                if (voiceOn(app)) speak(app, message)
                AssistantOverlayService.show(app, message, 4500L)
            }) return
        val act = activity?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            act.runOnUiThread { act.voiceCommand(cmd) }
            return
        }
        BackgroundCommandDispatcher.dispatch(app, cmd) { message ->
            if (voiceOn(app)) speak(app, message)
            AssistantOverlayService.show(app, message, 4500L)
        }
    }
}
