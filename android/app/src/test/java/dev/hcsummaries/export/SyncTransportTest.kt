package dev.hcsummaries.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Just enough of an HTTP/1.1 server on loopback for transport tests (android.jar has no HttpServer). */
class HttpExchange(val requestMethod: String, val path: String, private val headers: Map<String, String>, val requestBody: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun hasHeader(name: String) = headers.containsKey(name.lowercase())
    var status = 200
    var responseHeaders = mutableMapOf<String, String>()
    var responseBody = ByteArray(0)
}

class LoopbackServer(private val handler: (HttpExchange) -> Unit) {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    init {
        thread(isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    client.use { c ->
                        try {
                            val input = c.getInputStream()
                            val requestLine = readLine(input) ?: return@use
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = readLine(input) ?: break
                                if (line.isEmpty()) break
                                val i = line.indexOf(':')
                                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                            }
                            val length = headers["content-length"]?.toIntOrNull() ?: 0
                            val body = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val n = input.read(body, read, length - read)
                                if (n < 0) break
                                read += n
                            }
                            val parts = requestLine.split(" ")
                            val ex = HttpExchange(parts[0], parts.getOrElse(1) { "/" }, headers, body)
                            handler(ex)
                            val out = c.getOutputStream()
                            val head = StringBuilder("HTTP/1.1 ${ex.status} X\r\n")
                            ex.responseHeaders.forEach { (k, v) -> head.append("$k: $v\r\n") }
                            head.append("Content-Length: ${ex.responseBody.size}\r\nConnection: close\r\n\r\n")
                            out.write(head.toString().toByteArray())
                            out.write(ex.responseBody)
                            out.flush()
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            buf.write(b)
        }
    }

    fun stop() {
        running = false
        socket.close()
    }
}

/**
 * Exercises the real HttpURLConnection transport against a loopback HTTP server. HTTPS is
 * required in production; these tests construct the transport with that check disabled so a
 * plain local server can be used, and separately verify the check itself.
 */
class SyncTransportTest {
    private val servers = mutableListOf<LoopbackServer>()

    private fun server(handler: (HttpExchange) -> Unit): String {
        val s = LoopbackServer(handler)
        servers += s
        return "http://127.0.0.1:${s.port}"
    }

    @After
    fun stop() = servers.forEach { it.stop() }

    private fun respond(ex: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        ex.status = status
        ex.responseHeaders.putAll(headers)
        ex.responseBody = body.toByteArray()
    }

    private fun transport(readTimeoutMs: Int = 2_000, maxBytes: Int = 16 * 1024) =
        UrlConnectionTransport(requireHttps = false, connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs, maxResponseBytes = maxBytes)

    /** SyncApi only accepts https origins, so route its requests to the loopback server. */
    private fun api(base: String, t: HttpTransport = transport()) =
        SyncApi("https://health.example.test", "svc-test-token", HttpTransport { req -> t.execute(req.copy(url = req.url.replace("https://health.example.test", base))) })

    @Test
    fun sendsGateAndDeviceHeadersAndParsesUpload() {
        val seen = CopyOnWriteArrayList<String>()
        val base = server { ex ->
            seen += "${ex.requestMethod} ${ex.path}"
            seen += "gate=" + ex.header("OAI-Sites-Authorization")
            seen += "auth=" + ex.header("Authorization")
            seen += "trigger=" + ex.header("X-HC-Sync-Trigger")
            seen += "auto=" + ex.header("X-HC-Auto-Sync")
            seen += "body=" + ex.requestBody.toString(Charsets.UTF_8)
            respond(ex, 201, """{"status":"imported","rangeStart":"2026-09-28","rangeEnd":"2026-10-27","dayCount":30}""")
        }
        val result = api(base).upload("hcd_" + "C".repeat(43), "{\"x\":1}", SyncTrigger.SCHEDULED, autoSyncOn = true)
        assertEquals(ApiResult.Uploaded(false, "2026-09-28", "2026-10-27"), result)
        assertEquals(
            listOf(
                "POST /api/sync",
                "gate=Bearer svc-test-token",
                "auth=Bearer hcd_" + "C".repeat(43),
                "trigger=scheduled",
                "auto=on",
                "body={\"x\":1}",
            ),
            seen,
        )
    }

    @Test
    fun pairingDoesNotSendADeviceTokenAndValidatesTheResponse() {
        val auth = CopyOnWriteArrayList<String?>()
        val good = "hcd_" + "D".repeat(43)
        var reply = """{"deviceId":"id-1","deviceToken":"$good","deviceName":"Pixel"}"""
        val base = server { ex ->
            auth += ex.header("Authorization")
            respond(ex, 201, reply)
        }
        assertEquals(ApiResult.Paired("id-1", good, "Pixel"), api(base).pair("hcp_" + "E".repeat(43), "Pixel"))
        assertEquals(listOf<String?>(null), auth)
        reply = """{"deviceId":"id-1","deviceToken":"not-a-token"}"""
        assertEquals(ApiResult.Failed(Rejection.UNEXPECTED, 201), api(base).pair("hcp_" + "E".repeat(43), "Pixel"))
    }

