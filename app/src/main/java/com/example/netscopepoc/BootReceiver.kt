package com.example.netscopepoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts logging after a reboot. The persisted session is restored by the
 * service itself, so daily history and the current session continue.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, LoggerService::class.java)
        runCatching { context.startForegroundService(service) }
    }
}
