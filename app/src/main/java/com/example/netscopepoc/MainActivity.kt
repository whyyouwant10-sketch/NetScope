package com.example.netscopepoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val MB = 1024L * 1024L

    private enum class Page { Front, AllStats, History }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        HistoryStore.ensureLoaded(this)
        if (s == null) {
            runCatching { startForegroundService(Intent(this, LoggerService::class.java)) }
        }
        setContent { MaterialTheme { Screen() } }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun usageOk() = getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    private fun mb(b: Long) = "%.2f MB".format(b / 1048576.0)
    private fun mbOrNa(b: Long) = if (b < 0) "n/a" else mb(b)
    private fun speed(bps: Long) = when {
        bps >= 1_000_000L -> "%.2f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000L -> "%.1f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private val prefs by lazy { getSharedPreferences("netscope", MODE_PRIVATE) }
    private fun cap4gBytes() = prefs.getLong("cap4g_bytes", 0L)
    @SuppressLint("ApplySharedPref")
    private fun setCap4gBytes(b: Long) { prefs.edit().putLong("cap4g_bytes", b).commit() }
    private fun alertStepBytes() = prefs.getLong("alert_step_bytes", 500L * MB)
    @SuppressLint("ApplySharedPref")
    private fun setAlertStepBytes(b: Long) { prefs.edit().putLong("alert_step_bytes", b).commit() }

    @SuppressLint("ApplySharedPref")
    private fun resetCapUsage() {
        Live.capUsed4gBytes = 0L
        prefs.edit().putLong("cap_used_4g_bytes", 0L).commit()
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

    private fun remainingText(used: Long, cap: Long): String {
        if (cap <= 0L) return ""
        val remaining = cap - used
        return if (remaining >= 0L) "${preciseBytes(remaining)} left"
        else "over ${preciseBytes(-remaining)}"
    }

    private fun capInputText(b: Long): String {
        if (b <= 0L) return ""
        val gb = b / 1073741824.0
        return if (gb == gb.toLong().toDouble()) gb.toLong().toString()
        else "%.2f".format(gb).trimEnd('0').trimEnd('.')
    }

    private fun stepInputText(b: Long): String {
        if (b <= 0L) return "0"
        val mb = b / 1048576.0
        return if (mb == mb.toLong().toDouble()) mb.toLong().toString()
        else "%.1f".format(mb).trimEnd('0').trimEnd('.')
    }

    private fun pssMb(): Double? = try {
        val am = getSystemService(ActivityManager::class.java)
        am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull()?.totalPss?.let { it / 1024.0 }
    } catch (_: Exception) {
        null
    }

    private fun heapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun apkBytes(): Long = try {
        val ai = packageManager.getPackageInfo(packageName, 0).applicationInfo
        val paths = buildList {
            add(ai.sourceDir)
            ai.splitSourceDirs?.let { addAll(it.toList()) }
        }
        paths.sumOf { File(it).length() }
    } catch (_: Exception) {
        0L
    }
    private fun notificationState() = when {
        Build.VERSION.SDK_INT < 33 -> "not required (Android 12)"
        granted(Manifest.permission.POST_NOTIFICATIONS) -> "granted"
        else -> "denied"
    }

    private fun notificationsOk() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun transportLabel() = when (Live.transport) {
        "cell" -> "cellular"
        "wifi" -> "Wi-Fi"
        "other" -> "other"
        else -> "no network"
    }

    private fun currentNetworkLabel() = when {
        Live.transport == "wifi" -> "Wi-Fi"
        Live.transport == "cell" && Live.cls != "unknown" -> Live.cls.uppercase()
        Live.transport == "cell" -> "Mobile"
        Live.transport == "other" -> "Other"
        else -> "No network"
    }

    private fun observedMobile(): Long =
        (Live.totals["4g"] ?: 0L) + (Live.totals["5g"] ?: 0L) +
            (Live.totals["other"] ?: 0L) + (Live.totals["unknown"] ?: 0L)

    private fun coveragePercent(observed: Long): Double? =
        if (observed <= 0L) null
        else 100.0 * ((Live.totals["4g"] ?: 0L) + (Live.totals["5g"] ?: 0L)) / observed

    private fun qualityGate(sessionMobile: Long): Long =
        maxOf(10L * MB, sessionMobile.coerceAtLeast(0L) / 100)

    private fun toggleLogging() {
        val i = Intent(this, LoggerService::class.java)
        when {
            !Live.running -> startForegroundService(i)
            // Cancel a pending stop; the service keeps running.
            Live.stopAfterSample -> Live.stopAfterSample = false
            // A pending marker must be written before the service dies.
            Live.note.isNotEmpty() -> Live.stopAfterSample = true
            else -> startService(i.setAction(LoggerService.ACTION_STOP))
        }
    }

    @Composable
    fun Screen() {
        var tick by remember { mutableIntStateOf(0) }
        var marker by remember { mutableStateOf("") }
        var page by remember { mutableStateOf(Page.Front) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val f = File(filesDir, "netscope_poc.csv")
            if (uri != null && f.exists()) contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().copyTo(o) }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(24.dp))
                    Text("NetScope",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    NavigationDrawerItem(
                        label = { Text("Overview") },
                        selected = page == Page.Front,
                        onClick = {
                            page = Page.Front
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("History") },
                        selected = page == Page.History,
                        onClick = {
                            page = Page.History
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("All stats") },
                        selected = page == Page.AllStats,
                        onClick = {
                            page = Page.AllStats
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text("Daily history is kept separately from the 24-hour CSV log.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp))
                }
            },
        ) {
            when (page) {
                Page.AllStats -> AllStatsScreen(
                    tick = tick,
                    onBack = { page = Page.Front },
                    onToggleLogging = { toggleLogging() },
                )
                Page.History -> HistoryScreen(tick = tick, onBack = { page = Page.Front })
                Page.Front -> FrontScreen(
                    tick = tick,
                    marker = marker,
                    onMarkerChange = { marker = it },
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onRequestPermissions = {
                        val ask = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                        perms.launch(ask.toTypedArray())
                    },
                    onOpenUsageAccess = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onExport = { export.launch("netscope_poc.csv") },
                    onMark = {
                        Live.markerCount += 1
                        Live.note = marker.ifBlank { "mark_${Live.markerCount}" }
                        marker = ""
                    },
                )
            }
        }
    }

    @Composable
    private fun FrontScreen(
        tick: Int,
        marker: String,
        onMarkerChange: (String) -> Unit,
        onOpenMenu: () -> Unit,
        onRequestPermissions: () -> Unit,
        onOpenUsageAccess: () -> Unit,
        onExport: () -> Unit,
        onMark: () -> Unit,
    ) {
        val phoneOk = granted(Manifest.permission.READ_PHONE_STATE)
        val usageAccess = usageOk()
        val notifOk = notificationsOk()
        val observed = observedMobile()
        val sessionMobile = Live.nsMobileSession
        val gate = qualityGate(sessionMobile)
        val pss = remember(tick) { pssMb() }
        val heap = remember(tick) { heapMb() }
        val appData = remember(tick) { dirSize(filesDir) + dirSize(cacheDir) }
        val csvBytes = remember(tick) { File(filesDir, "netscope_poc.csv").length() }
        val apkBytes = remember(tick) { apkBytes() }
        var capInput by remember { mutableStateOf(capInputText(cap4gBytes())) }
        var stepInput by remember { mutableStateOf(stepInputText(alertStepBytes())) }
        val cap = cap4gBytes()

        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenMenu) { Text("Menu") }
                Text("NetScope", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(currentNetworkLabel(), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            // Permissions are only shown while something is missing. Once granted
            // they stay out of the way.
            if (!phoneOk || !usageAccess || !notifOk) {
                key(tick) {
                    Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                    if (!phoneOk) Text("Phone state: not granted")
                    if (!notifOk) Text("Notifications: not granted")
                    if (!usageAccess) Text("Usage access: not granted")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!phoneOk || !notifOk) {
                        Button(onClick = onRequestPermissions) { Text("Allow") }
                    }
                    if (!usageAccess) {
                        Button(onClick = onOpenUsageAccess) { Text("Usage access") }
                    }
                }
            }

            Text(
                when {
                    Live.stopAfterSample -> "Logging: stopping after the next sample"
                    Live.running -> "Logging: active"
                    else -> "Logging: stopped (start it from Menu > All stats)"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (Live.stopAfterSample) {
                Text("Marker pending: one more sample will be written before logging stops.",
                    style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text("Current", style = MaterialTheme.typography.titleMedium)
            if (Live.lastSampleAt == 0L) {
                Text("Waiting for first sample")
            } else {
                Text("${Live.cls.uppercase()} on ${transportLabel()}",
                    style = MaterialTheme.typography.titleLarge)
                Text("Speed: Down ${speed(Live.downBps)} / Up ${speed(Live.upBps)}",
                    style = MaterialTheme.typography.titleMedium)
            }

            Text("Session data", style = MaterialTheme.typography.titleMedium)
            key(tick) {
                Text("Mobile total: ${mbOrNa(Live.nsMobileSession)}")
                Text("Wi-Fi total: ${mbOrNa(Live.nsWifiSession)}")
                BucketLine("4G", Live.totals["4g"] ?: 0L, Live.ambiguous["4g"] ?: 0L)
                BucketLine("5G", Live.totals["5g"] ?: 0L, Live.ambiguous["5g"] ?: 0L)
                if (cap > 0L) {
                    Text("4G allowance used: ${mb(Live.capUsed4gBytes)} · " +
                        remainingText(Live.capUsed4gBytes, cap))
                }
            }

            Text("4G tracking", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = capInput,
                onValueChange = { v ->
                    capInput = v
                    val gb = v.toDoubleOrNull()
                    setCap4gBytes(if (gb == null || gb <= 0.0) 0L else (gb * 1073741824.0).toLong())
                },
                label = { Text("4G allowance (GB)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = stepInput,
                onValueChange = { v ->
                    stepInput = v
                    val stepMb = v.toDoubleOrNull()
                    setAlertStepBytes(if (stepMb == null || stepMb <= 0.0) 0L else (stepMb * MB).toLong())
                },
                label = { Text("4G usage alert every (MB)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (cap > 0L) {
                Text("Remaining = allowance - 4G used since the last reset. " +
                    "Usage is remembered across restarts.",
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { resetCapUsage() }) { Text("Reset usage") }
            Text("Set the alert step to 0 to disable usage alerts.",
                style = MaterialTheme.typography.bodySmall)

            if (observed > 0) {
                val coverage = coveragePercent(observed)
                if (coverage != null) {
                    Text("Coverage: ${"%.1f".format(coverage)}% of observed mobile (not accuracy)",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            ReconciliationBlock(observed = observed, sessionMobile = sessionMobile, detailed = false)

            if (Live.ambiguousTotal > gate) {
                Text("Warning: 4G ambiguity +/- ${mb(Live.ambiguousTotal)} exceeds the quality gate " +
                    "(${mb(gate)}). Treat the 4G figure as an upper bound.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (Live.rebaseTotal > 0L) {
                Text("Counter rebase excluded: ${mb(Live.rebaseTotal)}",
                    color = MaterialTheme.colorScheme.error)
            }
            if (Live.resetEvents > 0L) {
                Text("Counter drops (resets): ${Live.resetEvents}")
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = Live.upperBoundTo4g, onCheckedChange = { Live.upperBoundTo4g = it })
                Spacer(Modifier.width(8.dp))
                Text("4G upper bound (unknown/ambiguous -> 4G)")
            }
            Text("Narrow by design: keeps a time-weighted split or a known last mobile class. " +
                "Assigned amounts are shown as +/-.",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("Tools", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = marker,
                onValueChange = onMarkerChange,
                label = { Text("Marker label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = Live.running, onClick = onMark) { Text("Mark next row") }
            if (Live.lastMarker.isNotEmpty()) {
                Text("Last marker: ${Live.lastMarker} at " +
                    "${java.time.Instant.ofEpochMilli(Live.lastMarkerAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()}")
            }
            Button(onClick = onExport) { Text("Export CSV") }
            Text("Open Menu > All stats for raw counters, all buckets, and full reconciliation.",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("App resources", style = MaterialTheme.typography.titleMedium)
            Text("RAM (PSS): ${pss?.let { "%.1f MB".format(it) } ?: "n/a"}   " +
                "Java heap: ${"%.1f MB".format(heap)}")
            Text("App data: ${mb(appData)}   CSV log: ${mb(csvBytes)}")
            Text("APK: ${mb(apkBytes)}")
            Text("PSS is Android's dynamic process-memory estimate and includes shared pages; " +
                "it changes between samples.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun HistoryScreen(tick: Int, onBack: () -> Unit) {
        val revision = HistoryLive.revision
        val days = remember(tick, revision) { HistoryLive.days.values.toList() }
        val today = LocalDate.now()
        val summaries = listOf(
            "Last 7 days" to HistoryMath.lastDays(days, today, 7),
            "Last 30 days" to HistoryMath.lastDays(days, today, 30),
            "This month" to HistoryMath.thisMonth(days, today),
            "All time" to HistoryMath.allTime(days),
        )
        val rows = HistoryFormat.dailyRows(days, today, 30)
        val widths = listOf(104.dp, 72.dp, 72.dp, 82.dp, 82.dp, 82.dp)
        val tableScroll = rememberScrollState()

        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(4.dp))
                Text("History", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Mobile = 4G + 5G + other/unknown. Wi-Fi comes from NetworkStats. " +
                "Daily history is kept indefinitely; the CSV holds 24 hours.",
                style = MaterialTheme.typography.bodySmall)

            Column(
                Modifier
                    .horizontalScroll(tableScroll)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                HistoryTableRow(
                    cells = listOf("Date", "4G", "5G", "Mobile", "Wi-Fi", "Total"),
                    widths = widths,
                    header = true,
                    numericFrom = 1,
                )
                rows.forEach { day ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HistoryTableRow(
                        cells = listOf(
                            HistoryFormat.date(day.date),
                            HistoryFormat.bytes(day.mobile4g),
                            HistoryFormat.bytes(day.mobile5g),
                            HistoryFormat.bytes(day.mobile),
                            HistoryFormat.bytes(day.wifi),
                            HistoryFormat.bytes(day.total),
                        ),
                        widths = widths,
                        header = false,
                        numericFrom = 1,
                    )
                }
                summaries.forEach { (label, totals) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HistoryTableRow(
                        cells = listOf(
                            label,
                            HistoryFormat.bytes(totals.mobile4g),
                            HistoryFormat.bytes(totals.mobile5g),
                            HistoryFormat.bytes(totals.mobile),
                            HistoryFormat.bytes(totals.wifi),
                            HistoryFormat.bytes(totals.total),
                        ),
                        widths = widths,
                        header = false,
                        numericFrom = 1,
                        emphasized = true,
                    )
                }
            }
        }
    }

    @Composable
    private fun HistoryTableRow(
        cells: List<String>,
        widths: List<Dp>,
        header: Boolean,
        numericFrom: Int,
        emphasized: Boolean = false,
    ) {
        Row(
            Modifier
                .background(
                    when {
                        header -> MaterialTheme.colorScheme.surfaceVariant
                        emphasized -> MaterialTheme.colorScheme.surface
                        else -> Color.Transparent
                    },
                )
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { index, value ->
                Text(
                    text = value,
                    modifier = Modifier
                        .width(widths[index])
                        .padding(horizontal = 6.dp),
                    style = when {
                        header -> MaterialTheme.typography.titleSmall
                        emphasized -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodySmall
                    },
                    fontWeight = if (emphasized) FontWeight.SemiBold else null,
                    textAlign = if (index >= numericFrom) TextAlign.End else TextAlign.Start,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun AllStatsScreen(tick: Int, onBack: () -> Unit, onToggleLogging: () -> Unit) {
        val observed = observedMobile()
        val sessionMobile = Live.nsMobileSession
        val gate = qualityGate(sessionMobile)

        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(4.dp))
                Text("All stats", style = MaterialTheme.typography.headlineSmall)
            }

            HorizontalDivider()

            Text("Logging control", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onToggleLogging) {
                Text(
                    when {
                        Live.stopAfterSample -> "Cancel pending stop"
                        Live.running -> "Stop logging"
                        else -> "Start logging"
                    },
                )
            }
            if (Live.stopAfterSample) {
                Text("Marker pending: one more sample will be written before logging stops.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Stop ends the current session and clears its 4G/Wi-Fi totals. " +
                "Daily history is kept.",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            key(tick) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Text("Phone state: ${granted(Manifest.permission.READ_PHONE_STATE)}")
                Text("Notifications: ${notificationState()}")
                Text("Usage access: ${usageOk()}")

                HorizontalDivider()

                Text("Live", style = MaterialTheme.typography.titleMedium)
                Text("Classification: ${Live.cls}   Transport: ${Live.transport}")
                Text("Raw: ${Live.raw}  (13=LTE, 20=NR; override 1=LTE-CA, 3=NR NSA, 4=NR NSA mmWave, 5=NR Advanced)")
                Text("TrafficStats mobile RX/TX: ${mbOrNa(Live.mobRx)} / ${mbOrNa(Live.mobTx)}")
                Text("Speed (all interfaces): Down ${speed(Live.downBps)} / Up ${speed(Live.upBps)}")
                Text("Speed (mobile): Down ${speed(Live.mobileDownBps)} / Up ${speed(Live.mobileUpBps)}")
                Text("Speed (Wi-Fi/other): Down ${speed(Live.wifiDownBps)} / Up ${speed(Live.wifiUpBps)}")
                Text("NetworkStats today: mobile ${mbOrNa(Live.nsMobile)}, Wi-Fi ${mbOrNa(Live.nsWifi)}")
                Text("NetworkStats since logging started: mobile ${mbOrNa(Live.nsMobileSession)}, " +
                    "Wi-Fi ${mbOrNa(Live.nsWifiSession)}")
                Text("Last sample: ${if (Live.lastSampleAt == 0L) "none" else "${(System.currentTimeMillis() - Live.lastSampleAt) / 1000} s ago"}" +
                    "   interval ${Live.lastIntervalMs} ms   max gap ${Live.maxIntervalMs} ms")
                if (Live.maxIntervalMs > 10_000L) {
                    Text("Warning: a sampling gap over 10 s was observed; bytes around it are approximate.",
                        color = MaterialTheme.colorScheme.error)
                }

                HorizontalDivider()

                Text("Classified mobile since logging started", style = MaterialTheme.typography.titleMedium)
                listOf("4g", "5g", "other", "unknown").forEach { bucket ->
                    BucketLine(
                        label = bucket.uppercase(),
                        value = Live.totals[bucket] ?: 0L,
                        ambiguous = Live.ambiguous[bucket] ?: 0L,
                    )
                }
                val cap = cap4gBytes()
                if (cap > 0L) {
                    Text("4G allowance used: ${mb(Live.capUsed4gBytes)}")
                    Text("4G remaining: ${remainingText(Live.capUsed4gBytes, cap)}")
                }
                Text("4G alert step: ${compactBytes(alertStepBytes())}")
                Text("Wi-Fi since logging started (not generation-classified): ${mbOrNa(Live.nsWifiSession)}")

                if (observed > 0) {
                    val coverage = coveragePercent(observed)
                    if (coverage != null) {
                        Text("Coverage of observed mobile (4G+5G): ${"%.1f".format(coverage)}% " +
                            "(this is not accuracy)")
                    }
                }
                if (Live.rebaseTotal > 0L) {
                    Text("Excluded as counter rebase: ${mb(Live.rebaseTotal)}")
                    Text("A counter rebase was detected. It is excluded from every bucket; 4G/5G totals stay clean.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (Live.resetEvents > 0L) {
                    Text("Counter drops (resets): ${Live.resetEvents}")
                }

                ReconciliationBlock(observed = observed, sessionMobile = sessionMobile, detailed = true)

                if (Live.ambiguousTotal > gate) {
                    Text("Warning: 4G ambiguity +/- ${mb(Live.ambiguousTotal)} exceeds the quality gate " +
                        "(${mb(gate)}). Treat the 4G figure as an upper bound.",
                        color = MaterialTheme.colorScheme.error)
                } else if (Live.ambiguousTotal > 0L) {
                    Text("4G ambiguity: +/- ${mb(Live.ambiguousTotal)} (within the quality gate)")
                }

                Text("Estimates only: only mobile traffic is classified. LTE-CA stays 4G. Transitions are split by " +
                    "time spent in each state; unresolved mixed intervals go to 4G and are reported as +/-. " +
                    "Counter resets and rebases are excluded, never counted as traffic.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun BucketLine(label: String, value: Long, ambiguous: Long) {
        Text(
            if (ambiguous > 0L) "$label: ${mb(value)} (+/- ${mb(ambiguous)})"
            else "$label: ${mb(value)}",
        )
    }

    @Composable
    private fun ReconciliationBlock(observed: Long, sessionMobile: Long, detailed: Boolean) {
        if (sessionMobile <= 0L || observed <= 0L) {
            if (detailed) Text("Reconciliation: waiting for NetworkStats data")
            return
        }

        val diff = sessionMobile - observed
        val absDiff = kotlin.math.abs(diff)
        val percent = 100.0 * diff / sessionMobile
        val showPercent = sessionMobile >= 10L * MB

        if (detailed) {
            Text("Reconciliation: absolute difference ${mb(absDiff)}",
                style = MaterialTheme.typography.titleSmall)
            Text("System mobile ${mb(sessionMobile)}; observed mobile ${mb(observed)}")
            if (showPercent) Text("${"%.2f".format(percent)}% of system mobile")
        } else {
            Text("Reconciliation: ${mb(absDiff)} difference" +
                if (showPercent) " (${"%.2f".format(percent)}%)" else "")
        }

        val limit = maxOf(50L * MB, sessionMobile / 20)
        val observedExcess = observed - sessionMobile
        if (observedExcess > limit) {
            Text("Warning: observed mobile exceeds system mobile by ${mb(observedExcess)}; " +
                "session classification is unreliable.", color = MaterialTheme.colorScheme.error)
        } else if (absDiff > limit) {
            Text("Warning: observed mobile differs from system mobile by more than 5% or 50 MB; " +
                "session classification is unreliable.", color = MaterialTheme.colorScheme.error)
        }
    }
}
