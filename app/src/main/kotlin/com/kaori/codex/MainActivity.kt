package com.kaori.codex

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kaori.codex.bridge.CodexForegroundService

/** Coordinates one-time Android permission and service initialization. */
class MainActivity : Activity() {
    private var accessibilityPrompted = false
    private var notificationRequestInFlight = false
    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBridgeService()
    }

    override fun onResume() {
        super.onResume()
        if (!notificationRequestInFlight && !initialized) {
            continueInitialization()
        }
    }

    private fun continueInitialization() {
        if (!isAccessibilityEnabled()) {
            if (!accessibilityPrompted) {
                accessibilityPrompted = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
            return
        }

        initialized = true
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expectedNames = setOf(
            "$packageName/.bridge.CodexAccessibilityService",
            "$packageName/com.kaori.codex.bridge.CodexAccessibilityService",
        )
        return enabledServices.split(':').any { service ->
            expectedNames.any { it.equals(service, ignoreCase = true) }
        }
    }

    private fun startBridgeService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, CodexForegroundService::class.java))
        } catch (_: IllegalStateException) {
            // Android may reject a background start after a delayed boot event.
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST_CODE) {
            notificationRequestInFlight = false
            initialized = true
            finish()
        }
    }

    private companion object {
        const val NOTIFICATION_REQUEST_CODE = 1001
    }
}
