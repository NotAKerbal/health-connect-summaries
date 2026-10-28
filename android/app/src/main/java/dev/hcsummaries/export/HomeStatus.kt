package dev.hcsummaries.export

import java.time.Instant

/**
 * What the main screen shows, independent of rendering. It keeps two things apart that users
 * confused before: whether the phone has delivered data to the Site (device-to-Site sync), and
 * whether ChatGPT may read it (the owner's access switch on the Site, which only the dashboard
 * can change).
 */
enum class StatusTone { NEUTRAL, INFO, OK, WARN, ERROR }

enum class ConnectionState { NOT_CONFIGURED, NOT_PAIRED, REVOKED, CONNECTED }

/** Whether a sync has ever completed for this pairing, and if not, how far the first one got. */
enum class UploadPhase { NOT_DONE, QUEUED, RUNNING, COMPLETED }

/** What sync is doing right now. Independent of [UploadPhase], so a completed sync stays visible while a new one is queued. */
enum class UploadActivity { IDLE, QUEUED, WAITING_FOR_NETWORK, RUNNING, RETRYING, FAILED, PAUSED }

data class UploadStatus(
    val phase: UploadPhase,
    val activity: UploadActivity,
    val lastSuccessAt: Instant?,
    val lastSuccessRange: String?,
    val failure: FailReason?,
    val pause: PauseReason?,
)

enum class BackgroundState { NOT_APPLICABLE, ON, PAUSED, OFF, UNAVAILABLE }

data class BackgroundStatus(val state: BackgroundState, val pause: PauseReason?, val nextRunAt: Instant?)

enum class AccessState {
    NOT_APPLICABLE,
    /** Known (with [AccessStatus.checkedAt]). */
    ON,
    OFF,
    /** Never learned yet; a check is running. */
    CHECKING,
    /** Never learned, or the Site does not report it. */
    UNKNOWN,
}

data class AccessStatus(
    val state: AccessState,
    val checkedAt: Instant?,
    /** False when a later check failed, so the value is only the last known one. */
    val current: Boolean,
    val refreshing: Boolean,
    val failure: StatusCheckFailure?,
)

enum class CalloutKind {
    NOT_CONFIGURED,
    CONNECT,
    RECONNECT,
    FIRST_SYNC_NEEDED,
    FIRST_SYNC_QUEUED,
    FIRST_SYNC_RUNNING,
    FIRST_SYNC_FAILED,
    FIRST_SYNC_BLOCKED,
    /** Synced, but ChatGPT access is off. */
    ACCESS_OFF,
    /** Synced and ChatGPT access is on. */
    READY,
    ACCESS_CHECKING,
    ACCESS_UNKNOWN,
}

enum class CalloutAction { NONE, TURN_ON_AUTO, SYNC_NOW, FIX_HEALTH_CONNECT, OPEN_ACCESS_SETTINGS, REFRESH_STATUS, OPEN_SETTINGS, CHOOSE_DATA }

data class Callout(val kind: CalloutKind, val tone: StatusTone, val primary: CalloutAction, val secondary: CalloutAction = CalloutAction.NONE)

/** The single status word on the main screen. */
/** [CONNECTED]: first run, just paired; the next step is choosing health data. */
enum class Overall { NOT_CONNECTED, CONNECTED, WAITING, SYNCING, READY, NEEDS_ATTENTION }

/** The one short line under the status word, if any. */
enum class OverviewNote {
    NONE,
    NO_SITE,
    NOT_PAIRED,
    REVOKED,
    HEALTH_CONNECT,
    PERMISSION,
    /** First run: connected, nothing synced yet, and no selected category allowed. */
    CHOOSE_DATA,
    PAUSED,
    FIRST_SYNC_PENDING,
    QUEUED,
    WAITING_FOR_NETWORK,
    RETRYING,
    SYNC_FAILED,
    ACCESS_OFF,
    ACCESS_CHECKING,
    ACCESS_NOT_CHECKED,
    ACCESS_UNKNOWN,
    ACCESS_STALE,
}

/** Main screen: one status, one optional note, at most one action. Details stay in [HomeStatus]. */
data class Overview(val overall: Overall, val note: OverviewNote, val action: CalloutAction)

data class HomeStatus(
    val connection: ConnectionState,
    val upload: UploadStatus,
    val background: BackgroundStatus,
    val access: AccessStatus,
    val callout: Callout,
    val overview: Overview,
)

