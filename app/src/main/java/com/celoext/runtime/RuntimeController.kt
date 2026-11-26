package com.celoext.runtime

import com.celoext.data.Vault
import android.app.Application
import android.content.Intent
import android.system.Os
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

        // Native OpenCode: the opencode-termux community port (bionic binaries that run on Android
        // without Termux, targetSdk 28 keeps exec from app storage allowed). Pinned release + the
        // four Termux packages it depends on, checksums from the release SHA256SUMS / the repo index.
        const val OCT_REPO = "Spectra010s/opencode-termux"
        const val OCT_VERSION = "2.2.7"
        const val OCT_SHA = "432c809a6e2dcb4ea08be79d96694b755c22129de10d07ff1abf2bebae4c8266"
        fun octUrl(v: String) = "https://github.com/$OCT_REPO/releases/download/v$v/opencode-$v-android-aarch64-bundle.tar.gz"
        // Termux keeps only the latest build of each package, and the primary host drops the old
        // files without warning (issue #10: bun 1.4.4-3 began returning 404). Every package stays
        // pinned by sha256, and the first mirror that still has it serves the bytes.
        val TERMUX_HOSTS = listOf(
            "https://packages.termux.dev/apt/termux-main",
            "https://packages-cf.termux.dev/apt/termux-main",
        )
        // Tools for the agent, run natively from app storage (no proot, no root).
        val DEV_ROOTS = listOf("bash", "git", "curl", "gh", "jadx", "rust", "ca-certificates")
        // Termux housekeeping packages that only matter inside the Termux app itself.
        val DEV_SKIP = setOf(
            "termux-exec", "termux-tools", "termux-keyring", "termux-licenses", "termux-core",
            "termux-am", "termux-am-socket", "apt", "dpkg", "command-not-found",
        )
        class Deb(val name: String, val path: String, val sha: String) {
            val urls get() = TERMUX_HOSTS.map { "$it/$path" }
        }
        val TERMUX_DEBS = listOf(
            Deb("bun", "pool/main/b/bun/bun_1.4.5_aarch64.deb", "ebc12e294c3ee6dde21f8a78c1dd42f3304a599b68ebd074333838cff2d0e880"),
            Deb("libicu", "pool/main/libi/libicu/libicu_79.1_aarch64.deb", "83c980a010e957738e3567127801d66a1b9bd7e404a4fda98788048ee57d606c"),
            Deb("ripgrep", "pool/main/r/ripgrep/ripgrep_15.2.0_aarch64.deb", "38e28bc297000517b24702568a483eca7dc3323eb6bdccc9033f031776bdcc6c"),
            Deb("pcre2", "pool/main/p/pcre2/pcre2_10.49_aarch64.deb", "c27995e5f52b8ecc9b8b3e662eb08e49f82a18bfd3f34343936acc7b23964724"),
        )
    }

    val state = MutableStateFlow<RuntimeState>(
        if (prefs.runtimeReady && paths.nativeReady) RuntimeState.Stopped else RuntimeState.NotInstalled
    )
    val log = MutableStateFlow<List<String>>(emptyList())

    private val proot = ProotCommand(paths, prefs)
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    private val ops = RuntimeOps()
    private val supervising = java.util.concurrent.atomic.AtomicBoolean(false)

    fun logLine(s: String) {
        android.util.Log.w("CeloOC", s)
        log.update { (it + s).takeLast(600) }
    }

    // ---------- install ----------

    suspend fun install() = withContext(Dispatchers.IO) {
        if (!ops.beginInstall()) return@withContext
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
                tgz.delete()
            }

            migrateLegacyHome()
            Vault.restore()
            // The native home is new even when the old proot home had the skills, so check them
            // again: seed() keeps existing files and only copies the ones that are missing.
            prefs.seededSkills = ""
            Memory.sync() // copies the default skills and writes their rules into AGENTS.md
            installNative()
            nativeSmokeTest()
            // Written only after the bundle starts: a bundle that cannot run is replaced on the next try.
            paths.bundleVersionFile.writeText(OCT_VERSION)

            prefs.runtimeReady = true
            state.value = RuntimeState.Stopped
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.value = RuntimeState.InstallFailed("Installation was interrupted")
            throw e
        } catch (e: Throwable) {
            logLine("Install failed: ${e.message}")
            state.value = RuntimeState.InstallFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            ops.endInstall()
        }
    }

    /**
     * Re-download the bundled OpenCode and its Termux packages without touching chats or projects.
     * This is how a new OCT_VERSION is picked up: a running server must stop first, because the
     * bundle files under it are replaced.
     */
    suspend fun reinstallNative() {
        if (ops.installing || ops.resetting) return
        stopServer()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && (serverAlive || portBusy())) delay(200)
        if (serverAlive || portBusy()) {
            logLine("Reinstall skipped: the server did not stop")
            return
        }
        install()
    }

    /**
     * Native runtime, no proot: the opencode-termux bundle (a sh launcher + the JS module graph +
     * bionic native libraries) plus the Termux packages it runs on — bun, libicu, ripgrep, pcre2.
     * Everything is downloaded with pinned sha-256s and unpacked with our own extractors.
     */
    private fun installNative() {
        // Redo a bundle that is missing, was extracted but never fully patched (an earlier install
        // died half way), or came from an older opencode-termux release. The old check only looked
        // at the launcher, so a new OCT_VERSION in an app update never replaced the old bundle.
        val bundleVersion = runCatching { paths.bundleVersionFile.readText().trim() }.getOrNull()
        val needsBundle = bundleNeedsInstall(
            binExists = paths.opencodeNativeBin.isFile,
            indexExists = File(paths.opencodeBundle, "index.js").isFile,
            hasPlaceholders = bundleHasPlaceholders(),
            installedVersion = bundleVersion,
            wantedVersion = OCT_VERSION,
        )
        if (needsBundle) {
            if (paths.bundleDir.exists()) logLine("bundle: replacing ${bundleVersion ?: "unknown version"} with $OCT_VERSION")
            step("Downloading OpenCode", 0f)
            val tgz = File(paths.downloads, "opencode-native-$OCT_VERSION.tar.gz")
            Downloader.fetch(octUrl(OCT_VERSION), tgz, OCT_SHA) { d, t -> step("Downloading OpenCode", frac(d, t), mb(d, t)) }
            step("Installing OpenCode", 0f)
            paths.bundleDir.deleteRecursively()
            TarExtractor.extract(tgz, paths.bundleDir) { d, t -> step("Installing OpenCode", frac(d, t)) }
            tgz.delete()
            // The bundle is built against @TERMUX_PREFIX@ so one artefact fits every Termux
            // prefix; point it at our own directory instead (same as the upstream installer).
            val marker = "@TERMUX_PREFIX@/lib/opencode"
            val ours = paths.opencodeBundle.path
            var patched = 0
            // Never rewrite binaries (bundleFiles skips .so/.node/.bin). ISO-8859-1 maps every byte to one char,
            // so a file that isn't valid UTF-8 still comes back byte for byte except for the replaced marker.
            bundleFiles().forEach { f ->
                val text = runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
                if (text.contains(marker)) {
                    f.writeBytes(text.replace(marker, ours).toByteArray(Charsets.ISO_8859_1))
                    patched++
                }
            }
            logLine("bundle: patched $patched files from @TERMUX_PREFIX@ to ${paths.bundleDir.path}")
            if (bundleHasPlaceholders()) error("Release bundle still contains placeholder paths; it cannot run here")
            // The launcher ships with a Termux shebang; Android's /system/bin/sh is the equivalent here.
            val launcher = paths.opencodeNativeBin
            val shebang = "#!/system/bin/sh\n"
            runCatching {
                val text = launcher.readText()
                if (text.startsWith("#!/data/data/com.termux")) launcher.writeText(shebang + text.substringAfter('\n'))
            }
            if (runCatching { launcher.readText().startsWith("#!/system/bin/sh") }.getOrDefault(false)) {
                logLine("launcher: shebang patched to /system/bin/sh")
            } else {
                // Unexpected launcher layout: bypass the script at runtime and exec bun directly.
                logLine("launcher: unexpected format, the server will exec bun directly")
            }
            launcher.setExecutable(true, false)
        }
        // The Termux packages the bundle runs on. Nothing here is apt: pure downloads + unzip.
        val usrRoot = File(paths.nativeUsr, "data/data/com.termux/files/usr")
        paths.debMarkers.mkdirs()
        // Latest build of every package, read once from the Termux index. If the index can't be
        // reached the pinned files below are the fallback (and the other way round: a pin that
        // 404s is replaced by whatever the index has).
        var indexTried = false
        var index: Map<String, TermuxIndex.Entry>? = null
        fun currentIndex(): Map<String, TermuxIndex.Entry>? {
            if (!indexTried) {
                indexTried = true
                index = runCatching { TermuxIndex.resolve(TERMUX_HOSTS, TERMUX_DEBS.map { it.name }.toSet()) }
                    .onFailure { logLine("termux index unavailable, using pinned packages: ${it.message}") }
                    .getOrNull()
            }
            return index
        }
        for (deb in TERMUX_DEBS) {
            // A key file alone is not proof: an install killed mid-extraction leaves some files
            // behind, and the old check then called the package installed forever, so the missing
            // libraries were never restored and the server kept failing to start.
            val marker = File(paths.debMarkers, "${deb.name}.sha")
            val markerRaw = runCatching { marker.readText().trim() }.getOrNull()
            // "idx:<pinned sha>:<resolved sha>" means the pin was replaced by the index's file.
            val markerVersion = if (markerRaw?.startsWith("idx:${deb.sha}:") == true) deb.sha else markerRaw
            val have = !debNeedsInstall(markerVersion, deb.sha, debKeyFilePresent(deb.name, usrRoot))
            if (have) { logLine("termux ${deb.name}: already installed"); continue }
            // ripgrep and pcre2 only power the search tool; a missing one must not stop the setup.
            val optional = deb.name == "ripgrep" || deb.name == "pcre2"
            try {
                step("Downloading ${deb.name}", 0f)
                var f = File(paths.downloads, "${deb.name}-${deb.sha.take(8)}.deb")
                var markerText = deb.sha
                var done = false
                val entry = currentIndex()?.get(deb.name)
                if (entry != null) {
                    val latest = File(paths.downloads, "${deb.name}-${entry.sha.take(8)}.deb")
                    val ok = runCatching {
                        Downloader.fetch(TERMUX_HOSTS.map { "$it/${entry.path}" }, latest, entry.sha) { d, t -> step("Downloading ${deb.name}", frac(d, t), mb(d, t)) }
                    }.onFailure { logLine("termux ${deb.name}: latest failed (${it.message}), trying the pinned file") }.isSuccess
                    if (ok) {
                        f = latest
                        markerText = "idx:${deb.sha}:${entry.sha}"
                        logLine("termux ${deb.name}: latest ${entry.path.substringAfterLast('/')}")
                        done = true
                    }
                }
                if (!done) Downloader.fetch(deb.urls, f, deb.sha) { d, t -> step("Downloading ${deb.name}", frac(d, t), mb(d, t)) }
                step("Installing ${deb.name}", 0f)
                val r = DebExtractor.extract(f, paths.nativeUsr) { d, t -> step("Installing ${deb.name}", frac(d, t)) }
                // Written only after a complete unpack. A killed install leaves files but no marker, so
                // the next run extracts the package again instead of trusting a half-written tree.
                marker.writeText(markerText)
                logLine("termux ${deb.name}: ${r.files} files")
                f.delete()
            } catch (e: Exception) {
                if (!optional) throw e
                logLine("termux ${deb.name}: skipped (${e.message})")
            }
        }
        if (!File(usrRoot, "bin/bun").isFile) error("bun did not land in the Termux layout; install failed")
        paths.tmp.mkdirs()
        paths.nativeHome.mkdirs()
        paths.nativeHome.resolve("work").mkdirs()
        paths.nativeHome.resolve(".config/opencode").mkdirs()
        paths.nativeHome.resolve("uploads").mkdirs()
        paths.nativeHome.resolve("outbox").mkdirs()
        prefs.opencodeVersion = OCT_VERSION
    }

    /**
     * One-time copy of the old proot /root into the native home. The live rootfs is the newer truth,
     * but [Vault.restore] may already have filled the native home from its backups on this start, so
     * a file is replaced only when it is missing, has a different length or the old copy is newer.
     * That also retries a file cut short by a killed app, and it never lets an older backup shadow
     * newer chats or keys.
     *
     * The marker is written only after a complete pass. The old code marked a partial copy as done,
     * so files that failed (disk full, killed app) were never tried again.
     */
    private fun migrateLegacyHome() {
        val oldRoot = File(paths.rootfs, "root")
        // The marker sits next to the rootfs, outside the native directory a reset deletes. Inside it,
        // every reset would make the next install copy the stale proot home back over a fresh one.
        val marker = File(paths.rootfs.parentFile, ".migrated-from-rootfs")
        val oldMarker = File(paths.nativeHome, ".migrated-from-rootfs")
        // An install from before the marker moved still counts; do not copy again for those users.
        if (oldMarker.exists() && !marker.exists()) runCatching { marker.writeText("1") }
        if (marker.exists() || !oldRoot.isDirectory || oldRoot.list()?.isNotEmpty() != true) return
        logLine("migrating the old /root into the native home")
        paths.nativeHome.mkdirs()
        var failed = 0
        oldRoot.walkTopDown().forEach { src ->
            val dst = File(paths.nativeHome, src.relativeTo(oldRoot).path)
            if (src.isDirectory) {
                dst.mkdirs()
            } else if (src.isFile && shouldMigrateLegacyFile(dst.isFile, dst.length(), dst.lastModified(), src.length(), src.lastModified())) {
                runCatching { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
                    .onFailure { failed++ }
            }
        }
        if (failed == 0) runCatching { marker.writeText("1") }
        else logLine("migration left $failed files behind; it will retry on the next start")
    }

    /** The text-like files of the release bundle (launcher, JS): never the native libraries. */
    private fun bundleFiles(): Sequence<File> = paths.bundleDir.walkTopDown()
        .filter { it.isFile && it.length() < 40 * 1_000_000 && it.extension !in setOf("so", "node", "bin") }

    private fun bundleHasPlaceholders(): Boolean = bundleFiles().any { f ->
        runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains("@TERMUX_PREFIX@") }.getOrDefault(false)
    }

    /** One file that has to exist once a Termux package is unpacked; the marker proves it is complete. */
    private fun debKeyFilePresent(name: String, usrRoot: File): Boolean = when (name) {
        "bun" -> File(usrRoot, "bin/bun").isFile
        "libicu" -> File(usrRoot, "lib").listFiles()?.any { it.name.startsWith("libicuuc.so.") } == true
        "ripgrep" -> File(usrRoot, "bin/rg").isFile
        "pcre2" -> File(usrRoot, "lib/libpcre2-8.so").isFile
        else -> false
    }

    /** The environment the native server runs with: XDG paths all live in the native home. */
    private fun nativeEnv(): Map<String, String> {
        val base = mapOf(
        "HOME" to paths.nativeHome.path,
        "XDG_CONFIG_HOME" to File(paths.nativeHome, ".config").path,
        "XDG_DATA_HOME" to File(paths.nativeHome, ".local/share").path,
        "XDG_STATE_HOME" to File(paths.nativeHome, ".local/state").path,
        "XDG_CACHE_HOME" to File(paths.nativeHome, ".cache").path,
        "TMPDIR" to paths.tmp.path,
        "PATH" to "${paths.nativeBin.path}:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "${paths.nativeLib.path}:${paths.nativeDir}",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
        "SHELL" to "/system/bin/sh",
        "OPENCODE_BUN" to File(paths.nativeBin, "bun").path,
        "OPENCODE_ROOT" to paths.opencodeBundle.path,
        "OPENCODE_DISABLE_AUTOUPDATE" to "true",
        )
        // Termux tools (git, curl, gh, cargo, jadx) were unpacked with their prefix relocated into this app.
        val prefix = PrefixPatcher.targetPrefix(app.packageName) ?: return base
        val usr = File(paths.nativeUsr, "data/data/com.termux/files/usr")
        val cargoHome = File(paths.nativeHome, ".cargo")
        val ca = "$prefix/etc/tls/cert.pem"
        val jdk = File(usr, "lib/jvm").listFiles()?.firstOrNull { it.isDirectory }
        return base + buildMap {
            put("PREFIX", prefix)
            put("SSL_CERT_FILE", ca)
            put("CURL_CA_BUNDLE", ca)
            put("GIT_SSL_CAINFO", ca)
            put("CARGO_HOME", cargoHome.path)
            put("PATH", "${cargoHome.path}/bin:${paths.nativeBin.path}:/system/bin:/system/xbin")
            if (jdk != null) put("JAVA_HOME", "$prefix/lib/jvm/${jdk.name}")
        }
    }

    /** Runs a native binary directly (bun etc.): same guardrails as runGuest, no proot. */
    private suspend fun runNative(
        argv: List<String>,
        timeoutSec: Long = 120,
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val env = nativeEnv()
        val (p, viaRoot) = Root.spawnWithFallback(argv, env, paths.nativeHome.path) { pb -> pb.environment().putAll(env); pb.redirectErrorStream(true) }
        if (viaRoot) logLine("started through su (root)")
        val rd = launch { runCatching { p.inputStream.bufferedReader().forEachLine { onLine(it); logLine(it) } } }
        val done = try { runInterruptible { p.waitFor(timeoutSec, TimeUnit.SECONDS) } } finally { if (p.isAlive) p.destroyForcibly() }
        if (!done) { rd.cancel(); return@withContext -1 }
        rd.join()
        p.exitValue()
    }

    private suspend fun nativeSmokeTest() {
        step("Checking startup", null)
        val out = StringBuilder()
        val code = runNative(listOf(paths.opencodeNativeBin.path, "--version"), timeoutSec = 180) { out.appendLine(it) }
        if (code != 0 || !Regex("\\d+\\.\\d+\\.\\d+").containsMatchIn(out.toString())) {
            error("opencode does not start (code $code): ${out.toString().trim().takeLast(400)}")
        }
        logLine("opencode native: ${out.toString().trim()}")
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
        // Two loops (the service got recreated while the old loop was still winding down) take turns
        // killing each other's server and crash-loop on "port 4096 in use". Only one may run.
        if (!beginSupervising()) {
            logLine("Another server loop is still running; not starting a second one")
            return@withContext
        }
        try {
            superviseLoop()
        } finally {
            supervising.set(false)
        }
    }

    /**
     * Takes the single-loop slot. A service restart can arrive while the previous loop is still
     * being cancelled, so wait for it instead of returning: the caller stops the service right
     * after, and giving up here leaves the server down until Android retries the sticky service.
     */
    private suspend fun beginSupervising(): Boolean {
        if (supervising.compareAndSet(false, true)) return true
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            delay(100)
            if (supervising.compareAndSet(false, true)) return true
        }
        return false
    }

    private suspend fun superviseLoop() = withContext(Dispatchers.IO) {
        // A wiped or half-removed runtime must not turn into a crash/restart loop: there is nothing
        // to execute, so every attempt fails the same way and the user only sees "Server crashed".
        if (!paths.nativeReady) {
            logLine("The native runtime is missing; install it again")
            state.value = RuntimeState.NotInstalled
            return@withContext
        }
        stopRequested = false
        var failures = 0
        var windowStart = System.currentTimeMillis()
        while (isActive && !stopRequested) {
            state.value = RuntimeState.Starting
            // leftovers from the run before (or a crash) would keep the port: kill them first
            freePort()
            if (portBusy()) logLine("Port ${prefs.serverPort} is still busy, trying to start anyway")
            val argv = listOf(paths.opencodeNativeBin.path, "serve", "--hostname", "127.0.0.1", "--port", prefs.serverPort)
            val env = nativeEnv() + mapOf("OPENCODE_SERVER_PASSWORD" to prefs.serverPassword)
            val (p, viaRoot) = try {
                Root.spawnWithFallback(argv, env, paths.serverPath("/root/work")) { pb ->
                    pb.environment().putAll(env); pb.redirectErrorStream(true)
                }
            } catch (e: Exception) {
                state.value = RuntimeState.Crashed("Could not start the server: ${e.message}")
                return@withContext
            }
            if (viaRoot) logLine("Server started through su (root)")
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

    /**
     * Free the server port before starting a new server.
     * The previous `opencode serve` (or its bun process after a crash) holds the port, which crashed
     * every new server with "Is port 4096 in use?". The /proc scan kills them, with su as fallback
     * for a root-run server this app cannot signal.
     */
    private suspend fun freePort(attempts: Int = 3) {
        for (i in 0 until attempts) {
            if (!portBusy()) return
            if (i > 0 || !waitPortFree(2_000)) {
                logLine("Port ${prefs.serverPort} still busy, killing leftover server (attempt ${i + 1})")
                killLeftoverJava()
                if (waitPortFree(4_000)) return
                // A server started through su is invisible to the scan above: ask pidof as root.
                Root.pidsOf("bun").filter { pid -> isLeftoverServer(pid) }.forEach { Root.kill(it) }
                if (waitPortFree(2_000)) return
            }
        }
    }

    private suspend fun waitPortFree(maxWaitMs: Long): Boolean {
        var waited = 0
        while (portBusy() && waited < maxWaitMs) { delay(250); waited += 250 }
        return !portBusy()
    }

    /** True when [pid]'s cmdline is one of our server processes (bun running the bundle, or a proot-era leftover). */
    private fun isLeftoverServer(pid: Int): Boolean = isServerCommand(cmdlineOf(pid), paths.opencodeNativeBin.path)

    /**
     * The command line of [pid]. Read directly when possible, through su when the process is hidden
     * from this app (a server that [Root.spawnWithFallback] started as root).
     */
    private fun cmdlineOf(pid: Int): String {
        val direct = directCmdline(pid)
        return if (direct.isNotEmpty()) direct else Root.cmdlineOf(pid)
    }

    private fun directCmdline(pid: Int): String =
        runCatching { java.io.File("/proc/$pid/cmdline").readBytes().decodeToString() }.getOrDefault("")
            .replace('\u0000', ' ').trim()

    /**
     * Kills leftover server processes found by scanning /proc: the guest processes (and the native
     * bun) run with this app's UID, so their cmdline is readable and they can be signalled directly.
     * A root-run server is invisible here; [freePort] finds that one through pidof, and only when
     * the port is actually stuck.
     */
    private fun killLeftoverJava() {
        runCatching {
            for (p in java.io.File("/proc").listFiles() ?: emptyArray()) {
                val pid = p.name.toIntOrNull() ?: continue
                if (pid == android.os.Process.myPid()) continue
                val cmd = directCmdline(pid)
                if (cmd.isEmpty()) continue
                // The bun server cmdline carries the bundle entry (lib/opencode/index.js); the legacy
                // proot-era binary carried /usr/local/bin/opencode. Nothing else of ours matches.
                if (isServerCommand(cmd, paths.opencodeNativeBin.path)) {
                    val killed = runCatching { android.os.Process.killProcess(pid); true }.getOrDefault(false)
                    if (!killed || isAlive(pid)) Root.kill(pid) // a root-run server answers only to su
                    logLine("Killed leftover server process $pid: ${cmd.take(120)}")
                }
            }
        }
    }

    private fun isAlive(pid: Int): Boolean = runCatching { java.io.File("/proc/$pid").exists() }.getOrDefault(false)

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

    fun requestStop() {
        stopRequested = true
        process?.destroy()
        // in the background: scanning /proc and running su take a moment and this can be called from the main thread
        Thread { killLeftoverJava() }.apply { isDaemon = true }.start()
    }

    /** Stop, wait until the port is really free, start again (Restart button). */
    fun restartServer(beforeStart: () -> Unit = {}) {
        stopServer()
        Thread {
            var waited = 0
            while (waited < 10_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
            killLeftoverJava()
            runCatching { beforeStart() }
            startServer()
        }.apply { isDaemon = true }.start()
    }


    /** Start the server through a foreground service, otherwise Android kills the process in the background. */
    fun startServer() {
        if (ops.resetting) return
        val s = state.value
        if (s is RuntimeState.NotInstalled || s is RuntimeState.Installing || s is RuntimeState.InstallFailed) return
        if (!paths.nativeReady) {
            logLine("The native runtime is missing; install it again")
            state.value = RuntimeState.NotInstalled
            return
        }
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

    /**
     * git, curl, gh, jadx and cargo from the Termux repo, unpacked straight into app storage and run
     * natively: no proot, no root. The packages are resolved with their dependencies from the Termux
     * index (no apt), and every file gets its /data/data/com.termux prefix swapped for a same-length
     * path inside this app ([PrefixPatcher]); a symlink there leads to the real directory.
     */
    suspend fun installNativeDevTools(onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        fun say(s: String) { logLine(s); onLine(s) }
        try {
            val prefix = PrefixPatcher.targetPrefix(app.packageName)
                ?: error("The package name ${app.packageName} is too long to relocate Termux binaries")
            val realUsr = File(paths.nativeUsr, "data/data/com.termux/files/usr")
            realUsr.mkdirs()
            val link = File(app.filesDir, prefix.substringAfterLast('/'))
            if (runCatching { Os.readlink(link.path) }.getOrNull() != realUsr.path) {
                link.delete()
                Os.symlink(realUsr.path, link.path)
            }
            say("Reading the Termux package index")
            val index = TermuxIndex.resolveAll(TERMUX_HOSTS)
            val missing = DEV_ROOTS.filter { it !in index }
            if (missing.isNotEmpty()) say("Not in the Termux repo: ${missing.joinToString()}")
            val plan = TermuxIndex.closure(index, DEV_ROOTS, DEV_SKIP)
            val from = PrefixPatcher.TERMUX_PREFIX.toByteArray(Charsets.ISO_8859_1)
            val to = prefix.toByteArray(Charsets.ISO_8859_1)
            paths.debMarkers.mkdirs()
            for ((i, e) in plan.withIndex()) {
                val marker = File(paths.debMarkers, "${e.name}.sha")
                if (marker.isFile && marker.length() > 0) continue
                say("[${i + 1}/${plan.size}] ${e.name}")
                val f = File(paths.downloads, "${e.name}-${e.sha.take(8)}.deb")
                Downloader.fetch(TERMUX_HOSTS.map { "$it/${e.path}" }, f, e.sha) { _, _ -> }
                var patched = 0
                DebExtractor.extract(f, paths.nativeUsr, onFile = { patched += PrefixPatcher.patchFile(it, from, to) })
                // Written only after a complete unpack and patch: a killed install redoes this package.
                marker.writeText("idx:${e.sha}")
                f.delete()
                logLine("termux ${e.name}: relocated $patched paths")
            }
            // Scripts start with #!<prefix>/bin/sh; Android's own sh stands in when no package provided one.
            val sh = File(realUsr, "bin/sh")
            if (!sh.exists() && !java.nio.file.Files.isSymbolicLink(sh.toPath())) {
                sh.parentFile?.mkdirs()
                Os.symlink("/system/bin/sh", sh.path)
            }
            say("Checking the tools")
            var failed = 0
            for (cmd in listOf("git", "curl", "gh", "cargo", "jadx")) {
                if (!File(paths.nativeBin, cmd).exists()) { say("✗ $cmd is not installed"); failed++; continue }
                var first = true
                val code = runNative(listOf(File(paths.nativeBin, cmd).path, "--version"), timeoutSec = 90) { l ->
                    if (first) { first = false; onLine(l) }
                }
                if (code != 0) { say("✗ $cmd --version exited with $code"); failed++ }
            }
            if (failed == 0) 0 else 1
        } catch (e: Exception) {
            say("✗ ${e.message ?: e.javaClass.simpleName}")
            1
        }
    }

    /** git, ripgrep and the like: without them the agent in Ubuntu is nearly useless. */
    suspend fun installDevTools(onLine: (String) -> Unit): Int {
        // git, curl and the build tools come from Ubuntu; gh, jadx and Rust come straight from
        // their own latest releases (Ubuntu's copies are a year or more behind).
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends git ripgrep curl ca-certificates gnupg openssh-client unzip less nano python3 build-essential default-jre-headless

            echo "== gh =="
            install -d -m 755 /etc/apt/keyrings
            curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /etc/apt/keyrings/githubcli-archive-keyring.gpg
            chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
            echo "deb [arch=${'$'}(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list
            apt-get update
            apt-get install -y gh

            echo "== jadx =="
            JADX_URL=${'$'}(curl -fsSL https://api.github.com/repos/skylot/jadx/releases/latest | grep -o 'https://[^"]*/jadx-[0-9][^"]*[.]zip' | head -1)
            [ -n "${'$'}JADX_URL" ]
            curl -fsSL "${'$'}JADX_URL" -o /tmp/jadx.zip
            rm -rf /opt/jadx
            mkdir -p /opt/jadx
            unzip -q -o /tmp/jadx.zip -d /opt/jadx
            rm -f /tmp/jadx.zip
            ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx

            echo "== rust / cargo =="
            if [ ! -x /root/.cargo/bin/rustup ]; then
              curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
            else
              /root/.cargo/bin/rustup update stable
            fi
            for t in cargo rustc rustup; do ln -sf /root/.cargo/bin/${'$'}t /usr/local/bin/${'$'}t; done
            cargo --version
        """.trimIndent()
        return runGuest(listOf("/bin/bash", "-lc", script), timeoutSec = 3600, onLine = onLine)
    }

    /**
     * Deletes the native runtime. Returns false when an install is still running: both write the
     * same directories, and a reset in the middle of one leaves the ready flag over an empty tree.
     */
    fun resetRuntime(): Boolean {
        if (!ops.beginReset()) {
            logLine("Reset skipped: an install is still running")
            return false
        }
        // Deleting the environment is hundreds of megabytes and thousands of files: not on the main thread.
        Thread {
            try {
                // 9Router keeps its data under /root, which is inside the native home this reset
                // deletes. Stop it first instead of pulling its database out from under it.
                runCatching { com.celoext.Graph.ninerouter.stopServer() }
                var nr = 0
                while (nr < 10_000 && (com.celoext.Graph.ninerouter.serverAlive || com.celoext.Graph.ninerouter.portBusy())) {
                    Thread.sleep(200); nr += 200
                }
                stopServer()
                var waited = 0
                while (waited < 10_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
                if (serverAlive || portBusy()) {
                    killLeftoverJava()
                    waited = 0
                    while (waited < 5_000 && (serverAlive || portBusy())) { Thread.sleep(200); waited += 200 }
                }
                // SQLite finishes its last write just after the socket closes.
                Thread.sleep(800)
                val stopped = !serverAlive && !portBusy()
                if (!stopped) logLine("Server is still running; keeping the previous chat backup instead of copying a live database")
                Vault.save(includeChats = stopped)
                // Wipe the native runtime (bundle, termux debs, home). The proot rootfs stays: 9Router runs in it.
                paths.native.deleteRecursively()
                paths.downloads.deleteRecursively()
                prefs.runtimeReady = false
            } finally {
                ops.endReset()
                state.value = RuntimeState.NotInstalled
            }
        }.apply { isDaemon = true }.start()
        return true
    }
}

/**
 * Whether a file from the old proot /root should be written into the native home. A missing file is
 * copied. A different length means the target is truncated or an older revision, so copy. Otherwise
 * the old copy wins only when it is newer than what is already there.
 */
internal fun shouldMigrateLegacyFile(
    targetExists: Boolean,
    targetLength: Long,
    targetModified: Long,
    sourceLength: Long,
    sourceModified: Long,
): Boolean = !targetExists || targetLength != sourceLength || sourceModified > targetModified

/**
 * Whether a process command line belongs to our server: bun running the bundle, the launcher, or
 * the proot-era binary. Anything else must not be killed, even when the process is called bun.
 */
internal fun isServerCommand(cmd: String, nativeBinPath: String): Boolean =
    cmd.contains("lib/opencode/index.js") || cmd.contains("/usr/local/bin/opencode") || cmd.contains(nativeBinPath)

/**
 * Whether the OpenCode bundle on disk has to be downloaded again. A missing launcher or entry
 * point, leftover `@TERMUX_PREFIX@` placeholders or an older version all mean yes. A bundle from
 * before the version marker existed counts as older: its actual version is unknown.
 */
internal fun bundleNeedsInstall(
    binExists: Boolean,
    indexExists: Boolean,
    hasPlaceholders: Boolean,
    installedVersion: String?,
    wantedVersion: String,
): Boolean = !binExists || !indexExists || hasPlaceholders || installedVersion != wantedVersion

/**
 * Whether a Termux package has to be unpacked again. The marker proves the last unpack finished;
 * a key file alone does not, because a killed install leaves part of the tree behind.
 */
internal fun debNeedsInstall(markerVersion: String?, wantedVersion: String, keyFileExists: Boolean): Boolean =
    !keyFileExists || markerVersion != wantedVersion
