package com.celoext.runtime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Download with progress and a sha256 check. Writes to .part and renames when done. */
object Downloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Thrown for HTTP 5xx: GitHub's CDN returns 502/503/504 now and then, so these are retried. */
    private class Transient(msg: String) : RuntimeException(msg)

    fun fetch(url: String, dest: File, sha256: String?, onProgress: (done: Long, total: Long) -> Unit) {
        var attempt = 0
        while (true) {
            try { fetchOnce(url, dest, sha256, onProgress); return }
            catch (e: Exception) {
                val retry = e is Transient || e is java.io.IOException
                if (!retry || ++attempt >= 4) throw (if (e is Transient) IllegalStateException(e.message) else e)
                Thread.sleep(2000L * attempt)
            }
        }
    }

    private fun fetchOnce(url: String, dest: File, sha256: String?, onProgress: (done: Long, total: Long) -> Unit) {
        dest.parentFile?.mkdirs()
        // A cached file is reused only when its checksum is known and matches (without one it may be stale or partial).
        if (dest.exists() && sha256 != null && sha256Of(dest) == sha256) {
            onProgress(dest.length(), dest.length()); return
        }
        val part = File(dest.path + ".part")
        // Resume what an earlier attempt left behind: these files are tens of megabytes and phone networks drop.
        val offset = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "opencode-mobile")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 416) { // the leftover is longer than the remote file: start over
                part.delete()
                throw java.io.IOException("Download $url: HTTP 416, restarting from zero")
            }
            if (r.code >= 500) throw Transient("Download $url: HTTP ${r.code}")
            if (!r.isSuccessful) error("Download $url: HTTP ${r.code}")

            val body = r.body
            val resumed = r.code == 206 && offset > 0
            if (resumed) {
                val start = r.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (start != offset) {
                    part.delete()
                    throw java.io.IOException("Download $url: server resumed at $start, expected $offset")
                }
            }
            val from = if (resumed) offset else 0L
            // 206 reports the remaining length in the body: the total comes from Content-Range.
            val total = if (resumed) {
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (from + body.contentLength())
            } else body.contentLength()
            onProgress(from, total)
            var done = from
            var last = from
            java.io.FileOutputStream(part, resumed).buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                val src = body.byteStream()
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 128 * 1024) { last = done; onProgress(done, total) }
                }
            }
            if (total > 0 && done != total) {
                // Keep the partial file: the next attempt asks for the rest.
                throw java.io.IOException("Download of ${dest.name} was cut short ($done of $total bytes)")
            }
            if (sha256 != null) {
                val got = sha256Of(part)
                if (!got.equals(sha256, ignoreCase = true)) {
                    part.delete()
                    error("Checksum mismatch for ${dest.name}: $got")
                }
            }
            dest.delete()
            if (!part.renameTo(dest)) error("Could not rename ${part.name}")
            onProgress(done, total.takeIf { it > 0 } ?: done)
        }
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * sha256 of a GitHub release asset, taken from the API's `digest` field ("sha256:<hex>").
     * Returns null when GitHub doesn't report one.
     */
    fun githubAssetSha256(releaseApiUrl: String, assetName: String): String? {
        val o = org.json.JSONObject(getText(releaseApiUrl))
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == assetName) {
                val d = a.optString("digest")
                return if (d.startsWith("sha256:")) d.removePrefix("sha256:").lowercase() else null
            }
        }
        return null
    }

    fun getText(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", "opencode-mobile").build()).execute().use {
            if (!it.isSuccessful) error("GET $url: HTTP ${it.code}")
            it.body.string()
        }
}
