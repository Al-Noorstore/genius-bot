package com.alnoor.autobot

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * GgufEngine (v3.9) — REAL offline AI engine (low-RAM fixed).
 * - llama.cpp (libautobot-jni) se GGUF model chalata hai
 * - PRO APK mein Qwen2.5-0.5B built-in (assets/models) — pehli baar files mein extract
 * - LITE: transformer download karke wahi model chalao
 * - 3–4 GB RAM phones ke liye ctx/threads kam rakhe
 *
 * Chat: "gguf status" | "gguf on/off" | "gguf unload" | "gguf test"
 */
object GgufEngine {

    private const val PREF = "autobot"
    const val ASSET_MODEL = "models/smollm2-135m-instruct-q4_k_m.gguf"  // bundled default — sab phones
    private const val SYS = "You are Auto Bot. Answer ONLY the user request. " +
        "Languages: English, Urdu, Roman Urdu, Hindi, Hinglish. " +
        "Rules: stay on topic; no random names; no repeating words; " +
        "if user asks for a story, write a clear story with a title, beginning, middle and end; " +
        "if user asks to save a number or open an app, say you need the app command — do not invent. " +
        "Reply in the same language as the user. Never output nonsense loops."

    @Volatile private var loadedPath: String? = null
    @Volatile private var loading = false
    @Volatile private var lastError: String? = null

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("gguf_engine", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("gguf_engine", on).apply()
        if (!on) unload()
    }

    /** User-selected preferred model filename (e.g. smollm2-135m.gguf) or null = auto */
    fun preferredModel(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("gguf_preferred_model", null)

    fun setPreferredModel(ctx: Context, fileName: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("gguf_preferred_model", fileName).apply()
        // force reload next time
        if (loadedPath != null && fileName != null && !loadedPath!!.endsWith(fileName)) {
            unload()
        }
    }

    fun unload() {
        LlamaBridge.free()
        loadedPath = null
    }

    fun lastError(): String? = lastError

    /** Available offline models (bundled extracted + downloaded) with sizes */
    fun availableModels(ctx: Context): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val seen = mutableSetOf<String>()
        val dir = ModelStore.dir(ctx)
        // preferred / known names first
        listOf(
            ASSET_MODEL.substringAfterLast('/'),
            "smollm2-135m.gguf",
            "qwen2.5-0.5b.gguf",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "tinyllama-1.1b.gguf"
        ).forEach { name ->
            val f = File(dir, name)
            if (f.isFile && f.length() > 5_000_000L && name !in seen) {
                out.add(name to f.length()); seen.add(name)
            }
        }
        dir.listFiles()?.filter { it.isFile && it.length() > 5_000_000L && !it.name.endsWith(".part") }
            ?.forEach { f ->
                if (f.name !in seen) { out.add(f.name to f.length()); seen.add(f.name) }
            }
        return out
    }

    /** Suggest model by device RAM + task hint */
    fun suggestModel(ctx: Context, heavy: Boolean = false): String {
        val total = totalRamMb(ctx)
        val have = availableModels(ctx)
        val names = have.map { it.first.lowercase() }
        return when {
            heavy && total >= 5500 && names.any { it.contains("tinyllama") || it.contains("1.1b") } ->
                "💪 Heavy task: TinyLlama use karo — 'model use tinyllama'"
            heavy && total >= 4000 && names.any { it.contains("qwen") } ->
                "💪 Heavy task: Qwen use karo — 'model use qwen'"
            heavy && total >= 4000 ->
                "💪 Heavy task ke liye: 'transformer download 2' (Qwen ~400MB) ya API key jodo"
            names.any { it.contains("smol") } ->
                "✅ SmolLM ready — chhote phone ke liye best. Heavy ke liye: 'transformer download 2'"
            have.isNotEmpty() ->
                "✅ Model ready: ${have[0].first}. Switch: 'model use <naam>'"
            total < 4500 ->
                "📱 Low-RAM: 'transformer download 1' (SmolLM ~105MB) — ya APK mein bundled extract hoga"
            else ->
                "📦 'transformer download 1' (SmolLM) ya 2 (Qwen). API: 'api key <key>'"
        }
    }

