package com.kaori.codex.bridge

import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager

/** Describes the display and foreground window coordinate space used by one OCR frame. */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val windowRight: Int,
    val windowBottom: Int,
    val packageName: String?,
) {
    init {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        require(rotation in 0..3) { "Display rotation must be 0, 1, 2, or 3" }
        require(windowLeft in 0..width && windowTop in 0..height) {
            "Window origin must be inside the display"
        }
        require(windowRight in windowLeft..width && windowBottom in windowTop..height) {
            "Window bounds must be inside the display"
        }
    }
    /** Returns the width of the foreground window in display pixels. */
    fun windowWidth(): Int = windowRight - windowLeft

    /** Returns the height of the foreground window in display pixels. */
    fun windowHeight(): Int = windowBottom - windowTop

    /** Compares every coordinate-space value that can invalidate normalized coordinates. */
    fun sameViewport(other: DisplayGeometry): Boolean =
        width == other.width &&
            height == other.height &&
            rotation == other.rotation &&
            windowLeft == other.windowLeft &&
            windowTop == other.windowTop &&
            windowRight == other.windowRight &&
            windowBottom == other.windowBottom &&
            packageName == other.packageName
}

/** Reads the current display and foreground accessibility-window geometry on the main thread. */
object CodexDisplayGeometry {
    /** Reads current rotated display dimensions, insets, and foreground package. */
    fun read(service: CodexAccessibilityService): DisplayGeometry {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (service.getSystemService(WindowManager::class.java) as? WindowManager)?.defaultDisplay
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val rotation = display?.rotation ?: 0
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "Display dimensions are unavailable" }
        val root = service.rootInActiveWindow
        val rootBounds = Rect()
        root?.getBoundsInScreen(rootBounds)
        val windowLeft = if (rootBounds.isEmpty) 0 else rootBounds.left.coerceIn(0, width)
        val windowTop = if (rootBounds.isEmpty) 0 else rootBounds.top.coerceIn(0, height)
        val windowRight = if (rootBounds.isEmpty) width else rootBounds.right.coerceIn(windowLeft, width)
        val windowBottom = if (rootBounds.isEmpty) height else rootBounds.bottom.coerceIn(windowTop, height)
        return DisplayGeometry(
            width = width,
            height = height,
            rotation = rotation,
            windowLeft = windowLeft,
            windowTop = windowTop,
            windowRight = windowRight,
            windowBottom = windowBottom,
            packageName = root?.packageName?.toString(),
        )
    }
}

