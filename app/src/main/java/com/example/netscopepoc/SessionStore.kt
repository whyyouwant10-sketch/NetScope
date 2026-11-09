package com.example.netscopepoc

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistent snapshot of the current logging session. The service saves this
 * periodically and restores it after a restart, so totals survive process death
 * and system-initiated service restarts. An explicit user Stop clears it.
 */
data class SessionSnapshot(
    val totals: Map<String, Long> = emptyMap(),
    val ambiguous: Map<String, Long> = emptyMap(),
    val ambiguousTotal: Long = 0L,
    val rebaseTotal: Long = 0L,
    val resetEvents: Long = 0L,
    val nsMobileSession: Long = -1L,
    val nsWifiSession: Long = -1L,
    val maxIntervalMs: Long = 0L,
    val lastMarker: String = "",
    val lastMarkerAt: Long = 0L,
    val markerCount: Int = 0,
    val nsPrevMobile: Long = -1L,
    val nsPrevWifi: Long = -1L,
    val lastMilestone: Long = 0L,
    val lastAlertStep: Long = 0L,
)

object SessionStore {
    private const val FILE_NAME = "session.json"
    private const val VERSION = 1

    fun load(context: Context): SessionSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            SessionSnapshot(
                totals = readLongMap(root.optJSONObject("totals")),
                ambiguous = readLongMap(root.optJSONObject("ambiguous")),
                ambiguousTotal = root.optLong("ambiguousTotal"),
                rebaseTotal = root.optLong("rebaseTotal"),
                resetEvents = root.optLong("resetEvents"),
                nsMobileSession = root.optLong("nsMobileSession", -1L),
                nsWifiSession = root.optLong("nsWifiSession", -1L),
                maxIntervalMs = root.optLong("maxIntervalMs"),
                lastMarker = root.optString("lastMarker"),
                lastMarkerAt = root.optLong("lastMarkerAt"),
                markerCount = root.optInt("markerCount"),
                nsPrevMobile = root.optLong("nsPrevMobile", -1L),
                nsPrevWifi = root.optLong("nsPrevWifi", -1L),
                lastMilestone = root.optLong("lastMilestone"),
                lastAlertStep = root.optLong("lastAlertStep"),
            )
        }.getOrNull()
    }

    fun save(context: Context, snapshot: SessionSnapshot) {
        runCatching {
            val root = JSONObject()
                .put("version", VERSION)
                .put("totals", longMapToJson(snapshot.totals))
                .put("ambiguous", longMapToJson(snapshot.ambiguous))
                .put("ambiguousTotal", snapshot.ambiguousTotal)
                .put("rebaseTotal", snapshot.rebaseTotal)
                .put("resetEvents", snapshot.resetEvents)
                .put("nsMobileSession", snapshot.nsMobileSession)
                .put("nsWifiSession", snapshot.nsWifiSession)
                .put("maxIntervalMs", snapshot.maxIntervalMs)
                .put("lastMarker", snapshot.lastMarker)
                .put("lastMarkerAt", snapshot.lastMarkerAt)
                .put("markerCount", snapshot.markerCount)
                .put("nsPrevMobile", snapshot.nsPrevMobile)
                .put("nsPrevWifi", snapshot.nsPrevWifi)
                .put("lastMilestone", snapshot.lastMilestone)
                .put("lastAlertStep", snapshot.lastAlertStep)
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, "$FILE_NAME.tmp").delete()
        }
    }

    private fun longMapToJson(map: Map<String, Long>): JSONObject =
        JSONObject().apply { map.forEach { (key, value) -> put(key, value) } }

    private fun readLongMap(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val out = mutableMapOf<String, Long>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = o.optLong(key)
        }
        return out
    }
}
