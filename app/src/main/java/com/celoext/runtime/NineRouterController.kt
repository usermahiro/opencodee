package com.celoext.runtime

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.celoext.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs the 9Router gateway (https://github.com/decolua/9router) as a second process inside the
 * same Ubuntu rootfs as `opencode serve`.
 *
 * Nothing is installed with apt or npm: npm in proot tries to create `/usr` with the wrong
 * ownership and fails, and Ubuntu 24.04 ships Node 18 while the 9Router dashboard needs Node 20+.
 * Instead the official Node 22 tarball and the published 9router npm tarball are downloaded on
 * the Android side, unpacked with [TarExtractor], and only the files that are needed are copied
 * into the rootfs. The server then runs as `node /opt/9router/app/custom-server.js`, so the app
 * and the dashboard WebView reach it on 127.0.0.1 like any other localhost service.
 */
class NineRouterController(
    private val app: Application,
    val paths: RuntimePaths,
    private val prefs: Prefs,
) {
    companion object {
        const val DEFAULT_PORT = 20128
        /** Node binary inside the guest (official release, v22 line: Next 16 needs >= 20.9). */
        const val NODE_BIN = "/usr/local/bin/node"
        /** Unpacked npm package: package/app is the Next standalone build. */
        const val APP_DIR = "/opt/9router/app"
        const val SERVER_JS = "$APP_DIR/custom-server.js"
        const val NODE_DIST = "https://nodejs.org/dist/latest-v22.x"
        const val REGISTRY = "https://registry.npmjs.org/9router/latest"
        /**
         * The Next server that custom-server.js spawns renames itself ("next-server (v16…)"), so
         * `pkill -f 9router` misses it and it keeps the port forever (EADDRINUSE on every restart).
         * Scan /proc for anything running from the 9Router app dir or with a matching command line.
         * The [x] brackets stop this script (and the proot that carries it) from matching itself.
         */
        private val KILL_SCRIPT = """
            port=${'$'}1
            if [ -n "${'$'}port" ]; then
              command -v fuser >/dev/null 2>&1 && fuser -k -9 "${'$'}port/tcp" >/dev/null 2>&1
              if command -v lsof >/dev/null 2>&1; then for q in ${'$'}(lsof -t -i tcp:"${'$'}port" 2>/dev/null); do kill -9 "${'$'}q" 2>/dev/null; done; fi
            fi
            for sig in TERM KILL; do
              for d in /proc/[0-9]*; do
                pid=${'$'}{d#/proc/}
                [ "${'$'}pid" = "${'$'}${'$'}" ] && continue
                cmd=${'$'}(tr '\0' ' ' < "${'$'}d/cmdline" 2>/dev/null)
                cwd=${'$'}(readlink "${'$'}d/cwd" 2>/dev/null)
                case "${'$'}cmd" in
                  *[9]router*|*custom-serve[r].js*|*next-serve[r]*) kill -${'$'}sig "${'$'}pid" 2>/dev/null ;;
                  *) [ "${'$'}cwd" = "/opt/9router/app" ] && kill -${'$'}sig "${'$'}pid" 2>/dev/null ;;
                esac
              done
              sleep 0.5
            done
            exit 0
        """.trimIndent()
    }

    val state = MutableStateFlow<NineRouterState>(
        if (installed()) NineRouterState.Stopped else NineRouterState.NotInstalled
    )

    /** Both halves must exist: the node binary and the unpacked dashboard. */
    fun installed(): Boolean =
        File(paths.rootfs, NODE_BIN.removePrefix("/")).isFile && File(paths.rootfs, SERVER_JS.removePrefix("/")).isFile
    val log = MutableStateFlow<List<String>>(emptyList())

    private val proot = ProotCommand(paths, prefs)
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var installing = false

    fun logLine(s: String) {
        android.util.Log.w("Celo9R", s)
        log.update { (it + s).takeLast(600) }
    }

    val port: Int get() = prefs.ninerouterPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
    val url: String get() = "http://127.0.0.1:$port"
    val dashboardUrl: String get() = "$url/dashboard"
    val baseUrlV1: String get() = "$url/v1"

    val password: String get() = prefs.ninerouterPassword
    val jwtSecret: String get() = prefs.ninerouterJwt

    // ---------- install ----------

    /**
     * Downloads Node.js and 9Router on the Android side and unpacks them into the guest rootfs.
     * No apt, no npm: both were the source of the previous install failure.
     */
    suspend fun install(onLine: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (installing) return@withContext false
        installing = true
        try {
            // 1) Official Node 22 for arm64 (the APK only ships arm64-v8a).
            state.value = NineRouterState.Installing("Downloading Node.js")
            val sums = Downloader.getText("$NODE_DIST/SHASUMS256.txt")
            val (nodeSha, nodeFile) = sums.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 2 }
                .firstOrNull { it[1].startsWith("node-v22") && it[1].endsWith("-linux-arm64.tar.gz") }
                ?.let { it[0] to it[1] }
                ?: error("The Node.js release index has no linux-arm64 tarball")
            val nodeTgz = File(paths.downloads, nodeFile)
            Downloader.fetch("$NODE_DIST/$nodeFile", nodeTgz, nodeSha) { d, t ->
                state.value = NineRouterState.Installing("Downloading Node.js", frac(d, t), mb(d, t))
            }
            onLine("Node.js: $nodeFile")

            state.value = NineRouterState.Installing("Unpacking Node.js")
            val nodeTmp = File(paths.downloads, "node-extract")
            nodeTmp.deleteRecursively()
            TarExtractor.extract(nodeTgz, nodeTmp) { d, t ->
                state.value = NineRouterState.Installing("Unpacking Node.js", frac(d, t))
            }
            val node = nodeTmp.walkTopDown().firstOrNull { it.isFile && it.name == "node" && it.parentFile?.name == "bin" }
                ?: error("No bin/node in the Node.js archive")
            val nodeDst = paths.host(NODE_BIN)
            nodeDst.parentFile?.mkdirs()
            nodeDst.delete()
            // Same filesystem: rename is instant; copy only if that is refused.
            if (!node.renameTo(nodeDst)) node.copyTo(nodeDst, overwrite = true)
            nodeDst.setExecutable(true, false)
            nodeTmp.deleteRecursively()
            nodeTgz.delete()

            // 2) The published 9router package: its `app/` folder is a ready Next.js standalone build.
            state.value = NineRouterState.Installing("Downloading 9Router")
            val meta = org.json.JSONObject(Downloader.getText(REGISTRY))
            val version = meta.optString("version", "?")
            val dist = meta.getJSONObject("dist")
            val integrity = dist.optString("integrity")
            val nrTgz = File(paths.downloads, "9router-$version.tgz")
            Downloader.fetch(dist.getString("tarball"), nrTgz, null) { d, t ->
                state.value = NineRouterState.Installing("Downloading 9Router", frac(d, t), mb(d, t))
            }
            if (integrity.isNotBlank() && !sha512Matches(nrTgz, integrity)) {
                nrTgz.delete()
                error("The 9Router download does not match its published checksum")
            }
            onLine("9Router: $version")

            state.value = NineRouterState.Installing("Unpacking 9Router")
            val nrTmp = File(paths.downloads, "nr-extract")
            nrTmp.deleteRecursively()
            TarExtractor.extract(nrTgz, nrTmp) { d, t ->
                state.value = NineRouterState.Installing("Unpacking 9Router", frac(d, t))
            }
            val appSrc = File(nrTmp, "package/app")
            if (!appSrc.isDirectory) error("The 9router package has no app/ directory")
            val appDst = paths.host(APP_DIR)
            appDst.parentFile?.mkdirs()
            appDst.deleteRecursively()
            // Same filesystem, so this is a rename; copy only if the rename is refused.
            if (!appSrc.renameTo(appDst)) {
                appSrc.copyRecursively(appDst, overwrite = true)
                if (!File(appDst, "custom-server.js").isFile) error("Could not copy the 9Router app into the environment")
                appSrc.deleteRecursively()
            }
            nrTmp.deleteRecursively()
            nrTgz.delete()

            state.value = NineRouterState.Installing("Checking Node.js")
            val out = StringBuilder()
            val code = runGuest(listOf(NODE_BIN, "--version"), timeoutSec = 120) { out.appendLine(it); onLine(it) }
            val nodeVersion = out.toString().trim()
            if (code != 0 || !nodeVersion.contains(Regex("""v\d+\.\d+\.\d+"""))) {
                error("Node.js does not start inside the environment (exit $code): ${nodeVersion.takeLast(300)}")
            }
            prefs.ninerouterVersion = "9router $version · $nodeVersion"
            logLine("Installed 9Router $version with $nodeVersion")
            state.value = NineRouterState.Stopped
            true
        } catch (e: Throwable) {
            logLine("9Router install failed: ${e.message}")
            state.value = NineRouterState.Crashed(e.message ?: e.javaClass.simpleName)
            false
        } finally {
            installing = false
        }
    }

    /** Verifies an npm `dist.integrity` value ("sha512-<base64>"). */
    private fun sha512Matches(f: File, integrity: String): Boolean {
        if (!integrity.startsWith("sha512-")) return false
        val md = java.security.MessageDigest.getInstance("SHA-512")
        f.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val got = android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
        return got == integrity.removePrefix("sha512-")
    }

    private fun frac(done: Long, total: Long) = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    private fun mb(done: Long, total: Long) =
        if (total > 0) "%.0f / %.0f MB".format(done / 1e6, total / 1e6) else "%.0f MB".format(done / 1e6)

    // ---------- run ----------

    suspend fun supervise() = withContext(Dispatchers.IO) {
        stopRequested = false
        var failures = 0
        var windowStart = System.currentTimeMillis()
        while (isActive && !stopRequested) {
            state.value = NineRouterState.Starting
            // If a previous instance survived (app restart / crash), reuse it instead of fighting for the port.
            val existing = if (portBusy()) runCatching { healthVersionOrThrow() }.getOrNull() else null
            if (existing != null) {
                logLine("9Router is already running on port $port: using it")
                state.value = NineRouterState.Running(existing)
                var bad0 = 0
                while (isActive && !stopRequested && bad0 < 3) {
                    delay(5_000)
                    bad0 = if (runCatching { healthVersionOrThrow() }.isSuccess) 0 else bad0 + 1
                }
                if (!stopRequested) continue
                break
            }
            killLeftover()
            var waited = 0
            while (isActive && portBusy() && waited < 15_000) { delay(250); waited += 250 }
            if (waited > 0) logLine("Waited ${waited}ms for port $port to be free")
            if (portBusy()) {
                // Could not kill the holder. If it is a healthy 9Router (an instance from before), use it instead of fighting for the port.
                val v = runCatching { healthVersionOrThrow() }.getOrNull()
                if (v != null) {
                    logLine("9Router is already running on port $port: using it")
                    state.value = NineRouterState.Running(v)
                    var bad = 0
                    while (isActive && !stopRequested && bad < 3) {
                        delay(5_000)
                        bad = if (runCatching { healthVersionOrThrow() }.isSuccess) 0 else bad + 1
                    }
                    continue
                }
                logLine("Port $port is still in use by another process. Change the 9Router port in Settings, or stop whatever else is listening on it.")
                state.value = NineRouterState.Crashed("Port $port is already in use")
                delay(5_000)
                continue
            }
            val pb = proot.processBuilder(
                listOf(
                    NODE_BIN, "--dns-result-order=ipv4first", "--max-old-space-size=4096",
                    SERVER_JS,
                ),
                cwd = APP_DIR,
                extraEnv = mapOf(
                    "PORT" to port.toString(),
                    "HOSTNAME" to "127.0.0.1",
                    "INITIAL_PASSWORD" to password,
                    "JWT_SECRET" to jwtSecret,
                    "DATA_DIR" to "/root/.9router",
                    "NODE_ENV" to "production",
                    "NEXT_TELEMETRY_DISABLED" to "1",
                    "NO_UPDATE_NOTIFIER" to "true",
                ),
            )
            val p = try { pb.start() } catch (e: Exception) {
                state.value = NineRouterState.Crashed("Could not start 9Router: ${e.message}")
                return@withContext
            }
            process = p
            val reader = launch { runCatching { p.inputStream.bufferedReader().forEachLine { logLine(it); if (it.contains("EADDRINUSE")) { killLeftover(); p.destroy() } } } }
            try {
                var version: String? = null
                val deadline = System.currentTimeMillis() + 180_000
                while (isActive && p.isAlive && System.currentTimeMillis() < deadline && version == null) {
                    version = try { healthVersionOrThrow() } catch (e: Exception) { null }
                    if (version == null) delay(500)
                }
                if (version != null) {
                    state.value = NineRouterState.Running(version)
                    failures = 0
                } else if (p.isAlive) {
                    // The CLI is up but /api/health never answered in 3 minutes: restart it with the rest of the log.
                    logLine("9Router started but never answered on port $port — restarting")
                    p.destroy()
                }
                val dog = launch {
                    var bad = 0
                    while (isActive && p.isAlive) {
                        delay(20_000)
                        val ok = runCatching { kotlinx.coroutines.withTimeout(10_000) { healthVersionOrThrow() } }.isSuccess
                        bad = if (ok) 0 else bad + 1
                        if (bad >= 3) { logLine("9Router stopped answering — restarting"); p.destroy(); break }
                    }
                }
                runInterruptible { p.waitFor() }
                dog.cancel()
            } finally {
                p.destroy()
                if (prefs.rootMode) Root.killProot(paths.rootfs.path)
                if (p.isAlive) { runCatching { p.waitFor(3, TimeUnit.SECONDS) }; if (p.isAlive) p.destroyForcibly() }
                process = null
                reader.cancel()
            }
            if (stopRequested) break
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            logLine("9Router exited (code $code)")
            val now = System.currentTimeMillis()
            if (now - windowStart > 120_000) { failures = 0; windowStart = now }
            failures++
            state.value = NineRouterState.Crashed("9Router stopped (code $code). Restarting…")
            delay((1500L * failures).coerceAtMost(30_000))
        }
        state.value = NineRouterState.Stopped
    }

    @Volatile var userStopped = false
    val serverAlive get() = process != null

    fun portBusy(): Boolean = try {
        java.net.Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300); true }
    } catch (_: Exception) { false }

    /** Kill leftover 9Router processes (the CLI and the detached server it spawned). */
    fun killLeftover() {
        runCatching {
            val p = proot.processBuilder(
                listOf("/bin/sh", "-c", KILL_SCRIPT, "sh", port.toString()),
                cwd = "/root",
            ).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        if (prefs.rootMode) runCatching { Root.run("pkill -f '[9]router'", 10) }
    }

    fun requestStop() {
        stopRequested = true
        process?.destroy()
        if (prefs.rootMode) Thread { Root.killProot(paths.rootfs.path) }.start()
        Thread { killLeftover() }.apply { isDaemon = true }.start()
    }

    fun restartServer() {
        stopServer()
        Thread {
            var waited = 0
            while (waited < 10_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
            killLeftover()
            if (prefs.rootMode) Root.killProot(paths.rootfs.path)
            startServer()
        }.apply { isDaemon = true }.start()
    }

    fun startServer() {
        val s = state.value
        if (s is NineRouterState.NotInstalled || s is NineRouterState.Installing) return
        // The environment (rootfs + proot) is shared with the OpenCode server: without it there is nothing to run in.
        if (!paths.rootfsReady) {
            logLine("9Router needs the environment first — install it from Settings → Local server")
            return
        }
        userStopped = false
        try {
            ContextCompat.startForegroundService(app, Intent(app, NineRouterService::class.java).setAction(NineRouterService.ACTION_START))
        } catch (e: Throwable) {
            // A missing manifest entry or a blocked foreground service used to fail silently.
            logLine("Could not start the 9Router service: ${e.message}")
            state.value = NineRouterState.Crashed(e.message ?: "Could not start the 9Router service")
        }
    }

    fun stopServer() {
        userStopped = true
        requestStop()
        app.stopService(Intent(app, NineRouterService::class.java))
        if (state.value !is NineRouterState.NotInstalled) state.value = NineRouterState.Stopped
    }

    /** Run a command inside the same Ubuntu guest as the OpenCode server. */
    suspend fun runGuest(
        argv: List<String>,
        cwd: String = "/root",
        timeoutSec: Long = 900,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val p = proot.processBuilder(argv, cwd, extraEnv).start()
        val rd = launch { runCatching { p.inputStream.bufferedReader().forEachLine { onLine(it); logLine(it) } } }
        val done = try { runInterruptible { p.waitFor(timeoutSec, TimeUnit.SECONDS) } } finally { if (p.isAlive) p.destroyForcibly() }
        if (!done) { rd.cancel(); return@withContext -1 }
        rd.join()
        p.exitValue()
    }

    // ---------- health ----------

    private fun healthVersionOrThrow(): String {
        val c = URL("$url/api/health").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", "opencode-mobile")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val v = runCatching { org.json.JSONObject(body).optString("version") }.getOrDefault("")
            if (v.isBlank()) "running" else v
        } finally {
            c.disconnect()
        }
    }
}

sealed interface NineRouterState {
    /** Node.js + 9Router are not installed in the environment yet. */
    data object NotInstalled : NineRouterState
    data class Installing(val step: String, val progress: Float? = null, val detail: String = "") : NineRouterState
    data object Stopped : NineRouterState
    data object Starting : NineRouterState
    data class Running(val version: String) : NineRouterState
    data class Crashed(val message: String) : NineRouterState
}
