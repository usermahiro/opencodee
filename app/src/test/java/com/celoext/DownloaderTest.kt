package com.celoext

import com.celoext.runtime.Downloader
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/** The rootfs and the OpenCode archive are tens of MB: a dropped connection must resume, not start over. */
class DownloaderTest {
    private lateinit var server: HttpServer
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private var requests = 0

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @After fun stop() {
        runCatching { server.stop(0) }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun sha() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun tempDest() = File.createTempFile("download", ".bin").apply { delete() }

    @Test fun resumesAfterDroppedConnection() {
        server.createContext("/f") { ex ->
            requests++
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                ex.sendResponseHeaders(206, (payload.size - from).toLong())
                ex.responseBody.write(payload, from, payload.size - from)
            } else {
                // Announce the full length, send a third, then drop the connection.
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload, 0, payload.size / 3)
                ex.responseBody.flush()
                ex.close()
                return@createContext
            }
            ex.close()
        }
        server.start()
        val dest = tempDest()
        try {
            Downloader.fetch(url("/f"), dest, sha()) { _, _ -> }
            assertArrayEquals(payload, dest.readBytes())
            assertTrue("expected a resumed request, got $requests", requests >= 2)
            assertFalse(File(dest.path + ".part").exists())
        } finally {
            dest.delete()
            File(dest.path + ".part").delete()
        }
    }

    @Test fun restartsFromZeroWhenServerIgnoresRange() {
        server.createContext("/f") { ex ->
            requests++
            if (requests == 1) {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload, 0, payload.size / 2)
                ex.responseBody.flush()
                ex.close()
                return@createContext
            }
            // Ignores the Range header: the client must truncate the partial file and take the whole body.
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload)
            ex.close()
        }
        server.start()
        val dest = tempDest()
        try {
            Downloader.fetch(url("/f"), dest, sha()) { _, _ -> }
            assertArrayEquals(payload, dest.readBytes())
        } finally {
            dest.delete()
            File(dest.path + ".part").delete()
        }
    }

    @Test fun checksumMismatchLeavesNothingBehind() {
        server.createContext("/f") { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload)
            ex.close()
        }
        server.start()
        val dest = tempDest()
        try {
            assertThrows(IllegalStateException::class.java) {
                Downloader.fetch(url("/f"), dest, "0".repeat(64)) { _, _ -> }
            }
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".part").exists())
        } finally {
            dest.delete()
            File(dest.path + ".part").delete()
        }
    }
}
