package dev.hcsummaries.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Checks the Site's connection status for this phone (GET /api/sync/status): still connected,
 * last successful sync, and whether ChatGPT access is on. It never reads Health Connect and
 * sends no health data, so it works without background access.
 *
 * Failures never erase credentials or the last known values; they are recorded so the screen
 * can say "last known … couldn't check". Only an explicit device_revoked answer from the app
 * (not the gate) pauses sync and forgets the credentials, exactly like a revoked upload.
 */
class SiteStatusRefresher(
    private val credentials: CredentialStore,
    private val settings: SyncSettings,
    private val state: SyncStateStore,
    /** Null when the stored credentials are for a Site this build may not use; nothing is sent then. */
    private val api: (SyncCredentials) -> SyncApi?,
    /** Cancels scheduled and queued sync work (WorkManager on the device). */
    private val cancelWork: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    enum class Result { UPDATED, SITE_OUTDATED, FAILED, REVOKED, NOT_PAIRED, SUPERSEDED }

    suspend fun refresh(): Result {
        val generation = settings.generation
        val creds = credentials.load() ?: return Result.NOT_PAIRED
        val client = api(creds) ?: return Result.NOT_PAIRED
        val response = withContext(Dispatchers.IO) { client.status(creds.deviceToken) }
        // Disconnected or re-paired meanwhile: this answer is about credentials that are gone.
        if (settings.generation != generation || credentials.load()?.deviceToken != creds.deviceToken) return Result.SUPERSEDED
        val now = clock.instant()
        return when (response) {
            is ApiResult.Status -> {
                state.recordStatus(response.lastSuccessfulSyncAt, response.access, now)
                if (response.access == null) Result.SITE_OUTDATED else Result.UPDATED
            }
            is ApiResult.Failed -> when {
                response.rejection == Rejection.DEVICE_REVOKED -> {
                    state.markRevoked()
                    credentials.clear()
                    settings.bumpGeneration()
                    cancelWork()
                    Result.REVOKED
                }
                // An older Site has no status route.
                response.httpStatus == 404 || response.httpStatus == 405 -> {
                    state.recordStatusFailure(StatusCheckFailure.SITE_OUTDATED, now)
                    Result.SITE_OUTDATED
                }
                else -> {
                    state.recordStatusFailure(
                        when (response.rejection) {
                            Rejection.TRANSIENT -> StatusCheckFailure.OFFLINE
                            Rejection.ACCESS_DENIED -> StatusCheckFailure.ACCESS_DENIED
                            else -> StatusCheckFailure.UNEXPECTED
                        },
                        now,
                    )
                    Result.FAILED
                }
            }
            else -> {
                state.recordStatusFailure(StatusCheckFailure.UNEXPECTED, now)
                Result.FAILED
            }
        }
    }
}
