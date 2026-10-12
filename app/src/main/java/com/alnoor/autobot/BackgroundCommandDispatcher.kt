package com.alnoor.autobot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale

/** Runs safe commands without launching MainActivity; complex AI chat falls back to the existing UI pipeline. */
object BackgroundCommandDispatcher {
    fun dispatch(ctx: Context, raw: String, report: (String) -> Unit) {
        val cmd = raw.trim().removePrefix("kaam karo ").removePrefix("phone agent ").trim()
        if (cmd.isBlank()) { report("Command samajh nahi aaya."); return }
        val low = cmd.lowercase(Locale.ROOT)
        try {
            if (dispatchPriority(ctx, cmd, report)) return
            when {
                low in setOf("read screen", "what is on screen", "screen par kya hai", "screen par kya likha hai", "screen text", "screen padho") -> {
                    if (!AutoBotAccessibilityService.isOn()) report("Screen read karne ke liye Settings → Accessibility → Auto Bot ON karein.")
                    else report(AutoBotAccessibilityService.readScreen().ifBlank { "Screen ka text read nahi ho saka; is app ka UI Accessibility text expose nahi kar raha ho sakta hai." })
                }
                low in setOf("scroll down", "neeche scroll karo", "page neeche karo") -> report(if (AutoBotAccessibilityService.scroll(true) == "OK") "Neeche scroll kar diya." else "Scroll nahi hua. Accessibility ON check karein.")
                low in setOf("scroll up", "upar scroll karo", "page upar karo") -> report(if (AutoBotAccessibilityService.scroll(false) == "OK") "Upar scroll kar diya." else "Scroll nahi hua. Accessibility ON check karein.")
                low in setOf("go back", "back", "wapas jao") -> report(if (AutoBotAccessibilityService.goBack() == "OK") "Back kar diya." else "Back action nahi hua.")
                low in setOf("take screenshot", "screenshot lo", "screen shot lo") -> report(if (AutoBotAccessibilityService.globalNamed("screenshot") == "OK") "Screenshot command bhej di." else "Screenshot ke liye Accessibility ON aur Android 9+ chahiye.")
                low.startsWith("tap ") || low.startsWith("click ") || low.startsWith("button dabao ") -> {
                    val target = cmd.substringAfter(' ').trim()
                    report(if (AutoBotAccessibilityService.tapText(target) == "OK") "'$target' par tap kar diya." else "'$target' nahi mila. 'phone agent ...' se multi-step try karein.")
                }
                low.startsWith("type text ") || low.startsWith("type ") || low.startsWith("likho ") -> {
                    val value = cmd.substringAfter(' ').let { if (it.startsWith("text ", true)) it.substringAfter(' ') else it }.trim()
                    report(if (AutoBotAccessibilityService.typeText(value) == "OK") "Text type kar diya." else "Text field nahi mili. Pehle jis field mein likhna hai usay focus karein aur Accessibility ON karein.")
                }
                Regex("^(?:home|go home|ghar|home screen|ghar jao)$").containsMatchIn(low) -> {
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i); report("Home screen khol di.")
                }
                Regex("^(?:open|launch|kholo|khol do|chalao)\\s+.+|^.+\\s+(?:kholo|khol do|open karo|chalao)$").containsMatchIn(low) -> {
                    val target = low.replaceFirst(Regex("^(?:open|launch|kholo|khol do|chalao)\\s+"), "")
                        .replace(Regex("\\s+(?:kholo|khol do|open karo|chalao)$"), "").trim()
                    if (target in setOf("settings", "setting")) {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); report("Settings khol di."); return
                    }
                    if (target in setOf("phone", "dialer", "dial pad")) {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); report("Phone dialer khol diya."); return
                    }
                    val pm = ctx.packageManager
                    val knownPackages = mapOf(
                        "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
                        "chrome" to "com.android.chrome", "instagram" to "com.instagram.android",
                        "facebook" to "com.facebook.katana", "gmail" to "com.google.android.gm",
                        "maps" to "com.google.android.apps.maps", "google maps" to "com.google.android.apps.maps",
                        "play store" to "com.android.vending", "telegram" to "org.telegram.messenger"
                    )
                    val direct = knownPackages[target]?.let { pm.getLaunchIntentForPackage(it) }
                    if (direct != null) { direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(direct); report("$target khol diya."); return }
                    val matches = try { pm.getInstalledApplications(0).filter { app ->
                        val name = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
                        name.contains(target) || target.split(" ").all { it.isNotBlank() && name.contains(it) }
                    } } catch (_: Throwable) { emptyList() }
                    val app = matches.firstOrNull()
                    val launch = app?.let { pm.getLaunchIntentForPackage(it.packageName) }
                    if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(launch); report("${pm.getApplicationLabel(app!!)} khol diya.") }
                    else { report("App nahi mili. Chat khol kar is command ko dobara try karein."); openChat(ctx, cmd) }
                }
                low.startsWith("call ") || low.startsWith("dial ") -> {
                    val number = low.removePrefix("call ").removePrefix("dial ").trim()
                    if (number.matches(Regex("[+0-9() -]{5,}"))) {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); report("Dialer khol diya. Call karne se pehle number check karein.")
                    } else { report("Contact se call karne ke liye chat/contacts pipeline khol raha hoon."); openChat(ctx, cmd) }
                }
                low in setOf("stop", "cancel", "ruko", "ruk jao", "phone stop") -> { PhoneAgent.stop(); AssistantOverlayService.hide(ctx); report("Theek hai, ruk gaya.") }
                else -> {
                    // Existing PhoneAgent can operate headlessly when Accessibility is enabled.
                    if (low.startsWith("kaam karo") || low.startsWith("phone agent") || low.startsWith("phone ") || low.contains(" aur ") || low.contains(" then ")) {
                        PhoneAgent.start(ctx, cmd, { message -> report(message) }, { appName -> openApp(ctx, appName) })
                    } else {
                        report("Is request ke liye Auto Bot ka chat/AI pipeline khol raha hoon.")
                        openChat(ctx, cmd)
                    }
                }
            }
        } catch (e: Throwable) { report("Command nahi chal saka: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Commands that must work even while the chat Activity is already open. */
    fun dispatchPriority(ctx: Context, raw: String, report: (String) -> Unit): Boolean {
        val cmd = raw.trim().removePrefix("kaam karo ").removePrefix("phone agent ").trim()
        val reminder = ReminderEngine.handleCommand(ctx, cmd)
        if (reminder != null) { report(reminder); return true }
        return handleWhatsAppCall(ctx, cmd.lowercase(Locale.ROOT), report)
    }

    /** Opens the selected WhatsApp contact, then asks the existing accessibility agent to press voice/video call. */
    private fun handleWhatsAppCall(ctx: Context, low: String, report: (String) -> Unit): Boolean {
        val match = Regex("^(?:on\\s+)?whatsapp\\s+(?:(video|voice|audio)\\s+)?call\\s+(.+)$|^(video|voice|audio)\\s+call\\s+(.+?)\\s+on\\s+whatsapp$", RegexOption.IGNORE_CASE).find(low)
            ?: Regex("^whatsapp\\s+(video|voice|audio)\\s+call\\s+(.+)$", RegexOption.IGNORE_CASE).find(low)
            ?: return false
        val kind = (match.groupValues.getOrNull(1).orEmpty().ifBlank { match.groupValues.getOrNull(3).orEmpty() }).ifBlank { "voice" }
        val contactName = (match.groupValues.getOrNull(2).orEmpty().ifBlank { match.groupValues.getOrNull(4).orEmpty() }).trim()
        if (contactName.isBlank()) { report("Contact ka naam bhi bolein, misal: WhatsApp video call Ali."); return true }
        if (!Phonebook.hasPermission(ctx)) { report("WhatsApp contact dhoondhne ke liye Auto Bot ko Contacts permission dein, phir command dobara bolein."); return true }
        val matches = Phonebook.findByName(ctx, contactName, 3)
        if (matches.isEmpty()) { report("'$contactName' contact nahi mila. Naam ya Contacts permission check karein."); return true }
        if (matches.size > 1 && !matches.any { it.name.equals(contactName, true) }) {
            report("Milte-julte contacts: " + matches.joinToString { it.name } + ". Exact naam ke saath dobara bolein."); return true
        }
        val contact = matches.firstOrNull { it.name.equals(contactName, true) } ?: matches.first()
        val number = contact.number.filter { it.isDigit() }
        if (number.length < 7) { report("${contact.name} ka phone number valid nahi lag raha."); return true }
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(launch) } catch (_: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { report("WhatsApp chat open nahi hui. WhatsApp installed hai check karein."); return true }
        }
        if (!AutoBotAccessibilityService.isOn()) {
            report("${contact.name} ka WhatsApp chat khol diya. Call button dabane ke liye Settings → Accessibility → Auto Bot ON karein; abhi call khud confirm karein.")
            return true
        }
        report("${contact.name} ka WhatsApp chat khol raha hoon; $kind call button dhoondhne ki koshish karunga. Screen par sahi contact verify karein.")
        Thread {
            try { Thread.sleep(2200) } catch (_: InterruptedException) { return@Thread }
            PhoneAgent.start(ctx.applicationContext,
                "In WhatsApp chat for contact ${contact.name}, start a ${if (kind == "video") "video" else "voice"} call. Use the visible call/video-call control only if the contact header clearly matches ${contact.name}. If ambiguous, stop and report instead of tapping.",
                { msg -> report(msg) },
                { app -> openApp(ctx.applicationContext, app) })
        }.start()
        return true
    }

    private fun openApp(ctx: Context, name: String): String {
        val target = name.trim().lowercase(Locale.ROOT)
        if (target.startsWith("info:")) {
            val pkg = ctx.packageManager.getInstalledApplications(0).firstOrNull { ctx.packageManager.getApplicationLabel(it).toString().contains(target.removePrefix("info:"), true) }?.packageName
            if (pkg != null) { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "OK" }
            return "❌ App nahi mili"
        }
        val pm = ctx.packageManager
        val app = try { pm.getInstalledApplications(0).firstOrNull { pm.getApplicationLabel(it).toString().contains(target, true) || target.contains(pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT)) } } catch (_: Throwable) { null }
        val i = app?.let { pm.getLaunchIntentForPackage(it.packageName) } ?: return "❌ App nahi mili: $name"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); return "OK"
    }
    private fun openChat(ctx: Context, cmd: String) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("voice_cmd", cmd))
    }
}
