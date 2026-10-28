package dev.hcsummaries.export

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.KeyGenerator

/** Generic (unpinned) builds: which codes are accepted, host confirmation before any request, and origin pinning after pairing. */
class PortableSiteTest {
    private val now = Instant.parse("2026-10-27T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val pairing = "hcp_" + "B".repeat(43)
    private val mySite = "https://alex-health.alex.chatgpt.site"
    private val pinnedSite = "https://health.example.test"

    private fun code(origin: String = mySite, expiresAt: String = "2026-10-27T20:05:00Z", pairingToken: String = pairing): String {
        val json = JSONObject().put("v", 1).put("origin", origin).put("serviceToken", "svc-test-token-123")
            .put("pairingToken", pairingToken).put("expiresAt", expiresAt).toString()
        return ConnectionCodes.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private class Recorder(var status: Int = 201, var body: String = "{}") : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(status, body)
        }
    }

    private fun problem(text: String, policy: SitePolicy = SitePolicy.Generic) =
        (PairingFlow.start(text, policy, now) as? PairingFlow.Step.Reject)?.problem

    @Test
    fun buildConfigSelectsThePolicy() {
        assertEquals(SitePolicy.Generic, SitePolicy.fromBuildConfig(""))
        assertEquals(SitePolicy.Pinned(pinnedSite), SitePolicy.fromBuildConfig("$pinnedSite/"))
        assertEquals(SitePolicy.Misconfigured, SitePolicy.fromBuildConfig("http://health.example.test"))
        assertEquals(ConnectionCodes.Problem.NO_SITE_CONFIGURED, problem(code(), SitePolicy.Misconfigured))
    }

    @Test
    fun genericValidCodeNeedsConfirmationAndParsingSendsNothing() {
        val recorder = Recorder()
        val step = PairingFlow.start("  ${code()}\n", SitePolicy.Generic, now)
        assertTrue(step is PairingFlow.Step.Confirm)
        step as PairingFlow.Step.Confirm
        assertEquals("alex-health.alex.chatgpt.site", step.host)
        assertTrue(step.chatgptSite)
        assertEquals(mySite, step.code.origin)
        assertTrue(recorder.requests.isEmpty())
        val other = PairingFlow.start(code(origin = "https://health.example.com"), SitePolicy.Generic, now) as PairingFlow.Step.Confirm
        assertFalse(other.chatgptSite)
    }

    @Test
    fun cancelSendsNothingAndConnectSendsOnlyToTheConfirmedOrigin() {
        val recorder = Recorder(body = """{"deviceId":"dev-1","deviceToken":"hcd_${"C".repeat(43)}","deviceName":"Pixel"}""")
        val step = PairingFlow.start(code(), SitePolicy.Generic, now) as PairingFlow.Step.Confirm
        // Cancel: the activity simply drops the step. Nothing was sent.
        assertTrue(recorder.requests.isEmpty())
        // Connect: one pairing request, to the confirmed origin only.
        val result = SyncApi(step.code.origin, step.code.serviceToken, recorder).pair(step.code.pairingToken, "Pixel")
        assertTrue(result is ApiResult.Paired)
        assertEquals(1, recorder.requests.size)
        assertEquals("$mySite/api/sync/pair", recorder.requests.single().url)
        assertEquals("Bearer svc-test-token-123", recorder.requests.single().headers["OAI-Sites-Authorization"])
    }

