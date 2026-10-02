package com.kaori.codex.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kaori.codex.bridge.CodexForegroundService

/** Restarts the bridge service after a normal device boot when Android permits it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, CodexForegroundService::class.java))
        } catch (_: IllegalStateException) {
            // Android can reject a foreground-service start from a restricted boot state.
        }
    }
}
