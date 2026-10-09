package com.kaori.codex.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Persists short-lived bridge request states so callers can poll final results by request ID. */
object CodexRequestStore {
    /** Records a pending request before dispatch starts. */
    fun markPending(context: Context, requestId: String, operation: String) {
        val result = JSONObject()
            .put("ok", true)
            .put("pending", true)
            .put("status", "pending")
            .put("requestId", requestId)
            .put("operation", operation)
        write(context, requestId, result)
    }

    /** Replaces the pending record with the completed response. */
    fun complete(context: Context, requestId: String, response: JSONObject) {
        write(context, requestId, response)
    }

    /** Returns a pending or completed response for a request ID. */
    fun result(context: Context, args: Map<String, String>): JSONObject {
        val requestId = args["requestId"]?.takeIf { it.matches(REQUEST_ID) }
            ?: throw IllegalArgumentException("Missing or invalid requestId")
        cleanup(context)
        val file = resultFile(context, requestId)
        if (!file.isFile) {
            return JSONObject()
                .put("ok", false)
                .put("pending", false)
                .put("status", "unknown")
                .put("requestId", requestId)
                .put("error", "Unknown or expired bridge request")
        }
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (exception: Exception) {
            JSONObject()
                .put("ok", false)
                .put("pending", false)
                .put("status", "error")
                .put("requestId", requestId)
                .put("error", exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun write(context: Context, requestId: String, response: JSONObject) {
        val directory = File(context.filesDir, REQUEST_DIRECTORY)
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create bridge request directory"
        }
        cleanup(directory)
        val target = resultFile(context, requestId)
        val temporary = File(directory, "." + requestId + ".tmp")
        FileOutputStream(temporary).use {
            it.write(response.toString().toByteArray(Charsets.UTF_8))
        }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace bridge request result")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Unable to persist bridge request result")
        }
    }

    private fun cleanup(context: Context) {
        cleanup(File(context.filesDir, REQUEST_DIRECTORY))
    }

    private fun cleanup(directory: File) {
        val cutoff = System.currentTimeMillis() - REQUEST_TTL_MS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun resultFile(context: Context, requestId: String): File =
        File(File(context.filesDir, REQUEST_DIRECTORY), requestId + ".json")

    private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,96}")
    private const val REQUEST_DIRECTORY = "codex-requests"
    private const val REQUEST_TTL_MS = 30 * 60 * 1000L
}