    /** Available RAM in MB (approx). */
    private fun availRamMb(ctx: Context): Long {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem / (1024 * 1024)
        } catch (_: Exception) { 1024L }
    }

    /** Total device RAM in MB. */
    private fun totalRamMb(ctx: Context): Long {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / (1024 * 1024)
        } catch (_: Exception) { 4096L }
    }

    /** Low-RAM phone? (< 4.5 GB total) → smaller context + fewer threads. */
    private fun isLowRam(ctx: Context): Boolean = totalRamMb(ctx) < 4500

    /**
     * Model choose:
     * - Low-RAM (<4.5GB): pehle chhote downloaded (SmolLM ~145MB) — Qwen ~400MB skip
     * - Normal: bundled Qwen pehle, phir downloaded
     */

    /** Extract asset GGUF to files dir if missing; return path or null */
    private fun tryExtractAsset(ctx: Context, assetPath: String, onMsg: ((String) -> Unit)?): String? {
        val name = assetPath.substringAfterLast('/')
        val target = File(ModelStore.dir(ctx), name)
        if (target.isFile && target.length() > 5_000_000L) return target.absolutePath
        val part = File(target.absolutePath + ".part")
        val total = try { ctx.assets.openFd(assetPath).use { it.length } } catch (_: Exception) { return null }
        onMsg?.invoke("📦 Extract: $name (${"%.0f".format(total / 1048576.0)}MB)...")
        return try {
            ctx.assets.open(assetPath).use { input ->
                java.io.FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastPct = -100
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct >= lastPct + 25) { lastPct = pct; onMsg?.invoke("📦 Extract $name: $pct%") }
                        }
                    }
                }
            }
            if (part.isFile && part.length() > 5_000_000L) {
                if (target.exists()) target.delete()
                part.renameTo(target)
                // alias short name for smol
                if (name.contains("smollm")) {
                    val alias = File(ModelStore.dir(ctx), "smollm2-135m.gguf")
                    if (!alias.exists()) try { target.copyTo(alias, overwrite = false) } catch (_: Exception) {}
                }
                onMsg?.invoke("📦 $name ready (${target.length() / 1048576}MB)")
                target.absolutePath
            } else {
                part.delete(); null
            }
        } catch (e: Exception) {
            part.delete()
            onMsg?.invoke("❌ Extract fail $name: ${e.message}")
            null
        }
    }

    private fun pickModel(ctx: Context, onMsg: ((String) -> Unit)? = null): String? {
        val lowRam = isLowRam(ctx)

        // --- User preferred model (chat: model use smol / qwen / ...) ---
        preferredModel(ctx)?.let { pref ->
            val f = File(ModelStore.dir(ctx), pref)
            if (f.isFile && f.length() > 5_000_000L) {
                onMsg?.invoke("📦 Preferred model: ${f.name} (${f.length() / 1048576}MB)")
                return f.absolutePath
            }
            // fuzzy: partial name match among available
            availableModels(ctx).firstOrNull { it.first.lowercase().contains(pref.lowercase().removeSuffix(".gguf")) }?.let { (name, sz) ->
                val ff = File(ModelStore.dir(ctx), name)
                if (ff.isFile) {
                    onMsg?.invoke("📦 Preferred model: $name (${sz / 1048576}MB)")
                    return ff.absolutePath
                }
            }
        }

        // Extract bundled models if needed
        tryExtractAsset(ctx, ASSET_MODEL, onMsg)
        tryExtractAsset(ctx, "models/qwen2.5-0.5b-instruct-q4_k_m.gguf", onMsg)

        // Re-check preferred after extract
        preferredModel(ctx)?.let { pref ->
            availableModels(ctx).firstOrNull { it.first.lowercase().contains(pref.lowercase().removeSuffix(".gguf")) }?.let { (name, sz) ->
                onMsg?.invoke("📦 Preferred: $name (${sz / 1048576}MB)")
                return File(ModelStore.dir(ctx), name).absolutePath
            }
        }

        // --- Prefer smallest available on low-RAM ---
        if (lowRam) {
            val small = ModelStore.downloaded(ctx)
                .map { File(ModelStore.dir(ctx), it) }
                .filter { it.isFile && it.length() > 5_000_000L && it.length() < 220_000_000L }
                .minByOrNull { it.length() }
            if (small != null) {
                onMsg?.invoke("📦 Low-RAM: using small model ${small.name} (${small.length() / 1048576}MB)")
                return small.absolutePath
            }
            ModelStore.downloaded(ctx).firstOrNull()?.let {
                val f = File(ModelStore.dir(ctx), it)
                if (f.isFile && f.length() > 5_000_000L) {
                    onMsg?.invoke("📦 Low-RAM: using downloaded ${f.name} (${f.length() / 1048576}MB)")
                    return f.absolutePath
                }
            }
            onMsg?.invoke("💡 Low-RAM phone: pehle 'transformer download 1' (SmolLM2 ~145MB) karo — Qwen 400MB mushkil chalega.")
        }

        // After extract: prefer SmolLM on low-RAM, else any good file
        val smol = File(ModelStore.dir(ctx), ASSET_MODEL.substringAfterLast('/'))
        if (smol.isFile && smol.length() > 5_000_000L) {
            onMsg?.invoke("📦 Bundled SmolLM: ${smol.name} (${smol.length() / 1048576}MB)")
            if (lowRam || preferredModel(ctx) == null) return smol.absolutePath
        }
        val qwen = File(ModelStore.dir(ctx), "qwen2.5-0.5b-instruct-q4_k_m.gguf")
        if (qwen.isFile && qwen.length() > 10_000_000L && !lowRam) {
            onMsg?.invoke("📦 Bundled Qwen: ${qwen.name} (${qwen.length() / 1048576}MB)")
            return qwen.absolutePath
        }
        if (smol.isFile && smol.length() > 5_000_000L) return smol.absolutePath

        // Fallback: any downloaded
        ModelStore.downloaded(ctx).firstOrNull()?.let {
            val f = File(ModelStore.dir(ctx), it)
            if (f.isFile && f.length() > 5_000_000L) {
                onMsg?.invoke("📦 Using downloaded model: $it")
                return f.absolutePath
            }
        }
        return null
    }

    fun isReady(): Boolean = loadedPath != null && LlamaBridge.isLoaded()

    fun isLoading(): Boolean = loading

    /**
     * Model ready karo — extract + llama load.
     * true = ready. onMsg = live progress.
     */
    fun prepare(ctx: Context, onMsg: ((String) -> Unit)? = null): Boolean {
        if (loading) {
            var waited = 0
            while (loading && waited < 180_000) {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                waited += 400
            }
            if (loadedPath != null && LlamaBridge.isLoaded()) {
                onMsg?.invoke("✅ Model already loaded: ${File(loadedPath!!).name}")
                return true
            }
        }
        if (!enabled(ctx)) {
            onMsg?.invoke("⚪ GGUF engine OFF hai — 'gguf on' likho.")
            lastError = "engine_off"
            return false
        }
        if (!LlamaBridge.available) {
            onMsg?.invoke("❌ Native lib (libautobot-jni.so) load nahi hui — APK galat ABI / build incomplete.")
            lastError = "native_lib_missing"
            return false
        }

        val ram = availRamMb(ctx)
        val total = totalRamMb(ctx)
        onMsg?.invoke("📱 RAM: ${ram}MB free / ${total}MB total" +
            (if (isLowRam(ctx)) " ⚠️ low-RAM mode" else ""))

        if (ram < 600) {
            onMsg?.invoke("❌ Bahut kam free RAM ($ram MB). Background apps band karo, phir 'gguf test' dobara.")
            lastError = "low_ram:$ram"
            return false
        }

        return try {
            val path = pickModel(ctx, onMsg) ?: run {
                onMsg?.invoke("❌ Koi GGUF model nahi mila. (PRO: Qwen built-in; LITE: 'transformer download 2')")
                lastError = "no_model"
                return false
            }
            if (loadedPath == path && LlamaBridge.isLoaded()) {
                onMsg?.invoke("✅ Model already loaded: ${File(path).name}")
                return true
            }
            onMsg?.invoke("⏳ Model load ho raha hai (${File(path).name})...")
            val t0 = System.currentTimeMillis()
            loading = true
            try {
                unload()
                // Low-RAM: smaller context (1024) + 2 threads to avoid OOM
                val ctxSize = if (isLowRam(ctx)) 1024 else 2048
                val threads = if (isLowRam(ctx)) 2 else 0 // 0 = auto (JNI caps at 4)
                val ok = LlamaBridge.load(path, ctxSize = ctxSize, threads = threads)
                if (!ok) {
                    onMsg?.invoke("❌ llama.cpp load FAIL — RAM kam / model corrupt? Free RAM: ${availRamMb(ctx)}MB")
                    lastError = "native_load_fail"
                    false
                } else {
                    loadedPath = path
                    lastError = null
                    onMsg?.invoke("🧠 Offline AI ready! (load ${"%.0f".format((System.currentTimeMillis() - t0) / 1000.0)}s, ctx=$ctxSize)")
                    true
                }
            } finally {
                loading = false
            }
        } catch (e: Throwable) {
            onMsg?.invoke("❌ Prepare fail: ${e.message}")
            lastError = "prepare_exception:${e.message}"
            false
        }
    }

    /** model maujood hai? (light check — extract nahi karta) */
    fun modelPresent(ctx: Context): Boolean {
        for (a in listOf(ASSET_MODEL, "models/qwen2.5-0.5b-instruct-q4_k_m.gguf", "models/smollm2-135m.gguf")) {
            try { ctx.assets.open(a).close(); return true } catch (_: Exception) {}
        }
        return try {
            ModelStore.downloaded(ctx).isNotEmpty() ||
                File(ModelStore.dir(ctx), ASSET_MODEL.substringAfterLast('/')).isFile
        } catch (_: Exception) {
            false
        }
    }

    fun status(ctx: Context): String {
        val sb = StringBuilder("🧠 GGUF Engine v3.9.1 (llama.cpp 0.5.0):\n")
        sb.append("   Engine: ${if (LlamaBridge.available) "✅ native lib ready" else "❌ load nahi hui"}\n")
        sb.append("   On/Off: ${if (enabled(ctx)) "🟢 ON" else "⚪ OFF"}\n")
        val bundled = try {
            ctx.assets.open(ASSET_MODEL).close(); true
        } catch (_: Exception) { false }
        sb.append("   Built-in SmolLM: ${if (bundled) "✅ bundled" else "❌ nahi"}\n")
        val qwenAsset = try { ctx.assets.open("models/qwen2.5-0.5b-instruct-q4_k_m.gguf").close(); true } catch (_: Exception) { false }
        sb.append("   Built-in Qwen (PRO): ${if (qwenAsset) "✅ bundled" else "❌ nahi"}\n")
        val extracted = File(ModelStore.dir(ctx), ASSET_MODEL.substringAfterLast('/'))
        sb.append("   Extracted file: ${if (extracted.isFile && extracted.length() > 10_000_000L) "✅ ${extracted.length() / 1048576}MB" else "❌ nahi"}\n")
        val dl = ModelStore.downloaded(ctx)
        sb.append("   Downloaded models: ${if (dl.isEmpty()) "— koi nahi" else dl.joinToString()}\n")
        val ram = availRamMb(ctx)
        val total = totalRamMb(ctx)
        sb.append("   RAM: ${ram}MB free / ${total}MB total${if (isLowRam(ctx)) " ⚠️ low-RAM" else ""}\n")
        sb.append(
            if (isReady()) "   🟢 Model loaded: ${File(loadedPath!!).name}\n"
            else "   ⚪ Model memory mein nahi\n"
        )
        if (lastError != null) sb.append("   Last error: $lastError\n")
        sb.append("💡 'gguf test' chalao — step-by-step diagnosis.\n")
        sb.append("💡 Seedha sawal likho (ask ki zarurat nahi).")
        return sb.toString()
    }

    /**
     * Offline jawab. null = nahi bana (caller API/fallback).
     * BLOCKING — background thread se bulao.
     */
    private fun isGarbageAnswer(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return true
        // same word repeated many times
        val words = t.split(Regex("\\s+|[-–—,]")).filter { it.length > 2 }
        if (words.size >= 8) {
            val freq = words.groupingBy { it.lowercase() }.eachCount()
            val top = freq.values.maxOrNull() ?: 0
            if (top >= words.size * 0.4) return true
        }
        // hyphen spam like Samaa-Samaa-Shari
        if (t.count { it == '-' } >= 8 && t.length < 400) return true
        return false
    }

    // v4.16: jawab ki lambai app tay karti hai (chhota model "short/long" ka hukm nahi manta)
    private class Plan(val maxTok: Int, val hint: String)
    private val SHORT_RE = Regex("\\b(short|brief|briefly|in short|one line|one sentence|1 line|two lines|2 lines|chhota|chota|mukhtasir|mukhtasar)\\b", RegexOption.IGNORE_CASE)
    private val LONG_RE = Regex("\\b(long|detail|detailed|in detail|elaborate|essay|story|tafseel|tafsil|lamba|lambi|lambe|poori|poora|step by step|explain)\\b", RegexOption.IGNORE_CASE)

    private fun planFor(ctx: Context, q: String): Plan {
        val low = isLowRam(ctx)
        if (q.length > 400) return Plan(if (low) 120 else 220, "")          // agent/lambe prompts: koi hint nahi
        return when {
            SHORT_RE.containsMatchIn(q) -> Plan(if (low) 60 else 70, "Reply in at most 2 short sentences.")
            LONG_RE.containsMatchIn(q) -> Plan(if (low) 240 else 400, "Write a complete, detailed answer of 10 to 15 sentences. Do not stop early.")
            else -> Plan(if (low) 120 else 200, "Reply in 3 to 5 sentences.")
        }
    }

    /** adhoora aakhri jumla (token limit par kata hua) hata do */
    private fun tidy(a: String): String {
        val t = a.trim()
        if (t.length < 160 || t.last() in ".!?\u06D4\u2026\"')") return t
        val cut = maxOf(t.lastIndexOf(". "), t.lastIndexOf("! "), t.lastIndexOf("? "), t.lastIndexOf(".\n"))
        return if (cut > t.length / 2) t.substring(0, cut + 1) else t
    }

    /** v4.17: har model ka apna chat template (Qwen/SmolLM = ChatML; Llama3, Gemma, TinyLlama, Phi alag) */
    private fun buildPrompt(path: String, sys: String, user: String): String {
        val n = File(path).name.lowercase()
        return when {
            n.contains("llama-3") || n.contains("llama3") || n.contains("llama_3") ->
                "<|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            n.contains("gemma") ->
                "<start_of_turn>user\n$sys\n\n$user<end_of_turn>\n<start_of_turn>model\n"
            n.contains("tinyllama") || n.contains("zephyr") ->
                "<|system|>\n$sys</s>\n<|user|>\n$user</s>\n<|assistant|>\n"
            n.contains("phi-3") || n.contains("phi3") || n.contains("phi-4") || n.contains("phi4") ->
                "<|system|>\n$sys<|end|>\n<|user|>\n$user<|end|>\n<|assistant|>\n"
            else ->
                "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        }
    }

    fun ask(ctx: Context, question: String): String? {
        if (!enabled(ctx) || !LlamaBridge.available) {
            lastError = if (!LlamaBridge.available) "native_lib_missing" else "engine_off"
            return null
        }
        if (loading) {
            var waited = 0
            while (loading && waited < 180_000) {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                waited += 400
            }
            if (loadedPath == null) return null
        }
        val path = try {
            pickModel(ctx)
        } catch (e: Exception) {
            lastError = "pick:${e.message}"
            null
        } ?: return null
        try {
            if (loadedPath != path || !LlamaBridge.isLoaded()) {
                loading = true
                try {
                    unload()
                    val ctxSize = if (isLowRam(ctx)) 1024 else 2048
                    val threads = if (isLowRam(ctx)) 2 else 0
                    if (!LlamaBridge.load(path, ctxSize = ctxSize, threads = threads)) {
                        lastError = "native_load_fail"
                        loading = false
                        return null
                    }
                    loadedPath = path
                    lastError = null
                } finally {
                    loading = false
                }
            }
            val plan = planFor(ctx, question)
            val prompt = buildPrompt(path, SYS + (if (plan.hint.isNotEmpty()) " " + plan.hint else ""), question)
            val t0 = System.currentTimeMillis()
            // Low-RAM: shorter answers
            val maxTok = plan.maxTok
            var ans = LlamaBridge.generate(prompt, maxTokens = maxTok, temp = 0.35f, topP = 0.85f, topK = 30).trim()
            // garbage / loop filter (small models sometimes repeat tokens)
            if (isGarbageAnswer(ans)) {
                lastError = "garbage_output"
                return null
            }
            ans = ans.substringBefore("<|im_end|>")
                .substringBefore("<|endoftext|>")
                .substringBefore("<|im_start|>").substringBefore("<|eot_id|>").substringBefore("<end_of_turn>").substringBefore("</s>").substringBefore("<|end|>").substringBefore("<|user|>")
                .trim()
            ans = tidy(ans)
            if (ans.isBlank()) {
                lastError = "empty_generation"
                return null
            }
            val name = File(path).nameWithoutExtension
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            return "🧠 *GGUF offline* ($name, ${"%.0f".format(secs)}s):\n$ans"
        } catch (e: Throwable) {
            lastError = "ask_exception:${e.message}"
            return null
        }
    }
}
