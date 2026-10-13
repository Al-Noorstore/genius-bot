package com.alnoor.autobot

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.13 AUTO BOT TERMINAL — apna asli interactive terminal (Termux ki zaroorat nahi).
 *  • Har tab ka ek zinda shell (/system/bin/sh -i): cd, export, alias, variables yaad rehte hain
 *  • Output live (stream) aata hai — ping, tail -f, lambi commands; input (read, prompts) bhi jata hai
 *  • Ctrl+C:  ^c  ya  ctrl+c  likho  |  shell restart: exit / reset  |  root shell: su (agar root mila ho)
 *  • Built-ins: curl, wget, unzip, history (!!, !n), alias ll/la/.., clear, help
 * Note: Android 10+ app apni storage se nai binaries nahi chala sakti, isliye Android ke system tools
 * (toybox/mksh) chalte hain. Aur tools ke liye: "dep install busybox" ya "tx ..." (Termux bridge).
 */
object TerminalEngine {

    private class Proc(val p: Process, val stdin: OutputStream, @Volatile var emit: (String) -> Unit, val root: Boolean)

    private val procs = ConcurrentHashMap<Int, Proc>()
    private val hist = ConcurrentHashMap<Int, MutableList<String>>()
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Za-z0-9]|\u001B[=>]")

    private fun clean(s: String): String =
        ANSI.replace(s, "").replace("\r\n", "\n").replace('\r', '\n')

    private fun pidOf(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (_: Throwable) { null }

    private fun childrenOf(pid: Int): List<Int> {
        val out = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val n = d.name.toIntOrNull() ?: return@forEach
            try {
                val after = File(d, "stat").readText().substringAfterLast(')').trim().split(' ')
                if (after.size > 1 && after[1].toIntOrNull() == pid) out.add(n)
            } catch (_: Exception) {}
        }
        return out
    }

    private fun cwdOf(id: Int, home: String): String {
        val pr = procs[id] ?: return home
        val pid = pidOf(pr.p) ?: return home
        return try { File("/proc/$pid/cwd").canonicalPath } catch (_: Exception) { home }
    }

    private fun rcFile(home: String): File {
        val f = File(home, ".autobotrc")
        if (!f.exists()) try {
            f.writeText("export PS1='\$PWD \$ '\nalias ll='ls -la'\nalias la='ls -A'\nalias ..='cd ..'\nalias cls='clear'\n")
        } catch (_: Exception) {}
        return f
    }

    private fun start(ctx: Context, id: Int, home: String, root: Boolean, emit: (String) -> Unit): Proc {
        val cmd = if (root) listOf("su") else listOf("/system/bin/sh", "-i")
        val pb = ProcessBuilder(cmd).directory(File(home)).redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = home
        env["TERM"] = "xterm"
        env["PATH"] = DepStore.binDir(ctx).absolutePath + ":" + (env["PATH"] ?: "/system/bin:/system/xbin")
        env["PS1"] = "\$PWD \$ "
        env["ENV"] = rcFile(home).absolutePath
        val p = pb.start()
        val pr = Proc(p, p.outputStream, emit, root)
        procs[id] = pr
        Thread {
            try {
                val rd = InputStreamReader(p.inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                val sb = StringBuilder()
                while (true) {
                    val n = rd.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    try { Thread.sleep(30) } catch (_: Exception) {}
                    if (!rd.ready() || sb.length > 4000) {
                        val t = clean(sb.toString()); sb.setLength(0)
                        if (t.isNotEmpty()) pr.emit(t)
                    }
                }
                if (sb.isNotEmpty()) pr.emit(clean(sb.toString()))
            } catch (_: Exception) {}
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            procs.remove(id, pr)
            pr.emit("\n[shell band ho gaya (exit $code) — agli command par naya shell khulega]\n\$ ")
        }.start()
        return pr
    }

    private fun ensure(ctx: Context, id: Int, home: String, root: Boolean, emit: (String) -> Unit): Proc {
        val cur = procs[id]
        if (cur != null && cur.p.isAlive && cur.root == root) { cur.emit = emit; return cur }
        if (cur != null) try { cur.p.destroy() } catch (_: Exception) {}
        return start(ctx, id, home, root, emit)
    }

    fun interrupt(id: Int): Boolean {
        val pr = procs[id] ?: return false
        val pid = pidOf(pr.p) ?: return false
        var n = 0
        for (c in childrenOf(pid)) {
            try { android.system.Os.kill(c, android.system.OsConstants.SIGINT); n++ } catch (_: Exception) {}
        }
        return n > 0
    }

    fun shutdown(id: Int) { procs.remove(id)?.let { try { it.p.destroy() } catch (_: Exception) {} } }
    fun shutdownAll() { for (id in procs.keys.toList()) shutdown(id) }

    /** true = engine ne sambhal li. false = purana rasta (diag, chat, ...) */
    fun handle(ctx: Context, id: Int, line: String, home: String, rootOk: () -> Boolean,
               emit: (String) -> Unit, clear: () -> Unit): Boolean {
        var t = line.trim()
        if (t.isEmpty()) { ensure(ctx, id, home, false, emit); return true }
        if (t == "diag") return false

        // ---- history expansion ----
        val h = hist.getOrPut(id) { ArrayList() }
        if (t == "!!") t = h.lastOrNull() ?: ""
        else Regex("^!(\\d+)$").find(t)?.let { m -> t = h.getOrNull(m.groupValues[1].toInt() - 1) ?: "" }
        if (t.isEmpty()) { emit("(history mein nahi mila)\n\$ "); return true }
        if (t != "history" && h.lastOrNull() != t) { h.add(t); if (h.size > 500) h.removeAt(0) }

        // ---- built-ins ----
        val low = t.lowercase()
        when {
            low == "^c" || low == "ctrl+c" || low == "ctrl-c" -> {
                emit(if (interrupt(id)) "^C\n" else "(koi chalti hui command nahi mili)\n"); return true
            }
            low == "clear" || low == "cls" -> { clear(); ensure(ctx, id, home, procs[id]?.root ?: false, emit).stdin.let { it.write("\n".toByteArray()); it.flush() }; return true }
            low == "history" -> { emit(h.mapIndexed { i, s -> "${i + 1}  $s" }.joinToString("\n") + "\n\$ "); return true }
            low == "exit" || low == "reset" || low == "restart shell" -> {
                shutdown(id); emit("♻️ Shell restart.\n"); ensure(ctx, id, home, false, emit); return true
            }
            low == "su" -> {
                if (!rootOk()) { emit("❌ Root nahi mila (Magisk/su chahiye).\n\$ "); return true }
                shutdown(id); emit("🔴 Root shell shuru (wapis: exit)\n"); ensure(ctx, id, home, true, emit); return true
            }
            low == "help" || low == "?" -> {
                emit("🖥️ Auto Bot Terminal\n• Live shell: cd, export, alias, pipes, redirect — sab yaad rehta hai\n• ^c / ctrl+c = rokna  • exit / reset = shell restart  • su = root shell (agar mila)\n" +
                     "• curl <url> [-o file] • wget <url> [-O file] • unzip <file> [-d dir]\n• history, !!, !5  • clear\n• py <code>, pip install <pkg> (PRO)\n• dep install busybox|jq  • tx <cmd> / termux on (asli Termux)\n\$ ")
                return true
            }
            low.startsWith("curl ") || low.startsWith("wget ") -> {
                val dir = cwdOf(id, home)
                fetch(t, dir, emit); emit("\$ "); return true
            }
            low.startsWith("unzip ") -> { unzip(t, cwdOf(id, home), emit); emit("\$ "); return true }
        }

        // ---- shell ko bhejo ----
        return try {
            val pr = ensure(ctx, id, home, procs[id]?.root ?: false, emit)
            pr.stdin.write((t + "\n").toByteArray(Charsets.UTF_8)); pr.stdin.flush()
            true
        } catch (e: Exception) {
            shutdown(id); emit("❌ Shell error: " + (e.message ?: "") + "\n\$ "); true
        }
    }

    // ---------------- curl / wget ----------------
    private fun resolve(dir: String, name: String): File = if (name.startsWith("/")) File(name) else File(dir, name)

    private fun fetch(line: String, dir: String, emit: (String) -> Unit) {
        val args = line.split(Regex("\\s+")).drop(1)
        val isWget = line.trim().lowercase().startsWith("wget")
        var url = ""; var out: String? = null; var head = false; var remoteName = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-o" || (isWget && a == "-O") -> { out = args.getOrNull(i + 1); i++ }
                a == "-O" -> remoteName = true
                a == "-I" -> head = true
                a.startsWith("http://") || a.startsWith("https://") -> url = a
                a.isNotEmpty() && !a.startsWith("-") && url.isEmpty() -> url = "https://$a"
            }
            i++
        }
        if (url.isEmpty()) { emit("Use: curl <url> [-o file] | wget <url> [-O file]\n"); return }
        try {
            var conn: HttpURLConnection
            var cur = url
            var hops = 0
            while (true) {
                conn = URL(cur).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "AutoBot-Terminal/4.13")
                if (head) conn.requestMethod = "HEAD"
                val code = conn.responseCode
                if (code in 301..308 && hops < 5) { cur = URL(URL(cur), conn.getHeaderField("Location") ?: break).toString(); hops++; conn.disconnect(); continue }
                break
            }
            if (head) {
                val sb = StringBuilder("HTTP ${conn.responseCode}\n")
                conn.headerFields.forEach { (k, v) -> if (k != null) sb.append(k).append(": ").append(v.joinToString(", ")).append('\n') }
                emit(sb.toString()); return
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            if (stream == null) { emit("HTTP $code\n"); return }
            val target = out ?: if (isWget || remoteName) (cur.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { "index.html" }) else null
            if (target != null) {
                val f = resolve(dir, target)
                var total = 0L
                f.outputStream().use { o -> stream.use { s -> val b = ByteArray(16384); while (true) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n); total += n } } }
                emit("✅ HTTP $code — ${f.absolutePath} ($total bytes)\n")
            } else {
                val text = stream.bufferedReader().use { it.readText() }
                emit(text.take(20000) + (if (text.length > 20000) "\n…(kata gaya, poora ke liye: -o file)" else "") + "\n")
            }
            conn.disconnect()
        } catch (e: Exception) { emit("❌ " + (e.message ?: e.javaClass.simpleName) + "\n") }
    }

    // ---------------- unzip ----------------
    private fun unzip(line: String, dir: String, emit: (String) -> Unit) {
        val a = line.split(Regex("\\s+")).drop(1)
        val file = a.firstOrNull { !it.startsWith("-") } ?: run { emit("Use: unzip <file.zip> [-d dir]\n"); return }
        val di = a.indexOf("-d")
        val dest = if (di >= 0 && a.getOrNull(di + 1) != null) resolve(dir, a[di + 1]) else File(dir)
        try {
            dest.mkdirs()
            val root = dest.canonicalPath
            var n = 0
            java.util.zip.ZipInputStream(resolve(dir, file).inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(dest, e.name)
                    if (!f.canonicalPath.startsWith(root)) { emit("⚠️ skip (unsafe path): ${e.name}\n"); continue }   // zip-slip se bachao
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }; n++ }
                }
            }
            emit("✅ $n files → ${dest.absolutePath}\n")
        } catch (e: Exception) { emit("❌ unzip: " + (e.message ?: "") + "\n") }
    }
}
