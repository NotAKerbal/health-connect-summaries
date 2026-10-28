package dev.hcsummaries.export

import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal key-value storage so stores can be unit-tested without SharedPreferences. */
interface KeyValue {
    fun getString(key: String): String?
    fun getLong(key: String): Long?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getStringSet(key: String): Set<String>?
    /** Applies all changes at once and persists them before returning. */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putLong(key: String, value: Long?)
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun putStringSet(key: String, value: Set<String>)
        fun remove(key: String)
    }
}

/** AES-256-GCM with a 12-byte random IV; output is IV || ciphertext+tag. The field name is bound as associated data. */
class AesGcmCipher(private val key: () -> SecretKey) {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(data: ByteArray, associatedData: ByteArray): ByteArray {
        require(data.size > IV_BYTES + TAG_BITS / 8) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Pairing credentials, encrypted with a non-exportable Android Keystore key (see SyncAndroid.kt).
 * Preferences holding them are excluded from backup and device transfer by the manifest rules.
 * Any decryption problem (for example a lost key) is treated as "not paired", never as a crash.
 */
class CredentialStore(private val kv: KeyValue, private val cipher: AesGcmCipher) {
    fun load(): SyncCredentials? {
        val origin = kv.getString(K_ORIGIN) ?: return null
        return try {
            SyncCredentials(
                origin = origin,
                serviceToken = decrypt(K_SERVICE),
                deviceId = kv.getString(K_DEVICE_ID) ?: return null,
                deviceToken = decrypt(K_DEVICE),
                deviceName = kv.getString(K_NAME).orEmpty(),
                pairedAt = Instant.ofEpochMilli(kv.getLong(K_PAIRED_AT) ?: 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun isPaired(): Boolean = kv.getString(K_ORIGIN) != null

    fun save(credentials: SyncCredentials) {
        val service = encrypt(K_SERVICE, credentials.serviceToken)
        val device = encrypt(K_DEVICE, credentials.deviceToken)
        kv.edit {
            putString(K_ORIGIN, credentials.origin)
            putString(K_SERVICE, service)
            putString(K_DEVICE, device)
            putString(K_DEVICE_ID, credentials.deviceId)
            putString(K_NAME, credentials.deviceName)
            putLong(K_PAIRED_AT, credentials.pairedAt.toEpochMilli())
        }
    }

    fun clear() {
        kv.edit { ALL_KEYS.forEach { remove(it) } }
    }

    private fun encrypt(field: String, value: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8), field.toByteArray(Charsets.UTF_8)))

    private fun decrypt(field: String): String {
        val stored = kv.getString(field) ?: error("missing $field")
        return String(cipher.decrypt(Base64.getDecoder().decode(stored), field.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    private companion object {
        const val K_ORIGIN = "origin"
        const val K_SERVICE = "service_token_enc"
        const val K_DEVICE = "device_token_enc"
        const val K_DEVICE_ID = "device_id"
        const val K_NAME = "device_name"
        const val K_PAIRED_AT = "paired_at"
        val ALL_KEYS = listOf(K_ORIGIN, K_SERVICE, K_DEVICE, K_DEVICE_ID, K_NAME, K_PAIRED_AT)
    }
}

/** User choices shared by the screen and the background worker. */
class SyncSettings(private val kv: KeyValue) {
    /** Selected categories; every category when the user has never changed the selection. */
    var selected: Set<Metric>
        get() = kv.getStringSet(K_SELECTED)?.mapNotNull { Metric.fromKey(it) }?.toSet() ?: Metric.entries.toSet()
        set(value) = kv.edit { putStringSet(K_SELECTED, value.map { it.key }.toSet()) }

    /** The user's explicit consent to automatic background sync. */
    var autoSync: Boolean
        get() = kv.getBoolean(K_AUTO, false)
        set(value) = kv.edit { putBoolean(K_AUTO, value) }

    /** Incremented on every pair or disconnect, so an in-flight run started earlier cannot upload afterwards. */
    val generation: Int
        get() = kv.getInt(K_GENERATION, 0)

    fun bumpGeneration(): Int {
        val next = generation + 1
        kv.edit { putInt(K_GENERATION, next) }
        return next
    }

    private companion object {
        const val K_SELECTED = "selected"
        const val K_AUTO = "auto_sync"
        const val K_GENERATION = "sync_generation"
    }
}

/**
 * Sync results and the latest connection status. Keys from 0.2.0 are unchanged; the status keys
 * added in 0.3.0 are simply absent after an upgrade, which reads as "not checked yet".
 */
class SyncStateStore(private val kv: KeyValue) {
    fun load(): SyncState = SyncState(
        lastSuccessAt = kv.getLong(K_SUCCESS)?.let(Instant::ofEpochMilli),
        lastSuccessRange = kv.getString(K_RANGE),
        lastAttemptAt = kv.getLong(K_ATTEMPT)?.let(Instant::ofEpochMilli),
        lastFailure = kv.getString(K_FAILURE)?.let { name -> FailReason.entries.firstOrNull { it.name == name } },
        willRetry = kv.getBoolean(K_RETRY, false),
        paused = kv.getString(K_PAUSED)?.let { name -> PauseReason.entries.firstOrNull { it.name == name } },
    )

    fun record(outcome: SyncOutcome, now: Instant, willRetry: Boolean) {
        if (outcome == SyncOutcome.Superseded) return
        kv.edit {
            putLong(K_ATTEMPT, now.toEpochMilli())
            when (outcome) {
                is SyncOutcome.Success -> {
                    putLong(K_SUCCESS, now.toEpochMilli())
                    putString(K_RANGE, "${outcome.rangeStart} to ${outcome.rangeEnd}")
                    remove(K_FAILURE)
                    putBoolean(K_RETRY, false)
                    remove(K_PAUSED)
                    outcome.access?.let {
                        putAccessIfNewer(it, now)
                        putLong(K_CHECK_AT, now.toEpochMilli())
                        remove(K_CHECK_FAIL)
                    }
                }
                is SyncOutcome.Failed -> {
                    putString(K_FAILURE, outcome.reason.name)
                    putBoolean(K_RETRY, willRetry)
                }
                is SyncOutcome.Paused -> {
                    putString(K_PAUSED, outcome.reason.name)
                    remove(K_FAILURE)
                    putBoolean(K_RETRY, false)
                }
                SyncOutcome.Superseded -> Unit
            }
        }
    }

    fun clearPause() = kv.edit { remove(K_PAUSED) }

    fun clear() = kv.edit { ALL_KEYS.forEach { remove(it) } }

    /** The Site said this device is revoked: pause sync and forget connection status that no longer applies. */
    fun markRevoked() = kv.edit {
        putString(K_PAUSED, PauseReason.DEVICE_REVOKED.name)
        remove(K_FAILURE)
        putBoolean(K_RETRY, false)
        SITE_KEYS.forEach { remove(it) }
    }

    fun loadSite(): SiteStatus {
        val enabled = if (kv.getString(K_ACCESS) != null) kv.getString(K_ACCESS) == "on" else null
        val checkedAt = kv.getLong(K_ACCESS_AT)?.let(Instant::ofEpochMilli)
        return SiteStatus(
            // The flag is a fact only together with the time the Site read it.
            access = if (enabled != null && checkedAt != null) SiteAccess(enabled, checkedAt) else null,
            siteLastSyncAt = kv.getLong(K_SITE_SYNC)?.let(Instant::ofEpochMilli),
            lastCheckAt = kv.getLong(K_CHECK_AT)?.let(Instant::ofEpochMilli),
            lastCheckFailure = kv.getString(K_CHECK_FAIL)?.let { name -> StatusCheckFailure.entries.firstOrNull { it.name == name } },
        )
    }

    /** A successful status check. [access] null means the Site did not report it (older Site). */
    fun recordStatus(siteLastSyncAt: Instant?, access: SiteAccess?, now: Instant) = kv.edit {
        putLong(K_CHECK_AT, now.toEpochMilli())
        putLong(K_SITE_SYNC, siteLastSyncAt?.toEpochMilli())
        if (access != null) {
            putAccessIfNewer(access, now)
            remove(K_CHECK_FAIL)
        } else {
            putString(K_CHECK_FAIL, StatusCheckFailure.SITE_OUTDATED.name)
        }
    }

    /** A failed check keeps the last known access value; it is then shown as "last known", not current. */
    fun recordStatusFailure(failure: StatusCheckFailure, now: Instant) = kv.edit {
        putLong(K_CHECK_AT, now.toEpochMilli())
        putString(K_CHECK_FAIL, failure.name)
    }

    private fun KeyValue.Editor.putAccessIfNewer(access: SiteAccess, now: Instant) {
        // Never later than the phone's clock, so a skewed Site clock cannot make a value look newer.
        val checkedAt = minOf(access.checkedAt, now)
        val stored = kv.getLong(K_ACCESS_AT)
        if (stored != null && stored > checkedAt.toEpochMilli()) return
        putString(K_ACCESS, if (access.enabled) "on" else "off")
        putLong(K_ACCESS_AT, checkedAt.toEpochMilli())
    }

    private companion object {
        const val K_SUCCESS = "last_success_ms"
        const val K_RANGE = "last_success_range"
        const val K_ATTEMPT = "last_attempt_ms"
        const val K_FAILURE = "last_failure"
        const val K_RETRY = "will_retry"
        const val K_PAUSED = "paused"
        // Added in 0.3.0.
        const val K_ACCESS = "chatgpt_access"
        const val K_ACCESS_AT = "chatgpt_access_checked_ms"
        const val K_SITE_SYNC = "site_last_sync_ms"
        const val K_CHECK_AT = "status_check_ms"
        const val K_CHECK_FAIL = "status_check_failure"
        val SITE_KEYS = listOf(K_ACCESS, K_ACCESS_AT, K_SITE_SYNC, K_CHECK_AT, K_CHECK_FAIL)
        val ALL_KEYS = listOf(K_SUCCESS, K_RANGE, K_ATTEMPT, K_FAILURE, K_RETRY, K_PAUSED) + SITE_KEYS
    }
}
