package com.alnoor.autobot

import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

/**
 * PhoneAgent (v4.7) — poora phone voice/text se control.
 *
 * Chat/mic mein bolo:   phone agent <kaam>      (ya:  kaam karo <kaam>)
 *   misal: "kaam karo whatsapp kholo aur Ali ko likho kal milte hain"
 *          "phone agent wifi band karo"
 *          "kaam karo youtube par lofi music chalao"
 * Control:  phone stop | phone haan | phone nahi | phone status
 *
 * Loop: screen padho (numbered elements) -> AI se ek action poochho (JSON) -> chalao -> dobara.
 * AI: pehle API key, na ho/fail ho to offline GGUF (chhota model — complex kaam ke liye API behtar).
 * Safety: payment / delete / uninstall / factory reset jaise kaam se pehle user se haan/nahi poochta.
 * Password / OTP khud nahi likhta.
 */
object PhoneAgent {

    private const val MAX_STEPS = 30
    private val RISKY = Regex(
        "(pay\\b|payment|send money|transfer|purchase|buy now|place order|confirm order|delete|uninstall|" +
        "factory reset|erase|format|remove account|sign out|log ?out|bhejo paise|paise bhej)",
        RegexOption.IGNORE_CASE
    )

    @Volatile var lastEls: List<UiEl> = emptyList()   // v4.9: "screen list" ke baad "tap N" ke liye
    @Volatile var running = false; private set
    @Volatile private var stopFlag = false
    @Volatile private var waiting = false
    @Volatile private var answer: Boolean? = null
    @Volatile private var lastGoal = ""
    @Volatile private var lastStep = 0
    private val lock = java.lang.Object()

    val isWaiting: Boolean get() = waiting

    fun status(): String =
        if (running) "🤖 Phone agent chal raha hai (step $lastStep/$MAX_STEPS): $lastGoal" + (if (waiting) "\n⏸ Aap ke jawab ka intezar: 'phone haan' / 'phone nahi'" else "")
        else "🤖 Phone agent free hai. Likho: phone agent <kaam>"

    fun stop() { stopFlag = true; synchronized(lock) { answer = false; lock.notifyAll() } }

    fun reply(yes: Boolean): Boolean {
        if (!waiting) return false
        synchronized(lock) { answer = yes; lock.notifyAll() }
        return true
    }

