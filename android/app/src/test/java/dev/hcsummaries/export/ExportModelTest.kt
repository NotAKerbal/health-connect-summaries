package dev.hcsummaries.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ExportModelTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun windowsCoverConsecutiveLocalDaysEndingToday() {
        val w = ExportPlanner.windows(LocalDate.of(2026, 10, 27), 7, chicago)
        assertEquals(7, w.size)
        assertEquals(LocalDate.of(2026, 10, 21), w.first().date)
        assertEquals(LocalDate.of(2026, 10, 27), w.last().date)
        w.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun dstDaysAre23And25Hours() {
        val spring = ExportPlanner.windows(LocalDate.of(2026, 3, 8), 1, chicago).single()
        assertEquals(Duration.ofHours(23), Duration.between(spring.start, spring.end))
        val fall = ExportPlanner.windows(LocalDate.of(2026, 11, 1), 1, chicago).single()
        assertEquals(Duration.ofHours(25), Duration.between(fall.start, fall.end))
        assertEquals("2026-11-01T00:00-05:00", fall.start.toOffsetDateTime().toString())
        assertEquals("2026-11-02T00:00-06:00", fall.end.toOffsetDateTime().toString())
    }

    @Test
    fun skippedMidnightStartsAtFirstValidInstant() {
        // America/Santiago springs forward at midnight on 2026-09-06.
        val w = ExportPlanner.windows(LocalDate.of(2026, 9, 6), 1, ZoneId.of("America/Santiago")).single()
        assertEquals(1, w.start.hour)
    }

    @Test
    fun sleepWindowIsSixPmToSixPm() {
        val w = ExportPlanner.windows(LocalDate.of(2026, 10, 27), 1, chicago).single()
        assertEquals(LocalDate.of(2026, 10, 26), w.sleepStart.toLocalDate())
        assertEquals(18, w.sleepStart.hour)
        assertEquals(18, w.sleepEnd.hour)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rangeOver30DaysIsRejected() {
        ExportPlanner.windows(LocalDate.of(2026, 10, 27), 31, chicago)
    }

    @Test
    fun jsonPreservesZeroAndOmitsMissingValues() {
        val w = ExportPlanner.windows(LocalDate.of(2026, 10, 26), 2, chicago)
        val days = listOf(
            DayResult(w[0], mapOf(Metric.STEPS to 0.0, Metric.WEIGHT to 80.256, Metric.SLEEP to 412.49)),
            DayResult(w[1], emptyMap()),
        )
        val json = ExportJson.build(Instant.parse("2026-10-27T19:30:00Z"), chicago, listOf(Metric.WEIGHT, Metric.STEPS, Metric.SLEEP), days)
        assertTrue(json.contains("\"steps\": 0"))
        assertTrue(json.contains("\"weightKg\": 80.26"))
        assertTrue(json.contains("\"sleepMinutes\": 412.5"))
        assertTrue(json.contains("\"categories\": [\"steps\", \"sleepMinutes\", \"weightKg\"]"))
        assertTrue(json.contains("\"start\": \"2026-10-26T00:00:00-05:00\""))
        assertTrue(json.contains("\"sleepWindowStart\": \"2026-10-25T18:00:00-05:00\""))
        assertTrue(json.contains("\"generatedAt\": \"2026-10-27T19:30:00Z\""))
        val second = json.substringAfter("\"date\": \"2026-10-26\"").substringBefore("}")
        assertTrue(json.contains("\"date\": \"2026-10-26\""))
        assertFalse(second.contains("steps"))
    }

    @Test
    fun largeNumbersAreNotScientific() {
        val w = ExportPlanner.windows(LocalDate.of(2026, 10, 27), 1, chicago)
        val json = ExportJson.build(Instant.parse("2026-10-27T19:30:00Z"), chicago, listOf(Metric.DISTANCE), listOf(DayResult(w[0], mapOf(Metric.DISTANCE to 12000000.0 / 100))))
        assertTrue(json.contains("\"distanceMeters\": 120000"))
    }

    @Test
    fun boundsAndZonesMatchTheSite() {
        val w = ExportPlanner.windows(LocalDate.of(2026, 10, 27), 1, chicago)
        assertNull(ExportJson.firstOutOfBounds(listOf(DayResult(w[0], mapOf(Metric.HEART_RATE to 61.0)))))
        assertEquals(Metric.STEPS, ExportJson.firstOutOfBounds(listOf(DayResult(w[0], mapOf(Metric.STEPS to 250000.0))))?.first)
        assertEquals(Metric.HEART_RATE, ExportJson.firstOutOfBounds(listOf(DayResult(w[0], mapOf(Metric.HEART_RATE to 0.0))))?.first)
        assertTrue(ExportJson.isSupportedZone(chicago))
        assertTrue(ExportJson.isSupportedZone(ZoneId.of("UTC")))
        assertFalse(ExportJson.isSupportedZone(ZoneOffset.ofHours(5)))
    }
}
