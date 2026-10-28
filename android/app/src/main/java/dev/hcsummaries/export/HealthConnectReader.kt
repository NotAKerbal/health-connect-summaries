package dev.hcsummaries.export

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.ZonedDateTime
import kotlin.reflect.KClass

/** Health Connect record type behind each exported metric. */
val Metric.recordType: KClass<out Record>
    get() = when (this) {
        Metric.STEPS -> StepsRecord::class
        Metric.SLEEP -> SleepSessionRecord::class
        Metric.HEART_RATE -> HeartRateRecord::class
        Metric.WEIGHT -> WeightRecord::class
        Metric.DISTANCE -> DistanceRecord::class
        Metric.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
    }

/** The only permissions this app ever requests: one read permission per metric. */
val Metric.readPermission: String
    get() = HealthPermission.getReadPermission(recordType)

private val Metric.aggregateMetric: AggregateMetric<*>
    get() = when (this) {
        Metric.STEPS -> StepsRecord.COUNT_TOTAL
        Metric.SLEEP -> SleepSessionRecord.SLEEP_DURATION_TOTAL
        Metric.HEART_RATE -> HeartRateRecord.BPM_AVG
        Metric.WEIGHT -> WeightRecord.WEIGHT_AVG
        Metric.DISTANCE -> DistanceRecord.DISTANCE_TOTAL
        Metric.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
    }

/** Converts an aggregation result to the export unit; null means Health Connect had no data. */
private fun AggregationResult.valueOf(metric: Metric): Double? = when (metric) {
    Metric.STEPS -> this[StepsRecord.COUNT_TOTAL]?.toDouble()
    Metric.SLEEP -> this[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { it.seconds / 60.0 }
    Metric.HEART_RATE -> this[HeartRateRecord.BPM_AVG]?.toDouble()
    Metric.WEIGHT -> this[WeightRecord.WEIGHT_AVG]?.inKilograms
    Metric.DISTANCE -> this[DistanceRecord.DISTANCE_TOTAL]?.inMeters
    Metric.ACTIVE_CALORIES -> this[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
}

class PermissionRevokedException(val metrics: List<Metric>) : Exception()

/** Health Connect refused a read although every requested category is still granted. */
class HealthReadInterruptedException : Exception()

/**
 * Reads daily aggregates with the Health Connect aggregate API. Activity and sleep use
 * source-priority deduplication; other metrics combine contributing sources.
 * Raw records are never read or kept.
 */
class HealthConnectReader(private val client: HealthConnectClient) {

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun grantedMetrics(): Set<Metric> {
        val granted = grantedPermissions()
        return Metric.entries.filter { it.readPermission in granted }.toSet()
    }

    /**
     * Reads every window for every metric. Throws [PermissionRevokedException] if any requested
     * category is no longer granted, so a partial snapshot is never produced.
     */
    suspend fun read(windows: List<DayWindow>, metrics: List<Metric>): List<DayResult> {
        ensureGranted(metrics)
        val dayMetrics = metrics.filter { it != Metric.SLEEP }
        val wantsSleep = Metric.SLEEP in metrics
        return windows.map { window ->
            val values = mutableMapOf<Metric, Double>()
            try {
                if (dayMetrics.isNotEmpty()) {
                    val result = aggregate(dayMetrics, window.start, window.end)
                    for (m in dayMetrics) result.valueOf(m)?.let { values[m] = it }
                }
                if (wantsSleep) {
                    aggregate(listOf(Metric.SLEEP), window.sleepStart, window.sleepEnd)
                        .valueOf(Metric.SLEEP)?.let { values[Metric.SLEEP] = it }
                }
            } catch (e: SecurityException) {
                // Re-check which grant disappeared to give a precise message. If none did, the
                // read was refused for another reason (for example background access).
                val missing = metrics - grantedMetrics()
                if (missing.isEmpty()) throw HealthReadInterruptedException()
                throw PermissionRevokedException(missing)
            }
            DayResult(window, values)
        }
    }

    private suspend fun ensureGranted(metrics: List<Metric>) {
        val missing = metrics - grantedMetrics()
        if (missing.isNotEmpty()) throw PermissionRevokedException(missing)
    }

    private suspend fun aggregate(metrics: List<Metric>, start: ZonedDateTime, end: ZonedDateTime): AggregationResult =
        client.aggregate(
            AggregateRequest(
                metrics = metrics.map { it.aggregateMetric }.toSet(),
                timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant()),
            ),
        )
}
