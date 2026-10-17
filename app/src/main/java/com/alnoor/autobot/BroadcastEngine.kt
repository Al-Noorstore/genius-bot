package com.alnoor.autobot

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * v4.22 BroadcastEngine: "sab ko whatsapp message bhejo" — sab contacts ki list
 * chat mein deta hai; user selection (sab / 1,3,5 / direct numbers) + message deta hai;
 * phir har contact ko WhatsApp khul kar Accessibility se Send khud tap karta hai.
 */
object BroadcastEngine {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)
    private fun draft(ctx: Context): JSONObject? =
        try { prefs(ctx).getString("broadcast_draft", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private fun saveDraft(ctx: Context, o: JSONObject) { prefs(ctx).edit().putString("broadcast_draft", o.toString()).apply() }
    private fun clearDraft(ctx: Context) { prefs(ctx).edit().remove("broadcast_draft").apply() }

    /** true = handled (reply bhej diya). Pehle hi chat/dispatcher ke top par call karo jab draft ho, warna intent match par. */
    fun handle(ctx: Context, full: String, reply: (String) -> Unit): Boolean {
        val low = full.trim().lowercase()
        val d = draft(ctx)
        val isIntent = Regex("\\b(sab|sabhi|sabko|sab ko|all|everyone|sab log)\\b").containsMatchIn(low) &&
            Regex("\\b(whatsapp|watsapp|whatsap|whats app|wa|message|msg|bhejo|send)\\b").containsMatchIn(low)
        val multiNumber = (Regex("[+]?\\d{10,}").findAll(low).toList().size >= 2) && Regex("\\b(message|msg|bhejo|send|whatsapp)\\b").containsMatchIn(low)
        if (d == null && !isIntent && !multiNumber) return false

        if (d != null) {
            val phase = d.optString("phase")
            if (Regex("\\b(cancel|chhodo|khatam|nahi chahiye|rehne do)\\b").containsMatchIn(low) && !Regex("\\b(message|msg)\\b").containsMatchIn(low)) {
                clearDraft(ctx); reply("Broadcast cancel. Kuch aur bolo."); return true
            }
            if (phase == "list") return resolveSelection(ctx, full, d, reply)
            return false
        }

        // naya broadcast: list banao
        if (!Phonebook.hasPermission(ctx)) {
            reply("Contacts permission chahiye — Settings → Apps → Genius Bot → Permissions → Contacts allow karo, phir dobara bolo.")
            return true
        }
        val options: List<Pair<String, String>> = buildOptions(ctx, low)
        if (options.isEmpty()) {
            reply("Phonebook mein koi number-wala contact nahi mila.")
            return true
        }
        val arr = JSONArray()
        options.forEach { arr.put(JSONObject().put("label", it.first).put("number", it.second)) }
        saveDraft(ctx, JSONObject().put("phase", "list").put("options", arr))
        val listTxt = options.mapIndexed { i, o -> "${i + 1}. ${o.first} — ${o.second}" }.joinToString("\n")
        reply("📋 ${options.size} number-wale contacts list mein hain:\n$listTxt\n\nAb bolo kisko aur kya:\n• sab ko <message> bhejo\n• 1,3,5 ko <message> bhejo\n• message: <text> → phir main puchunga kisko\n('cancel' se rok do)" +
            (if (AutoBotAccessibilityService.isOn().not()) "\n\n⚠️ Auto-Send ke liye Settings → Accessibility → Genius Bot ON karo, warna har chat khulega lekin Send aap dabao ge." else ""))
        return true
    }

    private fun buildOptions(ctx: Context, low: String): List<Pair<String, String>> {
        val nums = Regex("\\+?\\d{10,}").findAll(low).map { it.value }.toList()
        if (nums.size >= 2) return nums.map { "Number ${it.takeLast(4)}" to OfflineBrain.normalizePhone(it) }
        val all = try { Phonebook.all(ctx, 200) } catch (_: Exception) { emptyList() }
        return all.filter { it.number.isNotBlank() }.map { it.name to OfflineBrain.normalizePhone(it.number) }.take(100)
    }

    private fun resolveSelection(ctx: Context, full: String, d: JSONObject, reply: (String) -> Unit): Boolean {
        val arr = d.optJSONArray("options") ?: JSONArray()
        if (arr.length() == 0) { clearDraft(ctx); return false }
        val low = full.trim().lowercase()
        val isAll = Regex("\\b(sab|sabhi|all|everyone|sabko)\\b").containsMatchIn(low) && !Regex("\\b(except|siwaye|bina)\\b").containsMatchIn(low)
        val picks = Regex("\\b(\\d{1,3})\\b").findAll(low).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..arr.length() }.distinct().toList()
        // Doosra command lag raha ho (call/open/reminder...) to draft chhodo aur flow aage barhne do
        val looksBroadcastReply = isAll || picks.isNotEmpty() || full.contains(":") || Regex("\\b(bhejo|bhej|bhej do|send|message|msg)\\b").containsMatchIn(low)
        if (!looksBroadcastReply) { clearDraft(ctx); return false }
        // message: colon ke baad, warna "ko <msg> bhejo" ke beech
        var msg = if (full.contains(":")) full.substringAfter(":").trim() else ""
        if (msg.isBlank()) {
            val m = Regex("\\bko\\s+(.+?)\\s+(?:bhejo|bhej do|bhej|send karo|send)\\b", RegexOption.IGNORE_CASE).find(full)
            if (m != null) msg = m.groupValues[1].trim()
        }
        if (msg.isBlank()) {
            reply("Message kya bhejna hai? Misal: 'sab ko Eid Mubarak bhejo' ya '1,3,5 ko <message> bhejo' — ya 'cancel'.")
            return true
        }
        val selected: List<Pair<String, String>> = when {
            isAll -> (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("label") to o.optString("number") } }
            picks.isNotEmpty() -> picks.map { arr.getJSONObject(it - 1).let { o -> o.optString("label") to o.optString("number") } }
            else -> emptyList()
        }
        if (selected.isEmpty()) {
            reply("Samajh nahi aaya kisko bhejna hai. Bolo: 'sab ko <message> bhejo' ya '1,3,5 ko <message> bhejo'. Ya 'cancel'.")
            return true
        }
        clearDraft(ctx)
        execute(ctx, selected, msg, reply)
        return true
    }

    fun execute(ctx: Context, targets: List<Pair<String, String>>, msg: String, reply: (String) -> Unit) {
        reply("🚀 ${targets.size} logon ko WhatsApp message bhejna shuru: \"$msg\"\n(Har chat khul kar Send khud dabega — phone ko chhorne ke liye Accessibility ON zaroori hai. Progress agle message mein.)")
        Thread {
            val ok = mutableListOf<String>()
            val fail = mutableListOf<String>()
            for ((name, ph) in targets) {
                try {
                    val num = OfflineBrain.normalizePhone(ph).filter { it.isDigit() || it == '+' }.removePrefix("+")
                    val url = "https://wa.me/" + num + "?text=" + URLEncoder.encode(msg, "UTF-8")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Thread.sleep(3000)
                    var sent = false; var tries = 0
                    while (!sent && tries < 10) {
                        tries++
                        val r = try { AutoBotAccessibilityService.tapWhatsAppSendConfirmed() } catch (_: Exception) { "OFF" }
                        if (r == "OK") sent = true else Thread.sleep(1500)
                    }
                    if (sent) ok += name else fail += name
                    Thread.sleep(1500)
                } catch (_: Exception) { fail += name }
            }
            val result = "✅ Broadcast done: ${ok.size}/${targets.size} ko message chala gaya." +
                (if (fail.isNotEmpty()) "\n⚠️ In ko nahi gaya (Send button nahi mila / WhatsApp khula): ${fail.joinToString(", ")}\nUn chats mein khud 'confirm whatsapp send' bolo." else "") +
                "\nDelivery WhatsApp mein check karein."
            reply(result)
        }.start()
    }
}
