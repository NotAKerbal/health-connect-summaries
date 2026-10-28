package dev.hcsummaries.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneId

/** Health Connect as seen by the sync engine (implemented over HealthConnectClient in SyncAndroid.kt). */
interface HealthSource {
    fun available(): Boolean
    fun backgroundReadSupported(): Boolean
    suspend fun grantedPermissions(): Set<String>
    suspend fun read(windows: List<DayWindow>, metrics: List<Metric>): List<DayResult>
}

/**
 * One sync run: check pairing and permissions, read a 30-day aggregate snapshot with the
 * same reader and JSON builder as manual export, and POST it to the Site. It never sends a
 * partial or empty snapshot: if any selected category (or, for background runs, background
 * access) is missing, it pauses instead of reading.
 */
class SyncEngine(
    private val health: HealthSource,
    private val credentials: () -> SyncCredentials?,
    private val settings: SyncSettings,
    /** Called only with credentials whose origin [policy] accepts. */
    private val api: (SyncCredentials) -> SyncApi,
    private val policy: SitePolicy,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun run(trigger: SyncTrigger, inBackground: Boolean): SyncOutcome {
        if (trigger == SyncTrigger.SCHEDULED && !settings.autoSync) return SyncOutcome.Superseded
        val generation = settings.generation
        val creds = credentials() ?: return SyncOutcome.Paused(PauseReason.NOT_PAIRED)
        // Only ever the origin stored with the approved pairing, and only if this build allows it.
        if (policy.trustedOrigin(creds) == null) return SyncOutcome.Paused(PauseReason.NOT_PAIRED)
        if (!health.available()) return SyncOutcome.Paused(PauseReason.HEALTH_CONNECT_UNAVAILABLE)
        if (inBackground && !health.backgroundReadSupported()) return SyncOutcome.Paused(PauseReason.BACKGROUND_UNSUPPORTED)

        val metrics = Metric.entries.filter { it in settings.selected }
        if (metrics.isEmpty()) return SyncOutcome.Paused(PauseReason.NO_CATEGORIES)
        val granted = try {
            health.grantedPermissions()
        } catch (_: Exception) {
            return SyncOutcome.Failed(FailReason.HEALTH_CONNECT_ERROR)
        }
        if (inBackground && BACKGROUND_PERMISSION !in granted) return SyncOutcome.Paused(PauseReason.BACKGROUND_PERMISSION_MISSING)
        val missing = metrics.filter { it.readPermission !in granted }
        if (missing.isNotEmpty()) return SyncOutcome.Paused(PauseReason.PERMISSION_MISSING, missing)

        val zone = zone()
        if (!ExportJson.isSupportedZone(zone)) return SyncOutcome.Failed(FailReason.TIMEZONE_UNSUPPORTED)
        // Fixed before reading so the day windows and generatedAt agree.
        val generatedAt = clock.instant()
        val windows = ExportPlanner.windows(generatedAt.atZone(zone).toLocalDate(), ExportPlanner.MAX_DAYS, zone)
        val days = try {
            health.read(windows, metrics)
        } catch (e: PermissionRevokedException) {
            return SyncOutcome.Paused(PauseReason.PERMISSION_MISSING, e.metrics)
        } catch (_: HealthReadInterruptedException) {
            // Access to every category is still granted; background access was removed, or a
            // foreground-only read was interrupted when the app left the screen.
            val stillBackground = try {
                BACKGROUND_PERMISSION in health.grantedPermissions()
            } catch (_: Exception) {
                false
            }
            return if (inBackground && !stillBackground) SyncOutcome.Paused(PauseReason.BACKGROUND_PERMISSION_MISSING)
            else SyncOutcome.Failed(FailReason.READ_INTERRUPTED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Includes Health Connect rate limiting and transient service errors.
            return SyncOutcome.Failed(FailReason.HEALTH_CONNECT_ERROR)
        }
        if (days.size != windows.size) return SyncOutcome.Failed(FailReason.UNEXPECTED)
        if (ExportJson.firstOutOfBounds(days) != null) return SyncOutcome.Failed(FailReason.OUT_OF_BOUNDS)
        val json = ExportJson.build(generatedAt, zone, metrics, days)

        // Disconnected or re-paired while reading: do not send with the old credentials.
        if (settings.generation != generation || credentials()?.deviceToken != creds.deviceToken ||
            (trigger == SyncTrigger.SCHEDULED && !settings.autoSync)) return SyncOutcome.Superseded

        val result = withContext(Dispatchers.IO) { api(creds).upload(creds.deviceToken, json, trigger, settings.autoSync) }
        return when (result) {
            is ApiResult.Uploaded -> SyncOutcome.Success(result.duplicate, result.rangeStart, result.rangeEnd, days.size, result.access)
            is ApiResult.Failed -> when (result.rejection) {
                Rejection.DEVICE_REVOKED, Rejection.PAIRING_INVALID -> SyncOutcome.Paused(PauseReason.DEVICE_REVOKED)
                Rejection.ACCESS_DENIED -> SyncOutcome.Paused(PauseReason.SITE_ACCESS_DENIED)
                Rejection.STALE -> SyncOutcome.Failed(FailReason.STALE)
                Rejection.INVALID -> SyncOutcome.Failed(FailReason.REJECTED)
                Rejection.TRANSIENT -> SyncOutcome.Failed(FailReason.OFFLINE_OR_SERVER)
                Rejection.UNEXPECTED -> SyncOutcome.Failed(FailReason.UNEXPECTED)
            }
            else -> SyncOutcome.Failed(FailReason.UNEXPECTED)
        }
    }

    companion object {
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    }
}
