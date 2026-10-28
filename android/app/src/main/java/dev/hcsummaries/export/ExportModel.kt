package dev.hcsummaries.export

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Exported categories. Keys, units, and bounds match the site's schemaVersion 1 validator
 * (cloud/lib/health/metrics.ts). This file has no Android dependencies so it is unit-testable.
 */
enum class Metric(
    val key: String,
    val label: String,
    val detail: String,
    val decimals: Int,
    val min: Double,
    val max: Double,
) {
    STEPS("steps", "Steps", "Daily total", 0, 0.0, 200_000.0),
    SLEEP("sleepMinutes", "Sleep", "Minutes of sleep in the night ending each day", 1, 0.0, 1_500.0),
    HEART_RATE("heartRateAvgBpm", "Heart rate", "Daily average of all samples (not resting)", 0, 20.0, 250.0),
    WEIGHT("weightKg", "Weight", "Average of that day's entries, in kg", 2, 1.0, 500.0),
    DISTANCE("distanceMeters", "Distance", "Daily total, in meters", 1, 0.0, 500_000.0),
    ACTIVE_CALORIES("activeCaloriesKcal", "Active calories", "Daily total, excluding resting energy", 1, 0.0, 20_000.0);

    companion object {
        fun fromKey(key: String): Metric? = entries.firstOrNull { it.key == key }
    }
}

/** One local calendar day plus the sleep window attributed to it. */
data class DayWindow(
    val date: LocalDate,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val sleepStart: ZonedDateTime,
    val sleepEnd: ZonedDateTime,
)

/** Aggregated values for a day. A metric missing from [values] had no records: it is never zero-filled. */
data class DayResult(val window: DayWindow, val values: Map<Metric, Double>)

object ExportPlanner {
    const val DEFAULT_DAYS = 7
    const val MAX_DAYS = 30
    val RANGE_OPTIONS = listOf(7, 14, 30)
    private val SLEEP_BOUNDARY: LocalTime = LocalTime.of(18, 0)

    /**
     * Consecutive local days ending [today]. Day boundaries use [LocalDate.atStartOfDay] so
     * 23- and 25-hour DST days, and zones where midnight is skipped, are handled correctly.
     */
    fun windows(today: LocalDate, days: Int, zone: ZoneId): List<DayWindow> {
        require(days in 1..MAX_DAYS) { "days must be 1..$MAX_DAYS" }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayWindow(
                date = date,
                start = date.atStartOfDay(zone),
                end = date.plusDays(1).atStartOfDay(zone),
                sleepStart = LocalDateTime.of(date.minusDays(1), SLEEP_BOUNDARY).atZone(zone),
                sleepEnd = LocalDateTime.of(date, SLEEP_BOUNDARY).atZone(zone),
            )
        }
    }
}

object ExportJson {
    const val SCHEMA_VERSION = 1
    const val SOURCE = "android-health-connect"
    private val TIMEZONE_RE = Regex("^(?:UTC|[A-Za-z][A-Za-z0-9_+-]*(?:/[A-Za-z0-9_+-]+){1,2})$")
    private val OFFSET_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** True for named region zones (e.g. America/Chicago) that the site accepts. */
    fun isSupportedZone(zone: ZoneId): Boolean = zone !is ZoneOffset && TIMEZONE_RE.matches(zone.id)

    /** Returns the first out-of-bounds value as (metric, date), or null when all values are acceptable. */
    fun firstOutOfBounds(days: List<DayResult>): Pair<Metric, LocalDate>? {
        for (day in days) for ((metric, value) in day.values) {
            val rounded = round(metric, value).toDouble()
            if (!value.isFinite() || rounded < metric.min || rounded > metric.max) return metric to day.window.date
        }
        return null
    }

    fun round(metric: Metric, value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(metric.decimals, RoundingMode.HALF_UP).stripTrailingZeros()

    /** Builds the export document. [metrics] are the categories read for every day. */
    fun build(generatedAt: Instant, zone: ZoneId, metrics: List<Metric>, days: List<DayResult>): String {
        require(days.isNotEmpty()) { "no days" }
        require(metrics.isNotEmpty()) { "no categories" }
        val ordered = Metric.entries.filter { it in metrics }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        sb.append("  \"source\": ").append(quote(SOURCE)).append(",\n")
        sb.append("  \"generatedAt\": ").append(quote(DateTimeFormatter.ISO_INSTANT.format(generatedAt))).append(",\n")
        sb.append("  \"timezone\": ").append(quote(zone.id)).append(",\n")
        sb.append("  \"rangeStart\": ").append(quote(days.first().window.date.toString())).append(",\n")
        sb.append("  \"rangeEnd\": ").append(quote(days.last().window.date.toString())).append(",\n")
        sb.append("  \"categories\": [").append(ordered.joinToString(", ") { quote(it.key) }).append("],\n")
        sb.append("  \"days\": [\n")
        days.forEachIndexed { i, day ->
            val fields = mutableListOf(
                "\"date\": ${quote(day.window.date.toString())}",
                "\"start\": ${quote(OFFSET_TIME.format(day.window.start))}",
                "\"end\": ${quote(OFFSET_TIME.format(day.window.end))}",
            )
            if (Metric.SLEEP in ordered) {
                fields += "\"sleepWindowStart\": ${quote(OFFSET_TIME.format(day.window.sleepStart))}"
                fields += "\"sleepWindowEnd\": ${quote(OFFSET_TIME.format(day.window.sleepEnd))}"
            }
            for (metric in ordered) {
                val value = day.values[metric] ?: continue // absent = no records, never 0
                fields += "\"${metric.key}\": ${round(metric, value).toPlainString()}"
            }
            sb.append("    {").append(fields.joinToString(", ")).append("}")
            sb.append(if (i < days.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    private fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
