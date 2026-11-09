package com.example.netscopepoc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One day of measured traffic. Mobile is split into 4G, 5G, and everything
 * else (3G/2G/unknown). Wi-Fi is not generation-classified.
 */
data class HistoryDay(
    val date: String,
    val mobile4g: Long = 0L,
    val mobile5g: Long = 0L,
    val mobileOther: Long = 0L,
    val wifi: Long = 0L,
) {
    val mobile: Long get() = mobile4g + mobile5g + mobileOther
    val total: Long get() = mobile + wifi
}

/** Aggregated bytes over a set of days. */
data class HistoryTotals(
    val mobile4g: Long = 0L,
    val mobile5g: Long = 0L,
    val mobileOther: Long = 0L,
    val wifi: Long = 0L,
) {
    val mobile: Long get() = mobile4g + mobile5g + mobileOther
    val total: Long get() = mobile + wifi

    operator fun plus(other: HistoryTotals) = HistoryTotals(
        mobile4g = mobile4g + other.mobile4g,
        mobile5g = mobile5g + other.mobile5g,
        mobileOther = mobileOther + other.mobileOther,
        wifi = wifi + other.wifi,
    )

    companion object {
        val ZERO = HistoryTotals()
    }
}

/**
 * Pure date and aggregation logic. Kept free of Android dependencies so the
 * daily/weekly/monthly/all-time ranges can be unit tested on the JVM.
 */
object HistoryMath {
    fun dateOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    fun add(day: HistoryDay, bucket: String, bytes: Long): HistoryDay {
        if (bytes <= 0L) return day
        return when (bucket) {
            NetworkClassifier.CLASS_4G -> day.copy(mobile4g = day.mobile4g + bytes)
            NetworkClassifier.CLASS_5G -> day.copy(mobile5g = day.mobile5g + bytes)
            else -> day.copy(mobileOther = day.mobileOther + bytes)
        }
    }

    fun addWifi(day: HistoryDay, bytes: Long): HistoryDay {
        if (bytes <= 0L) return day
        return day.copy(wifi = day.wifi + bytes)
    }

    /**
     * Sum of days whose date falls in [from, to]. Both bounds are inclusive and
     * a null bound means unbounded on that side.
     */
    fun sumBetween(
        days: Iterable<HistoryDay>,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): HistoryTotals {
        var acc = HistoryTotals.ZERO
        for (day in days) {
            val date = parseDate(day.date) ?: continue
            if (from != null && date.isBefore(from)) continue
            if (to != null && date.isAfter(to)) continue
            acc += HistoryTotals(day.mobile4g, day.mobile5g, day.mobileOther, day.wifi)
        }
        return acc
    }

    fun totalsOf(days: Iterable<HistoryDay>): HistoryTotals = sumBetween(days)

    /** Last [count] calendar days, including [today]. */
    fun lastDays(days: Iterable<HistoryDay>, today: LocalDate, count: Int): HistoryTotals {
        if (count <= 0) return HistoryTotals.ZERO
        return sumBetween(days, today.minusDays(count - 1L), today)
    }

    fun thisMonth(days: Iterable<HistoryDay>, today: LocalDate): HistoryTotals =
        sumBetween(days, today.withDayOfMonth(1), today)

    fun allTime(days: Iterable<HistoryDay>): HistoryTotals = totalsOf(days)

    /** Positive NetworkStats delta only; a rollback or missing value adds nothing. */
    fun gapBytes(previous: Long, current: Long): Long =
        if (previous >= 0L && current >= previous) current - previous else 0L

    fun entriesDescending(days: Iterable<HistoryDay>): List<HistoryDay> =
        days.sortedByDescending { it.date }

    fun utcDateOf(epochMillis: Long): String = dateOf(epochMillis, ZoneOffset.UTC)
}

/**
 * Display helpers for the History table. Pure so the exact screenshot-style
 * formatting can be unit tested.
 */
object HistoryFormat {
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** ISO date (`2026-11-02`) to the screenshot style (`2 Nov 2026`). */
    fun date(isoDate: String): String =
        HistoryMath.parseDate(isoDate)?.format(DATE_FORMAT) ?: isoDate

    /**
     * Screenshot-style size: `0 MB`, `133 MB`, `8.95 GB`, `5.2 GB`, `583 GB`.
     * Values below 1 GB are whole MB; GB keeps up to two decimals and trims
     * trailing zeros.
     */
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 MB"
        val gb = value / 1073741824.0
        if (gb >= 1.0) {
            val text = "%.2f".format(Locale.ENGLISH, gb).trimEnd('0').trimEnd('.')
            return "$text GB"
        }
        return "%.0f MB".format(Locale.ENGLISH, value / 1048576.0)
    }

    /**
     * The last [count] calendar days, newest first, with missing days filled
     * with zeroes so the table always has the same shape.
     */
    fun dailyRows(
        days: Iterable<HistoryDay>,
        today: LocalDate,
        count: Int,
    ): List<HistoryDay> {
        if (count <= 0) return emptyList()
        val byDate = days.associateBy { it.date }
        return (0 until count).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            byDate[date] ?: HistoryDay(date)
        }
    }
}

/**
 * Process-wide history used by both the logger and the History screen. The
 * service mutates it; Compose observes the snapshot state maps directly.
 */
object HistoryLive {
    val days = mutableStateMapOf<String, HistoryDay>()
    var revision by mutableLongStateOf(0L)
    var loaded = false

    fun addMobile(date: String, bucket: String, bytes: Long) {
        if (bytes <= 0L) return
        val day = days[date] ?: HistoryDay(date)
        days[date] = HistoryMath.add(day, bucket, bytes)
        revision++
    }

    fun addWifi(date: String, bytes: Long) {
        if (bytes <= 0L) return
        val day = days[date] ?: HistoryDay(date)
        days[date] = HistoryMath.addWifi(day, bytes)
        revision++
    }

    fun addGap(date: String, mobileBytes: Long, wifiBytes: Long) {
        if (mobileBytes > 0L) addMobile(date, NetworkClassifier.CLASS_UNKNOWN, mobileBytes)
        if (wifiBytes > 0L) addWifi(date, wifiBytes)
    }

    fun snapshot(): List<HistoryDay> = HistoryMath.entriesDescending(days.values)
}

/** JSON persistence for [HistoryLive]. Daily history is kept indefinitely. */
object HistoryStore {
    private const val FILE_NAME = "history.json"
    private const val VERSION = 1

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (HistoryLive.loaded) return
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            runCatching {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("days") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val date = o.getString("date")
                    if (HistoryMath.parseDate(date) == null) continue
                    HistoryLive.days[date] = HistoryDay(
                        date = date,
                        mobile4g = o.optLong("mobile4g"),
                        mobile5g = o.optLong("mobile5g"),
                        mobileOther = o.optLong("mobileOther"),
                        wifi = o.optLong("wifi"),
                    )
                }
            }
        }
        HistoryLive.loaded = true
    }

    @Synchronized
    fun save(context: Context, days: Map<String, HistoryDay>) {
        runCatching {
            val array = JSONArray()
            days.values.sortedBy { it.date }.forEach { day ->
                array.put(
                    JSONObject()
                        .put("date", day.date)
                        .put("mobile4g", day.mobile4g)
                        .put("mobile5g", day.mobile5g)
                        .put("mobileOther", day.mobileOther)
                        .put("wifi", day.wifi),
                )
            }
            val root = JSONObject().put("version", VERSION).put("days", array)
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
