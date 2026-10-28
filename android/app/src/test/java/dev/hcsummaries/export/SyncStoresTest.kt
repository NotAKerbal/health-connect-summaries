package dev.hcsummaries.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.crypto.KeyGenerator

/** In-memory [KeyValue] for tests. */
class MemoryKeyValue : KeyValue {
    val values = mutableMapOf<String, Any>()
    override fun getString(key: String) = values[key] as String?
    override fun getLong(key: String) = values[key] as Long?
    override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
    override fun getInt(key: String, default: Int) = values[key] as Int? ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String) = values[key] as Set<String>?
    override fun edit(block: KeyValue.Editor.() -> Unit) {
        val pending = values.toMutableMap()
        object : KeyValue.Editor {
            override fun putString(key: String, value: String?) { if (value == null) pending.remove(key) else pending[key] = value }
            override fun putLong(key: String, value: Long?) { if (value == null) pending.remove(key) else pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) { pending[key] = value }
            override fun putInt(key: String, value: Int) { pending[key] = value }
            override fun putStringSet(key: String, value: Set<String>) { pending[key] = value }
            override fun remove(key: String) { pending.remove(key) }
        }.block()
        values.clear()
        values.putAll(pending)
    }
}

class SyncStoresTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val creds = SyncCredentials(
        origin = "https://health.example.test",
        serviceToken = "svc-secret-value",
        deviceId = "4f7b0c1e-0000-4000-8000-000000000001",
        deviceToken = "hcd_" + "B".repeat(43),
        deviceName = "Pixel 9",
        pairedAt = Instant.parse("2026-10-27T20:00:00Z"),
    )

    @Test
    fun credentialsRoundTripAndAreNotStoredInPlaintext() {
        val kv = MemoryKeyValue()
        val store = CredentialStore(kv, AesGcmCipher { key })
        assertNull(store.load())
        assertFalse(store.isPaired())
        store.save(creds)
        assertTrue(store.isPaired())
        assertEquals(creds, store.load())
        val raw = kv.values.values.joinToString()
        assertFalse(raw.contains(creds.serviceToken))
        assertFalse(raw.contains(creds.deviceToken))
        store.clear()
        assertNull(store.load())
        assertTrue(kv.values.isEmpty())
    }

    @Test
    fun tamperedSwappedOrWrongKeyCiphertextIsTreatedAsNotPaired() {
        val kv = MemoryKeyValue()
        CredentialStore(kv, AesGcmCipher { key }).save(creds)
        // Different key (e.g. Keystore key lost after a restore).
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(CredentialStore(kv, AesGcmCipher { otherKey }).load())
        // Swapping the two ciphertexts fails authentication because the field name is bound as AAD.
        val service = kv.values["service_token_enc"]!!
        kv.values["service_token_enc"] = kv.values["device_token_enc"]!!
        kv.values["device_token_enc"] = service
        assertNull(CredentialStore(kv, AesGcmCipher { key }).load())
    }

    @Test
    fun encryptionUsesFreshIvs() {
        val cipher = AesGcmCipher { key }
        val a = cipher.encrypt("same".toByteArray(), "f".toByteArray())
        val b = cipher.encrypt("same".toByteArray(), "f".toByteArray())
        assertFalse(a.contentEquals(b))
        assertEquals("same", String(cipher.decrypt(a, "f".toByteArray())))
    }

    @Test
    fun settingsDefaultsAndGeneration() {
        val settings = SyncSettings(MemoryKeyValue())
        assertEquals(Metric.entries.toSet(), settings.selected)
        assertFalse(settings.autoSync)
        assertEquals(0, settings.generation)
        assertEquals(1, settings.bumpGeneration())
        settings.selected = setOf(Metric.STEPS)
        assertEquals(setOf(Metric.STEPS), settings.selected)
    }

    @Test
    fun stateRecordsSuccessFailureAndPause() {
        val store = SyncStateStore(MemoryKeyValue())
        val t = Instant.parse("2026-10-27T20:00:00Z")
        store.record(SyncOutcome.Failed(FailReason.OFFLINE_OR_SERVER), t, willRetry = true)
        assertEquals(FailReason.OFFLINE_OR_SERVER, store.load().lastFailure)
        assertTrue(store.load().willRetry)
        store.record(SyncOutcome.Success(false, "2026-09-28", "2026-10-27", 30), t.plusSeconds(60), willRetry = false)
        assertEquals(t.plusSeconds(60), store.load().lastSuccessAt)
        assertNull(store.load().lastFailure)
        store.record(SyncOutcome.Paused(PauseReason.BACKGROUND_PERMISSION_MISSING), t.plusSeconds(120), willRetry = false)
        assertEquals(PauseReason.BACKGROUND_PERMISSION_MISSING, store.load().paused)
        assertNotNull(store.load().lastSuccessAt)
        store.record(SyncOutcome.Superseded, t.plusSeconds(180), willRetry = false)
        assertEquals(t.plusSeconds(120), store.load().lastAttemptAt)
        store.clearPause()
        assertNull(store.load().paused)
    }
}
