package com.kaori.codex.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CodexDisplayTransformTest {
    @Test
    fun mapsNormalizedCoordinatesInCurrentPortraitAndLandscapeSpaces() {
        assertEquals(
            GesturePoint(630f, 700f),
            CodexDisplayTransform.point(
                0.5f,
                0.25f,
                DisplayGeometry(1260, 2800, 0, 0, 0, 1260, 2800, "game"),
                "display",
            ),
        )
        assertEquals(
            GesturePoint(1400f, 315f),
            CodexDisplayTransform.point(
                0.5f,
                0.25f,
                DisplayGeometry(2800, 1260, 1, 0, 0, 2800, 1260, "game"),
                "display",
            ),
        )
        assertEquals(
            GesturePoint(630f, 700f),
            CodexDisplayTransform.point(
                0.5f,
                0.25f,
                DisplayGeometry(1260, 2800, 2, 0, 0, 1260, 2800, "game"),
                "display",
            ),
        )
        assertEquals(
            GesturePoint(1400f, 315f),
            CodexDisplayTransform.point(
                0.5f,
                0.25f,
                DisplayGeometry(2800, 1260, 3, 0, 0, 2800, 1260, "game"),
                "display",
            ),
        )
    }

    @Test
    fun mapsNormalizedCoordinatesInsideTheForegroundWindow() {
        assertEquals(
            GesturePoint(500f, 320f),
            CodexDisplayTransform.point(
                0.5f,
                0.5f,
                DisplayGeometry(1000, 600, 1, 0, 80, 1000, 560, "game"),
                "window",
            ),
        )
        assertEquals(
            GesturePoint(0f, 0f),
            CodexDisplayTransform.point(
                0f,
                0f,
                DisplayGeometry(1000, 600, 1, 0, 80, 1000, 560, "game"),
                "display",
            ),
        )
        assertEquals(
            GesturePoint(1000f, 600f),
            CodexDisplayTransform.point(
                1f,
                1f,
                DisplayGeometry(1000, 600, 1, 0, 80, 1000, 560, "game"),
                "display",
            ),
        )
    }

    @Test
    fun rejectsUnsupportedCoordinateSpace() {
        try {
            CodexDisplayTransform.point(
                0.5f,
                0.5f,
                DisplayGeometry(1000, 600, 0, 0, 0, 1000, 600, "game"),
                "natural",
            )
        } catch (exception: IllegalArgumentException) {
            assertEquals("Unsupported coordinateSpace: natural", exception.message)
            return
        }
        throw AssertionError("Expected an unsupported coordinate space to be rejected")
    }

    @Test
    fun rejectsStaleFrameAfterRotationOrPackageChange() {
        val frame = DisplayGeometry(1260, 2800, 0, 0, 0, 1260, 2800, "game")
        val frameId = CodexFrameRegistry.register(frame)
        assertNull(CodexFrameRegistry.validate(mapOf("frameId" to frameId), frame))
        assertNotNull(
            CodexFrameRegistry.validate(
                mapOf("frameId" to frameId),
                DisplayGeometry(2800, 1260, 1, 0, 0, 2800, 1260, "game"),
            ),
        )
        assertNotNull(
            CodexFrameRegistry.validate(
                mapOf("frameId" to frameId),
                DisplayGeometry(1260, 2800, 0, 0, 0, 1260, 2800, "other"),
            ),
        )
    }

    @Test
    fun acceptsMetadataWithoutAnOptionalPackageName() {
        val args = mapOf(
            "frameWidth" to "1000",
            "frameHeight" to "600",
            "frameRotation" to "1",
            "frameWindowLeft" to "0",
            "frameWindowTop" to "80",
            "frameWindowRight" to "1000",
            "frameWindowBottom" to "560",
        )
        assertNull(
            CodexFrameRegistry.validate(
                args,
                DisplayGeometry(1000, 600, 1, 0, 80, 1000, 560, "game"),
            ),
        )
    }
}
