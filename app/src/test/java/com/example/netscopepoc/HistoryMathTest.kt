package com.example.netscopepoc

import com.example.netscopepoc.NetworkClassifier.CLASS_4G
import com.example.netscopepoc.NetworkClassifier.CLASS_5G
import com.example.netscopepoc.NetworkClassifier.CLASS_OTHER
import com.example.netscopepoc.NetworkClassifier.CLASS_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryMathTest {

    private val mb = 1024L * 1024L

    private fun day(
        date: String,
        fourG: Long = 0L,
        fiveG: Long = 0L,
        other: Long = 0L,
        wifi: Long = 0L,
    ) = HistoryDay(date, fourG, fiveG, other, wifi)

    @Test
    fun `dateOf uses the supplied zone`() {
        val epoch = Instant.parse("2026-11-09T23:30:00Z").toEpochMilli()
        assertEquals("2026-11-09", HistoryMath.dateOf(epoch, ZoneOffset.UTC))
        // UTC+2 is already the next calendar day.
        assertEquals("2026-11-10", HistoryMath.dateOf(epoch, ZoneOffset.ofHours(2)))
    }

    @Test
    fun `last seven days includes today and excludes the eighth day`() {
        val today = LocalDate.parse("2026-11-09")
        val days = listOf(
            day("2026-11-02", fourG = 99 * mb),
            day("2026-11-03", fourG = 1 * mb),
            day("2026-11-09", fourG = 2 * mb),
        )
        val totals = HistoryMath.lastDays(days, today, 7)
        assertEquals(3 * mb, totals.mobile4g)
        assertEquals(3 * mb, totals.mobile)
    }

    @Test
    fun `last thirty days spans a month boundary`() {
        val today = LocalDate.parse("2026-11-09")
        val days = listOf(
            day("2026-10-11", fiveG = 5 * mb),
            day("2026-10-10", fiveG = 50 * mb),
            day("2026-11-09", fiveG = 1 * mb),
        )
        val totals = HistoryMath.lastDays(days, today, 30)
        assertEquals(6 * mb, totals.mobile5g)
    }

    @Test
    fun `this month starts on the first day`() {
        val today = LocalDate.parse("2026-11-09")
        val days = listOf(
            day("2026-10-31", fourG = 50 * mb),
            day("2026-11-01", fourG = 1 * mb),
            day("2026-11-09", fourG = 2 * mb),
        )
        val totals = HistoryMath.thisMonth(days, today)
        assertEquals(3 * mb, totals.mobile4g)
    }

    @Test
    fun `mobile and total aggregate every bucket`() {
        val days = listOf(
            day("2026-11-08", fourG = 1 * mb, fiveG = 2 * mb, other = 3 * mb, wifi = 10 * mb),
            day("2026-11-09", fourG = 4 * mb, fiveG = 5 * mb, other = 6 * mb, wifi = 20 * mb),
        )
        val totals = HistoryMath.allTime(days)
        assertEquals(5 * mb, totals.mobile4g)
        assertEquals(7 * mb, totals.mobile5g)
        assertEquals(9 * mb, totals.mobileOther)
        assertEquals(21 * mb, totals.mobile)
        assertEquals(30 * mb, totals.wifi)
        assertEquals(51 * mb, totals.total)
    }

    @Test
    fun `add routes buckets to 4g 5g and other`() {
        val start = HistoryDay("2026-11-09")
        val with4g = HistoryMath.add(start, CLASS_4G, 1 * mb)
        val with5g = HistoryMath.add(with4g, CLASS_5G, 2 * mb)
        val withOther = HistoryMath.add(with5g, CLASS_OTHER, 3 * mb)
        val withUnknown = HistoryMath.add(withOther, CLASS_UNKNOWN, 4 * mb)

        assertEquals(1 * mb, withUnknown.mobile4g)
        assertEquals(2 * mb, withUnknown.mobile5g)
        assertEquals(7 * mb, withUnknown.mobileOther)
        assertEquals(10 * mb, withUnknown.mobile)
    }

    @Test
    fun `add ignores non-positive bytes`() {
        val start = HistoryDay("2026-11-09", mobile4g = 5 * mb)
        assertEquals(start, HistoryMath.add(start, CLASS_4G, 0L))
        assertEquals(start, HistoryMath.add(start, CLASS_4G, -1L))
        assertEquals(start, HistoryMath.addWifi(start, 0L))
    }

    @Test
    fun `gap bytes counts only positive deltas`() {
        assertEquals(10 * mb, HistoryMath.gapBytes(5 * mb, 15 * mb))
        assertEquals(0L, HistoryMath.gapBytes(-1L, 15 * mb))
        assertEquals(0L, HistoryMath.gapBytes(20 * mb, 15 * mb))
        assertEquals(0L, HistoryMath.gapBytes(15 * mb, 15 * mb))
    }

    @Test
    fun `entries are sorted newest first`() {
        val days = listOf(
            day("2026-11-07"),
            day("2026-11-09"),
            day("2026-11-08"),
        )
        val sorted = HistoryMath.entriesDescending(days).map { it.date }
        assertEquals(listOf("2026-11-09", "2026-11-08", "2026-11-07"), sorted)
    }

    @Test
    fun `invalid dates are skipped rather than throwing`() {
        val days = listOf(day("not-a-date", fourG = 5 * mb), day("2026-11-09", fourG = 1 * mb))
        val totals = HistoryMath.allTime(days)
        assertEquals(1 * mb, totals.mobile4g)
    }

    @Test
    fun `zero day range is empty`() {
        val today = LocalDate.parse("2026-11-09")
        assertTrue(HistoryMath.lastDays(listOf(day("2026-11-09", fourG = 1 * mb)), today, 0).total == 0L)
    }

    @Test
    fun `format date matches the screenshot style`() {
        assertEquals("2 Nov 2026", HistoryFormat.date("2026-11-02"))
        assertEquals("9 Nov 2026", HistoryFormat.date("2026-11-09"))
    }

    @Test
    fun `invalid date is passed through unchanged`() {
        assertEquals("not-a-date", HistoryFormat.date("not-a-date"))
    }

    @Test
    fun `format bytes matches the screenshot style`() {
        assertEquals("0 MB", HistoryFormat.bytes(0L))
        assertEquals("133 MB", HistoryFormat.bytes(133L * mb))
        assertEquals("8.95 GB", HistoryFormat.bytes((8.95 * 1024).toLong() * mb))
        assertEquals("5.2 GB", HistoryFormat.bytes((5.2 * 1024).toLong() * mb))
        assertEquals("583 GB", HistoryFormat.bytes(583L * 1024 * mb))
    }

    @Test
    fun `daily rows are newest first and zero filled`() {
        val today = LocalDate.parse("2026-11-09")
        val rows = HistoryFormat.dailyRows(
            listOf(day("2026-11-09", fourG = 2 * mb), day("2026-11-07", fourG = 1 * mb)),
            today,
            4,
        )
        assertEquals(
            listOf("2026-11-09", "2026-11-08", "2026-11-07", "2026-11-06"),
            rows.map { it.date },
        )
        assertEquals(2 * mb, rows[0].mobile4g)
        assertEquals(0L, rows[1].total)
        assertEquals(1 * mb, rows[2].mobile4g)
        assertEquals(0L, rows[3].total)
    }
}
