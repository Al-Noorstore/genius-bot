package com.alnoor.autobot

import org.json.JSONObject
import java.io.File

/**
 * v4.15 PROJECT DETECTOR — project ki files dekh kar pehchanta hai:
 *  stack (Node/Python/Java/Gradle/Android/Flutter/Rust/Go/PHP/Ruby/.NET/static), dependencies,
 *  install/build/test/run commands, aur kaam KAHAN chalana hai: 📱 phone ya ☁️ GitHub cloud.
 * Rule: halka kaam (static HTML, bina-dependency Python script) => phone.
 *       install/compile/build wala kaam (npm, pip packages, gradle, maven, cargo, go, dotnet...) => cloud.
 */
object ProjectDetector {

    class Analysis(
        val stacks: List<String>, val deps: List<String>, val install: String, val build: String,
        val test: String, val run: String, val where: String, val reason: String, val sub: String
    ) {
        val isCloud get() = where == "cloud"

        /** cloud pe ek hi command (har cloud run alag khali machine hai) */
        fun cloudCommand(): String {
            val parts = listOf(install, build, test).filter { it.isNotBlank() }
            if (parts.isEmpty()) return ""
            val cd = if (sub.isNotEmpty()) "cd '$sub' && " else ""
            return cd + parts.joinToString(" && ")
        }

        fun hint(): String =
            if (stacks.isEmpty()) "koi known project type nahi mila (files dekh kar khud tay karo)"
            else "stack=" + stacks.joinToString("+") + "; run on=" + where.uppercase() + " (" + reason + ")" +
                (if (isCloud && cloudCommand().isNotEmpty()) "; cloud command (install+build EK hi command mein)=" + cloudCommand() else "") +
                (if (!isCloud && run.isNotBlank()) "; phone run=" + run else "")

        fun report(name: String): String {
            if (stacks.isEmpty()) return "🔎 *$name*: koi known project type nahi mila (package.json, requirements.txt, pom.xml, build.gradle, Cargo.toml, go.mod, pubspec.yaml, index.html...)."
            val sb = StringBuilder("🔎 *Project analysis: $name*\n")
            sb.append("• Stack: ").append(stacks.joinToString(", ")).append('\n')
            if (sub.isNotEmpty()) sb.append("• Folder: ").append(sub).append('\n')
            if (deps.isNotEmpty()) sb.append("• Dependencies (").append(deps.size).append("): ").append(deps.take(10).joinToString(", ")).append(if (deps.size > 10) "…" else "").append('\n')
            if (install.isNotBlank()) sb.append("• Install: `").append(install).append("`\n")
            if (build.isNotBlank()) sb.append("• Build: `").append(build).append("`\n")
            if (test.isNotBlank()) sb.append("• Test: `").append(test).append("`\n")
            if (run.isNotBlank()) sb.append("• Run: `").append(run).append("`\n")
            sb.append(if (isCloud) "☁️ Kahan: GitHub cloud — " else "📱 Kahan: phone — ").append(reason).append('\n')
            sb.append(if (isCloud) "Chalane ke liye: *project auto*" else "Chalane ke liye: *project auto* (phone par chalega)")
            return sb.toString()
        }
    }

    private val SKIP = setOf("node_modules", ".git", "build", "dist", ".gradle", "venv", ".venv", "__pycache__", ".autobot_backup", ".idea")

    private fun find(dir: File, vararg names: String): File? {
        var best: File? = null
        var bestDepth = 99
        dir.walkTopDown().maxDepth(3).onEnter { it == dir || it.name !in SKIP }.forEach { f ->
            if (f.isFile && f.name in names) {
                val depth = f.relativeTo(dir).path.count { it == '/' || it == '\\' }
                if (depth < bestDepth) { best = f; bestDepth = depth }
            }
        }
        return best
    }

    private fun rel(dir: File, f: File?): String {
        val p = f?.parentFile ?: return ""
        return if (p == dir) "" else p.relativeTo(dir).path.replace('\\', '/')
    }