    @Test
    fun maps401And5xxResponses() {
        val status = AtomicInteger(401)
        var body = """{"error":"x","code":"device_revoked"}"""
        var errorHeader: String? = "device_revoked"
        val base = server { ex -> respond(ex, status.get(), body, errorHeader?.let { mapOf("X-HC-Error" to it) } ?: emptyMap()) }
        val token = "hcd_" + "F".repeat(43)
        assertEquals(ApiResult.Failed(Rejection.DEVICE_REVOKED, 401), api(base).upload(token, "{}", SyncTrigger.MANUAL, false))
        body = "<html>Sign in</html>"
        errorHeader = null
        assertEquals(ApiResult.Failed(Rejection.ACCESS_DENIED, 401), api(base).upload(token, "{}", SyncTrigger.MANUAL, false))
        status.set(503)
        assertEquals(ApiResult.Failed(Rejection.TRANSIENT, 503), api(base).upload(token, "{}", SyncTrigger.MANUAL, false))
        status.set(409)
        body = """{"code":"stale"}"""
        assertEquals(ApiResult.Failed(Rejection.STALE, 409), api(base).upload(token, "{}", SyncTrigger.MANUAL, false))
    }

    @Test
    fun statusIsAPlainGetOverTheRealTransportAndNeverFollowsRedirects() {
        val seen = CopyOnWriteArrayList<String>()
        val token = "hcd_" + "K".repeat(43)
        var reply = 200
        val other = server { ex ->
            seen += "LEAKED ${ex.path}"
            respond(ex, 200, "{}")
        }
        val base = server { ex ->
            seen += "${ex.requestMethod} ${ex.path} body=${ex.requestBody.size}"
            seen += "gate=" + ex.header("OAI-Sites-Authorization")
            seen += "auth=" + ex.header("Authorization")
            if (reply == 302) respond(ex, 302, "", mapOf("Location" to "$other/signin"))
            else respond(ex, 200, """{"deviceConnected":true,"lastSuccessfulSyncAt":null,"chatgptAccessEnabled":false,"checkedAt":"2026-10-27T21:30:00.000Z"}""")
        }
        assertEquals(
            ApiResult.Status(null, SiteAccess(false, java.time.Instant.parse("2026-10-27T21:30:00Z"))),
            api(base).status(token),
        )
        assertEquals(listOf("GET /api/sync/status body=0", "gate=Bearer svc-test-token", "auth=Bearer $token"), seen)
        reply = 302
        assertEquals(ApiResult.Failed(Rejection.ACCESS_DENIED, 302), api(base).status(token))
        assertTrue(seen.none { it.startsWith("LEAKED") })
    }

    @Test
    fun neverFollowsRedirectsWithCredentials() {
        val leaked = AtomicInteger(0)
        val other = server { ex ->
            // Any request here means the redirect was followed (with or without credentials).
            leaked.incrementAndGet()
            respond(ex, 200, """{"status":"imported"}""")
        }
        val base = server { ex -> respond(ex, 302, "", mapOf("Location" to "$other/steal")) }
        val result = api(base).upload("hcd_" + "G".repeat(43), "{}", SyncTrigger.SCHEDULED, true)
        assertEquals(ApiResult.Failed(Rejection.ACCESS_DENIED, 302), result)
        assertEquals(0, leaked.get())
    }

    @Test
    fun timeoutsAndUnreachableHostsAreTransient() {
        val base = server { ex ->
            Thread.sleep(1_500)
            respond(ex, 200, "{}")
        }
        val result = api(base, transport(readTimeoutMs = 300)).upload("hcd_" + "H".repeat(43), "{}", SyncTrigger.SCHEDULED, true)
        assertEquals(ApiResult.Failed(Rejection.TRANSIENT, null), result)
        try {
            transport(readTimeoutMs = 300).execute(HttpRequest("GET", base, emptyMap(), null))
            fail("expected timeout")
        } catch (_: SocketTimeoutException) {
        }
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = closed.localPort
        closed.close()
        val unreachable = api("http://127.0.0.1:$port").disconnect("hcd_" + "I".repeat(43))
        assertEquals(ApiResult.Failed(Rejection.TRANSIENT, null), unreachable)
    }

    @Test
    fun capsResponseBodies() {
        val base = server { ex -> respond(ex, 500, "x".repeat(100_000)) }
        val response = transport(maxBytes = 1024).execute(HttpRequest("GET", base, emptyMap(), null))
        assertEquals(500, response.status)
        assertEquals(1024, response.body.length)
    }

    @Test
    fun productionTransportRefusesPlainHttp() {
        val hits = AtomicInteger(0)
        val base = server { ex ->
            hits.incrementAndGet()
            respond(ex, 200, "{}")
        }
        try {
            UrlConnectionTransport().execute(HttpRequest("POST", "$base/api/sync", mapOf("Authorization" to "Bearer x"), ByteArray(0)))
            fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        } catch (e: IOException) {
            fail("should refuse before connecting: $e")
        }
        assertEquals(0, hits.get())
    }

    @Test
    fun apiRejectsNonHttpsOrigins() {
        try {
            SyncApi("http://health.example.test", "svc", transport())
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(SyncOrigin.normalize("http://health.example.test") != null)
    }
}
