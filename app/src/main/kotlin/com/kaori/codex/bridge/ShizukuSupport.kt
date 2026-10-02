package com.kaori.codex.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks Shizuku state and runs explicitly confirmed commands through its UserService. */
object ShizukuSupport {
    /** Returns installation, service, binder, and authorization state without requesting access. */
    fun status(context: Context): JSONObject {
        val result = JSONObject()
            .put("ok", false)
            .put("package", SHIZUKU_PACKAGE)
            .put("installed", isInstalled(context))
            .put("serviceRunning", false)
            .put("binderAlive", false)
            .put("permissionGranted", false)
        if (!result.optBoolean("installed")) {
            return result.put("error", "Shizuku is not installed")
        }
        registerListeners()
        if (!Shizuku.pingBinder()) {
            return result.put("error", "Shizuku service is not running; start Shizuku and retry")
        }
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.isBinderAlive) {
            return result
                .put("serviceRunning", true)
                .put("error", "Shizuku binder is unavailable or dead; retry after Shizuku reconnects")
        }
        result.put("serviceRunning", true).put("binderAlive", true)
        return try {
            if (Shizuku.isPreV11()) {
                result.put("error", "This Shizuku service version is unsupported")
            } else {
                val granted = Build.VERSION.SDK_INT >= 23 &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                result.put("permissionGranted", granted)
                if (!granted) {
                    result.put(
                        "error",
                        if (Shizuku.shouldShowRequestPermissionRationale()) {
                            "Shizuku access was denied; re-authorize Codex in Shizuku"
                        } else {
                            "Shizuku access is not authorized; request permission and retry"
                        },
                    )
                } else {
                    result.put("ok", true).put("uid", Shizuku.getUid())
                }
                result
            }
        } catch (exception: Exception) {
            result.put("error", exception.message ?: exception.javaClass.simpleName)
        }
    }

    /** Requests the Shizuku permission after confirming that the service is available. */
    fun requestPermission(context: Context): JSONObject {
        val current = status(context)
        if (!current.optBoolean("installed")) return current
        if (!current.optBoolean("serviceRunning") || !current.optBoolean("binderAlive")) return current
        if (current.optBoolean("permissionGranted")) {
            return current.put("requested", false)
        }
        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                current.put("error", "Re-authorize Codex in Shizuku before retrying").put("reauthorizationRequired", true)
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                JSONObject()
                    .put("ok", true)
                    .put("requested", true)
                    .put("message", "Shizuku authorization request sent; approve Codex in Shizuku, then retry")
            }
        } catch (exception: Exception) {
            current.put("error", exception.message ?: exception.javaClass.simpleName)
        }
    }

    /** Runs a shell command only when the caller supplies the explicit confirmation flag. */
    fun shell(context: Context, args: Map<String, String>): JSONObject {
        if (args["confirmed"] != "true") {
            return JSONObject().put("ok", false).put("error", "Shizuku shell requires confirmed=true")
        }
        val command = args["command"]?.takeIf { it.isNotBlank() }
            ?: return JSONObject().put("ok", false).put("error", "Missing command argument")
        val state = status(context)
        if (!state.optBoolean("ok")) return state

        val userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context, com.kaori.codex.shizuku.CodexShizukuUserService::class.java),
        ).daemon(false).tag(USER_SERVICE_TAG).version(USER_SERVICE_VERSION)
        val connected = CountDownLatch(1)
        var remote: com.kaori.codex.shizuku.IShizukuCommandService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = com.kaori.codex.shizuku.IShizukuCommandService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connected.countDown()
            }
        }
        return try {
            Shizuku.bindUserService(userServiceArgs, connection)
            if (!connected.await(USER_SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                JSONObject().put("ok", false).put("error", "Shizuku UserService did not connect")
            } else {
                val service = remote ?: return JSONObject().put("ok", false).put("error", "Shizuku UserService binder is unavailable")
                JSONObject(service.execute(command))
            }
        } catch (exception: Exception) {
            JSONObject().put("ok", false).put("error", exception.message ?: exception.javaClass.simpleName)
        } finally {
            try {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            } catch (unbindFailure: Exception) {
                // The service may already have died; the command result remains authoritative.
            }
        }
    }

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (notFound: PackageManager.NameNotFoundException) {
        false
    }

    private fun registerListeners() {
        synchronized(this) {
            if (listenersRegistered) return
            Shizuku.addBinderReceivedListener { }
            Shizuku.addBinderDeadListener { }
            listenersRegistered = true
        }
    }

    private var listenersRegistered = false

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 2002
    private const val USER_SERVICE_TAG = "codex-command"
    private const val USER_SERVICE_VERSION = 1
    private const val USER_SERVICE_TIMEOUT_MS = 5000L
}
