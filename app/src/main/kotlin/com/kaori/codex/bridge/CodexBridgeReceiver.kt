package com.kaori.codex.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/** Handles the shell-only, DUMP-protected Codex bridge broadcast protocol. */
class CodexBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.checkCallingPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            setResultCode(1)
            setResultData(encoded(error("Caller lacks android.permission.DUMP")))
            return
        }

        val response = try {
            dispatch(context, intent)
        } catch (exception: Exception) {
            Log.e(TAG, "Bridge request failed", exception)
            error(exception.message ?: exception.javaClass.simpleName)
        }
        setResultCode(if (response.optBoolean("ok", false)) 0 else 1)
        setResultData(encoded(response))
    }

    private fun dispatch(context: Context, intent: Intent): JSONObject {
        return when (intent.getStringExtra("op")) {
            "ping" -> ping()
            "tools" -> tools()
            "trace" -> trace(intent)
            "tool" -> dispatchTool(context, intent)
            else -> error("Missing or unsupported bridge operation")
        }
    }

    private fun dispatchTool(context: Context, intent: Intent): JSONObject {
        val tool = intent.getStringExtra("tool")
            ?: return error("Missing tool name")
        val args = decodeArguments(intent)
        return when (tool) {
            "device.info" -> deviceInfo(context)
            "device.battery" -> deviceBattery(context)
            "app.launch" -> launchApp(context, args)
            "ui.dump" -> accessibility()?.dumpUi() ?: accessibilityUnavailable()
            "ui.click" -> accessibility()?.click(args) ?: accessibilityUnavailable()
            "ui.input_text" -> accessibility()?.inputText(args) ?: accessibilityUnavailable()
            "ui.back" -> accessibility()?.globalAction(
                AccessibilityAction.BACK,
                "back",
            ) ?: accessibilityUnavailable()
            "ui.home" -> accessibility()?.globalAction(
                AccessibilityAction.HOME,
                "home",
            ) ?: accessibilityUnavailable()
            else -> error("Unsupported tool: $tool")
        }
    }

    private fun ping(): JSONObject = JSONObject()
        .put("ok", true)
        .put("bridge", "codex")
        .put("package", "com.kaori.codex")
        .put("uid", Process.myUid())
        .put("accessibilityConnected", accessibility() != null)

    private fun tools(): JSONObject {
        val names = JSONArray()
        TOOL_NAMES.forEach(names::put)
        return JSONObject().put("ok", true).put("tools", names)
    }

    private fun trace(intent: Intent): JSONObject {
        val message = decodeBase64(intent.getStringExtra("message64"))
        if (!message.isNullOrEmpty()) {
            Log.i(TAG, message)
        }
        return JSONObject().put("ok", true).put("trace", "recorded")
    }

    private fun deviceInfo(context: Context): JSONObject {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val service = accessibility()
        return JSONObject()
            .put("ok", true)
            .put("package", "com.kaori.codex")
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("width", metrics.widthPixels)
            .put("height", metrics.heightPixels)
            .put("density", metrics.density)
            .put("rotation", rotation)
            .put("foregroundPackage", service?.rootInActiveWindow?.packageName?.toString() ?: JSONObject.NULL)
            .put("accessibilityConnected", service != null)
    }

    private fun deviceBattery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return error("Battery information unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val result = JSONObject()
            .put("ok", true)
            .put("level", level)
            .put("scale", scale)
            .put("percent", if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL)
            .put("status", status)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged)
        if (temperature != Int.MIN_VALUE) {
            result.put("temperatureC", temperature / 10.0)
        }
        return result
    }

    private fun launchApp(context: Context, args: Map<String, String>): JSONObject {
        val requested = args["app"] ?: return error("Missing app argument")
        val packageManager = context.packageManager
        var packageName = requested
        var launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            val application = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .firstOrNull {
                    packageManager.getApplicationLabel(it).toString().equals(requested, ignoreCase = true)
                }
            packageName = application?.packageName ?: return error("App not found: $requested")
            launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        }
        val intent = launchIntent ?: return error("App has no launch Activity: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("package", packageName)
    }

    private fun decodeArguments(intent: Intent): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val extras: Bundle = intent.extras ?: return result
        for (key in extras.keySet()) {
            if (!key.startsWith("arg64.")) {
                continue
            }
            val name = key.removePrefix("arg64.")
            val value = extras.getString(key) ?: continue
            result[name] = decodeBase64(value) ?: throw IllegalArgumentException("Invalid base64 argument: $name")
        }
        return result
    }

    private fun decodeBase64(value: String?): String? {
        if (value.isNullOrEmpty()) {
            return null
        }
        return String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun encoded(response: JSONObject): String =
        Base64.encodeToString(response.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun accessibility(): CodexAccessibilityService? = CodexAccessibilityService.connected()

    private fun accessibilityUnavailable(): JSONObject =
        error("Accessibility service is not connected; enable Codex in Android Accessibility settings")

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private object AccessibilityAction {
        const val BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        const val HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
    }

    private companion object {
        const val TAG = "CodexBridge"
        val TOOL_NAMES = listOf(
            "device.info",
            "device.battery",
            "app.launch",
            "ui.dump",
            "ui.click",
            "ui.input_text",
            "ui.back",
            "ui.home",
        )
    }
}