    fun start(ctx: Context, goal: String, say: (String) -> Unit, openApp: (String) -> String) {
        if (running) { say("⚠️ Pehle se ek kaam chal raha hai. Rokne ke liye: phone stop"); return }
        if (!AutoBotAccessibilityService.isOn()) {
            say("♿ Accessibility OFF hai. Phone Settings → Accessibility → 'Auto Bot' ON karo (sirf ek baar), phir wahi kaam dobara bolo.")
            return
        }
        running = true; stopFlag = false; lastGoal = goal; lastStep = 0
        Thread {
            var wl: PowerManager.WakeLock? = null
            try {
                try {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                        "autobot:phoneagent")
                    wl?.acquire(10 * 60 * 1000L)
                } catch (_: Throwable) {}
                say("🤖 Shuru: $goal\n(rokne ke liye: phone stop)")
                loop(ctx, goal, say, openApp)
            } catch (e: Throwable) {
                say("❌ Agent error: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                running = false; waiting = false
                try { if (wl?.isHeld == true) wl?.release() } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun loop(ctx: Context, goal: String, say: (String) -> Unit, openApp: (String) -> String) {
        val history = ArrayList<String>()
        var sameCount = 0
        var lastSig = ""
        for (step in 1..MAX_STEPS) {
            lastStep = step
            if (stopFlag) { say("⏹ Rok diya."); return }

            val els = AutoBotAccessibilityService.snapshot()
            val pkg = AutoBotAccessibilityService.foregroundPackage()
            val screen = StringBuilder()
            els.forEachIndexed { i, e -> screen.append('[').append(i).append("] ").append(e.kind).append(" \"").append(e.label).append("\"\n") }

            val prompt = buildPrompt(goal, pkg, screen.toString(), history)
            val raw = askModel(ctx, prompt)
            if (raw == null) {
                say("❌ AI jawab nahi de saka. API key lagao (api key <key>) ya 'gguf on' karo, phir dobara try karo.")
                return
            }
            val j = parseJson(raw)
            if (j == null) {
                history.add("(AI ka jawab JSON nahi tha)")
                if (history.count { it.startsWith("(AI ka jawab") } >= 3) { say("❌ AI sahi format mein jawab nahi de raha. Behtar model/API key use karo."); return }
                Thread.sleep(500); continue
            }

            val action = j.optString("action").lowercase()
            val idx = if (j.has("index")) j.optInt("index", -1) else -1
            val text = j.optString("text")
            val why = j.optString("why")
            val el = if (idx in els.indices) els[idx] else null
            val desc = action + (if (idx >= 0) " [$idx]" else "") + (if (el != null) " \"${el.label}\"" else "") +
                (if (text.isNotEmpty() && action != "done" && action != "fail") " '$text'" else "")

            // ---- terminal actions ----
            if (action == "done") { say("✅ " + (text.ifBlank { "Kaam ho gaya." })); return }
            if (action == "fail") { say("⚠️ " + (text.ifBlank { "Kaam poora nahi ho saka." })); return }
            if (action == "say") { say("💬 $text"); history.add("say: $text"); continue }

            // ---- loop detection ----
            val sig = pkg + "|" + screen.toString().hashCode() + "|" + desc
            if (sig == lastSig) sameCount++ else { sameCount = 0; lastSig = sig }
            if (sameCount >= 2) { say("⚠️ Same action bar-bar ho raha hai ($desc) — ruk gaya. Kaam aur saaf bolo ya khud dekho."); return }

            // ---- safety gate ----
            val riskyText = (el?.label ?: "") + " " + why + " " + (if (action == "tap" || action == "long_press") text else "")
            if ((action == "tap" || action == "long_press") && RISKY.containsMatchIn(riskyText)) {
                waiting = true; answer = null
                say("⚠️ Khatarnak/na-wapsi kaam: $desc\nKya karun? → 'phone haan' ya 'phone nahi' (90 sec)")
                synchronized(lock) { if (answer == null) try { lock.wait(90_000) } catch (_: InterruptedException) {} }
                waiting = false
                if (stopFlag) { say("⏹ Rok diya."); return }
                if (answer != true) { say("🚫 Aap ne mana kiya / jawab nahi aaya — ruk gaya."); return }
            }

            if (step % 6 == 0) say("⏳ Kaam chal raha hai, step $step")
            say("▶ $step/$MAX_STEPS: $desc" + (if (why.isNotEmpty()) "  — $why" else ""))

            val res: String = when (action) {
                "tap" -> if (el != null) AutoBotAccessibilityService.clickEl(el) else "BAD_INDEX"
                "long_press" -> if (el != null) AutoBotAccessibilityService.clickEl(el, true) else "BAD_INDEX"
                "type" -> {
                    if (idx >= 0 && el == null) "BAD_INDEX"
                    else AutoBotAccessibilityService.setTextEl(el, text)
                }
                "enter" -> AutoBotAccessibilityService.pressEnter()
                "scroll" -> when (j.optString("direction", "down").lowercase()) {
                    "up" -> AutoBotAccessibilityService.scroll(false)
                    "left" -> AutoBotAccessibilityService.swipeHorizontal(false)
                    "right" -> AutoBotAccessibilityService.swipeHorizontal(true)
                    else -> AutoBotAccessibilityService.scroll(true)
                }
                "back" -> AutoBotAccessibilityService.goBack()
                "home" -> AutoBotAccessibilityService.goHome()
                "recents" -> AutoBotAccessibilityService.recents()
                "notifications" -> AutoBotAccessibilityService.notifications()
                "quick_settings", "power", "lock", "screenshot" -> AutoBotAccessibilityService.globalNamed(action)
                "close_app" -> { val r = openApp("info:" + j.optString("app", text)); if (r.contains("❌")) "FAIL: $r" else "OK" }
                "open_app" -> { val r = openApp(j.optString("app", text)); if (r.contains("❌") || r.contains("nahi mila", true)) "FAIL: $r" else "OK" }
                "wait" -> "OK"
                else -> "UNKNOWN_ACTION"
            }
            history.add("$step. $desc → $res")
            if (history.size > 8) history.removeAt(0)

            val pause = when (action) { "open_app" -> 2200L; "close_app" -> 1800L; "wait" -> 2000L; "lock", "screenshot" -> 800L; else -> 1000L }
            Thread.sleep(pause)
        }
        say("⏹ $MAX_STEPS steps poore ho gaye. Agar kaam baaki hai to dobara bolo: phone agent <baaki kaam>")
    }

    private fun buildPrompt(goal: String, pkg: String, screen: String, history: List<String>): String {
        val sb = StringBuilder()
        sb.append("You control an Android phone through Accessibility. Complete the GOAL step by step.\n")
        sb.append("Reply with EXACTLY ONE JSON object, no markdown, no extra text.\n\n")
        sb.append("Actions:\n")
        sb.append("{\"action\":\"open_app\",\"app\":\"whatsapp\"}  (prefer this to launch any app)\n")
        sb.append("{\"action\":\"close_app\",\"app\":\"whatsapp\"}  (opens that app's info page: then tap \"Force stop\" and confirm OK)\n")
        sb.append("{\"action\":\"tap\",\"index\":N}  {\"action\":\"long_press\",\"index\":N}\n")
        sb.append("{\"action\":\"type\",\"index\":N,\"text\":\"...\"}  (index optional = first text field)\n")
        sb.append("{\"action\":\"enter\"}  (submit/search)\n")
        sb.append("{\"action\":\"scroll\",\"direction\":\"down|up|left|right\"}\n")
        sb.append("{\"action\":\"back\"} {\"action\":\"home\"} {\"action\":\"recents\"} {\"action\":\"notifications\"} {\"action\":\"quick_settings\"} {\"action\":\"screenshot\"} {\"action\":\"lock\"} {\"action\":\"wait\"}\n")
        sb.append("{\"action\":\"say\",\"text\":\"...\"}  (tell user something, then continue)\n")
        sb.append("{\"action\":\"done\",\"text\":\"short result for the user (answer any question asked)\"}\n")
        sb.append("{\"action\":\"fail\",\"text\":\"why it cannot be done\"}\n")
        sb.append("Add a short \"why\" to every action.\n\n")
        sb.append("Rules: one action per reply; use only indexes from the CURRENT screen list; if the target is not visible scroll or go back; ")
        sb.append("after typing a search, use enter; never type passwords, PINs or OTPs (use fail and ask the user); ")
        sb.append("never guess; when the goal is achieved reply done immediately. User may write Urdu/Roman Urdu/English; keep \"text\" in the user's language.\n\n")
        sb.append("GOAL: ").append(goal).append("\n")
        sb.append("CURRENT APP: ").append(pkg.ifBlank { "unknown/home" }).append("\n")
        if (history.isNotEmpty()) { sb.append("PREVIOUS STEPS:\n"); history.forEach { sb.append(it).append('\n') } }
        sb.append("SCREEN ELEMENTS (index kind \"label\"):\n")
        sb.append(if (screen.isBlank()) "(empty — try wait, home, or open_app)\n" else screen)
        sb.append("\nJSON:")
        return sb.toString()
    }

    private fun askModel(ctx: Context, prompt: String): String? {
        try { AIBrain.askApi(ctx, prompt)?.let { return it } } catch (_: Throwable) {}
        try { GgufEngine.ask(ctx, prompt)?.let { return it } } catch (_: Throwable) {}
        return null
    }

    private fun parseJson(raw: String): JSONObject? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(raw.substring(a, b + 1)) } catch (_: Exception) { null }
    }
}
