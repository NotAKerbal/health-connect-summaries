package dev.hcsummaries.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class SyncModelTest {
    private val site = "https://health.example.test"
    private val now = Instant.parse("2026-10-27T20:00:00Z")
    private val pairing = "hcp_" + "A".repeat(43)

    private fun code(
        origin: String = site,
        service: String = "svc-test-token-123",
        pairingToken: String = pairing,
        expiresAt: String = "2026-10-27T20:05:00Z",
        v: Int = 1,
    ): String {
        val json = JSONObject().put("v", v).put("origin", origin).put("serviceToken", service)
            .put("pairingToken", pairingToken).put("expiresAt", expiresAt).toString()
        return ConnectionCodes.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun problem(text: String, expected: String = site) =
        (ConnectionCodes.parse(text, expected, now) as? ConnectionCodes.Result.Invalid)?.problem

    @Test
    fun parsesAValidCodePinnedToTheBuiltInSite() {
        val ok = ConnectionCodes.parse("  ${code()}\n", "$site/", now) as ConnectionCodes.Result.Ok
        assertEquals(site, ok.code.origin)
        assertEquals("svc-test-token-123", ok.code.serviceToken)
        assertEquals(pairing, ok.code.pairingToken)
    }

    @Test
    fun rejectsCodesForOtherOriginsOrInsecureSchemes() {
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "https://evil.example")))
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "http://health.example.test")))
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "https://health.example.test.evil.example")))
        assertEquals(ConnectionCodes.Problem.WRONG_SITE, problem(code(origin = "https://health.example.test/path")))
        assertEquals(ConnectionCodes.Problem.NO_SITE_CONFIGURED, problem(code(), expected = ""))
        assertEquals(ConnectionCodes.Problem.NO_SITE_CONFIGURED, problem(code(), expected = "http://health.example.test"))
    }

    @Test
    fun rejectsExpiredAndMalformedCodes() {
        assertEquals(ConnectionCodes.Problem.EXPIRED, problem(code(expiresAt = "2026-10-27T20:00:00Z")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem("not a code"))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(ConnectionCodes.PREFIX + "!!!"))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(v = 2)))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(pairingToken = "hcp_short")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(service = "has space in it")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(service = "")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(code(expiresAt = "soon")))
        assertEquals(ConnectionCodes.Problem.MALFORMED, problem(ConnectionCodes.PREFIX + "A".repeat(9000)))
    }

    @Test
    fun normalizesOnlyPlainHttpsOrigins() {
        assertEquals("https://a.example", SyncOrigin.normalize("https://A.example/"))
        assertEquals("https://a.example:8443", SyncOrigin.normalize("https://a.example:8443"))
        assertEquals("https://a.example", SyncOrigin.normalize("https://a.example:443"))
        assertNull(SyncOrigin.normalize("http://a.example"))
        assertNull(SyncOrigin.normalize("https://user@a.example"))
        assertNull(SyncOrigin.normalize("https://a.example/x"))
        assertNull(SyncOrigin.normalize("https://a.example?q=1"))
        assertNull(SyncOrigin.normalize("https://"))
    }

    @Test
    fun classifiesSiteResponses() {
        assertEquals(Rejection.DEVICE_REVOKED, ApiResponses.classify(401, """{"code":"device_revoked"}"""))
        assertEquals(Rejection.PAIRING_INVALID, ApiResponses.classify(401, """{"code":"pairing_invalid"}"""))
        // The header form works even when the HTTP client discarded the 401 body.
        assertEquals(Rejection.DEVICE_REVOKED, ApiResponses.classify(401, "", "device_revoked"))
        // A 401 or redirect without the app's JSON code comes from the private-Site gate.
        assertEquals(Rejection.ACCESS_DENIED, ApiResponses.classify(401, "<html>sign in</html>"))
        assertEquals(Rejection.ACCESS_DENIED, ApiResponses.classify(302, ""))
        assertEquals(Rejection.ACCESS_DENIED, ApiResponses.classify(403, "{}"))
        assertEquals(Rejection.STALE, ApiResponses.classify(409, "{}"))
        assertEquals(Rejection.INVALID, ApiResponses.classify(422, "{}"))
        assertEquals(Rejection.INVALID, ApiResponses.classify(413, "{}"))
        assertEquals(Rejection.TRANSIENT, ApiResponses.classify(429, ""))
        assertEquals(Rejection.TRANSIENT, ApiResponses.classify(503, ""))
        assertEquals(Rejection.TRANSIENT, ApiResponses.classify(408, ""))
        assertEquals(Rejection.UNEXPECTED, ApiResponses.classify(404, ""))
    }

    @Test
    fun workerRetriesTransientErrorsOnlyABoundedNumberOfTimes() {
        val transient = SyncOutcome.Failed(FailReason.OFFLINE_OR_SERVER)
        assertEquals(WorkerPolicy.Action.RETRY, WorkerPolicy.decide(transient, 0))
        assertEquals(WorkerPolicy.Action.RETRY, WorkerPolicy.decide(transient, WorkerPolicy.MAX_ATTEMPTS - 2))
        assertEquals(WorkerPolicy.Action.FAILURE, WorkerPolicy.decide(transient, WorkerPolicy.MAX_ATTEMPTS - 1))
        assertEquals(WorkerPolicy.Action.FAILURE, WorkerPolicy.decide(SyncOutcome.Failed(FailReason.STALE), 0))
        assertEquals(WorkerPolicy.Action.FAILURE, WorkerPolicy.decide(SyncOutcome.Failed(FailReason.REJECTED), 0))
        // Needs the user: pause, never retry.
        for (reason in PauseReason.entries) {
            assertEquals(WorkerPolicy.Action.PAUSE, WorkerPolicy.decide(SyncOutcome.Paused(reason), 0))
        }
        assertEquals(WorkerPolicy.Action.SUCCESS, WorkerPolicy.decide(SyncOutcome.Superseded, 0))
        assertEquals(WorkerPolicy.Action.SUCCESS, WorkerPolicy.decide(SyncOutcome.Success(false, "a", "b", 30), 3))
    }

    @Test
    fun revocationAndAccessPausesAreNotAutoResumed() {
        assertTrue(!PauseReason.DEVICE_REVOKED.resumable)
        assertTrue(!PauseReason.SITE_ACCESS_DENIED.resumable)
        assertTrue(PauseReason.BACKGROUND_PERMISSION_MISSING.resumable)
        assertTrue(PauseReason.PERMISSION_MISSING.resumable)
    }

    @Test
    fun statusPhases() {
        val idle = WorkSnapshot(periodicEnqueued = true, running = false, nextRunAt = null)
        fun phase(
            configured: Boolean = true,
            paired: Boolean = true,
            supported: Boolean = true,
            auto: Boolean = true,
            state: SyncState = SyncState(),
            work: WorkSnapshot = idle,
            online: Boolean = true,
        ) = SyncStatusModel.phase(configured, paired, supported, auto, state, work, online)

        assertEquals(SyncPhase.NOT_CONFIGURED, phase(configured = false))
        assertEquals(SyncPhase.NOT_PAIRED, phase(paired = false))
        assertEquals(SyncPhase.AUTO_UNAVAILABLE, phase(supported = false))
        assertEquals(SyncPhase.AUTO_OFF, phase(auto = false))
        assertEquals(SyncPhase.SCHEDULED, phase())
        assertEquals(SyncPhase.WAITING_FOR_NETWORK, phase(online = false))
        assertEquals(SyncPhase.RUNNING, phase(work = idle.copy(running = true)))
        assertEquals(SyncPhase.PAUSED, phase(state = SyncState(paused = PauseReason.BACKGROUND_PERMISSION_MISSING)))
        assertEquals(SyncPhase.RETRYING, phase(state = SyncState(lastFailure = FailReason.OFFLINE_OR_SERVER, willRetry = true)))
        assertEquals(SyncPhase.ERROR, phase(state = SyncState(lastFailure = FailReason.STALE)))
    }
}
