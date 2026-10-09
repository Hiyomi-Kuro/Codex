package com.kaori.codex.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Provides semantic UI inspection and actions for the Codex bridge. */
class CodexAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) {
            current = null
        }
        super.onDestroy()
    }

    /** Returns visible nodes from the active window and a short state fingerprint. */
    fun dumpUi(): JSONObject = try {
        onMain { dumpUiOnMain() }
    } catch (exception: Exception) {
        error(exception.message ?: exception.javaClass.simpleName)
    }


    /** Returns current display and foreground-window geometry for OCR and gestures. */
    fun displayGeometry(): DisplayGeometry = onMain { CodexDisplayGeometry.read(this) }
    /** Clicks one visible node selected by exact selectors unless fuzzy matching is requested. */
    fun click(args: Map<String, String>): JSONObject {
        val before = snapshotOrNull()
        val result = try {
            onMain {
                val root = rootInActiveWindow
                    ?: return@onMain error("No active accessibility window")
                foregroundErrorOnMain(args)?.let { return@onMain it }
                val selection = choose(findCandidates(root, args), args)
                if (selection.error != null) {
                    return@onMain selection.error
                }
                val clickable = clickableNode(selection.node!!)
                    ?: return@onMain error("UI node is not clickable")
                if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@onMain error("UI node click action was rejected")
                }
                JSONObject().put("ok", true).put("action", "click")
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "click")
    }

    /** Taps normalized display coordinates through AccessibilityService.dispatchGesture. */
    fun tap(args: Map<String, String>): JSONObject {
        val before = snapshotOrNull()
        val visualBefore = visualSnapshot(args)
        val result = try {
            val check = onMain { displayCheckOnMain(args) }
            if (check.error != null) {
                check.error
            } else {
                val geometry = check.geometry!!
                val staleError = CodexFrameRegistry.validate(args, geometry)
                if (staleError != null) {
                    error(staleError)
                } else {
                    val point = CodexGestureGeometry.point(
                        args["x"],
                        args["y"],
                        geometry,
                        args["coordinateSpace"] ?: "display",
                    )
                    val path = Path().apply {
                        moveTo(point.x, point.y)
                        lineTo(point.x, point.y)
                    }
                    runGesture(
                        path,
                        CodexGestureGeometry.duration(
                            args["durationMs"],
                            DEFAULT_TAP_DURATION_MS,
                            MIN_GESTURE_DURATION_MS,
                            MAX_TAP_DURATION_MS,
                        ),
                    ).put("action", "tap")
                }
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "tap", visualBefore)
    }

    /** Holds a normalized display coordinate through AccessibilityService.dispatchGesture. */
    fun longPress(args: Map<String, String>): JSONObject {
        val before = snapshotOrNull()
        val visualBefore = visualSnapshot(args)
        val result = try {
            val check = onMain { displayCheckOnMain(args) }
            if (check.error != null) {
                check.error
            } else {
                val geometry = check.geometry!!
                val staleError = CodexFrameRegistry.validate(args, geometry)
                if (staleError != null) {
                    error(staleError)
                } else {
                    val point = CodexGestureGeometry.point(
                        args["x"],
                        args["y"],
                        geometry,
                        args["coordinateSpace"] ?: "display",
                    )
                    val path = Path().apply {
                        moveTo(point.x, point.y)
                        lineTo(point.x, point.y)
                    }
                    runGesture(
                        path,
                        CodexGestureGeometry.duration(
                            args["durationMs"],
                            DEFAULT_LONG_PRESS_DURATION_MS,
                            MIN_LONG_PRESS_DURATION_MS,
                            MAX_LONG_PRESS_DURATION_MS,
                        ),
                    ).put("action", "long_press")
                }
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "long_press", visualBefore)
    }
    /** Performs two taps in one Accessibility gesture and verifies the resulting state. */
    fun doubleTap(args: Map<String, String>): JSONObject {
        val before = snapshotOrNull()
        val visualBefore = visualSnapshot(args)
        val result = try {
            val check = onMain { displayCheckOnMain(args) }
            if (check.error != null) {
                check.error
            } else {
                val geometry = check.geometry!!
                val staleError = CodexFrameRegistry.validate(args, geometry)
                if (staleError != null) {
                    error(staleError)
                } else {
                    val point = CodexGestureGeometry.point(
                        args["x"],
                        args["y"],
                        geometry,
                        args["coordinateSpace"] ?: "display",
                    )
                    val duration = CodexGestureGeometry.duration(
                        args["durationMs"],
                        DEFAULT_TAP_DURATION_MS,
                        MIN_GESTURE_DURATION_MS,
                        MAX_TAP_DURATION_MS,
                    )
                    val gap = args["gapMs"]?.toLongOrNull() ?: DEFAULT_DOUBLE_TAP_GAP_MS
                    require(gap in MIN_DOUBLE_TAP_GAP_MS..MAX_DOUBLE_TAP_GAP_MS) {
                        "Double-tap gap must be between $MIN_DOUBLE_TAP_GAP_MS and $MAX_DOUBLE_TAP_GAP_MS milliseconds"
                    }
                    val firstPath = Path().apply {
                        moveTo(point.x, point.y)
                        lineTo(point.x, point.y)
                    }
                    val secondPath = Path().apply {
                        moveTo(point.x, point.y)
                        lineTo(point.x, point.y)
                    }
                    runGesture(
                        listOf(
                            GestureDescription.StrokeDescription(firstPath, 0, duration),
                            GestureDescription.StrokeDescription(
                                secondPath,
                                duration + gap,
                                duration,
                            ),
                        ),
                        duration * 2 + gap,
                    ).put("action", "double_tap")
                }
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "double_tap", visualBefore)
    }

    /** Dispatches bounded simultaneous or sequential strokes supplied as JSON. */
    fun multiGesture(args: Map<String, String>): JSONObject {
        val rawStrokes = args["strokes"] ?: return error("Missing strokes JSON array")
        val before = snapshotOrNull()
        val visualBefore = visualSnapshot(args)
        val result = try {
            val items = JSONArray(rawStrokes)
            require(items.length() in 1..MAX_MULTI_STROKES) {
                "strokes must contain between 1 and $MAX_MULTI_STROKES entries"
            }
            val check = onMain { displayCheckOnMain(args) }
            if (check.error != null) {
                check.error
            } else {
                val geometry = check.geometry!!
                val staleError = CodexFrameRegistry.validate(args, geometry)
                if (staleError != null) {
                    error(staleError)
                } else {
                    val defaultSpace = args["coordinateSpace"] ?: "display"
                    val strokes = mutableListOf<GestureDescription.StrokeDescription>()
                    var totalDuration = 0L
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index)
                            ?: throw IllegalArgumentException("Stroke $index is not an object")
                        val startX = jsonString(item, "startX")
                            ?: throw IllegalArgumentException("Stroke $index is missing startX")
                        val startY = jsonString(item, "startY")
                            ?: throw IllegalArgumentException("Stroke $index is missing startY")
                        val endX = jsonString(item, "endX") ?: startX
                        val endY = jsonString(item, "endY") ?: startY
                        val space = jsonString(item, "coordinateSpace") ?: defaultSpace
                        val start = CodexGestureGeometry.point(startX, startY, geometry, space)
                        val end = CodexGestureGeometry.point(endX, endY, geometry, space)
                        val duration = CodexGestureGeometry.duration(
                            jsonString(item, "durationMs"),
                            DEFAULT_GESTURE_DURATION_MS,
                            MIN_GESTURE_DURATION_MS,
                            MAX_GESTURE_DURATION_MS,
                        )
                        val startTime = jsonString(item, "startMs")?.toLongOrNull() ?: 0L
                        require(startTime in 0L..MAX_MULTI_GESTURE_DURATION_MS) {
                            "Stroke $index startMs is outside the allowed range"
                        }
                        require(startTime + duration <= MAX_MULTI_GESTURE_DURATION_MS) {
                            "The combined multi-gesture duration exceeds $MAX_MULTI_GESTURE_DURATION_MS milliseconds"
                        }
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(end.x, end.y)
                        }
                        strokes += GestureDescription.StrokeDescription(path, startTime, duration)
                        totalDuration = maxOf(totalDuration, startTime + duration)
                    }
                    runGesture(strokes, totalDuration)
                        .put("action", "multi_gesture")
                        .put("strokes", strokes.size)
                        .put("durationMs", totalDuration)
                }
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "multi_gesture", visualBefore)
    }

    /** Matches a supplied image template against the current display without returning pixels. */
    fun templateMatch(args: Map<String, String>): JSONObject {
        val foregroundError = onMain { foregroundErrorOnMain(args) }
        if (foregroundError != null) {
            return foregroundError
                .put("action", "template_match")
                .put("executed", false)
                .put("verified", false)
        }
        return CodexTemplateMatcher.match(this, args)
    }

    /** Finds a template, taps its center, and verifies the post-action state. */
    fun templateTap(args: Map<String, String>): JSONObject {
        val foregroundError = onMain { foregroundErrorOnMain(args) }
        if (foregroundError != null) {
            return foregroundError
                .put("action", "template_tap")
                .put("executed", false)
                .put("verified", false)
        }
        return CodexTemplateMatcher.tap(this, args)
    }

    /** Captures and analyzes one in-memory display frame without persisting or returning pixels. */
    fun ocr(args: Map<String, String>): JSONObject {
        val foregroundError = onMain { foregroundErrorOnMain(args) }
        if (foregroundError != null) {
            return foregroundError
                .put("action", "ocr")
                .put("executed", false)
                .put("verified", false)
        }
        return CodexOcr.read(this, args)
    }


    /** Sets text on one visible editable node, optionally clearing it first. */
    fun inputText(args: Map<String, String>): JSONObject {
        val value = args["text"] ?: return error("Missing text argument")
        val before = snapshotOrNull()
        val selectorArgs = args.toMutableMap().apply {
            remove("text")
            remove("clear")
            remove("waitMs")
            remove("selectorText")
            args["selectorText"]?.let { put("text", it) }
        }
        val result = try {
            onMain {
                val root = rootInActiveWindow
                    ?: return@onMain error("No active accessibility window")
                val selection = choose(findCandidates(root, selectorArgs, editableOnly = true), selectorArgs)
                if (selection.error != null) {
                    return@onMain selection.error
                }
                val node = selection.node!!
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (args["clear"] == "true") {
                    val clearValues = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "",
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearValues)
                }
                if (!node.isFocused) {
                    clickableNode(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                val values = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values)) {
                    return@onMain error("UI node rejected text input")
                }
                JSONObject().put("ok", true).put("action", "input_text")
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishAction(result, before, args)
    }

    /** Scrolls the selected container using Accessibility scroll actions. */
    fun scroll(args: Map<String, String>): JSONObject {
        val direction = args["direction"]?.lowercase()
            ?: return error("Missing direction; use up, down, left, or right")
        if (direction !in DIRECTIONS) {
            return error("Unsupported scroll direction: " + direction)
        }
        val before = snapshotOrNull()
        val result = try {
            onMain {
                val root = rootInActiveWindow
                    ?: return@onMain error("No active accessibility window")
                val candidates = if (hasSelector(args)) {
                    findCandidates(root, args)
                } else {
                    findCandidates(root, args, scrollableOnly = true)
                }
                val selection = choose(candidates, args)
                if (selection.error != null) {
                    return@onMain selection.error
                }
                val scrollable = nearestScrollable(selection.node!!)
                    ?: return@onMain error("Selected node has no scrollable parent")
                val action = scrollAction(direction)
                val count = (args["distance"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_SCROLL_ACTIONS)
                var performed = 0
                repeat(count) {
                    if (scrollable.performAction(action)) {
                        performed++
                    }
                }
                if (performed == 0) {
                    return@onMain error("Scroll action was rejected by the selected container")
                }
                JSONObject()
                    .put("ok", true)
                    .put("action", "scroll")
                    .put("direction", direction)
                    .put("actions", performed)
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishAction(result, before, args)

    }
    /** Performs a bounded normalized swipe through AccessibilityService.dispatchGesture. */
    fun swipe(args: Map<String, String>): JSONObject {
        val hasExplicitCoordinates = listOf("startX", "startY", "endX", "endY")
            .any(args::containsKey)
        val requestedDirection = args["direction"]?.lowercase()
        val direction = requestedDirection ?: "down"
        val before = snapshotOrNull()
        val visualBefore = if (
            (requestedDirection == null && !hasExplicitCoordinates) ||
            (requestedDirection != null && requestedDirection !in DIRECTIONS)
        ) {
            null
        } else {
            visualSnapshot(args)
        }
        val result = try {
            when {
                requestedDirection == null && !hasExplicitCoordinates -> {
                    error("Missing direction or normalized start/end coordinates")
                }
                requestedDirection != null && requestedDirection !in DIRECTIONS -> {
                    error("Unsupported swipe direction: " + requestedDirection)
                }
                else -> {
                    val duration = CodexGestureGeometry.duration(
                        args["durationMs"],
                        DEFAULT_GESTURE_DURATION_MS,
                        MIN_GESTURE_DURATION_MS,
                        MAX_GESTURE_DURATION_MS,
                    )
                    val check = onMain { displayCheckOnMain(args) }
                    if (check.error != null) {
                        check.error
                    } else {
                        val geometry = check.geometry!!
                        val staleError = CodexFrameRegistry.validate(args, geometry)
                        if (staleError != null) {
                            error(staleError)
                        } else {
                            val points = gesturePoints(geometry, direction, args)
                            val path = Path().apply {
                                moveTo(points.first.first, points.first.second)
                                lineTo(points.second.first, points.second.second)
                            }
                            runGesture(path, duration)
                                .put("action", "swipe")
                                .put("direction", direction)
                                .put("durationMs", duration)
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishGestureAction(result, before, args, "swipe", visualBefore)
    }

    /** Waits for a visible UI selector and returns the observed page summary. */
    fun waitFor(args: Map<String, String>): JSONObject {
        if (!hasSelector(args)) {
            return error("ui.wait requires text, description, resource, or className")
        }
        val timeout = waitMillis(args)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        var last = JSONObject().put("ok", false).put("error", "UI selector not found")
        while (System.nanoTime() <= deadline) {
            val result = try {
                onMain {
                    val root = rootInActiveWindow
                        ?: return@onMain error("No active accessibility window")
                    val selection = choose(findCandidates(root, args), args)
                    if (selection.error == null) {
                        JSONObject()
                            .put("ok", true)
                            .put("matched", candidateSummary(selection.node!!))
                    } else {
                        selection.error
                    }
                }
            } catch (exception: Exception) {
                error(exception.message ?: exception.javaClass.simpleName)
            }
            last = result
            if (result.optBoolean("ok")) {
                return result.put("package", rootPackage()).put("after", dumpUi())
            }
            if (result.optString("errorCode") == "ambiguous_selector") {
                return result
            }
            Thread.sleep(WAIT_POLL_MS)
        }
        return last.put("timeoutMs", timeout).put("after", dumpUi())
    }

    /** Performs an Android global action and reports the resulting UI state. */
    fun globalAction(action: Int, name: String): JSONObject {
        val before = snapshotOrNull()
        val result = try {
            if (onMain { performGlobalAction(action) }) {
                JSONObject().put("ok", true).put("action", name)
            } else {
                error("Global action failed: " + name)
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishAction(result, before, emptyMap())
    }

    private fun dumpUiOnMain(): JSONObject {
        val root = rootInActiveWindow
            ?: return error("No active accessibility window")
        val nodes = JSONArray()
        appendNode(root, nodes, 0)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString() ?: JSONObject.NULL)
            .put("fingerprint", fingerprintOnMain(root))
            .put("nodes", nodes)
    }

    private fun finishAction(
        result: JSONObject,
        before: UiSnapshot?,
        args: Map<String, String>,
    ): JSONObject {
        if (!result.optBoolean("ok")) {
            return result
        }
        val after = awaitChanged(before?.fingerprint, waitMillis(args))
        return result
            .put("changed", before == null || after.fingerprint != before.fingerprint)
            .put("after", dumpUi())
    }

    private fun finishGestureAction(
        result: JSONObject,
        before: UiSnapshot?,
        args: Map<String, String>,
        action: String,
        visualBefore: JSONObject? = null,
    ): JSONObject {
        val verificationStarted = android.os.SystemClock.elapsedRealtime()
        val after = if (result.optBoolean("ok")) {
            awaitChanged(before?.fingerprint, waitMillis(args))
        } else {
            snapshotOrNull() ?: UiSnapshot(null, "")
        }
        val accessibilityChanged = before != null &&
            after.packageName != null &&
            after.fingerprint != before.fingerprint
        val verification = if (result.optBoolean("ok")) {
            verifyGesture(before, after, accessibilityChanged, args, visualBefore)
        } else {
            JSONObject()
                .put("verified", false)
                .put("stateChanged", false)
                .put("evidence", "gesture_dispatch_failed")
                .put("reason", "The Accessibility gesture did not complete")
        }
        val verificationMs = android.os.SystemClock.elapsedRealtime() - verificationStarted
        val changed = if (!result.optBoolean("ok")) {
            false
        } else if (visualBefore != null) {
            verification.optBoolean("stateChanged")
        } else {
            accessibilityChanged || verification.optBoolean("stateChanged")
        }
        verification.put("verificationMs", verificationMs)
        return result
            .put("action", action)
            .put("executed", result.optBoolean("ok"))
            .put("verified", verification.optBoolean("verified"))
            .put("changed", changed)
            .put("before", before?.json() ?: JSONObject.NULL)
            .put("after", after.json())
            .put("verification", verification)
    }

    private fun verifyGesture(
        before: UiSnapshot?,
        after: UiSnapshot,
        accessibilityChanged: Boolean,
        args: Map<String, String>,
        visualBefore: JSONObject?,
    ): JSONObject {
        val expectedText = (args["expectedText"] ?: args["expectText"])
            ?.takeIf { it.isNotBlank() }
        val absentText = (args["expectedNotText"] ?: args["expectNotText"])
            ?.takeIf { it.isNotBlank() }
        val stateChangeRequested = args["expectedStateChange"] == "true" ||
            args["expectStateChange"] == "true"
        val accessibilityResult = if (before == null || after.packageName == null) {
            JSONObject()
                .put("verified", false)
                .put("stateChanged", false)
                .put("evidence", "accessibility_unavailable")
                .put("visualEvidenceAvailable", false)
                .put("reason", "Accessibility state is unavailable")
        } else {
            onMain {
                val root = rootInActiveWindow
                    ?: return@onMain JSONObject()
                        .put("verified", false)
                        .put("stateChanged", false)
                        .put("evidence", "accessibility_unavailable")
                        .put("visualEvidenceAvailable", false)
                        .put("reason", "Accessibility state is unavailable")
                val expectedFound = expectedText?.let {
                    findCandidates(root, mapOf("text" to it, "fuzzy" to "true")).isNotEmpty()
                }
                val absentFound = absentText?.let {
                    findCandidates(root, mapOf("text" to it, "fuzzy" to "true")).isNotEmpty()
                }
                val expectationSatisfied = (expectedFound != false) && (absentFound != true)
                JSONObject()
                    .put("expectedText", expectedText ?: JSONObject.NULL)
                    .put("expectedTextFound", expectedFound ?: JSONObject.NULL)
                    .put("expectedNotText", absentText ?: JSONObject.NULL)
                    .put("expectedNotTextFound", absentFound ?: JSONObject.NULL)
                    .put("verified", accessibilityChanged && expectationSatisfied)
                    .put("stateChanged", accessibilityChanged)
                    .put("evidence", "accessibility_text_and_fingerprint")
                    .put("visualEvidenceAvailable", false)
                    .put("reason", if (accessibilityChanged && expectationSatisfied) {
                        "Accessibility state and requested text condition match"
                    } else {
                        "Requested state was not sufficiently confirmed by Accessibility"
                    })
            }
        }
        val accessibilityVerified = accessibilityResult.optBoolean("verified") &&
            (!stateChangeRequested || accessibilityChanged)
        if (visualBefore == null && accessibilityVerified) {
            return accessibilityResult
        }
        if (visualBefore == null && accessibilityChanged &&
            expectedText == null && absentText == null && !stateChangeRequested
        ) {
            return accessibilityResult
        }
        val ocrResult = ocr(args)
        if (ocrResult.optBoolean("ok")) {
            val beforeText = visualBefore?.optString("text") ?: ""
            val afterText = ocrResult.optString("text")
            val beforeRegions = visualBefore?.optJSONArray("regions")?.toString() ?: ""
            val afterRegions = ocrResult.optJSONArray("regions")?.toString() ?: ""
            val visualChanged = visualBefore != null &&
                (beforeText != afterText || beforeRegions != afterRegions)
            val expectedFound = expectedText != null &&
                ocrResult.optBoolean("expectedTextFound")
            val absentFound = absentText != null &&
                ocrResult.optBoolean("expectedNotTextFound")
            val expectationSatisfied = (expectedText == null || expectedFound) &&
                (absentText == null || !absentFound)
            val expectedTransition = expectedText != null && expectedFound &&
                !(visualBefore?.optBoolean("expectedTextFound") ?: false)
            val absentTransition = absentText != null && !absentFound &&
                (visualBefore?.optBoolean("expectedNotTextFound") ?: false)
            val transitionEvidence = visualChanged ||
                (visualBefore == null && accessibilityChanged) ||
                expectedTransition || absentTransition
            val ocrVerified = expectationSatisfied && when {
                stateChangeRequested -> visualChanged
                expectedText != null || absentText != null -> transitionEvidence
                else -> visualChanged
            }
            return ocrResult
                .put("verified", ocrVerified)
                .put("stateChanged", visualChanged)
                .put("ocrTextBefore", visualBefore?.optString("text") ?: JSONObject.NULL)
                .put("ocrTextAfter", afterText)
                .put("evidence", "ocr_text_and_regions")
                .put("reason", if (ocrVerified) {
                    "Post-action OCR matches the requested state and differs from the pre-action frame"
                } else if (!visualChanged && expectedText == null && absentText == null) {
                    "Post-action OCR is unchanged; gesture dispatch is not visual evidence of a state change"
                } else if (!expectationSatisfied) {
                    "Post-action OCR did not satisfy the requested text condition"
                } else {
                    "Post-action OCR did not prove a state transition"
                })
        }
        val visualVerificationFailed = visualBefore != null
        return accessibilityResult
            .put("verified", if (visualVerificationFailed) false else accessibilityResult.optBoolean("verified"))
            .put("ocr", ocrResult)
            .put("stateChanged", false)
            .put("reason", if (visualVerificationFailed) {
                "Pre-action OCR was available but post-action OCR failed: " + ocrResult.optString("error")
            } else {
                accessibilityResult.optString("reason") +
                    "; OCR unavailable: " + ocrResult.optString("error")
            })
    }

    private fun runGesture(path: Path, duration: Long): JSONObject =
        runGesture(
            listOf(GestureDescription.StrokeDescription(path, 0, duration)),
            duration,
        )

    private fun runGesture(
        strokes: List<GestureDescription.StrokeDescription>,
        waitDuration: Long,
    ): JSONObject {
        require(strokes.isNotEmpty()) { "At least one gesture stroke is required" }
        val completed = CountDownLatch(1)
        var succeeded = false
        val dispatched = onMain {
            val builder = GestureDescription.Builder()
            strokes.forEach { builder.addStroke(it) }
            dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        succeeded = true
                        completed.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.countDown()
                    }
                },
                null,
            )
        }
        return when {
            !dispatched -> error("Accessibility gesture dispatch was rejected")
            !completed.await(waitDuration + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS) ->
                error("Accessibility gesture timed out")
            !succeeded -> error("Accessibility gesture was cancelled")
            else -> JSONObject().put("ok", true)
        }
    }
    private fun displayCheckOnMain(args: Map<String, String>): DisplayCheck {
        val foregroundError = foregroundErrorOnMain(args)
        return if (foregroundError != null) {
            DisplayCheck(null, foregroundError)
        } else {
            DisplayCheck(CodexDisplayGeometry.read(this), null)
        }
    }

    private fun visualSnapshot(args: Map<String, String>): JSONObject? = try {
        ocr(args).takeIf { it.optBoolean("ok") }
    } catch (_: Exception) {
        null
    }

    private fun foregroundErrorOnMain(args: Map<String, String>): JSONObject? {
        val actualPackage = rootInActiveWindow?.packageName?.toString()
            ?: return error("No active accessibility window")
        val requestedPackage = args["targetPackage"]?.takeIf { it.isNotBlank() }
            ?: return null
        if (!requestedPackage.matches(PACKAGE_NAME)) {
            return error("Invalid targetPackage")
        }
        if (requestedPackage != actualPackage) {
            return error("Foreground package does not match targetPackage")
                .put("expectedPackage", requestedPackage)
                .put("actualPackage", actualPackage)
        }
        return null
    }

    private fun awaitChanged(before: String?, timeout: Long): UiSnapshot {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        var currentSnapshot = snapshotOrNull() ?: UiSnapshot(null, "")
        while (System.nanoTime() <= deadline) {
            if (before == null || currentSnapshot.fingerprint != before) {
                return currentSnapshot
            }
            Thread.sleep(WAIT_POLL_MS)
            currentSnapshot = snapshotOrNull() ?: currentSnapshot
        }
        return currentSnapshot
    }


    private fun snapshotOrNull(): UiSnapshot? = try {
        onMain {
            val root = rootInActiveWindow ?: return@onMain null
            UiSnapshot(root.packageName?.toString(), fingerprintOnMain(root))
        }
    } catch (_: Exception) {
        null
    }

    private fun rootPackage(): String? = try {
        onMain { rootInActiveWindow?.packageName?.toString() }
    } catch (_: Exception) {
        null
    }

    private fun choose(candidates: List<AccessibilityNodeInfo>, args: Map<String, String>): Selection {
        if (candidates.isEmpty()) {
            return Selection(error = error("No visible UI node matched the selector"))
        }
        val requestedIndex = args["index"]?.toIntOrNull()
        if (requestedIndex != null) {
            return candidates.getOrNull(requestedIndex)?.let { Selection(it) }
                ?: Selection(error = error("Selector index is out of range"))
        }
        if (candidates.size > 1) {
            val summaries = JSONArray()
            candidates.take(MAX_CANDIDATES).forEachIndexed { index, node ->
                summaries.put(candidateSummary(node).put("index", index))
            }
            return Selection(
                error = error("Selector matched multiple visible UI nodes")
                    .put("errorCode", "ambiguous_selector")
                    .put("candidates", summaries),
            )
        }
        return Selection(candidates.single())
    }

    private fun findCandidates(
        root: AccessibilityNodeInfo,
        args: Map<String, String>,
        editableOnly: Boolean = false,
        scrollableOnly: Boolean = false,
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            if (result.size >= MAX_CANDIDATES) {
                return@walk false
            }
            if (isUsable(node) &&
                (!editableOnly || node.isEditable) &&
                (!scrollableOnly || node.isScrollable) &&
                matchesSelector(node, args)
            ) {
                result += node
            }
            true
        }
        return result
    }

    private fun matchesSelector(node: AccessibilityNodeInfo, args: Map<String, String>): Boolean {
        if (!hasSelector(args)) {
            return true
        }
        val fuzzy = args["fuzzy"] == "true"
        val conditions = mutableListOf<Boolean>()
        args["text"]?.takeIf { it.isNotBlank() }?.let { expected ->
            conditions += matches(node.text?.toString(), expected, fuzzy)
        }
        args["description"]?.takeIf { it.isNotBlank() }?.let { expected ->
            conditions += matches(node.contentDescription?.toString(), expected, fuzzy)
        }
        args["resource"]?.takeIf { it.isNotBlank() }?.let { expected ->
            conditions += matches(node.viewIdResourceName, expected, fuzzy)
        }
        args["className"]?.takeIf { it.isNotBlank() }?.let { expected ->
            conditions += matches(node.className?.toString(), expected, fuzzy)
        }
        return if (args["mode"]?.equals("or", ignoreCase = true) == true) {
            conditions.any { it }
        } else {
            conditions.all { it }
        }
    }

    private fun hasSelector(args: Map<String, String>): Boolean =
        listOf("text", "description", "resource", "className").any { !args[it].isNullOrBlank() }

    private fun matches(value: String?, expected: String?, fuzzy: Boolean): Boolean {
        if (value.isNullOrEmpty() || expected.isNullOrEmpty()) {
            return false
        }
        return value == expected || (fuzzy && value.contains(expected, ignoreCase = true))
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_SEARCH + 1) {
            if (candidate != null && isUsable(candidate!!) && candidate!!.isClickable) {
                return candidate
            }
            candidate = candidate?.parent
        }
        return null
    }

    private fun nearestScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_SEARCH + 1) {
            if (candidate?.isScrollable == true) {
                return candidate
            }
            candidate = candidate?.parent
        }
        return null
    }

    private fun scrollAction(direction: String): Int = when (direction) {
        "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    }

    private fun gesturePoints(
        geometry: DisplayGeometry,
        direction: String,
        args: Map<String, String>,
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val coordinateSpace = (args["coordinateSpace"] ?: "display").lowercase()
        require(coordinateSpace == "display" || coordinateSpace == "window") {
            "Unsupported coordinateSpace: " + coordinateSpace
        }
        val hasExplicitCoordinates = listOf("startX", "startY", "endX", "endY")
            .any(args::containsKey)
        if (hasExplicitCoordinates) {
            val start = CodexGestureGeometry.point(
                args["startX"],
                args["startY"],
                geometry,
                coordinateSpace,
            )
            val end = CodexGestureGeometry.point(
                args["endX"],
                args["endY"],
                geometry,
                coordinateSpace,
            )
            require(start != end) { "Swipe start and end must differ" }
            return Pair(
                Pair(start.x, start.y),
                Pair(end.x, end.y),
            )
        }
        val left = if (coordinateSpace == "window") geometry.windowLeft.toFloat() else 0f
        val top = if (coordinateSpace == "window") geometry.windowTop.toFloat() else 0f
        val width = if (coordinateSpace == "window") geometry.windowWidth().toFloat() else geometry.width.toFloat()
        val height = if (coordinateSpace == "window") geometry.windowHeight().toFloat() else geometry.height.toFloat()
        val defaults = when (direction) {
            "up" -> Pair(
                Pair(left + width / 2f, top + height * 0.75f),
                Pair(left + width / 2f, top + height * 0.25f),
            )
            "down" -> Pair(
                Pair(left + width / 2f, top + height * 0.25f),
                Pair(left + width / 2f, top + height * 0.75f),
            )
            "left" -> Pair(
                Pair(left + width * 0.75f, top + height / 2f),
                Pair(left + width * 0.25f, top + height / 2f),
            )
            else -> Pair(
                Pair(left + width * 0.25f, top + height / 2f),
                Pair(left + width * 0.75f, top + height / 2f),
            )
        }
        return defaults
    }

    private fun appendNode(node: AccessibilityNodeInfo, output: JSONArray, depth: Int) {
        if (depth > MAX_DEPTH || output.length() >= MAX_NODES || !isUsable(node)) {
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        output.put(candidateSummary(node)
            .put("enabled", node.isEnabled)
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("selected", node.isSelected)
            .put("focused", node.isFocused)
            .put("depth", depth))
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            appendNode(child, output, depth + 1)
            if (output.length() >= MAX_NODES) {
                return
            }
        }
    }

    private fun candidateSummary(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return JSONObject()
            .put("className", node.className?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString()?.take(MAX_TEXT_LENGTH) ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString()?.take(MAX_TEXT_LENGTH) ?: JSONObject.NULL)
            .put("resource", node.viewIdResourceName ?: JSONObject.NULL)
            .put("package", node.packageName?.toString() ?: JSONObject.NULL)
            .put("bounds", JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom))
    }

    private fun fingerprintOnMain(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        var count = 0
        walk(root) { node ->
            if (isUsable(node)) {
                count++
                if (builder.length < MAX_FINGERPRINT_LENGTH) {
                    builder.append(node.className).append('|')
                        .append(node.viewIdResourceName).append('|')
                        .append(node.text?.toString()?.take(MAX_TEXT_LENGTH)).append('|')
                        .append(node.contentDescription?.toString()?.take(MAX_TEXT_LENGTH)).append('|')
                }
            }
            true
        }
        return Integer.toHexString(builder.toString().hashCode()) + ":" + count
    }

    private fun isUsable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) {
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val screen = screenBoundsOnMain()
        return !bounds.isEmpty &&
            bounds.left < screen.right &&
            bounds.right > screen.left &&
            bounds.top < screen.bottom &&
            bounds.bottom > screen.top
    }

    private fun screenBoundsOnMain(): Rect {
        val geometry = CodexDisplayGeometry.read(this)
        return Rect(0, 0, geometry.width, geometry.height)
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        if (!visit(node)) {
            return
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, visit)
        }
    }

    private fun waitMillis(args: Map<String, String>): Long =
        (args["waitMs"]?.toLongOrNull() ?: DEFAULT_WAIT_MS)
            .coerceIn(0L, MAX_WAIT_MS)

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val future = FutureTask<T> { block() }
        if (!mainHandler.post(future)) {
            throw IllegalStateException("Accessibility main thread is unavailable")
        }
        return try {
            future.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("Accessibility operation timed out")
        }
    }

    private fun jsonString(value: JSONObject, name: String): String? {
        if (!value.has(name) || value.isNull(name)) {
            return null
        }
        return value.optString(name).takeIf { it.isNotBlank() }
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private data class Selection(
        val node: AccessibilityNodeInfo? = null,
        val error: JSONObject? = null,
    )

    private data class UiSnapshot(val packageName: String?, val fingerprint: String) {
        fun json(): JSONObject = JSONObject()
            .put("package", packageName ?: JSONObject.NULL)
            .put("fingerprint", fingerprint)
    }

    private data class DisplayCheck(
        val geometry: DisplayGeometry?,
        val error: JSONObject?,
    )

    companion object {
        private const val MAX_DEPTH = 24
        private const val MAX_NODES = 600
        private const val MAX_CANDIDATES = 20
        private const val MAX_PARENT_SEARCH = 8
        private const val MAX_TEXT_LENGTH = 160
        private const val MAX_FINGERPRINT_LENGTH = 4096
        private const val MAX_SCROLL_ACTIONS = 4
        private const val DEFAULT_WAIT_MS = 1200L
        private const val MAX_WAIT_MS = 3000L
        private const val WAIT_POLL_MS = 100L
        private const val MAIN_THREAD_TIMEOUT_MS = 2000L
        private const val MIN_GESTURE_DURATION_MS = 80L
        private const val DEFAULT_GESTURE_DURATION_MS = 450L
        private const val MAX_GESTURE_DURATION_MS = 2000L
        private const val GESTURE_CALLBACK_GRACE_MS = 1500L
        private const val DEFAULT_TAP_DURATION_MS = 80L
        private const val MAX_TAP_DURATION_MS = 500L
        private const val DEFAULT_DOUBLE_TAP_GAP_MS = 120L
        private const val MIN_DOUBLE_TAP_GAP_MS = 40L
        private const val MAX_DOUBLE_TAP_GAP_MS = 500L
        private const val MAX_MULTI_STROKES = 5
        private const val MAX_MULTI_GESTURE_DURATION_MS = 5000L
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 700L
        private const val MIN_LONG_PRESS_DURATION_MS = 300L
        private const val MAX_LONG_PRESS_DURATION_MS = 3000L
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val DIRECTIONS = setOf("up", "down", "left", "right")

        @Volatile
        private var current: CodexAccessibilityService? = null

        /** Returns the currently connected service, if the user enabled it. */
        fun connected(): CodexAccessibilityService? = current
    }
}
