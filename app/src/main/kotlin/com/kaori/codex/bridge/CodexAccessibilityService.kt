package com.kaori.codex.bridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Provides semantic UI inspection and actions for the Codex bridge. */
class CodexAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) {
            current = null
        }
        super.onDestroy()
    }

    /** Returns a JSON representation of the active window's accessible nodes. */
    fun dumpUi(): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "No active accessibility window")
        val nodes = JSONArray()
        appendNode(root, nodes, 0, MAX_DEPTH)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString() ?: JSONObject.NULL)
            .put("nodes", nodes)
    }

    /** Clicks a node selected by text, content description, or resource id. */
    fun click(args: Map<String, String>): JSONObject {
        val root = rootInActiveWindow
            ?: return error("No active accessibility window")
        val node = findNode(root, args)
            ?: return error("UI node not found")
        val clickable = clickableNode(node)
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        return if (clicked) {
            JSONObject().put("ok", true).put("action", "click")
        } else {
            error("UI node is not clickable")
        }
    }

    /** Sets text on a selected editable node or the first editable node. */
    fun inputText(args: Map<String, String>): JSONObject {
        val value = args["text"] ?: return error("Missing text argument")
        val root = rootInActiveWindow
            ?: return error("No active accessibility window")
        val selectorArgs = args.filterKeys { it != "text" }
        val node = findNode(root, selectorArgs, editableOnly = true)
            ?: return error("Editable UI node not found")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val values = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values)
        return if (changed) {
            JSONObject().put("ok", true).put("action", "input_text")
        } else {
            error("UI node rejected text input")
        }
    }

    /** Performs an Android global action such as Back or Home. */
    fun globalAction(action: Int, name: String): JSONObject {
        return if (performGlobalAction(action)) {
            JSONObject().put("ok", true).put("action", name)
        } else {
            error("Global action failed: $name")
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        args: Map<String, String>,
        editableOnly: Boolean = false,
    ): AccessibilityNodeInfo? {
        val selectorKeys = listOf("text", "description", "resource")
        val hasSelector = selectorKeys.any { !args[it].isNullOrEmpty() }
        return walk(root) { node ->
            if (editableOnly && !node.isEditable) {
                return@walk false
            }
            if (!hasSelector) {
                return@walk true
            }
            matches(node.text?.toString(), args["text"]) ||
                matches(node.contentDescription?.toString(), args["description"]) ||
                matches(node.viewIdResourceName, args["resource"])
        }
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_SEARCH) {
            if (candidate?.isClickable == true) {
                return candidate
            }
            candidate = candidate?.parent
        }
        return null
    }

    private fun matches(value: String?, selector: String?): Boolean {
        if (selector.isNullOrEmpty() || value.isNullOrEmpty()) {
            return false
        }
        return value == selector || value.contains(selector, ignoreCase = true)
    }

    private fun walk(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = walk(child, predicate)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun appendNode(node: AccessibilityNodeInfo, output: JSONArray, depth: Int, remainingDepth: Int) {
        if (remainingDepth < 0 || output.length() >= MAX_NODES) {
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val item = JSONObject()
            .put("className", node.className?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("resource", node.viewIdResourceName ?: JSONObject.NULL)
            .put("package", node.packageName?.toString() ?: JSONObject.NULL)
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("enabled", node.isEnabled)
            .put("depth", depth)
            .put("bounds", JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom))
        output.put(item)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            appendNode(child, output, depth + 1, remainingDepth - 1)
            if (output.length() >= MAX_NODES) {
                return
            }
        }
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_NODES = 2000
        private const val MAX_PARENT_SEARCH = 8

        @Volatile
        private var current: CodexAccessibilityService? = null

        /** Returns the currently connected service, if the user enabled it. */
        fun connected(): CodexAccessibilityService? = current
    }
}
