package com.kaori.codex.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Process
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Handles broadcasts after the Manifest DUMP permission gate accepts the ADB shell caller. */
class CodexBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val requestId = validRequestId(intent.getStringExtra("requestId"))
            ?: UUID.randomUUID().toString()
        val timeoutMillis = intent.getLongExtra("timeoutMs", REQUEST_TIMEOUT_MS)
            .coerceIn(MIN_REQUEST_TIMEOUT_MS, REQUEST_TIMEOUT_MS)
        runCatching {
            CodexRequestStore.markPending(
                context,
                requestId,
                intent.getStringExtra("tool") ?: intent.getStringExtra("op") ?: "unknown",
            )
        }
        val task = FutureTask<JSONObject> { dispatch(context, intent, requestId) }
        val dispatchThread = Thread(task, "CodexBridgeDispatch")
        dispatchThread.isDaemon = true
        dispatchThread.start()
        val worker = Thread({
            val startedAt = SystemClock.elapsedRealtime()
            val response = try {
                task.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                task.cancel(true)
                error("Bridge request timed out after " + timeoutMillis + "ms")
                    .put("status", "timeout")
            } catch (_: CancellationException) {
                error("Bridge request was cancelled").put("status", "cancelled")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                task.cancel(true)
                error("Bridge request wait was interrupted").put("status", "cancelled")
            } catch (execution: ExecutionException) {
                Log.e(TAG, "Bridge request failed", execution.cause)
                error(execution.cause?.message ?: "Bridge request failed")
            } catch (exception: Exception) {
                Log.e(TAG, "Bridge request failed", exception)
                error(exception.message ?: exception.javaClass.simpleName)
            }
            val status = when {
                response.optString("status").isNotEmpty() -> response.optString("status")
                response.optBoolean("pending", false) -> "pending"
                response.optBoolean("ok", false) -> "ok"
                else -> "error"
            }
            response.put("requestId", requestId)
                .put("status", status)
                .put("durationMs", SystemClock.elapsedRealtime() - startedAt)
            runCatching { CodexRequestStore.complete(context, requestId, response) }
            CodexHealth.record(
                intent.getStringExtra("tool") ?: intent.getStringExtra("op") ?: "unknown",
                response,
            )
            try {
                pendingResult.setResultCode(if (response.optBoolean("ok", false)) 0 else 1)
                pendingResult.setResultData(encoded(response))
            } finally {
                pendingResult.finish()
            }
        }, "CodexBridge")
        worker.isDaemon = true
        worker.start()
    }

    private fun dispatch(context: Context, intent: Intent, requestId: String): JSONObject {
        return when (intent.getStringExtra("op")) {
            "ping" -> ping()
            "tools" -> tools()
            "trace" -> trace(intent)
            "tool" -> dispatchTool(context, intent, requestId)
            else -> error("Missing or unsupported bridge operation")
        }
    }

    private fun dispatchTool(context: Context, intent: Intent, requestId: String): JSONObject {
        val tool = intent.getStringExtra("tool")
            ?: return error("Missing tool name")
        val args = decodeArguments(intent)
        return when (tool) {
            "bridge.health" -> CodexHealth.snapshot(context)
            "bridge.result" -> CodexRequestStore.result(context, args)
            "device.info" -> deviceInfo(context)
            "device.battery" -> deviceBattery(context)
            "adb.status" -> AdbServerClient.status()
            "adb.shell" -> AdbServerClient.shell(context, args)
            "adb.result_chunk" -> CodexResultStore.readChunk(context, args)
            "file.read_chunk" -> CodexFileStore.readChunk(context, args)
            "app.launch" -> launchApp(context, args)
            "app.uninstall" -> uninstallApp(context, args)
            "file.read" -> CodexFileStore.read(context, args)
            "file.write" -> CodexFileStore.write(context, args)
            "file.write_begin" -> CodexFileTransferStore.begin(context, args)
            "file.write_chunk" -> CodexFileTransferStore.writeChunk(context, args)
            "file.write_commit" -> CodexFileTransferStore.commit(context, args)
            "file.write_cancel" -> CodexFileTransferStore.cancel(args)
            "file.delete" -> CodexFileStore.delete(context, args)
            "file.restore" -> CodexFileStore.restore(context, args)
            "file.commit" -> CodexFileStore.commit(context, args)
            "file.pick" -> CodexFileOperations.startPicker(context, args, requestId)
            "file.save" -> CodexFileOperations.startSaver(context, args, requestId)
            "file.result" -> CodexFileOperations.result(context, args)
            "file.cancel" -> CodexFileOperations.cancel(context, args)
            "file.import_selected" -> CodexFileOperations.importSelected(context, args)
            "file.export_media" -> CodexFileOperations.exportMedia(context, args)
            "shizuku.status" -> ShizukuSupport.status(context)
            "shizuku.request_permission" -> ShizukuSupport.requestPermission(context)
            "shizuku.shell" -> ShizukuSupport.shell(context, args)
            "ui.dump" -> accessibility()?.dumpUi() ?: accessibilityUnavailable()
            "ui.click" -> accessibility()?.click(args) ?: accessibilityUnavailable()
            "ui.tap" -> accessibility()?.tap(args) ?: accessibilityUnavailable("tap")
            "ui.double_tap" -> accessibility()?.doubleTap(args) ?: accessibilityUnavailable("double_tap")
            "ui.multi_gesture" -> accessibility()?.multiGesture(args) ?: accessibilityUnavailable("multi_gesture")
            "ui.template_match" -> accessibility()?.templateMatch(args) ?: accessibilityUnavailable("template_match")
            "ui.template_tap" -> accessibility()?.templateTap(args) ?: accessibilityUnavailable("template_tap")
            "ui.long_press" -> accessibility()?.longPress(args) ?: accessibilityUnavailable("long_press")
            "ui.ocr" -> accessibility()?.ocr(args) ?: accessibilityUnavailable("ocr")
            "ui.input_text" -> accessibility()?.inputText(args) ?: accessibilityUnavailable()
            "ui.scroll" -> accessibility()?.scroll(args) ?: accessibilityUnavailable()
            "ui.swipe" -> accessibility()?.swipe(args) ?: accessibilityUnavailable("swipe")
            "ui.wait" -> accessibility()?.waitFor(args) ?: accessibilityUnavailable()
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
        val received = !decodeBase64(intent.getStringExtra("message64")).isNullOrEmpty()
        return JSONObject()
            .put("ok", true)
            .put("trace", "recorded")
            .put("messageReceived", received)
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
            val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = packageManager.queryIntentActivities(
                launcherQuery,
                PackageManager.MATCH_DEFAULT_ONLY,
            ).firstOrNull {
                packageManager.getApplicationLabel(it.activityInfo.applicationInfo)
                    .toString().equals(requested, ignoreCase = true)
            }
            packageName = match?.activityInfo?.packageName ?: return error("App not found: $requested")
            launchIntent = match.let {
                Intent(launcherQuery).setClassName(it.activityInfo.packageName, it.activityInfo.name)
            }
        }
        val intent = launchIntent ?: return error("App has no launch Activity: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("package", packageName)
    }

    private fun uninstallApp(context: Context, args: Map<String, String>): JSONObject {
        val packageName = args["package"]?.takeIf { it.matches(PACKAGE_NAME) }
            ?: return error("Missing or invalid package argument")
        if (packageName == context.packageName) {
            return error("Refusing to request self-uninstall")
        }
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("userConfirmationRequired", true)
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

    private fun accessibilityUnavailable(action: String? = null): JSONObject {
        val response = error("Accessibility service is not connected; enable Codex in Android Accessibility settings")
            .put("executed", false)
            .put("verified", false)
            .put("before", JSONObject.NULL)
            .put("after", JSONObject.NULL)
            .put("visualEvidenceAvailable", false)
        return action?.let { response.put("action", it) } ?: response
    }
    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun validRequestId(value: String?): String? {
        return value?.takeIf { it.length in 1..96 && it.matches(Regex("[A-Za-z0-9._-]+")) }
    }

    private object AccessibilityAction {
        const val BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        const val HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
    }

    private companion object {
        const val TAG = "CodexBridge"
        const val MIN_REQUEST_TIMEOUT_MS = 1000L
        const val REQUEST_TIMEOUT_MS = 8000L
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val TOOL_NAMES = listOf(
            "bridge.health",
            "bridge.result",
            "device.info",
            "device.battery",
            "adb.status",
            "adb.shell",
            "adb.result_chunk",
            "app.launch",
            "app.uninstall",
            "file.read",
            "file.read_chunk",
            "file.write",
            "file.write_begin",
            "file.write_chunk",
            "file.write_commit",
            "file.write_cancel",
            "file.delete",
            "file.restore",
            "file.commit",
            "file.pick",
            "file.save",
            "file.result",
            "file.cancel",
            "file.import_selected",
            "file.export_media",
            "shizuku.status",
            "shizuku.request_permission",
            "shizuku.shell",
            "ui.dump",
            "ui.click",
            "ui.tap",
            "ui.double_tap",
            "ui.multi_gesture",
            "ui.template_match",
            "ui.template_tap",
            "ui.long_press",
            "ui.ocr",
            "ui.input_text",
            "ui.scroll",
            "ui.swipe",
            "ui.wait",
            "ui.back",
            "ui.home",
        )
    }
}
