package com.alnoor.autobot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v4.12 TERMUX BRIDGE — asli Termux (pkg, python, git, node, ssh, ffmpeg...) Auto Bot terminal se.
 * Android 10+ bina root ke app ke andar "Termux jaisa" poora userland nahi chala sakta, isliye
 * hum installed Termux app ko RUN_COMMAND intent se command bhejte hain aur output wapas lete hain.
 * Terminal mein:  tx <cmd>   |   termux on  (is tab ki har command Termux mein)   |   termux off
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val PERM = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    private val callbacks = ConcurrentHashMap<Int, (String, String?) -> Unit>()
    private val ids = AtomicInteger(5000)

    fun installed(ctx: Context): Boolean =
        try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    fun permitted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERM) == PackageManager.PERMISSION_GRANTED

    fun statusText(ctx: Context): String =
        "🧰 Termux status:\n• Termux install: " + (if (installed(ctx)) "✅" else "❌") +
        "\n• Auto Bot ko RUN_COMMAND permission: " + (if (permitted(ctx)) "✅" else "❌") +
        "\nTest: tx echo hello   (agar jawab na aaye to: termux setup)"

    fun setupText(ctx: Context): String = buildString {
        append("🧰 Termux setup (ek baar):\n")
        append("1) Termux install karo — F-Droid ya GitHub se (Play Store wala purana hai).\n")
        append("2) Termux kholo aur ye ek line chalao:\n")
        append("   mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n")
        append("   phir Termux ko band kar ke dobara kholo.\n")
        append("3) Auto Bot ko permission do: Phone Settings → Apps → Auto Bot → Permissions → Additional permissions → \"Run commands in Termux environment\" → Allow.\n")
        append("4) Test: tx echo hello\n")
        append("Phir: tx pkg install python git  |  tx python -V  |  termux on (poori tab Termux ban jaye)\n\n")
        append(statusText(ctx))
    }

    fun helpText(): String =
        "🧰 Termux commands:\n• tx <command> — ek command Termux mein\n• termux on / off — is terminal tab ki har command Termux mein\n• termux setup — setup ke steps\n• termux status — check\nMisal: tx pkg install python | tx python script.py | tx git clone <url>"

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /** null = bhej di gayi (result callback mein aayega); warna error text. */
    fun run(ctx: Context, cmd: String, cwd: String, onResult: (String, String?) -> Unit): String? {
        if (!installed(ctx)) return "❌ Termux install nahi hai. 'termux setup' likho."
        if (!permitted(ctx)) return "❌ Termux permission nahi mili. 'termux setup' likho (step 3)."
        val id = ids.incrementAndGet()
        callbacks[id] = onResult
        val script = "cd " + q(cwd) + " 2>/dev/null || cd \"\$HOME\"; " + cmd +
            "\n__rc=\$?; echo \"__PWD__\$(pwd)\"; exit \$__rc"
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, TermuxResultReceiver::class.java).putExtra("exec_id", id), flags)
        val i = Intent().setClassName(PKG, "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        return try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (e: Exception) {
            callbacks.remove(id)
            "❌ Termux ko command nahi ja saki: " + (e.message ?: e.javaClass.simpleName) +
                "\n(Check: allow-external-apps=true set hai? permission di hai?)"
        }
    }

    fun deliver(id: Int, stdout: String, stderr: String, exitCode: Int?, errmsg: String?) {
        val cb = callbacks.remove(id) ?: return
        var out = stdout
        var newCwd: String? = null
        Regex("__PWD__(.*)\\s*$").find(out)?.let { newCwd = it.groupValues[1].trim(); out = out.substring(0, it.range.first) }
        val sb = StringBuilder()
        if (out.isNotBlank()) sb.append(out.trimEnd())
        if (stderr.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(stderr.trimEnd()) }
        if (!errmsg.isNullOrBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append("⚠️ ").append(errmsg) }
        if (exitCode != null && exitCode != 0) { if (sb.isNotEmpty()) sb.append('\n'); sb.append("(exit ").append(exitCode).append(")") }
        if (sb.isEmpty()) sb.append("(no output, exit ok)")
        cb(sb.toString().take(6000), newCwd)
    }
}

/** Termux ka result yahan aata hai (PendingIntent broadcast) */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("exec_id", -1)
        val b = intent.getBundleExtra("result")
        val code = if (b != null && b.containsKey("exitCode")) b.getInt("exitCode") else null
        TermuxBridge.deliver(id, b?.getString("stdout").orEmpty(), b?.getString("stderr").orEmpty(), code, b?.getString("errmsg"))
    }
}
