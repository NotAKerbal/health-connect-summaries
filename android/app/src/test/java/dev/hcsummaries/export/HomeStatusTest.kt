package dev.hcsummaries.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeStatusTest {
    private val synced = Instant.parse("2026-10-27T21:28:00Z")
    private val checked = Instant.parse("2026-10-27T21:30:00Z")
    private val idle = WorkSnapshot(periodicEnqueued = true, running = false, nextRunAt = Instant.parse("2026-10-27T21:45:00Z"))
    private val syncedState = SyncState(lastSuccessAt = synced, lastSuccessRange = "2026-09-28 to 2026-10-27", lastAttemptAt = synced)

    private fun build(
        paired: Boolean = true,
        auto: Boolean = true,
        supported: Boolean = true,
        state: SyncState = syncedState,
        site: SiteStatus = SiteStatus(),
        work: WorkSnapshot = idle,
        online: Boolean = true,
        checking: Boolean = false,
        foreground: Boolean = false,
        configured: Boolean = true,
        available: Boolean = true,
        permissionMissing: Boolean = false,
        dataChosen: Boolean = true,
    ) = HomeStatusModel.build(
        HomeInputs(
            siteConfigured = configured,
            paired = paired,
            healthConnectAvailable = available,
            backgroundSupported = supported,
            autoSyncOn = auto,
            state = state,
            site = site,
            work = work,
            online = online,
            foregroundSyncRunning = foreground,
            checkingStatus = checking,
            permissionMissing = permissionMissing,
            dataChosen = dataChosen,
        ),
    )

    private fun known(enabled: Boolean, failure: StatusCheckFailure? = null) =
        SiteStatus(access = SiteAccess(enabled, checked), lastCheckAt = checked, lastCheckFailure = failure)

    @Test
    fun syncedButAccessOffAsksToOpenTheAccessSettings() {
        val h = build(site = known(enabled = false))
        assertEquals(ConnectionState.CONNECTED, h.connection)
        assertEquals(UploadPhase.COMPLETED, h.upload.phase)
        assertEquals(synced, h.upload.lastSuccessAt)
        assertEquals("2026-09-28 to 2026-10-27", h.upload.lastSuccessRange)
        assertEquals(AccessState.OFF, h.access.state)
        assertTrue(h.access.current)
        assertEquals(checked, h.access.checkedAt)
        assertEquals(Callout(CalloutKind.ACCESS_OFF, StatusTone.WARN, CalloutAction.OPEN_ACCESS_SETTINGS), h.callout)
    }

    @Test
    fun syncedAndAccessOnIsReadyWithTheCheckTime() {
        val h = build(site = known(enabled = true))
        assertEquals(Callout(CalloutKind.READY, StatusTone.OK, CalloutAction.NONE), h.callout)
        assertEquals(AccessState.ON, h.access.state)
        assertEquals(checked, h.access.checkedAt)
        assertTrue(h.access.current)
    }

    @Test
    fun withoutASuccessfulSyncItIsNeverReady() {
        val never = SyncState()
        // Even with ChatGPT access known to be on, nothing has reached the Site yet.
        for (state in listOf(never, SyncState(lastFailure = FailReason.OFFLINE_OR_SERVER))) {
            val h = build(state = state, site = known(enabled = true))
            assertNotEquals(CalloutKind.READY, h.callout.kind)
            assertNotEquals(UploadPhase.COMPLETED, h.upload.phase)
        }
        val needed = build(state = never, auto = false, site = known(true))
        assertEquals(UploadPhase.NOT_DONE, needed.upload.phase)
        assertEquals(CalloutKind.FIRST_SYNC_NEEDED, needed.callout.kind)
        assertEquals(CalloutAction.TURN_ON_AUTO, needed.callout.primary)
        assertEquals(CalloutAction.SYNC_NOW, needed.callout.secondary)
        // Without background support, the only way is a foreground Sync now.
        val manual = build(state = never, auto = false, supported = false)
        assertEquals(CalloutAction.SYNC_NOW, manual.callout.primary)
        assertEquals(BackgroundState.UNAVAILABLE, manual.background.state)
    }

    @Test
    fun firstSyncQueuedRunningFailedAndBlockedAreDistinct() {
        val never = SyncState()
        val queued = build(state = never, work = idle.copy(oneTimeQueued = true))
        assertEquals(UploadPhase.QUEUED, queued.upload.phase)
        assertEquals(UploadActivity.QUEUED, queued.upload.activity)
        assertEquals(CalloutKind.FIRST_SYNC_QUEUED, queued.callout.kind)

        val offline = build(state = never, work = idle.copy(oneTimeQueued = true), online = false)
        assertEquals(UploadActivity.WAITING_FOR_NETWORK, offline.upload.activity)
        assertEquals(CalloutKind.FIRST_SYNC_QUEUED, offline.callout.kind)

        val retrying = build(state = SyncState(lastFailure = FailReason.OFFLINE_OR_SERVER, willRetry = true), work = idle.copy(oneTimeQueued = true))
        assertEquals(UploadActivity.RETRYING, retrying.upload.activity)
        assertEquals(UploadPhase.QUEUED, retrying.upload.phase)

        val running = build(state = never, work = idle.copy(running = true))
        assertEquals(UploadPhase.RUNNING, running.upload.phase)
        assertEquals(CalloutKind.FIRST_SYNC_RUNNING, running.callout.kind)
        assertEquals(UploadPhase.RUNNING, build(state = never, foreground = true).upload.phase)

        val failed = build(state = SyncState(lastFailure = FailReason.REJECTED))
        assertEquals(CalloutKind.FIRST_SYNC_FAILED, failed.callout.kind)
        assertEquals(CalloutAction.SYNC_NOW, failed.callout.primary)
        assertEquals(FailReason.REJECTED, failed.upload.failure)

        val blocked = build(state = SyncState(paused = PauseReason.BACKGROUND_PERMISSION_MISSING))
        assertEquals(CalloutKind.FIRST_SYNC_BLOCKED, blocked.callout.kind)
        assertEquals(CalloutAction.FIX_HEALTH_CONNECT, blocked.callout.primary)
        assertEquals(BackgroundState.PAUSED, blocked.background.state)
        assertEquals(PauseReason.BACKGROUND_PERMISSION_MISSING, blocked.background.pause)
        // A pause the user cannot fix in Health Connect gets no misleading button.
        assertEquals(CalloutAction.NONE, build(state = SyncState(paused = PauseReason.SITE_ACCESS_DENIED)).callout.primary)
    }

    @Test
    fun aQueuedOrRunningSyncAfterTheFirstSuccessKeepsTheCompletion() {
        for (work in listOf(idle.copy(oneTimeQueued = true), idle.copy(running = true))) {
            val h = build(work = work, site = known(enabled = false))
            assertEquals(UploadPhase.COMPLETED, h.upload.phase)
            assertEquals(synced, h.upload.lastSuccessAt)
            assertNotEquals(UploadActivity.IDLE, h.upload.activity)
            // The access answer stays the main message; "sync scheduled" does not replace it.
            assertEquals(CalloutKind.ACCESS_OFF, h.callout.kind)
        }
        val failedAgain = build(state = syncedState.copy(lastFailure = FailReason.OFFLINE_OR_SERVER), site = known(true))
        assertEquals(UploadPhase.COMPLETED, failedAgain.upload.phase)
        assertEquals(UploadActivity.FAILED, failedAgain.upload.activity)
        assertEquals(CalloutKind.READY, failedAgain.callout.kind)
    }

    @Test
    fun anOlderSiteWithoutTheFlagIsUnknownNotOffOrReady() {
        val h = build(site = SiteStatus(lastCheckAt = checked, lastCheckFailure = StatusCheckFailure.SITE_OUTDATED))
        assertEquals(AccessState.UNKNOWN, h.access.state)
        assertNull(h.access.checkedAt)
        assertEquals(CalloutKind.ACCESS_UNKNOWN, h.callout.kind)
        assertEquals(StatusTone.NEUTRAL, h.callout.tone)
        assertEquals(CalloutAction.OPEN_ACCESS_SETTINGS, h.callout.primary)
    }

    @Test
    fun networkFailureKeepsTheLastKnownValueLabelledWithItsCheckTime() {
        val on = build(site = known(enabled = true, failure = StatusCheckFailure.OFFLINE))
        assertEquals(AccessState.ON, on.access.state)
        assertFalse(on.access.current)
        assertEquals(checked, on.access.checkedAt)
        assertEquals(StatusCheckFailure.OFFLINE, on.access.failure)
        // Not presented as a current "ready": neutral tone and a refresh action.
        assertEquals(Callout(CalloutKind.READY, StatusTone.NEUTRAL, CalloutAction.REFRESH_STATUS), on.callout)

        val off = build(site = known(enabled = false, failure = StatusCheckFailure.OFFLINE))
        assertFalse(off.access.current)
        assertEquals(Callout(CalloutKind.ACCESS_OFF, StatusTone.WARN, CalloutAction.OPEN_ACCESS_SETTINGS, CalloutAction.REFRESH_STATUS), off.callout)

        // Never learned and the check failed: "couldn't check", not on or off.
        val unknown = build(site = SiteStatus(lastCheckAt = checked, lastCheckFailure = StatusCheckFailure.OFFLINE))
        assertEquals(AccessState.UNKNOWN, unknown.access.state)
        assertEquals(Callout(CalloutKind.ACCESS_UNKNOWN, StatusTone.NEUTRAL, CalloutAction.REFRESH_STATUS, CalloutAction.OPEN_ACCESS_SETTINGS), unknown.callout)
    }

    @Test
    fun checkingIsShownWhileTheFirstAnswerIsPending() {
        val first = build(checking = true)
        assertEquals(AccessState.CHECKING, first.access.state)
        assertEquals(CalloutKind.ACCESS_CHECKING, first.callout.kind)
        // Rechecking a known value keeps showing it.
        val again = build(checking = true, site = known(true))
        assertEquals(AccessState.ON, again.access.state)
        assertTrue(again.access.refreshing)
        // Never checked and not checking.
        assertEquals(AccessState.UNKNOWN, build().access.state)
        assertNull(build().access.failure)
    }

    @Test
    fun revokedDeviceAsksToReconnectAndStopsClaimingAccess() {
        val h = build(paired = false, state = syncedState.copy(paused = PauseReason.DEVICE_REVOKED), site = known(true))
        assertEquals(ConnectionState.REVOKED, h.connection)
        assertEquals(CalloutKind.RECONNECT, h.callout.kind)
        assertEquals(AccessState.NOT_APPLICABLE, h.access.state)
        assertEquals(BackgroundState.NOT_APPLICABLE, h.background.state)
        assertEquals(UploadActivity.IDLE, h.upload.activity)
        assertEquals(CalloutKind.CONNECT, build(paired = false, state = SyncState()).callout.kind)
        assertEquals(CalloutKind.NOT_CONFIGURED, build(configured = false).callout.kind)
    }

    @Test
    fun backgroundRow() {
        assertEquals(BackgroundState.ON, build().background.state)
        assertEquals(idle.nextRunAt, build().background.nextRunAt)
        assertEquals(BackgroundState.OFF, build(auto = false).background.state)
        assertEquals(BackgroundState.UNAVAILABLE, build(auto = false, supported = false).background.state)
        val paused = build(state = syncedState.copy(paused = PauseReason.PERMISSION_MISSING))
        assertEquals(BackgroundState.PAUSED, paused.background.state)
        assertEquals(UploadActivity.PAUSED, paused.upload.activity)
        assertEquals(UploadPhase.COMPLETED, paused.upload.phase)
    }

    @Test
    fun theSiteRecordOfTheLastSyncCountsAsCompletion() {
        val siteOnly = build(state = SyncState(), site = SiteStatus(siteLastSyncAt = synced, access = SiteAccess(true, checked)))
        assertEquals(UploadPhase.COMPLETED, siteOnly.upload.phase)
        assertEquals(synced, siteOnly.upload.lastSuccessAt)
        // The range is known only from the phone's own record of that same sync.
        assertNull(siteOnly.upload.lastSuccessRange)
        assertEquals(CalloutKind.READY, siteOnly.callout.kind)
    }

    private fun overview(overall: Overall, note: OverviewNote, action: CalloutAction) = Overview(overall, note, action)

    @Test
    fun overviewIsReadyOnlyWhenSyncedAndAccessIsCurrentlyOn() {
        assertEquals(overview(Overall.READY, OverviewNote.NONE, CalloutAction.SYNC_NOW), build(site = known(true)).overview)
        assertEquals(
            overview(Overall.NEEDS_ATTENTION, OverviewNote.ACCESS_OFF, CalloutAction.OPEN_ACCESS_SETTINGS),
            build(site = known(false)).overview,
        )
        // Last known values stay labelled as such.
        assertEquals(
            overview(Overall.READY, OverviewNote.ACCESS_STALE, CalloutAction.REFRESH_STATUS),
            build(site = known(true, StatusCheckFailure.OFFLINE)).overview,
        )
        assertEquals(OverviewNote.ACCESS_OFF, build(site = known(false, StatusCheckFailure.OFFLINE)).overview.note)
        assertEquals(overview(Overall.WAITING, OverviewNote.ACCESS_NOT_CHECKED, CalloutAction.REFRESH_STATUS), build().overview)
        assertEquals(overview(Overall.WAITING, OverviewNote.ACCESS_CHECKING, CalloutAction.NONE), build(checking = true).overview)
        assertEquals(
            overview(Overall.NEEDS_ATTENTION, OverviewNote.ACCESS_UNKNOWN, CalloutAction.OPEN_ACCESS_SETTINGS),
            build(site = SiteStatus(lastCheckAt = checked, lastCheckFailure = StatusCheckFailure.SITE_OUTDATED)).overview,
        )
        // Never ready without a completed sync, even with access on.
        assertEquals(Overall.WAITING, build(state = SyncState(), site = known(true)).overview.overall)
    }

    @Test
    fun overviewPutsAMissingPermissionFirstWithItsFix() {
        val fix = overview(Overall.NEEDS_ATTENTION, OverviewNote.PERMISSION, CalloutAction.FIX_HEALTH_CONNECT)
        assertEquals(fix, build(site = known(true), permissionMissing = true).overview)
        assertEquals(fix, build(site = known(true), state = syncedState.copy(paused = PauseReason.BACKGROUND_PERMISSION_MISSING)).overview)
        assertEquals(fix, build(site = known(false), state = SyncState(paused = PauseReason.PERMISSION_MISSING)).overview)
        assertEquals(
            overview(Overall.NEEDS_ATTENTION, OverviewNote.PAUSED, CalloutAction.OPEN_SETTINGS),
            build(site = known(true), state = syncedState.copy(paused = PauseReason.NO_CATEGORIES)).overview,
        )
        assertEquals(
            overview(Overall.NEEDS_ATTENTION, OverviewNote.HEALTH_CONNECT, CalloutAction.FIX_HEALTH_CONNECT),
            build(site = known(true), available = false).overview,
        )
    }

    @Test
    fun overviewKeepsTheLastSyncWhileANewOneIsQueuedOrRunning() {
        val running = build(site = known(true), work = idle.copy(running = true))
        assertEquals(overview(Overall.SYNCING, OverviewNote.NONE, CalloutAction.NONE), running.overview)
        assertEquals(synced, running.upload.lastSuccessAt)
        val queued = build(site = known(true), work = idle.copy(oneTimeQueued = true))
        assertEquals(overview(Overall.READY, OverviewNote.QUEUED, CalloutAction.SYNC_NOW), queued.overview)
        assertEquals(synced, queued.upload.lastSuccessAt)
        // An access problem is not hidden by a queued sync.
        assertEquals(OverviewNote.ACCESS_OFF, build(site = known(false), work = idle.copy(oneTimeQueued = true)).overview.note)
    }

    @Test
    fun overviewBeforeTheFirstSyncAndWhenNotConnected() {
        val never = SyncState()
        assertEquals(
            overview(Overall.WAITING, OverviewNote.FIRST_SYNC_PENDING, CalloutAction.TURN_ON_AUTO),
            build(state = never, auto = false).overview,
        )
        assertEquals(CalloutAction.SYNC_NOW, build(state = never, auto = false, supported = false).overview.action)
        assertEquals(OverviewNote.QUEUED, build(state = never, work = idle.copy(oneTimeQueued = true)).overview.note)
        assertEquals(
            overview(Overall.NEEDS_ATTENTION, OverviewNote.SYNC_FAILED, CalloutAction.SYNC_NOW),
            build(state = SyncState(lastFailure = FailReason.REJECTED)).overview,
        )
        assertEquals(overview(Overall.NOT_CONNECTED, OverviewNote.NOT_PAIRED, CalloutAction.NONE), build(paired = false, state = never).overview)
        assertEquals(
            Overall.NEEDS_ATTENTION,
            build(paired = false, state = syncedState.copy(paused = PauseReason.DEVICE_REVOKED)).overview.overall,
        )
        assertEquals(OverviewNote.NO_SITE, build(configured = false).overview.note)
    }

    @Test
    fun firstRunShowsThreeConciseSteps() {
        val never = SyncState()
        // 1. Connect your private site.
        assertEquals(1, HomeStatusModel.setupStep(build(paired = false, state = never)))
        // 2. Just paired, nothing allowed yet: a positive "Connected" with Continue, not a warning.
        val choose = build(state = never, auto = false, permissionMissing = true, dataChosen = false)
        assertEquals(overview(Overall.CONNECTED, OverviewNote.CHOOSE_DATA, CalloutAction.CHOOSE_DATA), choose.overview)
        assertEquals(2, HomeStatusModel.setupStep(choose))
        // 3. Data allowed: turn on automatic sync, then ChatGPT access.
        val auto = build(state = never, auto = false)
        assertEquals(CalloutAction.TURN_ON_AUTO, auto.overview.action)
        assertEquals(3, HomeStatusModel.setupStep(auto))
        val accessOff = build(site = known(enabled = false))
        assertEquals(CalloutAction.OPEN_ACCESS_SETTINGS, accessOff.overview.action)
        assertEquals(3, HomeStatusModel.setupStep(accessOff))
        // Done: no step line.
        assertNull(HomeStatusModel.setupStep(build(site = known(enabled = true))))
        // An unchecked access state after an upgrade is not presented as unfinished setup.
        assertNull(HomeStatusModel.setupStep(build()))
    }

    @Test
    fun setupStepsNeverHideRealProblems() {
        // Grants removed after a successful sync: still "Needs attention", not onboarding.
        val revokedGrants = build(permissionMissing = true, dataChosen = false, site = known(enabled = true))
        assertEquals(Overall.NEEDS_ATTENTION, revokedGrants.overview.overall)
        assertEquals(CalloutAction.FIX_HEALTH_CONNECT, revokedGrants.overview.action)
        // A phone disconnected by the Site is a reconnect, not a first run.
        assertNull(HomeStatusModel.setupStep(build(paired = false, state = SyncState(paused = PauseReason.DEVICE_REVOKED))))
        assertNull(HomeStatusModel.setupStep(build(configured = false)))
        // A running first sync stays "Syncing".
        assertEquals(Overall.SYNCING, build(state = SyncState(), dataChosen = false, foreground = true).overview.overall)
    }
}