    @Test
    fun pinnedBuildPairsWithoutPromptAndRejectsOtherHosts() {
        val pinned = SitePolicy.Pinned(pinnedSite)
        assertTrue(PairingFlow.start(code(origin = pinnedSite), pinned, now) is PairingFlow.Step.Pair)
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = mySite), pinned))
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "https://health.example.test.evil.example"), pinned))
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "http://health.example.test"), pinned))
    }

    @Test
    fun malformedAndExpiredCodesAreBlockedInGenericBuilds() {
        assertEquals(ConnectionCodes.Problem.EXPIRED, problem(code(expiresAt = "2026-10-27T19:59:59Z")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem("https://alex-health.alex.chatgpt.site"))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(pairingToken = "hcp_short")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(ConnectionCodes.PREFIX + "%%%"))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(""))
    }

    @Test
    fun genericBuildsAcceptOnlyPublicHttpsOrigins() {
        val refused = listOf(
            "http://alex-health.alex.chatgpt.site",
            "https://127.0.0.1",
            "https://10.0.0.5",
            "https://192.168.1.20",
            "https://[::1]",
            "https://localhost",
            "https://printer.local",
            "https://wiki.corp",
            "https://db.internal",
            "https://router.home.arpa",
            "https://intranet",
            "https://health.example.test",
            "https://alex-health.alex.chatgpt.site:8443",
            "https://alex-health.alex.chatgpt.site:443",
            "https://alex-health.alex.chatgpt.site/",
            "https://Alex-Health.alex.chatgpt.site",
            "https://alex-health.alex.chatgpt.site/path",
            "https://user@alex-health.example.com",
            "https://-bad-.example.com",
            "https://a..example.com",
            "https://example.com.",
            "https://1.2.3.4.5",
        )
        for (origin in refused) {
            assertEquals(origin, ConnectionCodes.Problem.UNSAFE_SITE, problem(code(origin = origin)))
            assertFalse(origin, SitePolicy.Generic.accepts(origin))
        }
        for (origin in listOf(mySite, "https://health.example.com", "https://xn--bcher-kva.example.org")) {
            assertTrue(origin, PairingFlow.start(code(origin = origin), SitePolicy.Generic, now) is PairingFlow.Step.Confirm)
            assertTrue(origin, SitePolicy.Generic.accepts(origin))
        }
    }

    @Test
    fun storedPairingsKeepTheirOriginAndAreNeverRedirected() {
        val creds = SyncCredentials(mySite, "svc", "dev-1", "hcd_" + "D".repeat(43), "Pixel", now)
        assertEquals(mySite, SitePolicy.Generic.trustedOrigin(creds))
        assertNull(SitePolicy.Generic.trustedOrigin(null))
        // Credentials for a Site this build may not use are never used, and nothing is substituted.
        assertNull(SitePolicy.Pinned(pinnedSite).trustedOrigin(creds))
        assertNull(SitePolicy.Generic.trustedOrigin(creds.copy(origin = "https://10.0.0.5")))
        assertNull(SitePolicy.Generic.trustedOrigin(creds.copy(origin = "$mySite/")))
        // A 0.4.0 pinned pairing stays usable after upgrading to 0.5.0, pinned or generic.
        val existing = creds.copy(origin = pinnedSite)
        assertEquals(pinnedSite, SitePolicy.Pinned(pinnedSite).trustedOrigin(existing))
        val chatgptPinned = creds.copy(origin = "https://health-copy.someone.chatgpt.site")
        assertEquals(chatgptPinned.origin, SitePolicy.Generic.trustedOrigin(chatgptPinned))
    }

    @Test
    fun existingEncryptedPairingLoadsAndUploadsToItsOwnOrigin() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = CredentialStore(MemoryKeyValue(), AesGcmCipher { key })
        store.save(SyncCredentials(mySite, "svc", "dev-1", "hcd_" + "E".repeat(43), "Pixel", now))
        val recorder = Recorder(body = """{"status":"imported","rangeStart":"2026-09-28","rangeEnd":"2026-10-27"}""")
        val settings = SyncSettings(MemoryKeyValue()).apply {
            selected = setOf(Metric.STEPS)
            autoSync = true
        }
        val health = object : HealthSource {
            override fun available() = true
            override fun backgroundReadSupported() = true
            override suspend fun grantedPermissions() = setOf(Metric.STEPS.readPermission, SyncEngine.BACKGROUND_PERMISSION)
            override suspend fun read(windows: List<DayWindow>, metrics: List<Metric>) = windows.map { DayResult(it, emptyMap()) }
        }
        val engine = SyncEngine(health, store::load, settings, { SyncApi(it.origin, it.serviceToken, recorder) }, SitePolicy.Generic, clock, { ZoneId.of("UTC") })
        assertTrue(engine.run(SyncTrigger.SCHEDULED, inBackground = true) is SyncOutcome.Success)
        assertEquals(listOf("$mySite/api/sync"), recorder.requests.map { it.url })
    }

    @Test
    fun genericEngineAndStatusRefuseCredentialsForNonPublicSites() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = CredentialStore(MemoryKeyValue(), AesGcmCipher { key })
        store.save(SyncCredentials("https://10.0.0.5", "svc", "dev-1", "hcd_" + "F".repeat(43), "Pixel", now))
        val recorder = Recorder()
        val settings = SyncSettings(MemoryKeyValue()).apply { autoSync = true }
        var reads = 0
        val health = object : HealthSource {
            override fun available() = true
            override fun backgroundReadSupported() = true
            override suspend fun grantedPermissions() = Metric.entries.map { it.readPermission }.toSet() + SyncEngine.BACKGROUND_PERMISSION
            override suspend fun read(windows: List<DayWindow>, metrics: List<Metric>): List<DayResult> {
                reads++
                return windows.map { DayResult(it, emptyMap()) }
            }
        }
        val engine = SyncEngine(health, store::load, settings, { SyncApi(it.origin, it.serviceToken, recorder) }, SitePolicy.Generic, clock, { ZoneId.of("UTC") })
        assertEquals(SyncOutcome.Paused(PauseReason.NOT_PAIRED), engine.run(SyncTrigger.SCHEDULED, inBackground = true))
        val refresher = SiteStatusRefresher(
            store, settings, SyncStateStore(MemoryKeyValue()),
            { creds -> SitePolicy.Generic.trustedOrigin(creds)?.let { SyncApi(it, creds.serviceToken, recorder) } },
            { fail("must not cancel work") }, clock,
        )
        assertEquals(SiteStatusRefresher.Result.NOT_PAIRED, refresher.refresh())
        assertEquals(0, reads)
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun publicAddressCheck() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fd00::1")) {
            assertFalse(ip, PublicAddress.isPublic(InetAddress.getByName(ip)))
        }
        for (ip in listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")) {
            assertTrue(ip, PublicAddress.isPublic(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun publicOnlyTransportRefusesPrivateResolutionBeforeConnecting() {
        val transport = UrlConnectionTransport(
            requireHttps = true,
            blockNonPublicAddresses = true,
            resolve = { arrayOf(InetAddress.getByName("192.168.1.20")) },
        )
        try {
            transport.execute(HttpRequest("GET", "$mySite/api/sync/status", mapOf("Authorization" to "Bearer x"), null))
            fail("expected refusal")
        } catch (_: NonPublicAddressException) {
        }
        // Through SyncApi it is an ordinary failure: nothing was sent, and nothing else is tried.
        val api = SyncApi(mySite, "svc", transport)
        assertEquals(ApiResult.Failed(Rejection.TRANSIENT, null), api.status("hcd_" + "G".repeat(43)))
    }
}