data class HomeInputs(
    val siteConfigured: Boolean,
    val paired: Boolean,
    val healthConnectAvailable: Boolean,
    val backgroundSupported: Boolean,
    val autoSyncOn: Boolean,
    val state: SyncState,
    val site: SiteStatus,
    val work: WorkSnapshot,
    val online: Boolean,
    /** A foreground "Sync now" (no background access) is reading and uploading. */
    val foregroundSyncRunning: Boolean = false,
    /** A status check is in flight. */
    val checkingStatus: Boolean = false,
    /** Known (not just not yet loaded): a selected category, or background access while auto sync is on, is not granted. */
    val permissionMissing: Boolean = false,
    /** False when grants are known and not one selected category is allowed yet (typically right after pairing). */
    val dataChosen: Boolean = true,
)

object HomeStatusModel {
    /** Pauses the user can fix in Health Connect or in the category list. */
    private val FIXABLE = setOf(
        PauseReason.BACKGROUND_PERMISSION_MISSING,
        PauseReason.PERMISSION_MISSING,
        PauseReason.HEALTH_CONNECT_UNAVAILABLE,
    )

    fun build(i: HomeInputs): HomeStatus {
        val connection = when {
            !i.siteConfigured -> ConnectionState.NOT_CONFIGURED
            !i.paired && i.state.paused == PauseReason.DEVICE_REVOKED -> ConnectionState.REVOKED
            !i.paired -> ConnectionState.NOT_PAIRED
            else -> ConnectionState.CONNECTED
        }
        val connected = connection == ConnectionState.CONNECTED
        val upload = upload(i, connected)
        val background = background(i, connected)
        val access = access(i, connected)
        return HomeStatus(
            connection, upload, background, access,
            callout(i, connection, upload, access),
            overview(i, connection, upload, background, access),
        )
    }

    private fun overview(
        i: HomeInputs,
        connection: ConnectionState,
        upload: UploadStatus,
        background: BackgroundStatus,
        access: AccessStatus,
    ): Overview {
        fun o(overall: Overall, note: OverviewNote, action: CalloutAction = CalloutAction.NONE) = Overview(overall, note, action)
        if (connection == ConnectionState.NOT_CONFIGURED) return o(Overall.NOT_CONNECTED, OverviewNote.NO_SITE)
        if (!i.healthConnectAvailable) return o(Overall.NEEDS_ATTENTION, OverviewNote.HEALTH_CONNECT, CalloutAction.FIX_HEALTH_CONNECT)
        if (connection == ConnectionState.NOT_PAIRED) return o(Overall.NOT_CONNECTED, OverviewNote.NOT_PAIRED)
        if (connection == ConnectionState.REVOKED) return o(Overall.NEEDS_ATTENTION, OverviewNote.REVOKED)
        if (upload.activity == UploadActivity.RUNNING) return o(Overall.SYNCING, OverviewNote.NONE)
        // First run: choosing what to share is the next step, not a problem to fix.
        if (!i.dataChosen && upload.phase == UploadPhase.NOT_DONE) return o(Overall.CONNECTED, OverviewNote.CHOOSE_DATA, CalloutAction.CHOOSE_DATA)
        // A missing grant is fixed in Health Connect, so it always gets the button, wherever else it is shown.
        if (i.permissionMissing || upload.pause in FIXABLE || background.pause in FIXABLE) {
            return o(Overall.NEEDS_ATTENTION, OverviewNote.PERMISSION, CalloutAction.FIX_HEALTH_CONNECT)
        }
        if (upload.activity == UploadActivity.PAUSED) {
            val action = when (upload.pause) {
                PauseReason.BACKGROUND_UNSUPPORTED -> CalloutAction.SYNC_NOW
                PauseReason.NO_CATEGORIES -> CalloutAction.OPEN_SETTINGS
                else -> CalloutAction.NONE
            }
            return o(Overall.NEEDS_ATTENTION, OverviewNote.PAUSED, action)
        }
        if (upload.phase != UploadPhase.COMPLETED) {
            return when (upload.activity) {
                UploadActivity.QUEUED -> o(Overall.WAITING, OverviewNote.QUEUED)
                UploadActivity.WAITING_FOR_NETWORK -> o(Overall.WAITING, OverviewNote.WAITING_FOR_NETWORK)
                UploadActivity.RETRYING -> o(Overall.WAITING, OverviewNote.RETRYING)
                UploadActivity.FAILED -> o(Overall.NEEDS_ATTENTION, OverviewNote.SYNC_FAILED, CalloutAction.SYNC_NOW)
                else -> o(
                    Overall.WAITING,
                    OverviewNote.FIRST_SYNC_PENDING,
                    if (i.backgroundSupported && !i.autoSyncOn) CalloutAction.TURN_ON_AUTO else CalloutAction.SYNC_NOW,
                )
            }
        }
        return when (access.state) {
            AccessState.OFF -> o(Overall.NEEDS_ATTENTION, OverviewNote.ACCESS_OFF, CalloutAction.OPEN_ACCESS_SETTINGS)
            AccessState.CHECKING -> o(Overall.WAITING, OverviewNote.ACCESS_CHECKING)
            AccessState.UNKNOWN, AccessState.NOT_APPLICABLE -> when (access.failure) {
                null -> o(Overall.WAITING, OverviewNote.ACCESS_NOT_CHECKED, CalloutAction.REFRESH_STATUS)
                StatusCheckFailure.SITE_OUTDATED -> o(Overall.NEEDS_ATTENTION, OverviewNote.ACCESS_UNKNOWN, CalloutAction.OPEN_ACCESS_SETTINGS)
                else -> o(Overall.NEEDS_ATTENTION, OverviewNote.ACCESS_UNKNOWN, CalloutAction.REFRESH_STATUS)
            }
            AccessState.ON ->
                if (!access.current) o(Overall.READY, OverviewNote.ACCESS_STALE, CalloutAction.REFRESH_STATUS)
                else o(
                    Overall.READY,
                    when (upload.activity) {
                        UploadActivity.QUEUED -> OverviewNote.QUEUED
                        UploadActivity.WAITING_FOR_NETWORK -> OverviewNote.WAITING_FOR_NETWORK
                        UploadActivity.RETRYING -> OverviewNote.RETRYING
                        UploadActivity.FAILED -> OverviewNote.SYNC_FAILED
                        else -> OverviewNote.NONE
                    },
                    CalloutAction.SYNC_NOW,
                )
        }
    }

