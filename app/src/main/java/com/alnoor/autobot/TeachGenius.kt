package com.alnoor.autobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, user-controlled phrase -> safe accessibility action training. */
object TeachGenius {
    private const val PREF = "teach_genius_local_v1"
    private const val KEY = "rules"
    data class Rule(val phrase: String, val action: String)

    private fun norm(s: String) = s.trim().lowercase()
        .replace(Regex("[.!?,؛،؟]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun load(ctx: Context): MutableList<Rule> {
        val a = try { JSONArray(ctx.getSharedPreferences(PREF, 0).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Rule>()
        for (i in 0 until a.length()) try {
            val o = a.getJSONObject(i); val p = o.optString("phrase").trim(); val act = o.optString("action").trim()
            if (p.isNotBlank() && act.isNotBlank()) out.add(Rule(p, act))
        } catch (_: Exception) {}
        return out
    }
    private fun save(ctx: Context, rules: List<Rule>) {
        val a = JSONArray(); rules.forEach { a.put(JSONObject().put("phrase", it.phrase).put("action", it.action)) }
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, a.toString()).apply()
    }

    /** Returns a reply when a Teach Genius management command was handled, otherwise null. */
    fun manage(ctx: Context, text: String): String? {
        val raw = text.trim(); val low = norm(raw)
        if (low in setOf("show visual memory", "list visual memory", "visual training list")) return VisualMemory.list(ctx)
        val remember = Regex("^(?:remember button|teach visual button|visual button yaad rakho)\\s+(.+?)\\s+(?:as|label|ka text)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (remember != null) return VisualMemory.teach(ctx, remember.groupValues[1].trim().trim('\'', '\"'), remember.groupValues[2].trim().trim('\'', '\"'))
        val forgetVisual = Regex("^(?:forget visual memory|remove visual memory)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (forgetVisual != null) return VisualMemory.forget(ctx, forgetVisual.groupValues[1].trim().trim('\'', '\"'))
        if (low in setOf("show my genius training", "show genius training", "meri training dikhao", "genius training list", "teach genius list")) {
            val rules = load(ctx)
            return if (rules.isEmpty()) "Teach Genius: abhi koi custom rule save nahi. Example: Teach Genius when I say 'upar jao' do 'scroll up'." else
                "Teach Genius — saved on this phone:\n" + rules.mapIndexed { i, r -> "${i+1}. ‘${r.phrase}’ → ${r.action}" }.joinToString("\n")
        }
        val del = Regex("^(?:forget|remove|delete) (?:genius )?(?:training|rule|keyword) (.+)$", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("^(?:training|keyword) hatao (.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (del != null) {
            val phrase = del.groupValues[1].trim().trim('\'', '"')
            val rules = load(ctx); val n = rules.removeAll { norm(it.phrase) == norm(phrase) }; if (n) save(ctx, rules)
            return if (n) "Theek hai, ‘$phrase’ wali training is phone se hata di." else "‘$phrase’ naam ki training nahi mili. ‘Show my genius training’ bol kar list dekhein."
        }
        val teach = Regex("^(?:teach genius|genius ko sikhao|genius sikho|train genius)\\s+(?:when i say|when i speak|jab main kahun|jab ma kahun|jab mein bolun|jab ma bolun)\\s+(.+?)\\s+(?:do|then|to|phir|tu|tab)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (teach != null) {
            val phrase = teach.groupValues[1].trim().trim('\'', '"'); val action = teach.groupValues[2].trim().trim('\'', '"')
            if (phrase.length !in 2..100 || action.length !in 2..120) return "Phrase ya action thora clear likhein. Example: Teach Genius when I say 'upar jao' do 'scroll up'."
            val act = normalizeAction(action) ?: return "Abhi Teach Genius in safe actions ko support karta hai: scroll up, scroll down, scroll left, scroll right, read screen, back, click [button/text]. Ye action abhi save nahi hua."
            val rules = load(ctx); rules.removeAll { norm(it.phrase) == norm(phrase) }; rules.add(Rule(phrase, act)); save(ctx, rules)
            return "✅ Teach Genius ne ‘$phrase’ → ‘$act’ ko is phone par save kar liya. Ye rule exact phrase par chalega. Test karne ke liye phrase bol kar dekhein; app/accessibility permissions zaroori hain."
        }
        val visualTap = Regex("^(?:tap remembered button|click remembered button)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (visualTap != null) {
            val a = VisualMemory.find(ctx, visualTap.groupValues[1].trim()) ?: return "Visual memory not found. Say 'show visual memory'."
            val foreground = AutoBotAccessibilityService.foregroundPackage()
            if (a.packageName.isBlank() || foreground.isBlank()) return "Safety stop: I can't confirm which app is open. Open the app where you taught this button and try again."
            if (a.packageName != foreground) return "Safety stop: this button was learned in a different app. Open ${a.packageName} and try again."
            val before = AutoBotAccessibilityService.readScreen()
            AutoBotAccessibilityService.tapTextVerified(a.label) { result ->
                ctx.getSharedPreferences("genius_visual",0).edit()
                    .putString("last_action_verification",result)
                    .putString("last_action_label",a.label)
                    .putLong("last_action_time",System.currentTimeMillis()).apply()
            }
            return "Trying accessible label '${a.label}' in the matching app. The tap result is asynchronous; wait for verification and check the screen before repeating. A changed screen alone does not prove the intended action succeeded."
        }
        return null
    }

    private fun normalizeAction(a: String): String? {
        val x = norm(a)
        return when (x) {
            "scroll up", "upar scroll", "upar jao", "up" -> "scroll up"
            "scroll down", "neeche scroll", "neeche jao", "down" -> "scroll down"
            "scroll left", "left scroll", "left jao" -> "scroll left"
            "scroll right", "right scroll", "right jao" -> "scroll right"
            "read screen", "screen parho", "screen parh kar sunao", "screen read karo" -> "read screen"
            "capture screen", "screenshot lo", "screen capture karo", "screen ka screenshot lo" -> "capture screen"
            "back", "peeche jao", "wapis jao" -> "back"
            else -> if (x.startsWith("click ") && x.removePrefix("click ").isNotBlank()) "click " + a.trim().substringAfter(' ').trim() else null
        }
    }

    /** Executes only allow-listed actions; no arbitrary code or message/call sending. */
    fun execute(ctx: Context, text: String): String? {
        val rule = load(ctx).firstOrNull { norm(it.phrase) == norm(text) } ?: return null
        val action = rule.action
        return when {
            action == "scroll up" -> "Teach Genius: " + AutoBotAccessibilityService.scroll(false)
            action == "scroll down" -> "Teach Genius: " + AutoBotAccessibilityService.scroll(true)
            action == "scroll left" -> "Teach Genius: " + AutoBotAccessibilityService.swipeHorizontal(true)
            action == "scroll right" -> "Teach Genius: " + AutoBotAccessibilityService.swipeHorizontal(false)
            action == "read screen" -> AutoBotAccessibilityService.readScreen().ifBlank { "Screen read nahi ho saki. Accessibility service ON hai?" }
            action == "capture screen" -> {
                AutoBotAccessibilityService.captureScreen { result ->
                    // Store the latest capture path locally so the next image-analysis command can reuse it.
                    result.onSuccess { path -> ctx.getSharedPreferences("genius_visual", 0).edit().putString("last_capture", path).apply() }
                }
                "Screenshot capture request bhej di. Android 11+ aur Accessibility service ON hona zaroori hai; result local cache mein save hoga."
            }
            action == "back" -> { val svc = AutoBotAccessibilityService.instance; if (svc == null) "Accessibility service ON karein." else { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); "Teach Genius: back action ki koshish ki." } }
            action.startsWith("click ") -> "Teach Genius click: " + AutoBotAccessibilityService.tapText(action.removePrefix("click "))
            else -> "Saved action supported nahi hai."
        }
    }
}
