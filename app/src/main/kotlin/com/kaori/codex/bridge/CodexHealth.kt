package com.kaori.codex.bridge

import android.content.Context
import org.json.JSONObject

/** Aggregates bridge readiness and the most recent RPC outcome for diagnostics. */
object CodexHealth {
    private var lastOperation = "none"
    private var lastError: String? = null
    private var lastAt: Long? = null

    /** Records the operation summary without retaining command arguments or file contents. */
    fun record(operation: String, response: JSONObject) {
        synchronized(this) {
            lastOperation = operation
            lastError = response.optString("error").takeIf { it.isNotEmpty() }
            lastAt = System.currentTimeMillis()
        }
    }

    /** Returns current subsystem state and the most recent operation summary. */
    fun snapshot(context: Context): JSONObject {
        val adb = AdbServerClient.status()
        val shizuku = ShizukuSupport.status(context)
        val accessibilityConnected = CodexAccessibilityService.connected() != null
        val result = synchronized(this) {
            JSONObject()
                .put("ok", true)
                .put("accessibility", JSONObject().put("connected", accessibilityConnected))
                .put("adb", JSONObject().put("serverReachable", adb.optBoolean("ok")).put("status", adb))
                .put("shizuku", shizuku)
                .put("foregroundService", JSONObject().put("running", CodexForegroundService.isRunning()))
                .put("lastOperation", lastOperation)
                .put("lastError", lastError ?: JSONObject.NULL)
                .put("lastAt", lastAt ?: JSONObject.NULL)
        }
        return result
    }
}