    /**
     * First-run progress shown above the status: 1 connect your private site, 2 choose and allow
     * health data, 3 sync and turn on ChatGPT access. Null once nothing is left, or when the
     * remaining problem is not part of setup (for example a revoked phone).
     */
    fun setupStep(h: HomeStatus): Int? = when {
        h.connection == ConnectionState.NOT_PAIRED -> 1
        h.connection != ConnectionState.CONNECTED -> null
        h.overview.note == OverviewNote.CHOOSE_DATA -> 2
        h.upload.phase != UploadPhase.COMPLETED -> 3
        h.access.state == AccessState.OFF -> 3
        else -> null
    }

    private fun upload(i: HomeInputs, connected: Boolean): UploadStatus {
        val lastSuccess = listOfNotNull(i.state.lastSuccessAt, i.site.siteLastSyncAt).maxOrNull()
        val running = connected && (i.work.running || i.foregroundSyncRunning)
        val queued = connected && !running && i.work.oneTimeQueued
        val activity = when {
            !connected -> UploadActivity.IDLE
            running -> UploadActivity.RUNNING
            i.state.paused != null -> UploadActivity.PAUSED
            i.state.lastFailure != null && i.state.willRetry -> UploadActivity.RETRYING
            queued && !i.online -> UploadActivity.WAITING_FOR_NETWORK
            queued -> UploadActivity.QUEUED
            i.state.lastFailure != null -> UploadActivity.FAILED
            else -> UploadActivity.IDLE
        }
        val phase = when {
            lastSuccess != null -> UploadPhase.COMPLETED
            activity == UploadActivity.RUNNING -> UploadPhase.RUNNING
            activity in setOf(UploadActivity.QUEUED, UploadActivity.WAITING_FOR_NETWORK, UploadActivity.RETRYING) -> UploadPhase.QUEUED
            else -> UploadPhase.NOT_DONE
        }
        return UploadStatus(
            phase = phase,
            activity = activity,
            lastSuccessAt = lastSuccess,
            // The range is known only for the phone's own record of that sync.
            lastSuccessRange = i.state.lastSuccessRange.takeIf { i.state.lastSuccessAt != null && i.state.lastSuccessAt == lastSuccess },
            failure = i.state.lastFailure.takeIf { activity == UploadActivity.FAILED || activity == UploadActivity.RETRYING },
            pause = i.state.paused.takeIf { activity == UploadActivity.PAUSED },
        )
    }

