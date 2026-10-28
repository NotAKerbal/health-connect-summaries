package dev.hcsummaries.export

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)?.toSet()

    override fun edit(block: KeyValue.Editor.() -> Unit) {
        prefs.edit(commit = true) {
            val editor = this
            object : KeyValue.Editor {
                override fun putString(key: String, value: String?) {
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                }
                override fun putLong(key: String, value: Long?) {
                    if (value == null) editor.remove(key) else editor.putLong(key, value)
                }
                override fun putBoolean(key: String, value: Boolean) {
                    editor.putBoolean(key, value)
                }
                override fun putInt(key: String, value: Int) {
                    editor.putInt(key, value)
                }
                override fun putStringSet(key: String, value: Set<String>) {
                    editor.putStringSet(key, value)
                }
                override fun remove(key: String) {
                    editor.remove(key)
                }
            }.block()
        }
    }
}

/** A non-exportable AES-256 key in the Android Keystore, usable without user authentication so background sync works. */
object KeystoreKey {
    private const val ALIAS = "hc_sync_credentials_v1"
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun delete() {
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}

class HealthConnectSource(private val context: Context) : HealthSource {
    /** Not cached: Health Connect can be installed or updated while the app is running. */
    private val client: HealthConnectClient?
        get() = if (available()) HealthConnectClient.getOrCreate(context) else null

    override fun available(): Boolean =
        HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE) == HealthConnectClient.SDK_AVAILABLE

    /** Runtime feature check from the Health Connect background-read guidance; never assumed from the Android version. */
    override fun backgroundReadSupported(): Boolean = try {
        client?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) {
        false
    }

    override suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    override suspend fun read(windows: List<DayWindow>, metrics: List<Metric>): List<DayResult> =
        HealthConnectReader(requireNotNull(client) { "Health Connect unavailable" }).read(windows, metrics)
}

/** Wires the sync pieces to Android storage. Cheap to create; holds no state itself. */
class SyncComponents(context: Context) {
    private val app = context.applicationContext
    val settings = SyncSettings(PrefsKeyValue(app.getSharedPreferences("settings", Context.MODE_PRIVATE)))
    val state = SyncStateStore(PrefsKeyValue(app.getSharedPreferences("sync_state", Context.MODE_PRIVATE)))
    val credentials = CredentialStore(
        PrefsKeyValue(app.getSharedPreferences("sync_credentials", Context.MODE_PRIVATE)),
        AesGcmCipher(KeystoreKey::getOrCreate),
    )
    val health = HealthConnectSource(app)
    val policy: SitePolicy = SitePolicy.fromBuildConfig(BuildConfig.SITE_URL)

    /** The built-in Site, if any (pinned builds only). */
    val builtInOrigin: String? = (policy as? SitePolicy.Pinned)?.origin

    /** The Site this phone talks to: the built-in one, or the one confirmed when pairing. Null when there is none yet. */
    fun siteOrigin(credentials: SyncCredentials? = this.credentials.load()): String? =
        builtInOrigin ?: policy.trustedOrigin(credentials)

    /** A client for [origin]; refuses any origin this build may not use. */
    fun api(origin: String, serviceToken: String): SyncApi {
        require(policy.accepts(origin)) { "site not allowed" }
        val transport = if (policy is SitePolicy.Generic) UrlConnectionTransport.publicOnly() else UrlConnectionTransport()
        return SyncApi(origin, serviceToken, transport)
    }

    /** A client for the stored pairing's own origin, or null if this build may not use it. */
    fun api(credentials: SyncCredentials): SyncApi? =
        policy.trustedOrigin(credentials)?.let { api(it, credentials.serviceToken) }

    fun engine() = SyncEngine(
        health = health,
        credentials = credentials::load,
        settings = settings,
        api = { requireNotNull(api(it)) },
        policy = policy,
    )

    fun statusRefresher() = SiteStatusRefresher(
        credentials = credentials,
        settings = settings,
        state = state,
        api = { api(it) },
        cancelWork = { SyncScheduler.cancelAll(app) },
    )

    fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Stops future and pending work, erases credentials, and returns the credentials that were stored (for server revocation). */
    fun forgetPairing(): SyncCredentials? {
        val previous = credentials.load()
        settings.autoSync = false
        settings.bumpGeneration()
        SyncScheduler.cancelAll(app)
        credentials.clear()
        KeystoreKey.delete()
        state.clear()
        return previous
    }

    companion object {
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val model = Build.MODEL.orEmpty()
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().take(64)
        }
    }
}

object SyncScheduler {
    const val PERIODIC_WORK = "hc_auto_sync"
    const val ONE_TIME_WORK = "hc_sync_now"
    const val KEY_TRIGGER = "trigger"

    /** WorkManager's minimum. Android treats it as a lower bound; runs are often later. */
    const val INTERVAL_MINUTES = 15L
    const val BACKOFF_SECONDS = 30L

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.SCHEDULED.name))
            .build()

    fun oneTimeRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.MANUAL.name))
            .build()

    /**
     * Starts (or updates) periodic sync and queues one immediate run. [replaceRunning] restarts a
     * queued or running immediate run so it uses the current categories.
     */
    fun enable(context: Context, replaceRunning: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest())
        syncNow(context, replaceRunning)
    }

    /** Makes sure periodic sync is scheduled, without disturbing an existing schedule or queuing an extra run. */
    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
    }

    fun syncNow(context: Context, replaceRunning: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            oneTimeRequest(),
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(ONE_TIME_WORK)
    }

    fun snapshot(periodic: List<WorkInfo>, oneTime: List<WorkInfo>): WorkSnapshot {
        val all = periodic + oneTime
        val enqueued = periodic.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        val next = enqueued?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE && it > 0 }?.let(Instant::ofEpochMilli)
        return WorkSnapshot(
            periodicEnqueued = enqueued != null,
            running = all.any { it.state == WorkInfo.State.RUNNING },
            nextRunAt = next,
            oneTimeQueued = oneTime.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
        )
    }
}

/**
 * Background sync run (periodic or "Sync now"). Requires Health Connect's background-read
 * feature and permission. Conditions that need the user pause sync (periodic work is cancelled,
 * no retry loop); transient errors retry with exponential backoff up to [WorkerPolicy.MAX_ATTEMPTS].
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = SyncComponents(applicationContext)
        if (sync.policy is SitePolicy.Misconfigured) return Result.failure()
        val trigger = SyncTrigger.fromName(inputData.getString(SyncScheduler.KEY_TRIGGER))
        val outcome = sync.engine().run(trigger, inBackground = true)
        val action = WorkerPolicy.decide(outcome, runAttemptCount)
        sync.state.record(outcome, Instant.now(), willRetry = action == WorkerPolicy.Action.RETRY)
        return when (action) {
            WorkerPolicy.Action.SUCCESS -> Result.success()
            WorkerPolicy.Action.RETRY -> Result.retry()
            WorkerPolicy.Action.FAILURE -> Result.failure()
            WorkerPolicy.Action.PAUSE -> {
                val reason = (outcome as SyncOutcome.Paused).reason
                if (reason == PauseReason.DEVICE_REVOKED) {
                    // The Site no longer accepts this device; its credentials are useless.
                    sync.credentials.clear()
                    sync.settings.bumpGeneration()
                }
                SyncScheduler.cancelPeriodic(applicationContext)
                Result.success()
            }
        }
    }
}
