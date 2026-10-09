package com.kaori.codex.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexOcrGeometryTest {
    @Test
    fun defaultsToTheWholeDisplay() {
        assertEquals(NormalizedRegion(0f, 0f, 1f, 1f), CodexOcrGeometry.region(emptyMap()))
    }

    @Test
    fun acceptsAStrictNormalizedRegion() {
        val region = CodexOcrGeometry.region(
            mapOf("left" to "0.1", "top" to "0.2", "right" to "0.8", "bottom" to "0.9"),
        )
        assertEquals(NormalizedRegion(0.1f, 0.2f, 0.8f, 0.9f), region)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteRegionCoordinates() {
        CodexOcrGeometry.region(mapOf("left" to "NaN"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedRegionCoordinates() {
        CodexOcrGeometry.region(mapOf("left" to "not-a-number"))
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnInvertedRegion() {
        CodexOcrGeometry.region(mapOf("left" to "0.8", "right" to "0.2"))
    }
}

