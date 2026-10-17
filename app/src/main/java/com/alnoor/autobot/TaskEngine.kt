package com.alnoor.autobot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v4.22 TaskEngine: scheduled calls + WhatsApp messages.
 * "1 ghante baad dost ko call karo" / "4 baje abu ko whatsapp message bhejo: ghar aa raha hoon"
 * Recipient scheduling ke waqt hi resolve hota hai (ambiguity ho to foran poochta hai),
 * taake fire-time par task miss na ho. Fire par: notification + khud execution
 * (call = SIM auto-dial; WhatsApp = chat open + Accessibility se Send tap).
 */
object TaskEngine {
    data class Task(val id: Int, val at: Long, val action: String, val label: String, val number: String, val message: String)

    const val ACTION_FIRE = "com.alnoor.autobot.TASK_FIRE"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)
    private fun manager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun list(ctx: Context): MutableList<Task> {
        val out = mutableListOf<Task>()
        try {
            val arr = JSONArray(prefs(ctx).getString("brain_tasks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Task(o.optInt("id"), o.optLong("at"), o.optString("action"), o.optString("label"), o.optString("number"), o.optString("message"))
            }
        } catch (_: Exception) {}
        return out
    }
    private fun save(ctx: Context, items: List<Task>) {
        val arr = JSONArray()
        items.forEach { t -> arr.put(JSONObject().put("id", t.id).put("at", t.at).put("action", t.action).put("label", t.label).put("number", t.number).put("message", t.message)) }
        prefs(ctx).edit().putString("brain_tasks", arr.toString()).apply()
    }
    private fun pi(ctx: Context, t: Task): PendingIntent {
        val i = Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", t.id)
        return PendingIntent.getBroadcast(ctx, t.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun setAlarm(ctx: Context, t: Task) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager(ctx).canScheduleExactAlarms()) {
                manager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.at, pi(ctx, t))
            } else manager(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.at, pi(ctx, t))
        } catch (_: Exception) {
            try { manager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.at, pi(ctx, t)) } catch (_: Exception) {}
        }
    }
    fun schedule(ctx: Context, t: Task) { save(ctx, list(ctx) + t); setAlarm(ctx, t) }
    fun rescheduleAll(ctx: Context) { list(ctx).filter { it.at > System.currentTimeMillis() }.forEach { setAlarm(ctx, it) } }
    fun removeAll(ctx: Context): Int {
        val all = list(ctx)
        all.forEach { try { manager(ctx).cancel(pi(ctx, it)) } catch (_: Exception) {} }
        save(ctx, emptyList()); clearDraft(ctx)
        return all.size
    }
    fun cancelById(ctx: Context, id: Int): Boolean {
        val all = list(ctx)
        val t = all.firstOrNull { it.id == id } ?: return false
        try { manager(ctx).cancel(pi(ctx, t)) } catch (_: Exception) {}
        save(ctx, all.filterNot { it.id == id })
        return true
    }
    fun onFire(ctx: Context, id: Int) { save(ctx, list(ctx).filterNot { it.id == id }) }
    fun listText(ctx: Context): String {
        val items = list(ctx)
        val fmt = SimpleDateFormat("EEE, dd MMM h:mm a", Locale.getDefault())
        if (items.isEmpty()) return "Koi task set nahi. Misal:\n• 1 ghante baad Abu ko call karo\n• 4 baje dost ko whatsapp message bhejo: ghar aa raha hoon"
        return "📋 Scheduled tasks:\n" + items.joinToString("\n") { "• #${it.id} ${fmt.format(Date(it.at))} — ${if (it.action == "call") "${it.label} ko call" else "${it.label} ko WhatsApp: ${it.message}"}" } +
            "\n('cancel task <id>' ya 'cancel all tasks')"
    }

    // =============== DRAFTS (choice ya message ka intezar) ===============
    private fun draft(ctx: Context): JSONObject? =
        try { prefs(ctx).getString("task_draft", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private fun saveDraft(ctx: Context, o: JSONObject) { prefs(ctx).edit().putString("task_draft", o.toString()).apply() }
    private fun clearDraft(ctx: Context) { prefs(ctx).edit().remove("task_draft").apply() }

    /** Chat/dispatch ke start mein call karo; draft pending ho to user ke reply se task complete hota hai. */
    fun consumeChoice(ctx: Context, lowIn: String): String? {
        val d = draft(ctx) ?: return null
        val low = lowIn.trim().lowercase(Locale.ROOT)
        val phase = d.optString("phase")
        if (Regex("\\b(cancel|chhodo|chhod|khatam|mat karo|rehne do|nahi chahiye)\\b").containsMatchIn(low)) {
            clearDraft(ctx); return "Task cancel kar diya. Jab chaho dobara bolo."
        }
        if (phase == "choice") {
            val arr = d.optJSONArray("options") ?: JSONArray()
            val n = Regex("^\\D*(\\d{1,2})\\D*$").find(low)?.groupValues?.get(1)?.toIntOrNull()
                ?: return "Task pending hai — list se number bolo (1-${arr.length()}) ya 'cancel task' bolo:\n" + d.optString("list")
            if (n < 1 || n > arr.length()) return "1 se ${arr.length()} tak ka number bolo."
            val pick = arr.getJSONObject(n - 1)
            return completeSchedule(ctx, d.optLong("at"), d.optString("action"), pick.optString("label"), pick.optString("number"), d.optString("message"))
        }
        if (phase == "message") {
            val msg = if (low.contains(":")) lowIn.substringAfter(":").trim() else lowIn.trim()
            if (msg.length < 2) return "Message likho (ya 'cancel task' bolo)."
            return completeSchedule(ctx, d.optLong("at"), d.optString("action"), d.optString("label"), d.optString("number"), msg)
        }
        return null
    }

    private fun completeSchedule(ctx: Context, at: Long, action: String, label: String, number: String, message: String): String {
        val id = prefs(ctx).getInt("brain_task_seq", 30000)
        prefs(ctx).edit().putInt("brain_task_seq", id + 1).apply()
        schedule(ctx, Task(id, at, action, label, number, message))
        clearDraft(ctx)
        val fmt = SimpleDateFormat("EEE, dd MMM h:mm a", Locale.getDefault())
        val what = if (action == "call") "$label ($number) ko call" else "$label ($number) ko WhatsApp par \"$message\""
        return "✅ Task set: ${fmt.format(Date(at))} — $what.\nWaqt par notification + khud action loonga. ('tasks dikhao' / 'cancel task $id')"
    }

    // =============== PARSING ===============
    /** Returns (atMillis, baqi text) ya null. "remind/yaad" wale phrases ko nahi chhoota (ReminderEngine ki zimidari). */
    private fun extractTime(low: String): Pair<Long, String>? {
        if (Regex("\\b(remind|reminder|yaad dilana|yaad rakhna)\\b").containsMatchIn(low)) return null
        val now = Calendar.getInstance()
        // A) "in/after N unit" ya "N unit (ke) baad"
        val reIn = Regex("\\b(?:in|after|mein|ke)\\s*(\\d+)\\s*(seconds?|secs?|minutes?|mins?|min|ghantay?|ghante|hours?|hrs?|hr|days?|din)\\b|\\b(\\d+)\\s*(seconds?|secs?|minutes?|mins?|min|ghantay?|ghante|hours?|hrs?|hr|days?|din)\\s*(?:ke\\s+)?baad")
        val mIn = reIn.find(low)
        if (mIn != null) {
            val amount = (mIn.groupValues[1].ifBlank { mIn.groupValues[3] }).toIntOrNull() ?: return null
            val unit = (mIn.groupValues[2].ifBlank { mIn.groupValues[4] }).lowercase(Locale.ROOT)
            val at = (now.clone() as Calendar).apply {
                when {
                    unit.startsWith("sec") -> add(Calendar.SECOND, amount)
                    unit.startsWith("min") || unit == "min" -> add(Calendar.MINUTE, amount)
                    unit.startsWith("ghant") || unit.startsWith("hour") || unit.startsWith("hr") -> add(Calendar.HOUR_OF_DAY, amount)
                    else -> add(Calendar.DAY_OF_YEAR, amount)
                }
            }
            return Pair(at.timeInMillis, low.replace(mIn.value, " "))
        }
        // B) clock time: "4 baje", "6:30 pm", "kal 4 par", "tomorrow at 9 am"
        val reClock = Regex("\\b(kal|tomorrow|aaj|today|parso)\\b\\s*(?:at|par|pe|ko)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|bajay|bajega|bajegi|o'?clock)?|\\b(?:at|par|pe|@)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|bajay|o'?clock)?|(\\d{1,2}):(\\d{2})\\s*(am|pm)?")
        val mClock = reClock.find(low)
        if (mClock != null) {
            val g = mClock.groupValues
            val dayWord = (g.getOrNull(1) ?: "").lowercase(Locale.ROOT)
            var hour = (g.getOrNull(2) ?: g.getOrNull(5) ?: g.getOrNull(8) ?: "").toIntOrNull() ?: return null
            val minute = (g.getOrNull(3) ?: g.getOrNull(6) ?: g.getOrNull(9) ?: "0").toIntOrNull() ?: 0
            val ampm = (g.getOrNull(4) ?: g.getOrNull(7) ?: g.getOrNull(10) ?: "").lowercase(Locale.ROOT)
            val hadMeridiem = ampm.isNotBlank()
            if (hour !in 1..23 || minute !in 0..59) return null
            if (hadMeridiem) {
                if ((ampm == "pm" || ampm.startsWith("baj")) && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            }
            val at = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                when (dayWord) {
                    "kal", "tomorrow" -> add(Calendar.DAY_OF_YEAR, 1)
                    "parso" -> add(Calendar.DAY_OF_YEAR, 2)
                    else -> if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return Pair(at.timeInMillis, low.replace(mClock.value, " "))
        }
        return null
    }

    /** Returns reply string ya null (agar task command nahi). */
    fun handleCommand(ctx: Context, cmd: String): String? {
        val low0 = cmd.trim().lowercase(Locale.ROOT)
        if (low0 in setOf("tasks", "task list", "tasks dikhao", "list tasks", "kaam dikhao", "scheduled tasks", "mere tasks", "tasks dikha", "kaam ki list")) return listText(ctx)
        if (low0 in setOf("cancel all tasks", "sab tasks cancel karo", "tasks cancel karo", "sab task hatao", "delete all tasks", "cancel tasks", "sab kaam cancel")) {
            val n = removeAll(ctx); return "$n task(s) cancel ho gaye."
        }
        Regex("^cancel task\\s*#?(\\d+)\\b").find(low0)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return null
            return if (cancelById(ctx, id)) "Task #$id cancel ho gaya." else "Is id ka koi task nahi mila. ('tasks dikhao')"
        }
        val time = extractTime(low0) ?: return null
        val rest = time.second.trim()
        if (rest.isBlank()) return null
        val isCall = Regex("\\b(call|phone|dial|ring)\\b").containsMatchIn(rest)
        val isWa = Regex("\\b(whatsapp|watsapp|whatsap|whats app)\\b").containsMatchIn(rest) ||
            (Regex("\\bwa\\b").containsMatchIn(rest) && Regex("\\b(message|msg)\\b").containsMatchIn(rest)) ||
            (Regex("\\b(message|msg|bhejo|send)\\b").containsMatchIn(rest) && !isCall && Regex("\\bwhatsapp\\b|\\bwa\\b").containsMatchIn(rest))
        if (!isCall && !isWa) return null
        val action = if (isCall) "call" else "wa_message"

        // target nikalo
        var target = ""
        var message = ""
        if (isCall) {
            val m1 = Regex("(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+)$").find(rest)
            val m2 = Regex("^(.+?)\\s+ko\\s+(?:call|phone|dial|ring)").find(rest)
            target = (m1?.groupValues?.get(1) ?: m2?.groupValues?.get(1) ?: "").trim()
        } else {
            val m2 = Regex("^(.+?)\\s+ko\\s+(?:whatsapp|wa|watsapp)").find(rest)
            val m3 = Regex("(?:whatsapp|watsapp|whatsap|whats app|wa)\\s+(?:message|msg)?\\s*(.+?)(?:\\s+ko\\s+message|\\s+message|\\s+msg|\\s+bhejo|\\s*:)").find(rest)
            target = (m2?.groupValues?.get(1) ?: m3?.groupValues?.get(1) ?: "").trim()
            val colon = rest.indexOf(':')
            if (colon >= 0) message = rest.substring(colon + 1).trim()
            else {
                val mm = Regex("(?:message|msg|bhejo|send karo|bhej do|bhej)\\s+(.+)$").find(rest)
                if (mm != null && target.isNotBlank()) message = mm.groupValues[1].trim()
            }
        }
        // fillers hatao
        val FILL = setOf("karo", "kro", "krdo", "kar", "karna", "lagao", "lagana", "to", "ko", "par", "pe", "on", "using", "sim", "sim1", "sim2", "a", "the", "mere", "mera", "meri", "ka", "ki", "se", "abhi", "jaldi", "ek", "aik")
        target = target.split(Regex("[\\s,]+")).filter { it.isNotBlank() && it !in FILL }.joinToString(" ").trim().trim('.', '!', '?')
        if (target.isBlank()) {
            return "Kis ko ${if (isCall) "call" else "WhatsApp message"} karna hai? Naam, relation (abu/bhai/dost...) ya number batao — dobara poora bolo with waqt.\nMisal: ${if (isCall) "1 ghante baad abu ko call karo" else "4 baje abu ko whatsapp message bhejo: ghar aa raha hoon"}"
        }

        val res = RelationEngine.resolve(ctx, target)
        return when (res) {
            is RelationEngine.Res.Direct ->
                if (isWa && message.isBlank()) { saveDraft(ctx, JSONObject().put("phase", "message").put("at", time.first).put("action", action).put("label", res.number).put("number", res.number).put("message", "")); "✍️ ${fmtTime(time.first)} par ${res.number} ko WhatsApp karna hai. Message kya bhejna hai? Likho (ya 'cancel task'):" }
                else completeSchedule(ctx, time.first, action, res.number, res.number, message)
            is RelationEngine.Res.Single ->
                if (isWa && message.isBlank()) { saveDraft(ctx, JSONObject().put("phase", "message").put("at", time.first).put("action", action).put("label", res.label).put("number", res.number).put("message", "")); "✍️ ${fmtTime(time.first)} par ${res.label} ko WhatsApp karna hai. Message kya bhejna hai? Likho (ya 'cancel task'):" }
                else completeSchedule(ctx, time.first, action, res.label, res.number, message)
            is RelationEngine.Res.Ambiguous -> {
                val opts = JSONArray(); res.options.forEach { opts.put(JSONObject().put("label", it.first).put("number", it.second)) }
                val listTxt = res.options.mapIndexed { i, o -> "${i + 1}. ${o.first} — ${o.second}" }.joinToString("\n")
                saveDraft(ctx, JSONObject().put("phase", "choice").put("at", time.first).put("action", action).put("options", opts).put("message", message).put("list", listTxt))
                "⏰ ${fmtTime(time.first)} ka task hai — lekin '$target' se kai log milay:\n$listTxt\nKaun sa? Number bolo (ya 'cancel task')."
            }
            RelationEngine.Res.NotFound ->
                "'$target' nahi mila — na saved relations mein na phone contacts mein. Pehle save karo (misal: save ${target} 03001234567) ya number ke sath bolo: ${if (isCall) "1 ghante baad 03001234567 ko call karo" else "4 baje 03001234567 ko whatsapp message bhejo: text"}"
        }
    }

    private fun fmtTime(at: Long): String = SimpleDateFormat("EEE, dd MMM h:mm a", Locale.getDefault()).format(Date(at))

    // =============== EXECUTION (fire par) ===============
    fun execute(ctx: Context, t: Task) {
        Thread {
            try {
                if (t.action == "call") {
                    val res = SimDialer.placeCall(ctx, t.number, null)
                    notify(ctx, t.id, "📞 Task — call ${t.label}", res)
                } else {
                    val num = RelationEngine.normalize(t.number).filter { it.isDigit() || it == '+' }.removePrefix("+")
                    val url = "https://wa.me/" + num + "?text=" + URLEncoder.encode(t.message, "UTF-8")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Thread.sleep(3000)
                    var sent = false; var tries = 0
                    while (!sent && tries < 10) {
                        tries++
                        val r = try { AutoBotAccessibilityService.tapWhatsAppSendConfirmed() } catch (_: Exception) { "OFF" }
                        if (r == "OK") sent = true else Thread.sleep(1500)
                    }
                    notify(ctx, t.id, if (sent) "✅ Task — WhatsApp bhej diya: ${t.label}" else "⚠️ Task — ${t.label} ka chat khula hai, Send khud dabayen (Accessibility ON rakhein)", "Message: ${t.message}")
                }
            } catch (e: Exception) {
                notify(ctx, t.id, "❌ Task fail hua", e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel("genius_tasks", "Genius Bot tasks", android.app.NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
            val b = android.app.Notification.Builder(ctx, "genius_tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
            nm.notify(id, b.build())
        } catch (_: Exception) {}
    }
}

class TaskReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TaskEngine.ACTION_FIRE) return
        val id = intent.getIntExtra("id", -1)
        val t = TaskEngine.list(ctx).firstOrNull { it.id == id }
        TaskEngine.onFire(ctx, id)
        if (t != null) TaskEngine.execute(ctx, t)
    }
}
