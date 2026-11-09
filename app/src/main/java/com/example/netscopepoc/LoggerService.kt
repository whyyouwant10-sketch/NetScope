package com.example.netscopepoc

import android.app.*
import android.app.usage.NetworkStatsManager
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.widget.RemoteViews
import androidx.compose.runtime.*
import java.io.File
import java.time.*
import java.time.temporal.ChronoUnit

/** Observable state shown on the diagnostics screen. */
object Live {
    var running by mutableStateOf(false)
    var cls by mutableStateOf("-")
    var raw by mutableStateOf("-")
    var transport by mutableStateOf("-")
    var mobRx by mutableLongStateOf(0)
    var mobTx by mutableLongStateOf(0)
    var nsMobile by mutableLongStateOf(0)
    var nsWifi by mutableLongStateOf(0)

    /** Current throughput in bytes/second, all interfaces. */
    var downBps by mutableLongStateOf(0L)
    var upBps by mutableLongStateOf(0L)
    /** Mobile-only throughput in bytes/second. */
    var mobileDownBps by mutableLongStateOf(0L)
    var mobileUpBps by mutableLongStateOf(0L)
    /** Non-mobile throughput (Wi-Fi/other), derived from total minus mobile. */
    var wifiDownBps by mutableLongStateOf(0L)
    var wifiUpBps by mutableLongStateOf(0L)

    /** NetworkStats mobile/Wi-Fi bytes accumulated during this logging session. -1 = no data yet. */
    var nsMobileSession by mutableLongStateOf(-1)
    var nsWifiSession by mutableLongStateOf(-1)

    /** Latest cellular signal. Int.MIN_VALUE means no reading yet. */
    var signalDbm by mutableIntStateOf(Int.MIN_VALUE)
    /** 0..4 bars from the same CellSignalStrength reading. */
    var signalLevel by mutableIntStateOf(0)

    var note by mutableStateOf("")
    /** Wall-clock time of the last completed sample; makes sampling gaps visible. */
    var lastSampleAt by mutableLongStateOf(0L)
    /** Length of the last sampling interval; 0 for the first sample. */
    var lastIntervalMs by mutableLongStateOf(0L)
    /** Largest interval since logging started; reveals service stalls. */
    var maxIntervalMs by mutableLongStateOf(0L)

    /** Bytes attributed per bucket since logging started. */
    val totals = mutableStateMapOf<String, Long>()
    /** Best-effort part of each bucket, shown as +/- on screen. */
    val ambiguous = mutableStateMapOf<String, Long>()

    var ambiguousTotal by mutableLongStateOf(0L)
    var rebaseTotal by mutableLongStateOf(0L)
    var resetEvents by mutableLongStateOf(0L)

    /**
     * 4G bytes counted against the user's 4G allowance. This is persisted
     * separately from the session so the remaining figure survives restarts
     * and explicit stops until the user resets it.
     */
    var capUsed4gBytes by mutableLongStateOf(0L)

    /**
     * 4G upper bound: assign residual ambiguity to 4G. Default on because an
     * under-counted 4G figure is the failure mode this POC is guarding against.
     */
    var upperBoundTo4g by mutableStateOf(true)

    var lastMarker by mutableStateOf("")
    var lastMarkerAt by mutableLongStateOf(0L)
    var markerCount by mutableIntStateOf(0)

    /**
     * Set when the user stops logging while a marker is still pending. The
     * service writes one more row (consuming the marker) and then stops, so a
     * start/end marker cannot be lost.
     */
    var stopAfterSample by mutableStateOf(false)
}

/**
 * POC-01: samples every 1-5 s, classifies, appends to CSV.
 *
 * Sampling is fast while mobile bytes are moving and slow while idle. Radio
 * transitions are time-split using the display-info callback timestamp, counter
 * rebases are excluded instead of counted as traffic, and unresolved mixed
 * intervals are biased to 4G but reported as ambiguous.
 */
