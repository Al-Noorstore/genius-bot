package com.alnoor.autobot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One-shot reminders with a saved timestamp; restored after device reboot. */
object ReminderEngine {
    data class Reminder(val id: Int, val at: Long, val text: String)
    private const val ACTION = "com.alnoor.autobot.REMINDER_FIRE"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)
    private fun manager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun list(ctx: Context): List<Reminder> {
        val out = mutableListOf<Reminder>()
        try {
            val arr = JSONArray(prefs(ctx).getString("brain_reminders", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Reminder(o.optInt("id"), o.optLong("at"), o.optString("text"))
            }
        } catch (_: Exception) {}
        return out.sortedBy { it.at }
    }
    private fun save(ctx: Context, items: List<Reminder>) {
        val arr = JSONArray()
        items.forEach { r -> arr.put(JSONObject().put("id", r.id).put("at", r.at).put("text", r.text)) }
        prefs(ctx).edit().putString("brain_reminders", arr.toString()).apply()
    }
    private fun pending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION)
            .putExtra("id", r.id).putExtra("text", r.text)
        return PendingIntent.getBroadcast(ctx, r.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun schedule(ctx: Context, atMillis: Long, text: String): Reminder {
        val id = prefs(ctx).getInt("brain_reminder_seq", 20000)
        prefs(ctx).edit().putInt("brain_reminder_seq", id + 1).apply()
        val r = Reminder(id, atMillis, text.ifBlank { "Reminder" })
        save(ctx, list(ctx) + r)
        setAlarm(ctx, r)
        return r
    }
    private fun setAlarm(ctx: Context, r: Reminder) {
        try {
            val pi = pending(ctx, r)
            if (Build.VERSION.SDK_INT >= 31 && !manager(ctx).canScheduleExactAlarms()) {
                manager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            } else manager(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        } catch (_: Exception) {
            try { manager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r)) } catch (_: Exception) {}
        }
    }
    fun rescheduleAll(ctx: Context) { list(ctx).filter { it.at > System.currentTimeMillis() }.forEach { setAlarm(ctx, it) } }
    fun removeAll(ctx: Context): Int {
        val all = list(ctx)
        all.forEach { try { manager(ctx).cancel(pending(ctx, it)) } catch (_: Exception) {} }
        save(ctx, emptyList())
        return all.size
    }
    fun onFire(ctx: Context, id: Int) { save(ctx, list(ctx).filterNot { it.id == id }) }
    fun listText(ctx: Context): String {
        val items = list(ctx)
        if (items.isEmpty()) return "Koi reminder set nahi. Misal: 'remind me in 10 minutes to drink water' ya 'remind me tomorrow at 6 pm to study'."
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy h:mm a", Locale.getDefault())
        return "⏰ Reminders:\n" + items.joinToString("\n") { "• ${fmt.format(java.util.Date(it.at))} — ${it.text}" }
    }

    /** Handles common English/Roman Urdu reminder phrasing. Returns null if command isn't a reminder. */
    fun handleCommand(ctx: Context, command: String): String? {
        val s = command.trim()
        val low = s.lowercase(Locale.ROOT)
        if (Regex("^(show|list|mere|my)?\\s*(reminders|reminder list|yaad dilanay ki list|reminder dikhao)$").matches(low)) return listText(ctx)
        if (low in setOf("cancel all reminders", "delete all reminders", "sab reminders hatao", "reminders cancel karo")) {
            val n = removeAll(ctx); return "$n reminder(s) cancel kar diye."
        }
        val lead = Regex("^(?:hey bot[, ]*)?(?:please\\s+)?(?:remind me|reminder set karo|yaad dilana|mujhe yaad dilana|remind karo)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE)
        if (!lead.containsMatchIn(s)) return null
        val body = lead.find(s)?.let { s.removeRange(it.range) }?.trim().orEmpty()
        val now = Calendar.getInstance()
        var at: Calendar? = null
        var task = body
        val inMatch = Regex("^(?:in|after|mein|ke baad)\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|second|minute|min|ghantay?|ghante|din)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(body)
        if (inMatch != null) {
            val amount = inMatch.groupValues[1].toIntOrNull() ?: return "Time samajh nahi aaya. Misal: remind me in 10 minutes to study."
            val unit = inMatch.groupValues[2].lowercase(Locale.ROOT)
            task = inMatch.groupValues[3].trim()
            at = (now.clone() as Calendar).apply {
                when { unit.startsWith("sec") -> add(Calendar.SECOND, amount); unit.startsWith("min") || unit.startsWith("minute") -> add(Calendar.MINUTE, amount); unit.startsWith("hour") || unit.startsWith("hr") || unit.startsWith("ghant") -> add(Calendar.HOUR_OF_DAY, amount); else -> add(Calendar.DAY_OF_YEAR, amount) }
            }
        }
        if (at == null) {
            // Also support: "remind me to study at 6 pm".
            val trailingTime = Regex("^(.+?)\\s+(?:at|ko)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)$", RegexOption.IGNORE_CASE).find(body)
            if (trailingTime != null) {
                task = trailingTime.groupValues[1].removePrefix("to ").trim()
                var hour = trailingTime.groupValues[2].toIntOrNull() ?: 0
                val minute = trailingTime.groupValues[3].toIntOrNull() ?: 0
                val ampm = trailingTime.groupValues[4].lowercase(Locale.ROOT)
                if (hour !in 1..12 || minute !in 0..59) return "Time valid nahi. Misal: 6:30 pm."
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                at = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        if (at == null) {
            // ISO date support: "remind me on 2026-10-20 at 5:30 pm to study".
            val dated = Regex("^(?:on\\s+)?(\\d{4})-(\\d{2})-(\\d{2})\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(body)
            if (dated != null) {
                val year = dated.groupValues[1].toInt(); val month = dated.groupValues[2].toInt(); val day = dated.groupValues[3].toInt()
                var hour = dated.groupValues[4].toInt(); val minute = dated.groupValues[5].ifBlank { "0" }.toInt()
                val ampm = dated.groupValues[6].lowercase(Locale.ROOT); task = dated.groupValues[7].trim()
                if (minute !in 0..59 || hour !in 0..23 || (ampm.isNotBlank() && hour !in 1..12)) return "Time valid nahi."
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                val target = (now.clone() as Calendar).apply {
                    set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                at = target
                if (target.timeInMillis <= System.currentTimeMillis()) return "Yeh date/time guzar chuka hai. Future time dein."
            }
        }
        if (at == null) {
            val dateTime = Regex("^(tomorrow|today|kal|aaj)?\\s*(?:at|ko)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(body)
            if (dateTime != null) {
                val day = dateTime.groupValues[1].lowercase(Locale.ROOT)
                var hour = dateTime.groupValues[2].toIntOrNull() ?: 0
                val minute = dateTime.groupValues[3].toIntOrNull() ?: 0
                val ampm = dateTime.groupValues[4].lowercase(Locale.ROOT)
                task = dateTime.groupValues[5].trim()
                if (minute !in 0..59 || hour !in 0..23 || (ampm.isNotBlank() && hour !in 1..12)) return "Time valid nahi. Misal: 6:30 pm."
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                at = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (day == "tomorrow" || day == "kal" || timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        if (at == null) return "Reminder ka time clear nahi. Misal: 'remind me in 10 minutes to study' ya 'remind me tomorrow at 6 pm to study'."
        if (task.isBlank()) task = "Your reminder"
        val r = schedule(ctx, at.timeInMillis, task)
        val fmt = SimpleDateFormat("EEE, dd MMM h:mm a", Locale.getDefault())
        return "⏰ Reminder set: ${r.text} — ${fmt.format(java.util.Date(r.at))}"
    }
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "com.alnoor.autobot.REMINDER_FIRE") return
        val id = intent.getIntExtra("id", -1)
        val text = intent.getStringExtra("text") ?: "Reminder"
        ReminderEngine.onFire(ctx, id)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("autobot_reminders", "Auto Bot reminders", android.app.NotificationManager.IMPORTANCE_HIGH))
        @Suppress("DEPRECATION")
        val n = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, "autobot_reminders") else android.app.Notification.Builder(ctx)
        n.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Auto Bot reminder").setContentText(text).setAutoCancel(true).setDefaults(android.app.Notification.DEFAULT_ALL)
        nm.notify(id, n.build())
        // Some Android versions permit the ringing screen from a reminder receiver; if blocked, the notification still appears.
        try {
            ctx.startActivity(Intent(ctx, AlarmRingingActivity::class.java)
                .putExtra("id", id).putExtra("label", "Reminder: $text")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }
}
