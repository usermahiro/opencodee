package com.celoext.runtime

/**
 * Resolves the current .deb of a Termux package from the apt index. Termux keeps only the latest
 * build of each package and drops older files from pool/ (ripgrep 15.2.0 began returning 404), so
 * when a pinned file is gone the installer looks the file up here instead of failing the setup.
 * The sha256 comes from the same index, served over HTTPS.
 */
object TermuxIndex {
    class Entry(val name: String, val path: String, val sha: String, val depends: List<String> = emptyList())

    private const val INDEX_PATH = "dists/stable/main/binary-aarch64/Packages"

    /** Reads the Debian "Packages" text and keeps the entries named in [wanted]. */
    internal fun parse(text: String, wanted: Set<String>? = null): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        for (block in text.split(Regex("\r?\n[ \t]*\r?\n"))) {
            var pkg: String? = null
            var file: String? = null
            var sha: String? = null
            var deps: List<String> = emptyList()
            for (line in block.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> pkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> file = line.removePrefix("Filename: ").trim()
                    line.startsWith("SHA256: ") -> sha = line.removePrefix("SHA256: ").trim()
                    // "a, b (>= 1.2), c | d": names only, the first alternative of each
                    line.startsWith("Depends: ") -> deps = line.removePrefix("Depends: ").split(',')
                        .map { it.substringBefore('|').substringBefore('(').trim() }
                        .filter { it.isNotEmpty() }
                }
            }
            if (pkg != null && (wanted == null || pkg in wanted) && file != null && sha != null) {
                out[pkg] = Entry(pkg, file, sha.lowercase(), deps)
            }
        }
        return out
    }

    /** Tries each host in order and returns the entries of the first index that has any. */
    fun resolve(hosts: List<String>, wanted: Set<String>? = null): Map<String, Entry> {
        var last: Exception? = null
        for (host in hosts) {
            try {
                val found = parse(Downloader.getText("$host/$INDEX_PATH"), wanted)
                if (found.isNotEmpty()) return found
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Termux package index unavailable" + (last?.message?.let { ": $it" } ?: ""))
    }

    /** The whole index, for resolving dependencies. */
    fun resolveAll(hosts: List<String>): Map<String, Entry> = resolve(hosts, null)

    /**
     * [roots] and everything they depend on, dependencies first. Names in [skip] and names the index
     * doesn't know (virtual packages) are left out.
     */
    fun closure(index: Map<String, Entry>, roots: List<String>, skip: Set<String> = emptySet()): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        fun visit(name: String) {
            if (name in skip || !seen.add(name)) return
            val e = index[name] ?: return
            e.depends.forEach { visit(it) }
            out += e
        }
        roots.forEach { visit(it) }
        return out
    }
}
