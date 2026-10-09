package com.kaori.codex.bridge

/** Validates normalized gesture inputs against the current rotated display dimensions. */
object CodexGestureGeometry {
    /** Converts normalized coordinates using the current display's rotated pixel space. */
    fun point(
        xText: String?,
        yText: String?,
        width: Int,
        height: Int,
        rotation: Int,
    ): GesturePoint = point(
        xText,
        yText,
        DisplayGeometry(width, height, rotation, 0, 0, width, height, null),
        "display",
    )

    /** Converts normalized coordinates using an OCR-frame-compatible display geometry. */
    fun point(
        xText: String?,
        yText: String?,
        geometry: DisplayGeometry,
        coordinateSpace: String,
    ): GesturePoint {
        val x = normalized(xText, "x")
        val y = normalized(yText, "y")
        return CodexDisplayTransform.point(x, y, geometry, coordinateSpace)
    }
    fun duration(value: String?, defaultValue: Long, minimum: Long, maximum: Long): Long {
        val result = if (value == null) {
            defaultValue
        } else {
            value.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid gesture duration")
        }
        require(result in minimum..maximum) {
            "Duration must be between " + minimum + " and " + maximum + " milliseconds"
        }
        return result
    }

    private fun normalized(value: String?, name: String): Float {
        val result = value?.toFloatOrNull()
            ?: throw IllegalArgumentException("Missing or invalid " + name + " coordinate")
        require(result.isFinite() && result in 0f..1f) {
            name + " must be finite and between 0.0 and 1.0"
        }
        return result
    }
}

/** A pixel point derived from a normalized display coordinate. */
data class GesturePoint(val x: Float, val y: Float)
