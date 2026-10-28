package dev.hcsummaries.export

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Checks the WorkManager requests the app enqueues (built with the real WorkManager builders). */
class SyncSchedulerTest {
    @Test
    fun periodicSyncIsEvery15MinutesOnAnyNetworkWithExponentialBackoff() {
        val spec = SyncScheduler.periodicRequest().workSpec
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
        assertEquals(SyncTrigger.SCHEDULED.name, spec.input.getString(SyncScheduler.KEY_TRIGGER))
    }

    @Test
    fun syncNowIsAOneTimeRunWithTheSameConstraints() {
        val spec = SyncScheduler.oneTimeRequest().workSpec
        assertEquals(0L, spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(SyncTrigger.MANUAL.name, spec.input.getString(SyncScheduler.KEY_TRIGGER))
    }
}
