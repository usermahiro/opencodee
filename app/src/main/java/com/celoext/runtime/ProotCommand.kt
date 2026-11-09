package com.celoext.runtime

import com.celoext.data.Prefs
import java.io.File

/** Builds the proot command line: a guest Ubuntu with fake root and a "proper" /proc. */
class ProotCommand(private val paths: RuntimePaths, private val prefs: Prefs) {

    private companion object {
        const val SHARED_STORAGE = "/storage/emulated/0"
        const val TERMUX_HOME = "/data/user/0/com.termux/files/home"
        const val TERMUX_ROOT = "/data/user/0/com.termux"
    }

    fun env(): Map<String, String> = buildMap {
        put("LD_LIBRARY_PATH", "${paths.nativeDir}:${paths.libDir.path}")
        put("PROOT_LOADER", paths.prootLoader.path)
        put("PROOT_TMP_DIR", paths.tmp.path)
        if (prefs.noSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    /** The process to start: proot directly, or through `su -c` in root mode. */
    fun processBuilder(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        val argv = build(guestArgv, cwd, extraEnv)
        val pb = if (prefs.rootMode) ProcessBuilder("su", "-c", Root.script(env(), argv))
        else ProcessBuilder(argv).also { it.environment().putAll(env()) }
        return pb.redirectErrorStream(true)
    }

    /** [guestArgv] runs inside the rootfs under `env -i` with a clean environment. */
    fun build(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): List<String> {
        prepareFakeProc()
        val cmd = mutableListOf(
            paths.proot.path,
            "--kernel-release=6.2.1-PRoot-Distro",
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "--rootfs=${paths.rootfs.path}",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=${paths.fake}/loadavg:/proc/loadavg",
            "--bind=${paths.fake}/stat:/proc/stat",
            "--bind=${paths.fake}/uptime:/proc/uptime",
            "--bind=${paths.fake}/version:/proc/version",
            "--bind=${File(paths.rootfs, "tmp")}:/dev/shm",
        )
        // Internal storage: same path as on the host and as /sdcard (needs the storage permission).
        if (File(SHARED_STORAGE).exists()) {
            cmd += "--bind=$SHARED_STORAGE"
            cmd += "--bind=$SHARED_STORAGE:/sdcard"
        }
        // Termux: bind the FULL dir /data/user/0/com.termux (not only files/home) so
        // /data/user/0/com.termux, files/home, cache and storage all resolve inside the guest.
        val hasTermux = if (prefs.rootMode) Root.termuxHomeExists() else File(TERMUX_ROOT).exists()
        if (hasTermux) {
            cmd += "--bind=$TERMUX_ROOT"
            cmd += "--bind=$TERMUX_ROOT:/root/termux"
        }
        val guestEnv = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "NODE_EXTRA_CA_CERTS" to "/etc/ssl/certs/ca-certificates.crt",
            "DEBIAN_FRONTEND" to "noninteractive",
        ).apply { putAll(extraEnv) }
        cmd += "/usr/bin/env"
        cmd += "-i"
        guestEnv.forEach { (k, v) -> cmd += "$k=$v" }
        cmd += guestArgv
        return cmd
    }

    /** Android hides /proc/stat and others from apps; Bun/Node read them, so we provide our own. */
    private fun prepareFakeProc() {
        paths.fake.mkdirs()
        paths.tmp.mkdirs()
        File(paths.rootfs, "tmp").mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        write("loadavg", "0.12 0.07 0.02 2/165 765\n")
        write("uptime", "124.08 932.80\n")
        write("version", "Linux version 6.2.1-PRoot-Distro (proot@android) (gcc 12.2.1) #1 SMP PREEMPT_DYNAMIC Wed, 01 Mar 2023 00:00:00 +0000\n")
        write("stat", buildString {
            append("cpu  ${1957 * cpus} 0 ${2877 * cpus} ${93280 * cpus} 262 342 254 87 0 0\n")
            for (i in 0 until cpus) append("cpu$i 1957 0 2877 93280 262 342 254 87 0 0\n")
            append("intr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        })
    }

    private fun write(name: String, text: String) {
        val f = File(paths.fake, name)
        if (!f.exists() || f.readText() != text) f.writeText(text)
    }
}
