package com.kaori.codex.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexGestureGeometryTest {
    @Test
    fun convertsNormalizedCoordinatesForEachDisplayRotation() {
        val expected = GesturePoint(200f, 100f)
        assertEquals(expected, CodexGestureGeometry.point("0.5", "0.25", 400, 400, 0))
        assertEquals(expected, CodexGestureGeometry.point("0.5", "0.25", 400, 400, 1))
        assertEquals(expected, CodexGestureGeometry.point("0.5", "0.25", 400, 400, 2))
        assertEquals(expected, CodexGestureGeometry.point("0.5", "0.25", 400, 400, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeCoordinates() {
        CodexGestureGeometry.point("1.01", "0.5", 400, 400, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCoordinates() {
        CodexGestureGeometry.point("NaN", "0.5", 400, 400, 0)
    }

    @Test
    fun validatesLongPressAndSwipeDurations() {
        assertEquals(450L, CodexGestureGeometry.duration(null, 450L, 80L, 2000L))
        assertEquals(1200L, CodexGestureGeometry.duration("1200", 450L, 300L, 3000L))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDuration() {
        CodexGestureGeometry.duration("NaN", 450L, 80L, 2000L)
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnExcessiveDuration() {
        CodexGestureGeometry.duration("3001", 450L, 300L, 3000L)
    }
}