    private fun background(i: HomeInputs, connected: Boolean): BackgroundStatus = when {
        !connected -> BackgroundStatus(BackgroundState.NOT_APPLICABLE, null, null)
        i.autoSyncOn && i.state.paused != null -> BackgroundStatus(BackgroundState.PAUSED, i.state.paused, null)
        !i.healthConnectAvailable || !i.backgroundSupported -> BackgroundStatus(BackgroundState.UNAVAILABLE, null, null)
        !i.autoSyncOn -> BackgroundStatus(BackgroundState.OFF, null, null)
        else -> BackgroundStatus(BackgroundState.ON, null, i.work.nextRunAt)
    }

    private fun access(i: HomeInputs, connected: Boolean): AccessStatus {
        if (!connected) return AccessStatus(AccessState.NOT_APPLICABLE, null, current = false, refreshing = false, failure = null)
        val known = i.site.access
        val failure = i.site.lastCheckFailure
        return when {
            known != null -> AccessStatus(
                if (known.enabled) AccessState.ON else AccessState.OFF,
                known.checkedAt,
                current = failure == null,
                refreshing = i.checkingStatus,
                failure = failure,
            )
            i.checkingStatus -> AccessStatus(AccessState.CHECKING, null, current = false, refreshing = true, failure = null)
            else -> AccessStatus(AccessState.UNKNOWN, null, current = false, refreshing = false, failure = failure)
        }
    }

    private fun callout(i: HomeInputs, connection: ConnectionState, upload: UploadStatus, access: AccessStatus): Callout {
        when (connection) {
            ConnectionState.NOT_CONFIGURED -> return Callout(CalloutKind.NOT_CONFIGURED, StatusTone.NEUTRAL, CalloutAction.NONE)
            ConnectionState.NOT_PAIRED -> return Callout(CalloutKind.CONNECT, StatusTone.INFO, CalloutAction.NONE)
            ConnectionState.REVOKED -> return Callout(CalloutKind.RECONNECT, StatusTone.WARN, CalloutAction.NONE)
            ConnectionState.CONNECTED -> Unit
        }
        if (upload.phase != UploadPhase.COMPLETED) {
            return when (upload.activity) {
                UploadActivity.RUNNING -> Callout(CalloutKind.FIRST_SYNC_RUNNING, StatusTone.INFO, CalloutAction.NONE)
                UploadActivity.QUEUED, UploadActivity.WAITING_FOR_NETWORK, UploadActivity.RETRYING ->
                    Callout(CalloutKind.FIRST_SYNC_QUEUED, StatusTone.INFO, CalloutAction.NONE)
                UploadActivity.PAUSED -> Callout(
                    CalloutKind.FIRST_SYNC_BLOCKED,
                    StatusTone.WARN,
                    if (upload.pause in FIXABLE) CalloutAction.FIX_HEALTH_CONNECT else CalloutAction.NONE,
                )
                UploadActivity.FAILED -> Callout(CalloutKind.FIRST_SYNC_FAILED, StatusTone.ERROR, CalloutAction.SYNC_NOW)
                UploadActivity.IDLE -> Callout(
                    CalloutKind.FIRST_SYNC_NEEDED,
                    StatusTone.WARN,
                    if (i.backgroundSupported && i.healthConnectAvailable && !i.autoSyncOn) CalloutAction.TURN_ON_AUTO else CalloutAction.SYNC_NOW,
                    if (i.backgroundSupported && i.healthConnectAvailable && !i.autoSyncOn) CalloutAction.SYNC_NOW else CalloutAction.NONE,
                )
            }
        }
        return when (access.state) {
            AccessState.OFF -> Callout(
                CalloutKind.ACCESS_OFF,
                StatusTone.WARN,
                CalloutAction.OPEN_ACCESS_SETTINGS,
                if (access.current) CalloutAction.NONE else CalloutAction.REFRESH_STATUS,
            )
            AccessState.ON ->
                if (access.current) Callout(CalloutKind.READY, StatusTone.OK, CalloutAction.NONE)
                else Callout(CalloutKind.READY, StatusTone.NEUTRAL, CalloutAction.REFRESH_STATUS)
            AccessState.CHECKING -> Callout(CalloutKind.ACCESS_CHECKING, StatusTone.INFO, CalloutAction.NONE)
            AccessState.UNKNOWN, AccessState.NOT_APPLICABLE ->
                if (access.failure == StatusCheckFailure.SITE_OUTDATED) {
                    Callout(CalloutKind.ACCESS_UNKNOWN, StatusTone.NEUTRAL, CalloutAction.OPEN_ACCESS_SETTINGS)
                } else {
                    Callout(CalloutKind.ACCESS_UNKNOWN, StatusTone.NEUTRAL, CalloutAction.REFRESH_STATUS, CalloutAction.OPEN_ACCESS_SETTINGS)
                }
        }
    }
}
