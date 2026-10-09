package com.kaori.codex.bridge

import android.graphics.Rect
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** Validates normalized OCR regions and converts pixel boxes to normalized bounds. */
object CodexOcrGeometry {
    /** Parses a normalized OCR region, defaulting omitted edges to the full display. */
    fun region(args: Map<String, String>): NormalizedRegion {
        val left = normalized(args["left"] ?: args["regionLeft"], "left", 0f)
        val top = normalized(args["top"] ?: args["regionTop"], "top", 0f)
        val right = normalized(args["right"] ?: args["regionRight"], "right", 1f)
        val bottom = normalized(args["bottom"] ?: args["regionBottom"], "bottom", 1f)
        require(left < right) { "OCR region left must be less than right" }
        require(top < bottom) { "OCR region top must be less than bottom" }
        return NormalizedRegion(left, top, right, bottom)
    }
    /** Converts a normalized display region into a non-empty pixel crop. */
    fun pixelRect(region: NormalizedRegion, width: Int, height: Int): Rect {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        val left = floor(region.left * width).toInt().coerceIn(0, width - 1)
        val top = floor(region.top * height).toInt().coerceIn(0, height - 1)
        val right = ceil(region.right * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(region.bottom * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    /** Returns whether a pixel bounding box intersects a normalized OCR region. */
    fun intersects(
        bounds: Rect,
        width: Int,
        height: Int,
        region: NormalizedRegion,
    ): Boolean {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        return bounds.right.toFloat() > region.left * width &&
            bounds.left.toFloat() < region.right * width &&
            bounds.bottom.toFloat() > region.top * height &&
            bounds.top.toFloat() < region.bottom * height
    }

    /** Converts a pixel bounding box into normalized coordinates clipped to the display. */
    fun normalizedBounds(bounds: Rect, width: Int, height: Int): JSONObject {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        return JSONObject()
            .put("left", (bounds.left.toFloat() / width).coerceIn(0f, 1f))
            .put("top", (bounds.top.toFloat() / height).coerceIn(0f, 1f))
            .put("right", (bounds.right.toFloat() / width).coerceIn(0f, 1f))
            .put("bottom", (bounds.bottom.toFloat() / height).coerceIn(0f, 1f))
    }
    /** Maps a box in a cropped and optionally scaled image back to display coordinates. */
    fun normalizedBounds(
        bounds: Rect,
        sourceRect: Rect,
        scale: Float,
        width: Int,
        height: Int,
    ): JSONObject {
        require(scale.isFinite() && scale > 0f) { "OCR analysis scale must be positive" }
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        return JSONObject()
            .put("left", ((sourceRect.left + bounds.left / scale) / width).coerceIn(0f, 1f))
            .put("top", ((sourceRect.top + bounds.top / scale) / height).coerceIn(0f, 1f))
            .put("right", ((sourceRect.left + bounds.right / scale) / width).coerceIn(0f, 1f))
            .put("bottom", ((sourceRect.top + bounds.bottom / scale) / height).coerceIn(0f, 1f))
    }

    private fun normalized(value: String?, name: String, defaultValue: Float): Float {
        val result = if (value == null) {
            defaultValue
        } else {
            value.toFloatOrNull()
                ?: throw IllegalArgumentException("Invalid OCR " + name + " coordinate")
        }
        require(result.isFinite() && result in 0f..1f) {
            name + " must be finite and between 0.0 and 1.0"
        }
        return result
    }
}

/** A normalized display region used to filter OCR text blocks. */
data class NormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

