package com.kaori.codex.bridge

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keeps OCR frame metadata in memory so stale normalized coordinates can be rejected. */
object CodexFrameRegistry {
    /** Registers a frame without retaining any pixels and returns its short-lived opaque ID. */
    fun register(geometry: DisplayGeometry): String {
        cleanup()
        val frameId = UUID.randomUUID().toString()
        frames[frameId] = Entry(geometry, System.currentTimeMillis())
        return frameId
    }

    /** Rejects frame metadata that no longer matches the current display or foreground window. */
    fun validate(args: Map<String, String>, current: DisplayGeometry): String? {
        cleanup()
        val frameId = args["frameId"] ?: args["ocrFrameId"]
        val registered = frameId?.let { frames[it] }
            ?: if (frameId != null) {
                return "Unknown or expired OCR frameId"
            } else {
                null
            }
        val expected = registered?.geometry ?: metadata(args) ?: return null
        if (!sameCoordinateSpace(expected, current) ||
            (expected.packageName != null && expected.packageName != current.packageName)
        ) {
            return "OCR frame is stale: display rotation, size, window bounds, or foreground package changed"
        }
        return null
    }

    private fun metadata(args: Map<String, String>): DisplayGeometry? {
        if (FRAME_KEYS.none(args::containsKey)) {
            return null
        }
        val width = args["frameWidth"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid OCR frameWidth")
        val height = args["frameHeight"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid OCR frameHeight")
        val rotation = args["frameRotation"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid OCR frameRotation")
        require(width > 0 && height > 0) { "Invalid OCR frame dimensions" }
        require(rotation in 0..3) { "Invalid OCR frameRotation" }
        val windowLeft = args["frameWindowLeft"]?.toIntOrNull() ?: 0
        val windowTop = args["frameWindowTop"]?.toIntOrNull() ?: 0
        val windowRight = args["frameWindowRight"]?.toIntOrNull() ?: width
        val windowBottom = args["frameWindowBottom"]?.toIntOrNull() ?: height
        require(windowLeft in 0..width && windowTop in 0..height) {
            "Invalid OCR frame window origin"
        }
        require(windowRight in windowLeft..width && windowBottom in windowTop..height) {
            "Invalid OCR frame window bounds"
        }
        return DisplayGeometry(
            width,
            height,
            rotation,
            windowLeft,
            windowTop,
            windowRight,
            windowBottom,
            args["framePackage"],
        )
    }

    private fun sameCoordinateSpace(expected: DisplayGeometry, current: DisplayGeometry): Boolean =
        expected.width == current.width &&
            expected.height == current.height &&
            expected.rotation == current.rotation &&
            expected.windowLeft == current.windowLeft &&
            expected.windowTop == current.windowTop &&
            expected.windowRight == current.windowRight &&
            expected.windowBottom == current.windowBottom

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - FRAME_TTL_MS
        frames.entries.removeIf { it.value.createdAt < cutoff }
    }

    private data class Entry(
        val geometry: DisplayGeometry,
        val createdAt: Long,
    )

    private val frames = ConcurrentHashMap<String, Entry>()
    private val FRAME_KEYS = setOf(
        "frameWidth",
        "frameHeight",
        "frameRotation",
        "frameWindowLeft",
        "frameWindowTop",
        "frameWindowRight",
        "frameWindowBottom",
        "framePackage",
    )
    private const val FRAME_TTL_MS = 30_000L
}
