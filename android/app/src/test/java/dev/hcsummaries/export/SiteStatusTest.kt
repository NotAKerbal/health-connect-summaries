package dev.hcsummaries.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator

/** Status endpoint parsing, status persistence (including upgrades from 0.2.0), and the refresh flow. */
class SiteStatusTest {
    private val site = "https://health.example.test"
    private val now = Instant.parse("2026-10-27T21:40:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val deviceToken = "hcd_" + "S".repeat(43)
    private val creds = SyncCredentials(site, "svc-test-token", "dev-1", deviceToken, "Pixel 10 Pro", Instant.parse("2026-10-27T21:20:00Z"))

    private class Fake(var status: Int = 200, var body: String = "{}", var errorCode: String? = null) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var throwIo = false
        var beforeReply: () -> Unit = {}
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            beforeReply()
            if (throwIo) throw IOException("offline")
            return HttpResponse(status, body, errorCode)
        }
    }

    private fun statusBody(enabled: Any? = false, checkedAt: Any? = "2026-10-27T21:39:00.000Z", last: Any? = "2026-10-27T21:28:00.000Z") =
        buildString {
            append("""{"deviceConnected":true""")
            if (last != null) append(""","lastSuccessfulSyncAt":${if (last is String) "\"$last\"" else last}""")
            if (enabled != null) append(""","chatgptAccessEnabled":$enabled""")
            if (checkedAt != null) append(""","checkedAt":"$checkedAt"""")
            append("}")
        }

    private class Fixture(
        val kv: MemoryKeyValue,
        val credentials: CredentialStore,
        val settings: SyncSettings,
        val state: SyncStateStore,
        val transport: Fake,
        val refresher: SiteStatusRefresher,
        var cancelled: Int,
    )

    private fun fixture(transport: Fake = Fake(body = statusBody())): Fixture {
        val credentials = CredentialStore(MemoryKeyValue(), AesGcmCipher { key }).apply { save(creds) }
        val settings = SyncSettings(MemoryKeyValue()).apply { autoSync = true }
        val kv = MemoryKeyValue()
        val state = SyncStateStore(kv)
        lateinit var f: Fixture
        val refresher = SiteStatusRefresher(credentials, settings, state, { SyncApi(site, it.serviceToken, transport) }, { f.cancelled++ }, clock)
        f = Fixture(kv, credentials, settings, state, transport, refresher, 0)
        return f
    }

    @Test
    fun statusRequestIsAGetWithGateAndDeviceHeadersAndNoBody() {
        val t = Fake(body = statusBody(enabled = true))
        val result = SyncApi(site, "svc-test-token", t).status(deviceToken)
        assertEquals(
            ApiResult.Status(Instant.parse("2026-10-27T21:28:00Z"), SiteAccess(true, Instant.parse("2026-10-27T21:39:00Z"))),
            result,
        )
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals("$site/api/sync/status", req.url)
        assertNull(req.body)
        assertEquals("Bearer svc-test-token", req.headers["OAI-Sites-Authorization"])
        assertEquals("Bearer $deviceToken", req.headers["Authorization"])
        assertFalse("token never in the URL", req.url.contains(deviceToken))
    }

    @Test
    fun statusParsingNeverInventsAFlag() {
        fun parse(body: String) = SyncApi(site, "svc", Fake(body = body)).status(deviceToken)
        // Older Site or a malformed field: connected, access unknown.
        assertEquals(ApiResult.Status(Instant.parse("2026-10-27T21:28:00Z"), null), parse(statusBody(enabled = null)))
        assertEquals(null, (parse(statusBody(enabled = "\"true\"")) as ApiResult.Status).access)
        assertEquals(null, (parse(statusBody(enabled = 1)) as ApiResult.Status).access)
        // A flag without a valid time is not a fact.
        assertEquals(null, (parse(statusBody(checkedAt = null)) as ApiResult.Status).access)
        assertEquals(null, (parse(statusBody(checkedAt = "yesterday")) as ApiResult.Status).access)
        assertEquals(null, (parse(statusBody(last = "null")) as ApiResult.Status).lastSuccessfulSyncAt)
        // Not a status answer at all.
        assertEquals(ApiResult.Failed(Rejection.UNEXPECTED, 200), parse("""{"chatgptAccessEnabled":true,"checkedAt":"2026-10-27T21:39:00Z"}"""))
        assertEquals(ApiResult.Failed(Rejection.UNEXPECTED, 200), parse("<html>"))
        assertEquals(ApiResult.Failed(Rejection.DEVICE_REVOKED, 401), SyncApi(site, "svc", Fake(401, "", "device_revoked")).status(deviceToken))
    }

    @Test
    fun uploadResponsesCarryTheAccessFlagWhenTheSiteReportsIt() {
        val t = Fake(201, """{"status":"imported","rangeStart":"2026-09-28","rangeEnd":"2026-10-27","chatgptAccessEnabled":false,"checkedAt":"2026-10-27T21:28:00Z"}""")
        val result = SyncApi(site, "svc", t).upload(deviceToken, "{}", SyncTrigger.SCHEDULED, true)
        assertEquals(ApiResult.Uploaded(false, "2026-09-28", "2026-10-27", SiteAccess(false, Instant.parse("2026-10-27T21:28:00Z"))), result)
        t.body = """{"status":"duplicate","rangeStart":"a","rangeEnd":"b"}"""
        assertEquals(ApiResult.Uploaded(true, "a", "b", null), SyncApi(site, "svc", t).upload(deviceToken, "{}", SyncTrigger.MANUAL, false))
    }

    @Test
    fun upgradeFrom020KeepsEverythingAndStartsWithAccessUnknown() {
        // Exactly the keys version 0.2.0 wrote.
        val stateKv = MemoryKeyValue().apply {
            values["last_success_ms"] = Instant.parse("2026-10-27T21:28:00Z").toEpochMilli()
            values["last_success_range"] = "2026-09-28 to 2026-10-27"
            values["last_attempt_ms"] = Instant.parse("2026-10-27T21:28:00Z").toEpochMilli()
            values["will_retry"] = false
        }
        val settingsKv = MemoryKeyValue().apply {
            values["auto_sync"] = true
            values["sync_generation"] = 3
            values["selected"] = setOf("steps", "sleepMinutes")
        }
        val credKv = MemoryKeyValue()
        CredentialStore(credKv, AesGcmCipher { key }).save(creds)

        val state = SyncStateStore(stateKv)
        val settings = SyncSettings(settingsKv)
        assertEquals(Instant.parse("2026-10-27T21:28:00Z"), state.load().lastSuccessAt)
        assertEquals("2026-09-28 to 2026-10-27", state.load().lastSuccessRange)
        assertTrue(settings.autoSync)
        assertEquals(3, settings.generation)
        assertEquals(setOf(Metric.STEPS, Metric.SLEEP), settings.selected)
        assertEquals(creds, CredentialStore(credKv, AesGcmCipher { key }).load())
        assertEquals(SiteStatus(), state.loadSite())

        val h = HomeStatusModel.build(
            HomeInputs(true, true, true, true, settings.autoSync, state.load(), state.loadSite(), WorkSnapshot(true, false, null), true),
        )
        assertEquals(UploadPhase.COMPLETED, h.upload.phase)
        assertEquals(AccessState.UNKNOWN, h.access.state)
        assertEquals(CalloutKind.ACCESS_UNKNOWN, h.callout.kind)
    }

    @Test
    fun theFlagIsStoredOnlyWithItsTimestampAndNewerAnswersWin() {
        val kv = MemoryKeyValue()
        val store = SyncStateStore(kv)
        kv.values["chatgpt_access"] = "on"
        assertNull("flag without time is not a fact", store.loadSite().access)

        store.recordStatus(Instant.parse("2026-10-27T21:28:00Z"), SiteAccess(false, now.minusSeconds(60)), now)
        assertEquals(SiteAccess(false, now.minusSeconds(60)), store.loadSite().access)
        assertEquals(now, store.loadSite().lastCheckAt)
        assertNull(store.loadSite().lastCheckFailure)

        // An older answer (e.g. an upload response that raced a status check) does not overwrite.
        store.record(SyncOutcome.Success(false, "a", "b", 30, SiteAccess(true, now.minusSeconds(120))), now, willRetry = false)
        assertEquals(false, store.loadSite().access?.enabled)
        store.record(SyncOutcome.Success(false, "a", "b", 30, SiteAccess(true, now.minusSeconds(10))), now, willRetry = false)
        assertEquals(true, store.loadSite().access?.enabled)

        // A Site clock ahead of the phone cannot make a value look newer than now.
        store.recordStatus(null, SiteAccess(false, now.plusSeconds(3600)), now)
        assertEquals(SiteAccess(false, now), store.loadSite().access)
        assertNull(store.loadSite().siteLastSyncAt)

        // Failures keep the last known value and mark it as not current.
        store.recordStatusFailure(StatusCheckFailure.OFFLINE, now.plusSeconds(30))
        assertEquals(SiteAccess(false, now), store.loadSite().access)
        assertEquals(StatusCheckFailure.OFFLINE, store.loadSite().lastCheckFailure)
        assertEquals(now.plusSeconds(30), store.loadSite().lastCheckAt)

        // An older Site answering without the flag: known value kept, marked as outdated.
        store.recordStatus(null, null, now.plusSeconds(40))
        assertEquals(StatusCheckFailure.SITE_OUTDATED, store.loadSite().lastCheckFailure)
        assertNotNull(store.loadSite().access)

        // Sync results are unaffected, and clear() (pair or disconnect) removes everything.
        assertNotNull(store.load().lastSuccessAt)
        store.clear()
        assertEquals(SiteStatus(), store.loadSite())
        assertTrue(kv.values.isEmpty())
    }

    @Test
    fun refreshStoresTheAnswerWithoutTouchingCredentials() = runBlocking {
        val f = fixture(Fake(body = statusBody(enabled = true)))
        assertEquals(SiteStatusRefresher.Result.UPDATED, f.refresher.refresh())
        assertEquals(SiteAccess(true, Instant.parse("2026-10-27T21:39:00Z")), f.state.loadSite().access)
        assertEquals(Instant.parse("2026-10-27T21:28:00Z"), f.state.loadSite().siteLastSyncAt)
        assertEquals(creds, f.credentials.load())
        assertEquals(0, f.cancelled)
    }

    @Test
    fun offlineAndGateFailuresKeepCredentialsAndTheLastKnownValue() = runBlocking {
        val f = fixture(Fake(body = statusBody(enabled = false)))
        f.refresher.refresh()
        val known = f.state.loadSite().access
        f.transport.throwIo = true
        assertEquals(SiteStatusRefresher.Result.FAILED, f.refresher.refresh())
        assertEquals(StatusCheckFailure.OFFLINE, f.state.loadSite().lastCheckFailure)
        f.transport.throwIo = false
        f.transport.status = 503
        assertEquals(SiteStatusRefresher.Result.FAILED, f.refresher.refresh())
        assertEquals(StatusCheckFailure.OFFLINE, f.state.loadSite().lastCheckFailure)
        // The private-Site gate (no app error code), or a redirect to sign-in.
        f.transport.status = 401
        f.transport.body = "<html>sign in</html>"
        assertEquals(SiteStatusRefresher.Result.FAILED, f.refresher.refresh())
        assertEquals(StatusCheckFailure.ACCESS_DENIED, f.state.loadSite().lastCheckFailure)
        f.transport.status = 302
        assertEquals(SiteStatusRefresher.Result.FAILED, f.refresher.refresh())
        assertEquals(known, f.state.loadSite().access)
        assertEquals(creds, f.credentials.load())
        assertEquals(0, f.cancelled)
        assertEquals(null, f.state.load().paused)
    }

    @Test
    fun anOlderSiteIsReportedAsOutdatedNotAsRevoked() = runBlocking {
        val f = fixture(Fake(status = 404, body = "<html>Not found</html>"))
        assertEquals(SiteStatusRefresher.Result.SITE_OUTDATED, f.refresher.refresh())
        assertEquals(StatusCheckFailure.SITE_OUTDATED, f.state.loadSite().lastCheckFailure)
        assertNull(f.state.loadSite().access)
        f.transport.status = 200
        f.transport.body = statusBody(enabled = null)
        assertEquals(SiteStatusRefresher.Result.SITE_OUTDATED, f.refresher.refresh())
        assertEquals(creds, f.credentials.load())
    }

    @Test
    fun aRevokedStatusPausesSyncAndForgetsTheCredentials() = runBlocking {
        val f = fixture(Fake(body = statusBody(enabled = true)))
        f.refresher.refresh()
        f.state.record(SyncOutcome.Success(false, "2026-09-28", "2026-10-27", 30), now, willRetry = false)
        val generation = f.settings.generation
        f.transport.status = 401
        f.transport.body = """{"code":"device_revoked"}"""
        f.transport.errorCode = "device_revoked"
        assertEquals(SiteStatusRefresher.Result.REVOKED, f.refresher.refresh())
        assertNull(f.credentials.load())
        assertEquals(generation + 1, f.settings.generation)
        assertEquals(1, f.cancelled)
        assertEquals(PauseReason.DEVICE_REVOKED, f.state.load().paused)
        assertNull("no access claim for a disconnected phone", f.state.loadSite().access)
        assertNotNull("history of the earlier sync is kept", f.state.load().lastSuccessAt)
        val h = HomeStatusModel.build(
            HomeInputs(true, f.credentials.load() != null, true, true, true, f.state.load(), f.state.loadSite(), WorkSnapshot(false, false, null), true),
        )
        assertEquals(ConnectionState.REVOKED, h.connection)
        assertEquals(SiteStatusRefresher.Result.NOT_PAIRED, f.refresher.refresh())
    }

    @Test
    fun anAnswerArrivingAfterDisconnectOrRepairIsDiscarded() = runBlocking {
        val f = fixture(Fake(body = statusBody(enabled = true)))
        f.transport.beforeReply = { f.settings.bumpGeneration() }
        assertEquals(SiteStatusRefresher.Result.SUPERSEDED, f.refresher.refresh())
        assertEquals(SiteStatus(), f.state.loadSite())
        // Even a revoked answer for old credentials must not wipe the new pairing.
        f.transport.beforeReply = { f.credentials.save(creds.copy(deviceToken = "hcd_" + "N".repeat(43))) }
        f.transport.status = 401
        f.transport.errorCode = "device_revoked"
        assertEquals(SiteStatusRefresher.Result.SUPERSEDED, f.refresher.refresh())
        assertNotNull(f.credentials.load())
        assertEquals(0, f.cancelled)
    }
}
