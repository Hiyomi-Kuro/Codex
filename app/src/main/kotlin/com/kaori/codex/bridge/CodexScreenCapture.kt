package com.kaori.codex.bridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Captures one Accessibility screenshot into a short-lived software bitmap. */
internal object CodexScreenCapture {
    /** Captures the display without persisting or returning its pixels. */
    fun capture(service: CodexAccessibilityService): CapturedScreenFrame {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Screen capture requires Android 11 or newer"
        }
        val before = service.displayGeometry()
        val completed = CountDownLatch(1)
        var screenshot: AccessibilityService.ScreenshotResult? = null
        var failureCode: Int? = null
        val request = Runnable {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        screenshot = result
                        completed.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failureCode = errorCode
                        completed.countDown()
                    }
                },
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            request.run()
        } else if (!mainHandler.post(request)) {
            throw IllegalStateException("Accessibility main thread is unavailable")
        }
        if (!completed.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("Screen capture timed out")
        }
        failureCode?.let { throw IllegalStateException("Screen capture failed: " + it) }
        val result = screenshot ?: throw IllegalStateException("Screen capture returned no frame")
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        if (hardwareBitmap == null) {
            result.hardwareBuffer.close()
            throw IllegalStateException("Screen capture returned an unusable frame")
        }
        val bitmap = try {
            hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Screen capture could not create an analysis bitmap")
        } finally {
            hardwareBitmap.recycle()
            result.hardwareBuffer.close()
        }
        val after = service.displayGeometry()
        require(before.sameViewport(after)) {
            "Display changed while capturing the screen frame"
        }
        return CapturedScreenFrame(bitmap, before)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val SCREENSHOT_TIMEOUT_MS = 3000L
}

/** A screenshot bitmap and the display coordinate space from which it came. */
internal data class CapturedScreenFrame(
    val bitmap: Bitmap,
    val geometry: DisplayGeometry,
)