    private val HEAVY_PY = Regex("numpy|pandas|scipy|torch|tensorflow|opencv|scikit|sklearn|matplotlib|pillow|lxml|cryptography|grpc", RegexOption.IGNORE_CASE)

    fun analyze(dir: File): Analysis {
        val stacks = ArrayList<String>()
        var deps: List<String> = emptyList()
        var install = ""; var build = ""; var test = ""; var run = ""
        var where = "cloud"; var reason = ""; var sub = ""
        var primarySet = false
        fun primary(s: String, i: String, b: String, t: String, r: String, w: String, why: String, sb: String, d: List<String>) {
            if (primarySet) return
            primarySet = true; install = i; build = b; test = t; run = r; where = w; reason = why; sub = sb; deps = d
        }
        if (!dir.exists()) return Analysis(emptyList(), emptyList(), "", "", "", "", "phone", "folder nahi mila", "")

        // ---- Android / Gradle / Flutter ----
        val manifest = find(dir, "AndroidManifest.xml")
        val gradle = find(dir, "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val pubspec = find(dir, "pubspec.yaml")
        if (pubspec != null) {
            stacks.add("Flutter")
            primary("Flutter", "flutter pub get", "flutter build apk --release", "", "", "cloud", "Flutter SDK + build bhari hai", rel(dir, pubspec), emptyList())
        } else if (gradle != null && manifest != null) {
            stacks.add("Android (Gradle)")
            primary("Android", "", "", "", "", "cloud", "Android build bhari hai — 'github build' (APK workflow) behtar", rel(dir, gradle), emptyList())
        } else if (gradle != null) {
            stacks.add("Gradle (Java/Kotlin)")
            val wrapper = File(gradle.parentFile, "gradlew").exists()
            primary("Gradle", "", if (wrapper) "chmod +x gradlew && ./gradlew build" else "gradle build", "", "", "cloud", "JDK + Gradle build bhari hai", rel(dir, gradle), emptyList())
        }

        // ---- Node ----
        val pkg = find(dir, "package.json")
        if (pkg != null) {
            var label = "Node.js"
            var nd: List<String> = emptyList()
            var i = "npm install"; var b = ""; var t = ""; var r = ""
            try {
                val j = JSONObject(pkg.readText())
                val names = ArrayList<String>()
                j.optJSONObject("dependencies")?.keys()?.forEach { names.add(it) }
                j.optJSONObject("devDependencies")?.keys()?.forEach { names.add(it) }
                nd = names
                val tags = listOf("react-native", "expo", "react", "next", "vue", "vite", "angular", "svelte", "express", "electron", "nestjs", "typescript").filter { names.any { n -> n == it || n.startsWith("@$it/") || n.startsWith("$it-") } }
                if (tags.isNotEmpty()) label += " (" + tags.take(3).joinToString(", ") + ")"
                val dirp = pkg.parentFile
                val mgr = when {
                    File(dirp, "pnpm-lock.yaml").exists() -> "pnpm"
                    File(dirp, "yarn.lock").exists() -> "yarn"
                    else -> "npm"
                }
                i = when (mgr) {
                    "pnpm" -> "npm i -g pnpm && pnpm install --frozen-lockfile"
                    "yarn" -> "yarn install --frozen-lockfile"
                    else -> if (File(dirp, "package-lock.json").exists()) "npm ci" else "npm install"
                }
                val sc = j.optJSONObject("scripts")
                if (sc != null) {
                    if (sc.has("build")) b = if (mgr == "yarn") "yarn build" else "$mgr run build"
                    val ts = sc.optString("test", "")
                    if (ts.isNotBlank() && !ts.contains("no test specified")) t = if (mgr == "yarn") "yarn test" else "$mgr test"
                    if (sc.has("start")) r = if (mgr == "yarn") "yarn start" else "$mgr start"
                }
            } catch (_: Exception) {}
            stacks.add(label)
            primary(label, i, b, t, r, "cloud", "npm packages install + build bhari kaam hai", rel(dir, pkg), nd)
        }

        // ---- Python ----
        val req = find(dir, "requirements.txt")
        val pyproj = find(dir, "pyproject.toml", "setup.py", "Pipfile")
        val hasPy = dir.walkTopDown().maxDepth(2).onEnter { it == dir || it.name !in SKIP }.any { it.isFile && it.extension == "py" }
        if (req != null || pyproj != null || hasPy) {
            stacks.add("Python")
            val pd = ArrayList<String>()
            req?.let { f -> f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-") }
                .forEach { pd.add(it.split(Regex("[=<>!~\\[; ]"))[0]) } }
            val main = listOf("main.py", "app.py", "manage.py", "run.py").firstOrNull { n -> find(dir, n) != null }
            val mainRel = main?.let { rel(dir, find(dir, it)).let { s -> if (s.isEmpty()) it else "$s/$it" } } ?: ""
            when {
                req != null -> {
                    val heavy = pd.any { HEAVY_PY.containsMatchIn(it) }
                    val inst = "pip install -r requirements.txt"
                    val tst = if (pd.any { it.equals("pytest", true) } || find(dir, "pytest.ini") != null) "pytest -q" else ""
                    primary("Python", inst, "", tst, if (mainRel.isNotEmpty()) "python $mainRel" else "", "cloud",
                        if (heavy) "bhari/compiled packages (numpy/torch...) phone par nahi chalte" else "pip packages install karne hain — cloud zyada bharosemand", rel(dir, req), pd)
                }
                pyproj != null -> primary("Python", if (pyproj.name == "Pipfile") "pip install pipenv && pipenv install" else "pip install .", "", "", if (mainRel.isNotEmpty()) "python $mainRel" else "", "cloud", "package install cloud par behtar", rel(dir, pyproj), pd)
                else -> primary("Python", "", "", "", if (mainRel.isNotEmpty()) "python $mainRel" else "", "phone", "koi external dependency nahi — script phone par chal sakti hai", "", pd)
            }
        }

        // ---- Java Maven ----
        find(dir, "pom.xml")?.let { stacks.add("Java (Maven)"); primary("Maven", "", "mvn -B package", "", "", "cloud", "JDK + Maven build bhari hai", rel(dir, it), emptyList()) }
        // ---- Rust / Go / PHP / Ruby / .NET ----
        find(dir, "Cargo.toml")?.let { stacks.add("Rust"); primary("Rust", "", "cargo build --release", "cargo test", "", "cloud", "Rust compile bhari hai", rel(dir, it), emptyList()) }
        find(dir, "go.mod")?.let { stacks.add("Go"); primary("Go", "go mod download", "go build ./...", "go test ./...", "", "cloud", "Go compile cloud par behtar", rel(dir, it), emptyList()) }
        find(dir, "composer.json")?.let { stacks.add("PHP (Composer)"); primary("PHP", "composer install", "", "", "", "cloud", "composer packages install", rel(dir, it), emptyList()) }
        find(dir, "Gemfile")?.let { stacks.add("Ruby"); primary("Ruby", "bundle install", "", "", "", "cloud", "gems install", rel(dir, it), emptyList()) }
        dir.walkTopDown().maxDepth(3).onEnter { it == dir || it.name !in SKIP }.firstOrNull { it.isFile && (it.extension == "csproj" || it.extension == "sln") }
            ?.let { stacks.add(".NET"); primary(".NET", "dotnet restore", "dotnet build -c Release", "", "", "cloud", ".NET build bhari hai", rel(dir, it), emptyList()) }
        if (find(dir, "Dockerfile") != null) stacks.add("Docker")

        // ---- static web ----
        if (stacks.isEmpty() && find(dir, "index.html") != null) {
            stacks.add("Static web (HTML/JS)")
            primary("Static", "", "", "", "", "phone", "sirf HTML/JS — phone mein seedha khul jata hai", "", emptyList())
        }
        if (!primarySet) return Analysis(stacks, emptyList(), "", "", "", "", "phone", "pehchan nahi hui", "")
        return Analysis(stacks, deps, install, build, test, run, where, reason, sub)
    }
}
