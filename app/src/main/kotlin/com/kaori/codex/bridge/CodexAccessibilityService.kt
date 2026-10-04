package com.kaori.codex.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
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

    /** Clicks one visible node selected by exact selectors unless fuzzy matching is requested. */
    fun click(args: Map<String, String>): JSONObject {
        val before = snapshotOrNull()
        val result = try {
            onMain {
                val root = rootInActiveWindow
                    ?: return@onMain error("No active accessibility window")
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
        return finishAction(result, before, args)
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

    /** Performs a bounded semantic swipe through AccessibilityService.dispatchGesture. */
    fun swipe(args: Map<String, String>): JSONObject {
        val direction = args["direction"]?.lowercase()
            ?: return error("Missing direction; use up, down, left, or right")
        if (direction !in DIRECTIONS) {
            return error("Unsupported swipe direction: " + direction)
        }
        val duration = (args["durationMs"]?.toLongOrNull() ?: DEFAULT_GESTURE_DURATION_MS)
            .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
        val before = snapshotOrNull()
        val result = try {
            val screen = onMain { screenBoundsOnMain() }
            val points = gesturePoints(screen, direction, args)
            val path = Path().apply {
                moveTo(points.first.first, points.first.second)
                lineTo(points.second.first, points.second.second)
            }
            val completed = CountDownLatch(1)
            var succeeded = false
            val dispatched = onMain {
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path,
                                0,
                                duration,
                            ),
                        )
                        .build(),
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
            if (!dispatched) {
                error("Accessibility gesture dispatch was rejected")
            } else if (!completed.await(duration + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
                error("Accessibility gesture timed out")
            } else if (!succeeded) {
                error("Accessibility gesture was cancelled")
            } else {
                JSONObject()
                    .put("ok", true)
                    .put("action", "swipe")
                    .put("direction", direction)
                    .put("durationMs", duration)
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName)
        }
        return finishAction(result, before, args)
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
            if (result.optString("error").contains("matched")) {
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
        return matches(node.text?.toString(), args["text"], fuzzy) ||
            matches(node.contentDescription?.toString(), args["description"], fuzzy) ||
            matches(node.viewIdResourceName, args["resource"], fuzzy) ||
            matches(node.className?.toString(), args["className"], fuzzy)
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
        screen: Rect,
        direction: String,
        args: Map<String, String>,
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val defaults = when (direction) {
            "up" -> Pair(
                Pair(screen.width() / 2f, screen.height() * 0.75f),
                Pair(screen.width() / 2f, screen.height() * 0.25f),
            )
            "down" -> Pair(
                Pair(screen.width() / 2f, screen.height() * 0.25f),
                Pair(screen.width() / 2f, screen.height() * 0.75f),
            )
            "left" -> Pair(
                Pair(screen.width() * 0.75f, screen.height() / 2f),
                Pair(screen.width() * 0.25f, screen.height() / 2f),
            )
            else -> Pair(
                Pair(screen.width() * 0.25f, screen.height() / 2f),
                Pair(screen.width() * 0.75f, screen.height() / 2f),
            )
        }
        val start = Pair(
            args["startX"]?.toFloatOrNull() ?: defaults.first.first,
            args["startY"]?.toFloatOrNull() ?: defaults.first.second,
        )
        val end = Pair(
            args["endX"]?.toFloatOrNull() ?: defaults.second.first,
            args["endY"]?.toFloatOrNull() ?: defaults.second.second,
        )
        require(start.first >= 0f && start.first < screen.right)
        require(start.second >= 0f && start.second < screen.bottom)
        require(end.first >= 0f && end.first < screen.right)
        require(end.second >= 0f && end.second < screen.bottom)
        require(start != end) { "Swipe start and end must differ" }
        return Pair(start, end)
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
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
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

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private data class Selection(
        val node: AccessibilityNodeInfo? = null,
        val error: JSONObject? = null,
    )

    private data class UiSnapshot(val packageName: String?, val fingerprint: String)

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
        private val DIRECTIONS = setOf("up", "down", "left", "right")

        @Volatile
        private var current: CodexAccessibilityService? = null

        /** Returns the currently connected service, if the user enabled it. */
        fun connected(): CodexAccessibilityService? = current
    }
}
