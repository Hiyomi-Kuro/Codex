package com.kaori.codex.bridge

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Coordinates user-authorized SAF selection and app-owned import/export results. */
object CodexFileOperations {
    /** Starts an ACTION_OPEN_DOCUMENT or ACTION_OPEN_DOCUMENT_TREE request. */
    fun startPicker(context: Context, args: Map<String, String>, requestId: String): JSONObject =
        start(context, args, requestId, "pick")

    /** Starts an ACTION_CREATE_DOCUMENT request for an app-specific source file. */
    fun startSaver(context: Context, args: Map<String, String>, requestId: String): JSONObject {
        val sourcePath = args["sourcePath"]?.takeIf { it.isNotBlank() }
            ?: return error("file.save requires sourcePath in Codex app-specific storage")
        return start(context, args, requestId, "save").put("sourcePath", sourcePath)
    }

    /** Waits for a correlated picker result without reading any unselected URI. */
    fun result(context: Context, args: Map<String, String>): JSONObject {
        val requestId = requestIdArgument(args)
        val waitMs = (args["waitMs"]?.toLongOrNull() ?: DEFAULT_RESULT_WAIT_MS)
            .coerceIn(0L, MAX_RESULT_WAIT_MS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (true) {
            val result = readResult(context, requestId)
            if (result != null && !result.optBoolean("pending", false)) {
                return result
            }
            if (System.nanoTime() >= deadline) {
                return (result ?: pending(requestId, "unknown")).put("waitedMs", waitMs)
            }
            try {
                Thread.sleep(RESULT_POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return error("File result wait was interrupted").put("requestId", requestId)
            }
        }
    }

    /** Cancels a pending picker request without deleting any user-selected file. */
    fun cancel(context: Context, args: Map<String, String>): JSONObject {
        val requestId = requestIdArgument(args)
        val existing = readResult(context, requestId)
        if (existing != null && !existing.optBoolean("pending", false)) {
            return existing
        }
        val result = JSONObject()
            .put("ok", false)
            .put("pending", false)
            .put("status", "cancelled")
            .put("requestId", requestId)
            .put("error", "File request cancelled")
        writeResult(context, requestId, result)
        return result
    }

    /** Completes a picker request and persists only its metadata and selected URI grants. */
    fun complete(
        context: Context,
        requestId: String,
        kind: String,
        sourcePath: String?,
        data: Intent?,
        resultCode: Int,
    ) {
        if (readResult(context, requestId)?.optString("status") == "cancelled") {
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            writeResult(context, requestId, cancelled(requestId))
            return
        }
        val uris = selectedUris(data)
        if (uris.isEmpty()) {
            writeResult(
                context,
                requestId,
                error("File picker returned no URI")
                    .put("pending", false)
                    .put("status", "error")
                    .put("requestId", requestId),
            )
            return
        }
        if (kind == "save") {
            val source = sourcePath ?: run {
                writeResult(context, requestId, error("Missing save source path"))
                return
            }
            val exported = CodexFileStore.exportToUri(context, source, uris.single())
            exported
                .put("requestId", requestId)
                .put("pending", false)
                .put("status", if (exported.optBoolean("ok")) "saved" else "error")
                .put("uri", uris.single().toString())
            writeResult(context, requestId, exported)
            return
        }

        val items = JSONArray()
        var allPersisted = true
        uris.forEach { uri ->
            val persisted = persistUriPermission(context, data, uri)
            allPersisted = allPersisted && persisted
            items.put(describeUri(context, uri).put("persisted", persisted))
        }
        writeResult(
            context,
            requestId,
            JSONObject()
                .put("ok", true)
                .put("pending", false)
                .put("status", "selected")
                .put("requestId", requestId)
                .put("persisted", allPersisted)
                .put("items", items),
        )
    }

    /** Imports only URIs recorded by a completed user picker request. */
    fun importSelected(context: Context, args: Map<String, String>): JSONObject {
        val requestId = requestIdArgument(args)
        val result = readResult(context, requestId)
            ?: return error("Unknown file request: " + requestId)
        if (result.optString("status") != "selected") {
            return error("File request is not a completed selection: " + result.optString("status"))
        }
        val items = result.optJSONArray("items")
            ?: return error("File request has no selected items")
        val explicitPath = args["targetPath"]?.takeIf { it.isNotBlank() }
        if (explicitPath != null && items.length() != 1) {
            return error("targetPath is valid only for one selected file")
        }
        val directory = safeDirectory(args["targetDir"] ?: "imports")
        val imported = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
                ?: return error("Selected item metadata is invalid")
            val uri = item.optString("uri").takeIf { it.isNotBlank() }
                ?: return error("Selected item has no URI")
            val name = safeName(item.optString("name"), "file-" + index)
            val target = explicitPath ?: directory + "/" + name
            val copied = CodexFileStore.importUri(
                context,
                Uri.parse(uri),
                target,
                args["overwrite"] == "true",
            )
            if (!copied.optBoolean("ok")) {
                return copied
                    .put("requestId", requestId)
                    .put("imported", imported)
            }
            imported.put(copied)
        }
        return JSONObject()
            .put("ok", true)
            .put("requestId", requestId)
            .put("status", "imported")
            .put("files", imported)
    }

    /** Exports an app-specific file to MediaStore Downloads with IS_PENDING protection. */
    fun exportMedia(context: Context, args: Map<String, String>): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return error("MediaStore pending exports require Android 10 or newer")
        }
        val sourcePath = args["sourcePath"]?.takeIf { it.isNotBlank() }
            ?: return error("file.export_media requires sourcePath")
        val name = safeName(args["displayName"] ?: sourcePath.substringAfterLast('/'), "export.bin")
        val relativePath = safeMediaDirectory(args["relativePath"] ?: "Codex")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, args["mime"] ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return error("Unable to create MediaStore download")
        return try {
            val copied = CodexFileStore.exportToUri(context, sourcePath, uri)
            if (!copied.optBoolean("ok")) {
                resolver.delete(uri, null, null)
                copied
            } else {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                copied
                    .put("uri", uri.toString())
                    .put("displayName", name)
                    .put("status", "saved")
            }
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            error(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun start(
        context: Context,
        args: Map<String, String>,
        requestId: String,
        kind: String,
    ): JSONObject {
        if (readResult(context, requestId) != null) {
            return error("Request ID already exists: " + requestId)
        }
        if (kind == "save" && args["sourcePath"].isNullOrBlank()) {
            return error("file.save requires sourcePath")
        }
        val pending = pending(requestId, kind)
        writeResult(context, requestId, pending)
        val intent = Intent(context, CodexFilePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_SOURCE_PATH, args["sourcePath"])
            .putExtra(EXTRA_DIRECTORY, args["directory"] == "true")
            .putExtra(EXTRA_ALLOW_MULTIPLE, args["allowMultiple"] == "true")
            .putExtra(EXTRA_MIME, args["mime"] ?: "*/*")
            .putExtra(EXTRA_MIME_TYPES, args["mimeTypes"])
            .putExtra(EXTRA_TITLE, args["title"])
        return try {
            context.startActivity(intent)
            pending
        } catch (exception: Exception) {
            val failure = error(exception.message ?: exception.javaClass.simpleName)
                .put("pending", false)
                .put("status", "error")
                .put("requestId", requestId)
            writeResult(context, requestId, failure)
            failure
        }
    }

    private fun pending(requestId: String, kind: String): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("pending", true)
            .put("status", "pending")
            .put("requestId", requestId)
            .put("kind", kind)

    private fun cancelled(requestId: String): JSONObject =
        error("User cancelled the file request")
            .put("pending", false)
            .put("status", "cancelled")
            .put("requestId", requestId)

    private fun selectedUris(data: Intent): List<Uri> {
        val clip = data.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(data.data)
    }

    private fun persistUriPermission(context: Context, data: Intent, uri: Uri): Boolean {
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) return false
        return try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun describeUri(context: Context, uri: Uri): JSONObject {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        return JSONObject()
            .put("uri", uri.toString())
            .put("name", safeName(name ?: uri.lastPathSegment.orEmpty(), "selected-file"))
            .put("size", size ?: JSONObject.NULL)
            .put("mime", context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    private fun readResult(context: Context, requestId: String): JSONObject? {
        val file = resultFile(context, requestId)
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeResult(context: Context, requestId: String, result: JSONObject) {
        val directory = File(context.filesDir, OPERATION_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create file operation directory")
        }
        val target = resultFile(context, requestId)
        val temporary = File(directory, "." + requestId + ".tmp")
        FileOutputStream(temporary).use { it.write(result.toString().toByteArray(Charsets.UTF_8)) }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IOException("Unable to replace file operation result")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Unable to persist file operation result")
        }
    }

    private fun resultFile(context: Context, requestId: String): File =
        File(File(context.filesDir, OPERATION_DIRECTORY), requestId + ".json")

    private fun requestIdArgument(args: Map<String, String>): String {
        val value = args["requestId"]?.takeIf { it.matches(REQUEST_ID) }
            ?: throw IllegalArgumentException("Missing or invalid requestId")
        return value
    }

    private fun safeName(value: String, fallback: String): String {
        val cleaned = value
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .trim()
            .take(MAX_NAME_LENGTH)
        return cleaned.ifEmpty { fallback }
    }

    private fun safeDirectory(value: String): String {
        val normalized = value.replace('\\', '/').trim('/')
        require(normalized.isNotBlank())
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." })
        return normalized
    }

    private fun safeMediaDirectory(value: String): String =
        safeDirectory(value).trimEnd('/') + "/"

    private const val EXTRA_REQUEST_ID = "requestId"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_SOURCE_PATH = "sourcePath"
    private const val EXTRA_DIRECTORY = "directory"
    private const val EXTRA_ALLOW_MULTIPLE = "allowMultiple"
    private const val EXTRA_MIME = "mime"
    private const val EXTRA_MIME_TYPES = "mimeTypes"
    private const val EXTRA_TITLE = "title"
    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private const val OPERATION_DIRECTORY = "codex-operations"
    private const val MAX_NAME_LENGTH = 128
    private const val DEFAULT_RESULT_WAIT_MS = 7000L
    private const val MAX_RESULT_WAIT_MS = 7000L
    private const val RESULT_POLL_MS = 100L
    private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,96}")
}

