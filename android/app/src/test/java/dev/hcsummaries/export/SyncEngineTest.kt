package dev.hcsummaries.export

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SyncEngineTest {
    private val site = "https://health.example.test"
    private val zone = ZoneId.of("America/Chicago")
    private val clock = Clock.fixed(Instant.parse("2026-10-27T20:00:00Z"), ZoneOffset.UTC)
    private val creds = SyncCredentials(site, "svc-token-x", "dev-1", "hcd_" + "J".repeat(43), "Pixel", clock.instant())
    private val allPermissions = Metric.entries.map { it.readPermission }.toSet() + SyncEngine.BACKGROUND_PERMISSION

    private class FakeHealth(
        var granted: Set<String>,
        var available: Boolean = true,
        var backgroundSupported: Boolean = true,
        var onRead: (List<DayWindow>, List<Metric>) -> List<DayResult> = { w, _ -> w.map { DayResult(it, emptyMap()) } },
    ) : HealthSource {
        var reads = 0
        override fun available() = available
        override fun backgroundReadSupported() = backgroundSupported
        override suspend fun grantedPermissions() = granted
        override suspend fun read(windows: List<DayWindow>, metrics: List<Metric>): List<DayResult> {
            reads++
            return onRead(windows, metrics)
        }
    }

    private class Recorder(var status: Int = 201, var body: String = """{"status":"imported","rangeStart":"2026-09-28","rangeEnd":"2026-10-27"}""") : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var throwIo = false
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            if (throwIo) throw java.io.IOException("offline")
            return HttpResponse(status, body)
        }
    }

    private fun setup(
        health: FakeHealth = FakeHealth(allPermissions),
        transport: Recorder = Recorder(),
        selected: Set<Metric> = setOf(Metric.STEPS, Metric.SLEEP),
        stored: () -> SyncCredentials? = { creds },
    ): Triple<SyncEngine, SyncSettings, Recorder> {
        val settings = SyncSettings(MemoryKeyValue()).apply {
            this.selected = selected
            autoSync = true
        }
        val engine = SyncEngine(health, stored, settings, { SyncApi(site, it.serviceToken, transport) }, SitePolicy.Pinned(site), clock, { zone })
        return Triple(engine, settings, transport)
    }

    @Test
    fun disabledAutomaticSyncDoesNotReadAndDisablingDuringReadDoesNotUpload() = runBlocking {
        val health = FakeHealth(allPermissions)
        val (engine, settings, transport) = setup(health)
        settings.autoSync = false
        assertEquals(SyncOutcome.Superseded, engine.run(SyncTrigger.SCHEDULED, true))
        assertEquals(0, health.reads)
        settings.autoSync = true
        health.onRead = { windows, _ ->
            settings.autoSync = false
            windows.map { DayResult(it, emptyMap()) }
        }
        assertEquals(SyncOutcome.Superseded, engine.run(SyncTrigger.SCHEDULED, true))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun uploadsA30DaySnapshotDirectlyWithoutAFile() = runBlocking {
        val health = FakeHealth(allPermissions, onRead = { w, _ ->
            w.mapIndexed { i, win -> DayResult(win, if (i == 0) mapOf(Metric.STEPS to 0.0) else if (i == 1) mapOf(Metric.SLEEP to 420.0) else emptyMap()) }
        })
        val (engine, _, transport) = setup(health)
        val outcome = engine.run(SyncTrigger.SCHEDULED, inBackground = true)
        assertEquals(SyncOutcome.Success(false, "2026-09-28", "2026-10-27", 30), outcome)
        val request = transport.requests.single()
        assertEquals("$site/api/sync", request.url)
        assertEquals("scheduled", request.headers["X-HC-Sync-Trigger"])
        assertEquals("on", request.headers["X-HC-Auto-Sync"])
        val json = JSONObject(String(request.body!!))
        assertEquals("America/Chicago", json.getString("timezone"))
        assertEquals("2026-09-28", json.getString("rangeStart"))
        assertEquals("2026-10-27", json.getString("rangeEnd"))
        val days = json.getJSONArray("days")
        assertEquals(30, days.length())
        assertEquals(0, days.getJSONObject(0).getInt("steps"))
        assertFalse("absent is not zero", days.getJSONObject(2).has("steps"))
        assertFalse(days.getJSONObject(0).has("sleepMinutes"))
    }

    @Test
    fun successCarriesTheSiteReportedChatGptAccess() = runBlocking {
        val transport = Recorder(
            body = """{"status":"imported","rangeStart":"2026-09-28","rangeEnd":"2026-10-27","chatgptAccessEnabled":true,"checkedAt":"2026-10-27T20:00:00Z"}""",
        )
        val (engine, _, _) = setup(transport = transport)
        val outcome = engine.run(SyncTrigger.SCHEDULED, inBackground = true) as SyncOutcome.Success
        assertEquals(SiteAccess(true, Instant.parse("2026-10-27T20:00:00Z")), outcome.access)
    }

    @Test
    fun backgroundRunPausesWithoutReadingWhenBackgroundAccessIsMissing() = runBlocking {
        val health = FakeHealth(allPermissions - SyncEngine.BACKGROUND_PERMISSION)
        val (engine, _, transport) = setup(health)
        assertEquals(SyncOutcome.Paused(PauseReason.BACKGROUND_PERMISSION_MISSING), engine.run(SyncTrigger.SCHEDULED, true))
        assertEquals(0, health.reads)
        assertTrue(transport.requests.isEmpty())
        // The same phone can still sync while the app is open.
        assertTrue(engine.run(SyncTrigger.MANUAL, inBackground = false) is SyncOutcome.Success)
    }

    @Test
    fun backgroundRunPausesWhereTheFeatureIsUnsupported() = runBlocking {
        val health = FakeHealth(allPermissions, backgroundSupported = false)
        val (engine, _, transport) = setup(health)
        assertEquals(SyncOutcome.Paused(PauseReason.BACKGROUND_UNSUPPORTED), engine.run(SyncTrigger.SCHEDULED, true))
        assertEquals(0, health.reads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun missingCategoryPausesAndNeverSendsAPartialSnapshot() = runBlocking {
        val health = FakeHealth(allPermissions - Metric.SLEEP.readPermission)
        val (engine, _, transport) = setup(health)
        assertEquals(SyncOutcome.Paused(PauseReason.PERMISSION_MISSING, listOf(Metric.SLEEP)), engine.run(SyncTrigger.SCHEDULED, true))
        assertEquals(0, health.reads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun revocationDuringTheReadSendsNothing() = runBlocking {
        val health = FakeHealth(allPermissions, onRead = { _, _ -> throw PermissionRevokedException(listOf(Metric.STEPS)) })
        val (engine, _, transport) = setup(health)
        assertEquals(SyncOutcome.Paused(PauseReason.PERMISSION_MISSING, listOf(Metric.STEPS)), engine.run(SyncTrigger.SCHEDULED, true))
        assertTrue(transport.requests.isEmpty())

        health.onRead = { _, _ ->
            health.granted = health.granted - SyncEngine.BACKGROUND_PERMISSION
            throw HealthReadInterruptedException()
        }
        assertEquals(SyncOutcome.Paused(PauseReason.BACKGROUND_PERMISSION_MISSING), engine.run(SyncTrigger.SCHEDULED, true))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun disconnectDuringTheReadSupersedesTheUpload() = runBlocking {
        var settingsRef: SyncSettings? = null
        val health = FakeHealth(allPermissions, onRead = { w, _ ->
            settingsRef!!.bumpGeneration()
            w.map { DayResult(it, emptyMap()) }
        })
        val (engine, settings, transport) = setup(health)
        settingsRef = settings
        assertEquals(SyncOutcome.Superseded, engine.run(SyncTrigger.SCHEDULED, true))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun notPairedOrPairedToAnotherOriginDoesNothing() = runBlocking {
        val (unpaired, _, t1) = setup(stored = { null })
        assertEquals(SyncOutcome.Paused(PauseReason.NOT_PAIRED), unpaired.run(SyncTrigger.SCHEDULED, true))
        val (other, _, t2) = setup(stored = { creds.copy(origin = "https://other.example") })
        assertEquals(SyncOutcome.Paused(PauseReason.NOT_PAIRED), other.run(SyncTrigger.SCHEDULED, true))
        assertTrue(t1.requests.isEmpty() && t2.requests.isEmpty())
    }

    @Test
    fun mapsSiteResponsesToPauseRetryOrFailure() = runBlocking {
        val transport = Recorder(401, """{"code":"device_revoked"}""")
        val (engine, _, _) = setup(transport = transport)
        val revoked = engine.run(SyncTrigger.SCHEDULED, true)
        assertEquals(SyncOutcome.Paused(PauseReason.DEVICE_REVOKED), revoked)
        assertEquals(WorkerPolicy.Action.PAUSE, WorkerPolicy.decide(revoked, 0))

        transport.status = 401
        transport.body = "Unauthorized"
        assertEquals(SyncOutcome.Paused(PauseReason.SITE_ACCESS_DENIED), engine.run(SyncTrigger.SCHEDULED, true))

        transport.status = 502
        val serverError = engine.run(SyncTrigger.SCHEDULED, true)
        assertEquals(SyncOutcome.Failed(FailReason.OFFLINE_OR_SERVER), serverError)
        assertEquals(WorkerPolicy.Action.RETRY, WorkerPolicy.decide(serverError, 0))
        assertEquals(WorkerPolicy.Action.FAILURE, WorkerPolicy.decide(serverError, WorkerPolicy.MAX_ATTEMPTS - 1))

        transport.throwIo = true
        assertEquals(SyncOutcome.Failed(FailReason.OFFLINE_OR_SERVER), engine.run(SyncTrigger.SCHEDULED, true))
        transport.throwIo = false

        transport.status = 409
        transport.body = """{"code":"stale"}"""
        val stale = engine.run(SyncTrigger.SCHEDULED, true)
        assertEquals(SyncOutcome.Failed(FailReason.STALE), stale)
        assertEquals(WorkerPolicy.Action.FAILURE, WorkerPolicy.decide(stale, 0))
    }

    @Test
    fun healthConnectErrorsAreRetryableAndBoundsErrorsAreNot() = runBlocking {
        val health = FakeHealth(allPermissions, onRead = { _, _ -> throw IllegalStateException("rate limited") })
        val (engine, _, transport) = setup(health)
        assertEquals(SyncOutcome.Failed(FailReason.HEALTH_CONNECT_ERROR), engine.run(SyncTrigger.SCHEDULED, true))
        health.onRead = { w, _ -> w.map { DayResult(it, mapOf(Metric.STEPS to 1e9)) } }
        assertEquals(SyncOutcome.Failed(FailReason.OUT_OF_BOUNDS), engine.run(SyncTrigger.SCHEDULED, true))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun noCategoriesOrHealthConnectUnavailablePauses() = runBlocking {
        val (engine, _, _) = setup(selected = emptySet())
        assertEquals(SyncOutcome.Paused(PauseReason.NO_CATEGORIES), engine.run(SyncTrigger.SCHEDULED, true))
        val (engine2, _, _) = setup(health = FakeHealth(allPermissions, available = false))
        assertEquals(SyncOutcome.Paused(PauseReason.HEALTH_CONNECT_UNAVAILABLE), engine2.run(SyncTrigger.SCHEDULED, true))
    }
}
