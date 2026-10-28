package dev.hcsummaries.export

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)

/** [errorCode] is the Site's X-HC-Error header, which survives even when a client drops a 401 body. */
data class HttpResponse(val status: Int, val body: String, val errorCode: String? = null)

fun interface HttpTransport {
    /** Throws [IOException] for network failures and timeouts. */
    fun execute(request: HttpRequest): HttpResponse
}

/** The Site's hostname resolved to a loopback, private, link-local, or otherwise non-public address. */
class NonPublicAddressException : IOException("site address is not public")

object PublicAddress {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> !(
                b[0] == 0 || b[0] >= 240 ||
                    (b[0] == 100 && b[1] in 64..127) || // carrier-grade NAT
                    (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
                    (b[0] == 198 && b[1] in 18..19)
                )
            is Inet6Address -> (b[0] and 0xfe) != 0xfc // unique local fc00::/7
            else -> false
        }
    }
}

/**
 * HttpURLConnection transport for credentialed requests: HTTPS only, redirects never followed
 * (a redirect could carry credentials elsewhere, or means the private-Site gate refused us),
 * bounded connect/read timeouts, and a cap on how much of a response is read.
 *
 * With [blockNonPublicAddresses] (builds without a built-in Site), a hostname that resolves to a
 * loopback, private, or link-local address is refused before connecting.
 */
class UrlConnectionTransport internal constructor(
    private val requireHttps: Boolean,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxResponseBytes: Int = 16 * 1024,
    private val blockNonPublicAddresses: Boolean = false,
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : HttpTransport {
    constructor() : this(requireHttps = true)

    override fun execute(request: HttpRequest): HttpResponse {
        val url = URL(request.url)
        require(!requireHttps || url.protocol == "https") { "only https requests are allowed" }
        if (blockNonPublicAddresses) {
            val addresses = try {
                resolve(url.host)
            } catch (e: Exception) {
                throw IOException("could not resolve the site", e)
            }
            if (addresses.isEmpty() || !addresses.all(PublicAddress::isPublic)) throw NonPublicAddressException()
        }
        val connection = url.openConnection() as HttpURLConnection
        if (requireHttps) check(connection is HttpsURLConnection) { "not an https connection" }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = request.method
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val errorCode = connection.getHeaderField("X-HC-Error")?.take(64)
            val stream = try {
                if (status >= 400) connection.errorStream else connection.inputStream
            } catch (_: IOException) {
                null
            }
            return HttpResponse(status, stream?.use { readCapped(it) } ?: "", errorCode)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** HTTPS transport that also refuses hostnames resolving to non-public addresses. */
        fun publicOnly() = UrlConnectionTransport(requireHttps = true, blockNonPublicAddresses = true)
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < maxResponseBytes) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxResponseBytes - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/**
 * The calls the phone makes to its private Site. Every request carries the Site's service
 * access key in OAI-Sites-Authorization (the private-Site gate); sync, status, and disconnect
 * also carry the device token. Nothing here can read health data from the Site: status returns
 * only connection metadata (connected, last successful sync, ChatGPT access on or off).
 */
class SyncApi(origin: String, private val serviceToken: String, private val transport: HttpTransport) {
    private val origin: String = requireNotNull(SyncOrigin.normalize(origin)) { "invalid site origin" }

    fun pair(pairingToken: String, deviceName: String): ApiResult {
        val body = JSONObject().put("pairingToken", pairingToken).put("deviceName", deviceName).toString()
        return call("POST", "/api/sync/pair", null, body.toByteArray(Charsets.UTF_8), emptyMap()) { json ->
            val id = json.optString("deviceId", "")
            val token = json.optString("deviceToken", "")
            if (id.isEmpty() || !DEVICE_TOKEN_RE.matches(token)) null
            else ApiResult.Paired(id, token, json.optString("deviceName", deviceName))
        }
    }

    fun upload(deviceToken: String, snapshotJson: String, trigger: SyncTrigger, autoSyncOn: Boolean): ApiResult {
        val extra = mapOf("X-HC-Sync-Trigger" to trigger.header, "X-HC-Auto-Sync" to if (autoSyncOn) "on" else "off")
        return call("POST", "/api/sync", deviceToken, snapshotJson.toByteArray(Charsets.UTF_8), extra) { json ->
            when (val status = json.optString("status", "")) {
                "imported", "duplicate" -> ApiResult.Uploaded(
                    status == "duplicate",
                    json.optString("rangeStart", ""),
                    json.optString("rangeEnd", ""),
                    SiteAccessJson.parse(json),
                )
                else -> null
            }
        }
    }

    /** Reads connection metadata only; it does not touch Health Connect or send any health data. */
    fun status(deviceToken: String): ApiResult =
        call("GET", "/api/sync/status", deviceToken, null, emptyMap()) { json ->
            if (json.opt("deviceConnected") != true) null
            else ApiResult.Status(SiteAccessJson.instantOrNull(json, "lastSuccessfulSyncAt"), SiteAccessJson.parse(json))
        }

    fun disconnect(deviceToken: String): ApiResult =
        call("DELETE", "/api/sync", deviceToken, null, emptyMap()) { ApiResult.Disconnected }

    private fun call(
        method: String,
        path: String,
        deviceToken: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String>,
        parse: (JSONObject) -> ApiResult?,
    ): ApiResult {
        val headers = buildMap {
            put("OAI-Sites-Authorization", "Bearer $serviceToken")
            if (deviceToken != null) put("Authorization", "Bearer $deviceToken")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
            putAll(extraHeaders)
        }
        val response = try {
            transport.execute(HttpRequest(method, origin + path, headers, body))
        } catch (_: IOException) {
            return ApiResult.Failed(Rejection.TRANSIENT, null)
        }
        if (response.status !in 200..299) {
            return ApiResult.Failed(ApiResponses.classify(response.status, response.body, response.errorCode), response.status)
        }
        val json = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            return ApiResult.Failed(Rejection.UNEXPECTED, response.status)
        }
        return parse(json) ?: ApiResult.Failed(Rejection.UNEXPECTED, response.status)
    }

    companion object {
        private val DEVICE_TOKEN_RE = Regex("^hcd_[A-Za-z0-9_-]{43}$")
    }
}
