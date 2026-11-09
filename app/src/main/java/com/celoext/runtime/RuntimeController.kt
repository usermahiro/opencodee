package com.celoext.runtime

import com.celoext.data.Vault
import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.celoext.api.OpenCodeApi
import com.celoext.data.Memory
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
import java.util.concurrent.TimeUnit

/**
 * Owns the local server: installs the Ubuntu rootfs and OpenCode, runs
 * `opencode serve` inside proot and keeps the process alive.
 */
class RuntimeController(
    private val app: Application,
    val paths: RuntimePaths,
    private val prefs: Prefs,
    private val api: OpenCodeApi,
) {
    companion object {
        const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
        const val ROOTFS_SHA = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
        // Pin a tested version: the client is written against its API.
        const val OC_VERSION = "2.2.5"
        const val OC_BIN = "/usr/local/bin/opencode"
        fun ocUrl(v: String) = "https://github.com/anomalyco/opencode/releases/download/v$v/opencode-linux-arm64.tar.gz"
    }

    val state = MutableStateFlow<RuntimeState>(
        if (prefs.runtimeReady && paths.opencodeBin.exists()) RuntimeState.Stopped else RuntimeState.NotInstalled
    )
    val log = MutableStateFlow<List<String>>(emptyList())

    private val proot = ProotCommand(paths, prefs)
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var installing = false
    @Volatile private var resetting = false

    fun logLine(s: String) {
        android.util.Log.w("CeloOC", s)
        log.update { (it + s).takeLast(600) }
    }

    // ---------- install ----------

    suspend fun install() = withContext(Dispatchers.IO) {
        if (installing || resetting) return@withContext
        installing = true
        try {
            // Rootfs (~800 MB) + OpenCode (~240 MB) + downloads: fail early with a clear message instead of ENOSPC.
            val root = paths.rootfs.parentFile ?: paths.rootfs
            val free = runCatching { android.os.StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < 1_500_000_000L) {
                error("Not enough free space: about 1.5 GB is needed, ${free / (1024 * 1024)} MB is free")
            }
            step("Preparing", null)
            paths.libDir.mkdirs()
            File(paths.nativeDir, "libtalloc.so").copyTo(File(paths.libDir, "libtalloc.so.2"), overwrite = true)

            if (!paths.rootfsReady) {
                val tgz = File(paths.downloads, "ubuntu-base.tar.gz")
                step("Downloading environment", 0f)
                Downloader.fetch(ROOTFS_URL, tgz, ROOTFS_SHA) { d, t -> step("Downloading environment", frac(d, t), mb(d, t)) }
                step("Unpacking environment", 0f)
                paths.rootfs.deleteRecursively()
                val r = TarExtractor.extract(tgz, paths.rootfs) { d, t -> step("Unpacking environment", frac(d, t)) }
                logLine("rootfs: ${r.files} files, skipped ${r.skipped}")
                configureRootfs()
                prefs.seededSkills = "" // fresh Ubuntu: the default skills have to be copied in again
                tgz.delete()
            }

            Vault.restore()
            Memory.sync() // copies the default skills and writes their rules into AGENTS.md
            // The checksum comes from GitHub (asset digest); no checksum, no install.
            val ocSha = Downloader.githubAssetSha256("https://api.github.com/repos/anomalyco/opencode/releases/tags/v$OC_VERSION", "opencode-linux-arm64.tar.gz")
                ?: error("GitHub gave no checksum for OpenCode $OC_VERSION, so it was not installed")
            installOpencode(OC_VERSION, OC_URL_OR(OC_VERSION), ocSha)
            smokeTest()

            prefs.runtimeReady = true
            state.value = RuntimeState.Stopped
        } catch (e: Throwable) {
            logLine("Install failed: ${e.message}")
            state.value = RuntimeState.InstallFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            installing = false
        }
    }

    private fun OC_URL_OR(v: String) = ocUrl(v)

    private fun installOpencode(version: String, url: String, sha: String?) {
        val tgz = File(paths.downloads, "opencode-$version.tar.gz")
        step("Downloading OpenCode $version", 0f)
        Downloader.fetch(url, tgz, sha) { d, t -> step("Downloading OpenCode $version", frac(d, t), mb(d, t)) }
        step("Installing OpenCode", 0f)
        val tmp = File(paths.downloads, "oc-extract")
        tmp.deleteRecursively()
        TarExtractor.extract(tgz, tmp) { d, t -> step("Installing OpenCode", frac(d, t)) }
        val bin = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" } ?: error("No opencode binary in the archive")
        paths.opencodeBin.parentFile?.mkdirs()
        paths.opencodeBin.delete()
        bin.copyTo(paths.opencodeBin, overwrite = true)
        paths.opencodeBin.setExecutable(true, false)
        tmp.deleteRecursively()
        tgz.delete()
        prefs.opencodeVersion = version
    }

    private suspend fun smokeTest() {
        step("Checking startup", null)
        suspend fun once(): Pair<Int, String> {
            val out = StringBuilder()
            val code = runGuest(listOf(OC_BIN, "--version"), timeoutSec = 120) { out.appendLine(it) }
            return code to out.toString()
        }
        var (code, out) = once()
        if (code != 0 || !Regex("\\d+\\.\\d+\\.\\d+").containsMatchIn(out)) {
            logLine("First launch failed (code $code), retrying in compatibility mode")
            prefs.noSeccomp = true
            val second = once()
            code = second.first; out = second.second
            if (code != 0 || !Regex("\\d+\\.\\d+\\.\\d+").containsMatchIn(out)) {
                prefs.noSeccomp = false
                error("opencode does not start (code $code): ${out.trim().takeLast(400)}")
            }
        }
        logLine("opencode: ${out.trim()}")
    }

    private fun configureRootfs() {
        fun w(guest: String, text: String) {
            val f = paths.host(guest)
            f.parentFile?.mkdirs()
            f.delete()
            f.writeText(text)
        }
        w("/etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        w("/etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        w("/etc/hostname", "opencode-mobile\n")
        // apt inside proot can't drop privileges to the _apt user.
        w("/etc/apt/apt.conf.d/99proot", "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
        val pem = app.assets.open("cacert.pem").use { it.readBytes() }
        for (p in listOf("/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/cert.pem")) {
            val f = paths.host(p); f.parentFile?.mkdirs(); f.delete(); f.writeBytes(pem)
        }
        paths.host("/root/work").mkdirs()
        paths.host("/root/.config/opencode").mkdirs()
        paths.host("/tmp").mkdirs()
        File(paths.rootfs, ".oc-configured").writeText("1")
    }

    private fun step(name: String, p: Float?, detail: String = "") {
        state.value = RuntimeState.Installing(name, p, detail)
    }
    private fun frac(d: Long, t: Long) = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else null
    private fun mb(d: Long, t: Long) = if (t > 0) "%.0f / %.0f MB".format(d / 1e6, t / 1e6) else "%.0f MB".format(d / 1e6)

    // ---------- run ----------

    suspend fun supervise() = withContext(Dispatchers.IO) {
        stopRequested = false
        var failures = 0
        var windowStart = System.currentTimeMillis()
        while (isActive && !stopRequested) {
            state.value = RuntimeState.Starting
            // leftovers from the run before (or a crash) would keep the port: pkill -f opencode first
            killLeftoverServer()
            var waited = 0
            while (isActive && portBusy() && waited < 8_000) { delay(250); waited += 250 }
            if (waited > 0) logLine("Waited ${waited}ms for the port to be free")
            // root mode: files the server made as real root (0600) would lock the app out of its own config
            if (prefs.rootMode) runCatching {
                val uid = android.os.Process.myUid()
                val dirs = listOf("root/.config", "root/.local", "root/work").map { File(paths.rootfs, it) }.filter { it.exists() }
                if (dirs.isNotEmpty()) Root.run(dirs.joinToString("; ") { "chown -R $uid:$uid ${Root.quote(it.path)}; chmod -R u+rwX ${Root.quote(it.path)}" }, 90)
            }
            val pb = proot.processBuilder(
                listOf(OC_BIN, "serve", "--hostname", "127.0.0.1", "--port", prefs.serverPort),
                cwd = "/root/work",
                extraEnv = mapOf(
                    "OPENCODE_SERVER_PASSWORD" to prefs.serverPassword,
                    "OPENCODE_DISABLE_AUTOUPDATE" to "true",
                ),
            )
            val p = try { pb.start() } catch (e: Exception) {
                state.value = RuntimeState.Crashed("Could not start the server: ${e.message}")
                return@withContext
            }
            process = p
            val reader = launch { runCatching { p.inputStream.bufferedReader().forEachLine { logLine(it) } } }
            try {
                var version: String? = null
                val deadline = System.currentTimeMillis() + 120_000
                while (isActive && p.isAlive && System.currentTimeMillis() < deadline && version == null) {
                    val t0 = System.currentTimeMillis()
                    version = try { api.healthVersionOrThrow() } catch (e: Exception) { android.util.Log.d("ocm-health", "err ${e.javaClass.simpleName} ${e.message}"); logLine("health: ${e.javaClass.simpleName} ${e.message}"); null }
                    android.util.Log.d("ocm-health", "version=$version in ${System.currentTimeMillis() - t0}ms")
                    if (version == null) delay(250)
                }
                if (version != null) {
                    state.value = RuntimeState.Running(version)
                    prefs.opencodeVersion = version
                    failures = 0
                }
                // Watchdog: the process is alive but not answering (hung), so restart it instead of waiting forever.
                val dog = launch {
                    var bad = 0
                    while (isActive && p.isAlive) {
                        delay(15_000)
                        val ok = runCatching { kotlinx.coroutines.withTimeout(10_000) { api.healthVersionOrThrow() } }.isSuccess
                        bad = if (ok) 0 else bad + 1
                        if (bad >= 3) { logLine("Server stopped answering — restarting"); p.destroy(); break }
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
            logLine("Server exited (code $code)")
            val now = System.currentTimeMillis()
            if (now - windowStart > 120_000) { failures = 0; windowStart = now }
            failures++
            state.value = RuntimeState.Crashed("Server stopped (code $code). Restarting…")
            // don't give up: restart with a growing pause (up to 30 s) until the user stops the server
            delay((1500L * failures).coerceAtMost(30_000))
        }
        state.value = RuntimeState.Stopped
    }

    /** The user stopped the server: then we don't start it when they come back to the app. */
    @Volatile var userStopped = false

    /** The server process is still alive (after stopServer it doesn't exit right away). */
    val serverAlive get() = process != null

    /** Something is still listening on the server port (a leftover `opencode serve` from an earlier run). */
    fun portBusy(): Boolean {
        val port = prefs.serverPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stop leftover OpenCode processes inside the Ubuntu guest.
     *
     * Guest cleanup runs through pkill inside proot (or Root in root mode).
     * The tracked proot client is stopped by requestStop(). Avoid desktop-JVM-only
     * process enumeration APIs because they are unavailable on Android's runtime.
     * The pattern is written `[o]pencode` so it doesn't match this command's own line.
     */
    fun killLeftoverServer() {
        runCatching {
            val p = proot.processBuilder(
                listOf("/bin/sh", "-c", "pkill -f '[o]pencode'; sleep 0.4; pkill -9 -f '[o]pencode'; exit 0"),
                cwd = "/root",
            ).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        if (prefs.rootMode) runCatching { Root.run("pkill -f '[o]pencode'", 10) }
    }

    fun requestStop() {
        stopRequested = true
        process?.destroy()
        if (prefs.rootMode) Thread { Root.killProot(paths.rootfs.path) }.start()
        // in the background: starting a proot takes a moment and this can be called from the main thread
        Thread { killLeftoverServer() }.apply { isDaemon = true }.start()
    }

    /** Stop, wait until the port is really free, start again (Restart button, root-mode switch). */
    /**
     * Stops the server, kills whatever is left (`pkill -f opencode`, in the mode that started it), runs [beforeStart]
     * (for settings that must change only once the old process is gone) and starts it again.
     */
    fun restartServer(beforeStart: () -> Unit = {}) {
        stopServer()
        Thread {
            var waited = 0
            while (waited < 10_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
            killLeftoverServer()
            if (prefs.rootMode) Root.killProot(paths.rootfs.path)
            runCatching { beforeStart() }
            startServer()
        }.apply { isDaemon = true }.start()
    }

    /**
     * Switch root mode on/off. Asks for root first. The running server is killed in the OLD mode before the switch
     * (a root process can't be killed from the app, and the other way round), then files made as root go back to the app
     * when turning it off, and the server starts again in the new mode.
     */
    suspend fun setRootMode(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (on && !Root.check()) return@withContext false
        restartServer {
            val was = prefs.rootMode
            if (on) { Root.forgetProbe(); prefs.rootMode = true }
            else {
                prefs.rootMode = false
                if (was) Root.handBack(app.filesDir.path, android.os.Process.myUid())
            }
        }
        true
    }

    /** Start the server through a foreground service, otherwise Android kills the process in the background. */
    fun startServer() {
        if (resetting) return
        val s = state.value
        if (s is RuntimeState.NotInstalled || s is RuntimeState.Installing || s is RuntimeState.InstallFailed) return
        userStopped = false
        ContextCompat.startForegroundService(app, Intent(app, ServerService::class.java).setAction(ServerService.ACTION_START))
    }

    fun stopServer() {
        userStopped = true
        requestStop()
        app.stopService(Intent(app, ServerService::class.java))
        if (state.value !is RuntimeState.NotInstalled) state.value = RuntimeState.Stopped
    }

    /** Run a command inside Ubuntu (installing git and so on). */
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

    /** git, ripgrep and the like: without them the agent in Ubuntu is nearly useless. */
    suspend fun installDevTools(onLine: (String) -> Unit): Int {
        val script = "apt-get update && apt-get install -y --no-install-recommends git ripgrep curl ca-certificates openssh-client unzip less nano python3 build-essential"
        return runGuest(listOf("/bin/bash", "-lc", script), onLine = onLine)
    }

    fun resetRuntime() {
        if (resetting) return
        resetting = true
        // Deleting the environment is hundreds of megabytes and thousands of files: not on the main thread.
        Thread {
            try {
                stopServer()
                var waited = 0
                while (waited < 10_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
                killLeftoverServer()
                // The chat database is only consistent once the server has stopped writing.
                Vault.save()
                if (prefs.rootMode) Root.run("rm -rf ${Root.quote(paths.rootfs.path)} ${Root.quote(paths.downloads.path)}", 300)
                paths.rootfs.deleteRecursively()
                paths.downloads.deleteRecursively()
                prefs.runtimeReady = false
            } finally {
                resetting = false
                state.value = RuntimeState.NotInstalled
            }
        }.apply { isDaemon = true }.start()
    }
}