class LoggerService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var tm: TelephonyManager
    private lateinit var cm: ConnectivityManager

    private var nt = NetworkClassifier.TYPE_UNKNOWN
    private var ov = NetworkClassifier.OVERRIDE_NONE
    private var haveDisplayInfo = false
    private var currentClass = NetworkClassifier.CLASS_UNKNOWN
    /** Class at the previous sample, i.e. the class the interval started in. */
    private var sampleClass = NetworkClassifier.CLASS_UNKNOWN
    private var lastStableClass = NetworkClassifier.CLASS_UNKNOWN
    /** Display-info class changes since the previous sample, in callback order. */
    private val transitions = mutableListOf<ClassTransition>()

    private var last: MobileCounters? = null
    private var lastElapsed = 0L
    private var lastTotalRx = -1L
    private var lastTotalTx = -1L
    private var registered = false
    private lateinit var file: File

    /**
     * Highest counter seen per direction. Tracking RX and TX separately catches
     * a reset in one direction that is masked by growth in the other.
     */
    private var highWaterRx = 0L
    private var highWaterTx = 0L
    private var rebasePending = false
    private var rebaseHighWaterRx = 0L
    private var rebaseHighWaterTx = 0L
    private var resetAtElapsed = 0L

    /** NetworkStats bookkeeping. Deltas are summed per query so midnight rollover is harmless. */
    private var nsPrevMobile = -1L
    private var nsPrevWifi = -1L
    private var lastNsElapsed = 0L

    /** Highest 4G usage milestone already announced, in units of the current step. */
    private var lastMilestone = 0L
    private var lastAlertStep = 0L

    /** True when the user asked to stop, so the persisted session must be cleared. */
    private var explicitStop = false
    /** First sample after a restore: its NetworkStats delta is downtime traffic. */
    private var pendingRestartGap = false
    private var lastPersistElapsed = 0L
    private var lastPruneElapsed = 0L

    private val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.SignalStrengthsListener {
        override fun onDisplayInfoChanged(ti: TelephonyDisplayInfo) {
            val nextClass = NetworkClassifier.networkClass(ti.networkType, ti.overrideNetworkType)
            val previousClass = currentClass
            // A change of the classified bucket is a real transition. LTE-CA
            // override flapping (0 <-> 1) stays 4G and must not discard the interval.
            // Keep every change so multiple transitions inside one interval can
            // be replayed in order.
            if (haveDisplayInfo && nextClass != currentClass) {
                transitions += ClassTransition(SystemClock.elapsedRealtime(), nextClass)
                if (transport() == "cell" &&
                    ((previousClass == NetworkClassifier.CLASS_4G &&
                            nextClass == NetworkClassifier.CLASS_5G) ||
                        (previousClass == NetworkClassifier.CLASS_5G &&
                            nextClass == NetworkClassifier.CLASS_4G))
                ) {
                    notifyClassChange(previousClass, nextClass)
                }
            }
            nt = ti.networkType
            ov = ti.overrideNetworkType
            currentClass = nextClass
            haveDisplayInfo = true
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val best = signalStrength.cellSignalStrengths.maxByOrNull { it.level } ?: return
            Live.signalDbm = best.dbm
            Live.signalLevel = best.level
            updateNotification()
        }
    }

    private fun transport(): String {
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }

    private fun currentNetworkLabel(): String = when {
        Live.transport == "wifi" -> "Wi-Fi"
        Live.transport == "cell" && Live.cls != "unknown" -> Live.cls.uppercase()
        Live.transport == "cell" -> "Mobile"
        Live.transport == "other" -> "Other"
        else -> "No network"
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000L -> "%.2f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000L -> "%.1f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun compactBytes(b: Long): String {
        val mb = b / 1048576.0
        return if (mb >= 1024.0) "%.1fGB".format(mb / 1024.0) else "%.0fMB".format(mb)
    }

    /** Two-decimal formatting for the 4G remaining figure. */
    private fun preciseBytes(b: Long): String {
        val mb = b / 1048576.0
        return if (mb >= 1024.0) "%.2fGB".format(mb / 1024.0) else "%.2fMB".format(mb)
    }

    private fun compactMbOrNa(b: Long): String = if (b < 0L) "n/a" else compactBytes(b)

    private fun formatConsumed(b: Long): String {
        val mb = b / 1048576.0
        if (mb < 1024.0) return "%.0f MB".format(mb)
        val gb = mb / 1024.0
        return if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB"
        else "%.2f GB".format(gb).trimEnd('0').trimEnd('.')
    }

    private fun cap4gBytes(): Long =
        getSharedPreferences("netscope", MODE_PRIVATE).getLong("cap4g_bytes", 0L)

    private fun alertStepBytes(): Long =
        getSharedPreferences("netscope", MODE_PRIVATE)
            .getLong("alert_step_bytes", 500L * 1024 * 1024)

    private fun saveCapUsed() {
        getSharedPreferences("netscope", MODE_PRIVATE).edit()
            .putLong("cap_used_4g_bytes", Live.capUsed4gBytes)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun wifiRssi(): Int? = try {
        val wm = getSystemService(WifiManager::class.java)
        val rssi = wm.connectionInfo?.rssi
        if (rssi == null || rssi == -127) null else rssi
    } catch (_: Exception) {
        null
    }

    private fun cellQuality(): String? = when {
        Live.signalDbm == Int.MIN_VALUE -> null
        Live.signalLevel >= 4 -> "Excellent"
        Live.signalLevel == 3 -> "Good"
        Live.signalLevel == 2 -> "Fair"
        Live.signalLevel == 1 -> "Weak"
        else -> "No signal"
    }

    private fun wifiQuality(): String? = wifiRssi()?.let {
        when {
            it >= -55 -> "Excellent"
            it >= -67 -> "Good"
            it >= -78 -> "Fair"
            it >= -90 -> "Weak"
            else -> "Poor"
        }
    }

    private fun signalLine(): String {
        val cell = cellQuality()?.let { "Cell $it" } ?: "Cell n/a"
        val wifi = wifiQuality()?.let { "Wi-Fi $it" } ?: "Wi-Fi n/a"
        return "$cell  ·  $wifi"
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val net = currentNetworkLabel()
        val speedLine = "$net  ·  Down ${formatSpeed(Live.downBps)}  Up ${formatSpeed(Live.upBps)}"
        val cap = cap4gBytes()
        val fourG = if (cap > 0L) {
            val remaining = cap - Live.capUsed4gBytes
            val tail = if (remaining >= 0L) preciseBytes(remaining)
            else "over ${preciseBytes(-remaining)}"
            "4G: ${compactBytes(Live.capUsed4gBytes)} ($tail)"
        } else {
            "4G: ${compactBytes(Live.capUsed4gBytes)}"
        }
        val totalsLine = "$fourG  ·  5G: ${compactBytes(Live.totals["5g"] ?: 0L)}  ·  " +
            "Wi-Fi: ${compactMbOrNa(Live.nsWifiSession)}"
        val signal = "Signal: ${signalLine()}"

        val collapsed = RemoteViews(packageName, R.layout.notification_collapsed)
        collapsed.setTextViewText(R.id.notif_line1, speedLine)
        collapsed.setTextViewText(R.id.notif_line2, totalsLine)

        val expanded = RemoteViews(packageName, R.layout.notification_expanded)
        expanded.setTextViewText(R.id.notif_line1, speedLine)
        expanded.setTextViewText(R.id.notif_line2, totalsLine)
        expanded.setTextViewText(R.id.notif_signal, signal)

        return Notification.Builder(this, "log")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NetScope")
            .setSubText(net)
            .setContentText(speedLine)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification() {
        if (!Live.running) return
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun notifyClassChange(from: String, to: String) {
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Mobile network changed")
            .setContentText("${from.uppercase()} → ${to.uppercase()}")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun checkUsageMilestone() {
        val step = alertStepBytes()
        val used = Live.capUsed4gBytes
        if (step != lastAlertStep) {
            lastAlertStep = step
            lastMilestone = if (step > 0L) used / step else 0L
            return
        }
        if (step <= 0L) return
        val milestone = used / step
        if (milestone <= lastMilestone) return
        lastMilestone = milestone

        val consumed = milestone * step
        val cap = cap4gBytes()
        val text = if (cap > 0L) {
            val remaining = cap - used
            val tail = if (remaining >= 0L) "${preciseBytes(remaining)} left"
            else "over ${preciseBytes(-remaining)}"
            "${formatConsumed(consumed)} 4G consumed · $tail"
        } else {
            "${formatConsumed(consumed)} 4G consumed"
        }
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("4G usage")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(3, n)
    }

    private fun today(type: Int): Long = try {
        val s = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val b = getSystemService(NetworkStatsManager::class.java)
            .querySummaryForDevice(type, null, s, System.currentTimeMillis())
        b.rxBytes + b.txBytes
    } catch (_: Exception) {
        -1L
    }

    private fun snapshot(): SessionSnapshot = SessionSnapshot(
        totals = Live.totals.toMap(),
        ambiguous = Live.ambiguous.toMap(),
        ambiguousTotal = Live.ambiguousTotal,
        rebaseTotal = Live.rebaseTotal,
        resetEvents = Live.resetEvents,
        nsMobileSession = Live.nsMobileSession,
        nsWifiSession = Live.nsWifiSession,
        maxIntervalMs = Live.maxIntervalMs,
        lastMarker = Live.lastMarker,
        lastMarkerAt = Live.lastMarkerAt,
        markerCount = Live.markerCount,
        nsPrevMobile = nsPrevMobile,
        nsPrevWifi = nsPrevWifi,
        lastMilestone = lastMilestone,
        lastAlertStep = lastAlertStep,
    )

    /** Persist the session, allowance usage, and daily history together. */
    private fun persist() {
        HistoryStore.save(this, HistoryLive.days)
        SessionStore.save(this, snapshot())
        saveCapUsed()
    }

    /**
     * Keep only the last 24 hours of the detailed CSV. Daily history is stored
     * separately in history.json and is never pruned.
     */
    private fun pruneCsv() {
        if (!file.exists()) return
        val cutoff = Instant.now().minus(24, ChronoUnit.HOURS)
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        if (lines.size <= 1) return
        val header = lines.first()
        val kept = lines.drop(1).filter { line ->
            val stamp = line.substringBefore(',').let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
            stamp == null || !stamp.isBefore(cutoff)
        }
        if (kept.size == lines.size - 1) return
        val tmp = File(filesDir, "netscope_poc.csv.tmp")
        runCatching {
            tmp.writeText((listOf(header) + kept).joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun resetSessionState() {
        Live.totals.clear()
        Live.ambiguous.clear()
        Live.ambiguousTotal = 0
        Live.rebaseTotal = 0
        Live.resetEvents = 0
        Live.nsMobileSession = -1
        Live.nsWifiSession = -1
        Live.signalDbm = Int.MIN_VALUE
        Live.signalLevel = 0
        Live.mobRx = 0
        Live.mobTx = 0
        Live.nsMobile = 0
        Live.nsWifi = 0
        Live.downBps = 0
        Live.upBps = 0
        Live.mobileDownBps = 0
        Live.mobileUpBps = 0
        Live.wifiDownBps = 0
        Live.wifiUpBps = 0
        Live.note = ""
        Live.lastMarker = ""
        Live.lastMarkerAt = 0
        Live.lastSampleAt = 0
        Live.lastIntervalMs = 0
        Live.maxIntervalMs = 0
        Live.stopAfterSample = false
        Live.markerCount = 0
        highWaterRx = 0
        highWaterTx = 0
        rebasePending = false
        rebaseHighWaterRx = 0
        rebaseHighWaterTx = 0
        resetAtElapsed = 0
        nsPrevMobile = -1
        nsPrevWifi = -1
        lastNsElapsed = 0
        lastMilestone = 0
        lastAlertStep = 0
        last = null
        lastElapsed = 0
        lastTotalRx = -1L
        lastTotalTx = -1L
        sampleClass = NetworkClassifier.CLASS_UNKNOWN
        lastStableClass = NetworkClassifier.CLASS_UNKNOWN
        transitions.clear()
        pendingRestartGap = false
    }

    private fun applySnapshot(snapshot: SessionSnapshot) {
        Live.totals.clear()
        Live.totals.putAll(snapshot.totals)
        Live.ambiguous.clear()
        Live.ambiguous.putAll(snapshot.ambiguous)
        Live.ambiguousTotal = snapshot.ambiguousTotal
        Live.rebaseTotal = snapshot.rebaseTotal
        Live.resetEvents = snapshot.resetEvents
        Live.nsMobileSession = snapshot.nsMobileSession
        Live.nsWifiSession = snapshot.nsWifiSession
        Live.maxIntervalMs = snapshot.maxIntervalMs
        Live.lastMarker = snapshot.lastMarker
        Live.lastMarkerAt = snapshot.lastMarkerAt
        Live.markerCount = snapshot.markerCount
        nsPrevMobile = snapshot.nsPrevMobile
        nsPrevWifi = snapshot.nsPrevWifi
        Live.nsMobile = snapshot.nsPrevMobile
        Live.nsWifi = snapshot.nsPrevWifi
        lastMilestone = snapshot.lastMilestone
        lastAlertStep = snapshot.lastAlertStep
        // The first NetworkStats query after a restart reports the traffic that
        // happened while the service was down. Only add a positive gap.
        pendingRestartGap = snapshot.nsPrevMobile >= 0L
    }

    private val loop = object : Runnable {
        override fun run() {
            val next = sample()
            if (Live.stopAfterSample) {
                Live.stopAfterSample = false
                explicitStop = true
                stopSelf()
                return
            }
            h.postDelayed(this, next)
        }
    }

    /** Returns how long to wait before the next sample. */
    private fun sample(): Long {
        val now = SystemClock.elapsedRealtime()
        val current = MobileCounters(TrafficStats.getMobileRxBytes(), TrafficStats.getMobileTxBytes())
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val tr = transport()
        val own = currentClass
        val previous = last
        val previousTotal = previous?.total ?: 0L
        val rawDelta = if (previous != null && previous.supported && current.supported) {
            current.total - previousTotal
        } else {
            0L
        }

        // Query NetworkStats every few seconds, and immediately for a suspicious jump.
        val needNs = lastNsElapsed == 0L ||
            now - lastNsElapsed >= NS_REFRESH_MS ||
            rawDelta > NS_FORCE_QUERY_BYTES
        var nsM = Live.nsMobile
        var nsW = Live.nsWifi
        var nsDelta: Long? = null
        var wifiDelta: Long? = null
        var nsRefreshed = false
        var mobileQueryOk = false
        val todayDate = HistoryMath.dateOf(System.currentTimeMillis())
        if (needNs) {
            val qM = today(ConnectivityManager.TYPE_MOBILE)
            val qW = today(ConnectivityManager.TYPE_WIFI)
            nsRefreshed = qM >= 0 || qW >= 0
            mobileQueryOk = qM >= 0
            if (qM >= 0) {
                val prevM = nsPrevMobile
                if (prevM < 0) {
                    Live.nsMobileSession = 0
                } else if (qM >= prevM) {
                    val d = qM - prevM
                    Live.nsMobileSession = Live.nsMobileSession.coerceAtLeast(0L) + d
                    nsDelta = d
                }
                nsPrevMobile = qM
                nsM = qM
            }
            if (qW >= 0) {
                val prevW = nsPrevWifi
                if (prevW < 0) {
                    Live.nsWifiSession = 0
                } else if (qW >= prevW) {
                    val d = qW - prevW
                    Live.nsWifiSession = Live.nsWifiSession.coerceAtLeast(0L) + d
                    wifiDelta = d
                }
                nsPrevWifi = qW
                nsW = qW
            }
            lastNsElapsed = now
        }

        // Wi-Fi is not generation-classified, so its daily history comes from
        // the authoritative NetworkStats delta.
        wifiDelta?.let { HistoryLive.addWifi(todayDate, it) }

        // Traffic that occurred while the service was down. Only a positive
        // NetworkStats gap is added, and it goes to the unknown mobile bucket.
        if (pendingRestartGap && mobileQueryOk) {
            val gap = nsDelta ?: 0L
            if (gap > 0L) {
                Live.totals[NetworkClassifier.CLASS_UNKNOWN] =
                    (Live.totals[NetworkClassifier.CLASS_UNKNOWN] ?: 0L) + gap
                HistoryLive.addMobile(todayDate, NetworkClassifier.CLASS_UNKNOWN, gap)
            }
            pendingRestartGap = false
        }

        // A recovery is only a rebase signal while the counter is still below
        // the high-water mark observed before the drop.
        val recoveringTowardHighWater = rebasePending &&
            (current.rx < rebaseHighWaterRx || current.tx < rebaseHighWaterTx)

        val attribution = TrafficAttributor.attribute(
            previous = previous,
            current = current,
            previousElapsedMs = if (lastElapsed == 0L) now else lastElapsed,
            elapsedMs = now,
            ownClass = own,
            previousClass = sampleClass,
            lastStableClass = lastStableClass,
            onWifi = tr == "wifi",
            transitions = transitions.toList(),
            nsDeltaBytes = nsDelta,
            recoveringTowardHighWater = recoveringTowardHighWater,
            upperBoundTo4g = Live.upperBoundTo4g,
        )
        transitions.clear()

        attribution.assignments.forEach { assignment ->
            Live.totals[assignment.bucket] = (Live.totals[assignment.bucket] ?: 0L) + assignment.bytes
            if (assignment.ambiguousBytes > 0L) {
                Live.ambiguous[assignment.bucket] =
                    (Live.ambiguous[assignment.bucket] ?: 0L) + assignment.ambiguousBytes
            }
            HistoryLive.addMobile(todayDate, assignment.bucket, assignment.bytes)
            if (assignment.bucket == NetworkClassifier.CLASS_4G) {
                Live.capUsed4gBytes += assignment.bytes
            }
        }
        Live.ambiguousTotal += attribution.ambiguousBytes
        Live.rebaseTotal += attribution.rebaseBytes
        if (attribution.resetDetected) Live.resetEvents += 1
        checkUsageMilestone()

        // Rebase bookkeeping, per direction. A reset in RX can be hidden by TX
        // growth, so each direction keeps its own high-water mark. The recovery
        // is complete only when both directions are back at their high-water.
        if (attribution.resetDetected) {
            rebasePending = true
            rebaseHighWaterRx = maxOf(rebaseHighWaterRx, highWaterRx, previous?.rx ?: 0L)
            rebaseHighWaterTx = maxOf(rebaseHighWaterTx, highWaterTx, previous?.tx ?: 0L)
            resetAtElapsed = now
        } else {
            highWaterRx = maxOf(highWaterRx, current.rx)
            highWaterTx = maxOf(highWaterTx, current.tx)
            val recovered = current.rx >= rebaseHighWaterRx && current.tx >= rebaseHighWaterTx
            if (rebasePending && (recovered || now - resetAtElapsed > REBASE_EXPIRY_MS)) {
                rebasePending = false
                rebaseHighWaterRx = 0L
                rebaseHighWaterTx = 0L
            }
        }

        if (tr == "cell" && own != NetworkClassifier.CLASS_UNKNOWN) lastStableClass = own

        val intervalMs = if (lastElapsed == 0L) 0L else now - lastElapsed
        if (intervalMs > 0L) {
            val dTotalRx = if (totalRx >= 0L && lastTotalRx >= 0L) totalRx - lastTotalRx else 0L
            val dTotalTx = if (totalTx >= 0L && lastTotalTx >= 0L) totalTx - lastTotalTx else 0L
            val dMobileRx = previous?.let { current.rx - it.rx } ?: 0L
            val dMobileTx = previous?.let { current.tx - it.tx } ?: 0L
            Live.downBps = if (dTotalRx > 0L) dTotalRx * 1_000L / intervalMs else 0L
            Live.upBps = if (dTotalTx > 0L) dTotalTx * 1_000L / intervalMs else 0L
            Live.mobileDownBps = if (dMobileRx > 0L) dMobileRx * 1_000L / intervalMs else 0L
            Live.mobileUpBps = if (dMobileTx > 0L) dMobileTx * 1_000L / intervalMs else 0L
            Live.wifiDownBps = (Live.downBps - Live.mobileDownBps).coerceAtLeast(0L)
            Live.wifiUpBps = (Live.upBps - Live.mobileUpBps).coerceAtLeast(0L)
        } else {
            Live.downBps = 0L
            Live.upBps = 0L
            Live.mobileDownBps = 0L
            Live.mobileUpBps = 0L
            Live.wifiDownBps = 0L
            Live.wifiUpBps = 0L
        }
        lastTotalRx = totalRx
        lastTotalTx = totalTx
        Live.cls = own
        Live.transport = tr
        Live.mobRx = current.rx
        Live.mobTx = current.tx
        Live.raw = "type=$nt override=$ov"
        Live.nsMobile = nsM
        Live.nsWifi = nsW
        Live.lastSampleAt = System.currentTimeMillis()
        Live.lastIntervalMs = intervalMs
        if (intervalMs > Live.maxIntervalMs) Live.maxIntervalMs = intervalMs

        val note = Live.note
        if (note.isNotEmpty()) {
            Live.note = ""
            Live.lastMarker = note
            Live.lastMarkerAt = System.currentTimeMillis()
        }

        val bytes4g = attributedBytes(attribution, NetworkClassifier.CLASS_4G)
        val bytes5g = attributedBytes(attribution, NetworkClassifier.CLASS_5G)
        val bytesOther = attributedBytes(attribution, NetworkClassifier.CLASS_OTHER)
        val bytesUnknown = attributedBytes(attribution, NetworkClassifier.CLASS_UNKNOWN)
        val dominant = when {
            attribution.resetDetected -> NetworkClassifier.BUCKET_RESET
            attribution.rebaseBytes > attribution.attributedBytes -> NetworkClassifier.BUCKET_REBASE
            else -> attribution.assignments.maxByOrNull { it.bytes }?.bucket ?: own
        }

        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        // May throw SecurityException when READ_PHONE_STATE is not granted.
        val sub = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        file.appendText(
            "${Instant.now()},${SystemClock.elapsedRealtime()},$boot,${current.rx},${current.tx}," +
                "$totalRx,$totalTx,$nt,$ov,$sub,$tr,$own," +
                "$dominant,${attribution.attributedBytes},$nsM,$nsW,$note,$intervalMs," +
                "${attribution.reason},${attribution.ambiguousBytes},${attribution.rebaseBytes}," +
                "$rawDelta,${nsDelta ?: 0},$nsRefreshed," +
                "$bytes4g,$bytes5g,$bytesOther,$bytesUnknown\n",
        )

        last = current
        lastElapsed = now
        sampleClass = own

        if (now - lastPersistElapsed >= PERSIST_INTERVAL_MS) {
            persist()
            lastPersistElapsed = now
        }
        if (now - lastPruneElapsed >= PRUNE_INTERVAL_MS) {
            pruneCsv()
            lastPruneElapsed = now
        }

        updateNotification()
        return if (rawDelta > ACTIVE_SAMPLE_BYTES) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
    }

    private fun attributedBytes(attribution: Attribution, bucket: String): Long =
        attribution.assignments.filter { it.bucket == bucket }.sumOf { it.bytes }

    override fun onCreate() {
        file = File(filesDir, "netscope_poc.csv")
        // A new column schema must not be appended under an old header. Rotate the
        // previous log instead of deleting it; the export uses the current file.
        if (file.exists()) {
            val header = file.bufferedReader().use { it.readLine() }
            if (header != CSV_HEADER) {
                val stamp = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val legacy = File(filesDir, "netscope_poc_$stamp.csv")
                if (!file.renameTo(legacy)) file.delete()
                file = File(filesDir, "netscope_poc.csv")
            }
        }
        if (!file.exists()) file.writeText("$CSV_HEADER\n")

        HistoryStore.ensureLoaded(this)
        pruneCsv()

        resetSessionState()
        Live.capUsed4gBytes = getSharedPreferences("netscope", MODE_PRIVATE)
            .getLong("cap_used_4g_bytes", 0L)
        SessionStore.load(this)?.let { applySnapshot(it) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("log", "Logging", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel("alerts", "Network alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "4G/5G changes and 4G usage milestones"
            },
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        tm = getSystemService(TelephonyManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        try {
            tm.registerTelephonyCallback(mainExecutor, cb)
            registered = true
        } catch (_: SecurityException) {
            // Without READ_PHONE_STATE every interval is honestly classified as unknown.
        }
        Live.running = true
        lastPruneElapsed = SystemClock.elapsedRealtime()
        h.post(loop)
    }

    private fun stopExplicitly() {
        explicitStop = true
        HistoryStore.save(this, HistoryLive.days)
        stopSelf()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            explicitStop = true
            if (Live.note.isNotEmpty()) {
                // One more sample consumes the pending marker before stopping.
                Live.stopAfterSample = true
            } else {
                stopExplicitly()
            }
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacks(loop)
        if (registered) {
            try {
                tm.unregisterTelephonyCallback(cb)
            } catch (_: Exception) {
            }
        }
        if (explicitStop) {
            // An explicit user Stop ends the session; daily history and the
            // allowance usage are kept until the user resets usage.
            HistoryStore.save(this, HistoryLive.days)
            SessionStore.clear(this)
            saveCapUsed()
        } else {
            persist()
        }
        Live.running = false
        Live.stopAfterSample = false
    }

    companion object {
        const val ACTION_STOP = "com.example.netscopepoc.action.STOP"
        const val FAST_INTERVAL_MS = 1_000L
        const val SLOW_INTERVAL_MS = 5_000L
        const val ACTIVE_SAMPLE_BYTES = 200_000L
        const val PERSIST_INTERVAL_MS = 5_000L
        const val PRUNE_INTERVAL_MS = 60L * 60L * 1000L

        /**
         * NetworkStats is queried no more often than every 2 s. TrafficStats and
         * telephony sample at 1 s while mobile bytes are moving so the displayed
         * speed stays fresh.
         */
        const val NS_REFRESH_MS = 2_000L
        const val NS_FORCE_QUERY_BYTES = 256L * 1024 * 1024
        const val REBASE_EXPIRY_MS = 60_000L
        const val CSV_HEADER =
            "time_utc,elapsed_ms,boot_count,mobile_rx,mobile_tx,total_rx,total_tx," +
                "net_type,override,sub_id,transport,class,bucket,delta_bytes,ns_mobile_today," +
                "ns_wifi_today,note,sample_ms,reason,ambiguous_bytes,rebase_excess_bytes,ts_delta_bytes," +
                "ns_delta_bytes,ns_refreshed,bytes_4g,bytes_5g,bytes_other,bytes_unknown"
    }
}
