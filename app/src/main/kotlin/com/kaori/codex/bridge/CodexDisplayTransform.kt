package com.kaori.codex.bridge

/** Converts one normalized coordinate space into current Accessibility gesture pixels. */
object CodexDisplayTransform {
    /** Converts normalized display or foreground-window coordinates into screen pixels. */
    fun point(
        x: Float,
        y: Float,
        geometry: DisplayGeometry,
        coordinateSpace: String,
    ): GesturePoint {
        require(x.isFinite() && x in 0f..1f) { "x must be finite and between 0.0 and 1.0" }
        require(y.isFinite() && y in 0f..1f) { "y must be finite and between 0.0 and 1.0" }
        return when (coordinateSpace.lowercase()) {
            "display" -> GesturePoint(x * geometry.width, y * geometry.height)
            "window" -> GesturePoint(
                geometry.windowLeft + x * geometry.windowWidth(),
                geometry.windowTop + y * geometry.windowHeight(),
            )
            else -> throw IllegalArgumentException("Unsupported coordinateSpace: " + coordinateSpace)
        }
    }
}

