package com.kaori.codex.bridge

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Owns bounded, sequential file uploads that keep large data out of broadcast extras. */
object CodexFileTransferStore {
    /** Starts an app-private upload and returns an opaque handle for subsequent chunks. */
    fun begin(context: Context, args: Map<String, String>): JSONObject {
        val path = args["path"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing path argument")
        val totalSize = args["size"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid size argument")
        require(totalSize in 0..CodexFileStore.MAX_FILE_BYTES) {
            "File exceeds " + CodexFileStore.MAX_FILE_BYTES + " bytes"
        }
        val directory = File(context.filesDir, TRANSFER_DIRECTORY)
        require(directory.exists() || directory.mkdirs()) { "Unable to create transfer directory" }
        val handle = UUID.randomUUID().toString()
        val temporary = File(directory, handle + ".tmp")
        require(temporary.createNewFile()) { "Unable to create upload" }
        val upload = Upload(
            path = path,
            totalSize = totalSize,
            overwrite = args["overwrite"] == "true",
            temporary = temporary,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(uploads) {
            cleanupExpired()
            uploads[handle] = upload
        }
        return JSONObject()
            .put("ok", true)
            .put("handle", handle)
            .put("path", path)
            .put("size", totalSize)
            .put("received", 0)
            .put("chunkSize", CodexFileStore.MAX_CHUNK_BYTES)
    }

    /** Appends one bounded chunk at the expected offset. */
    fun writeChunk(context: Context, args: Map<String, String>): JSONObject {
        val handle = handleArgument(args)
        val content64 = args["content64"] ?: throw IllegalArgumentException("Missing content64 argument")
        val bytes = Base64.decode(content64, Base64.DEFAULT)
        require(bytes.size <= CodexFileStore.MAX_CHUNK_BYTES) {
            "Chunk exceeds " + CodexFileStore.MAX_CHUNK_BYTES + " bytes"
        }
        return synchronized(uploads) {
            cleanupExpired()
            val upload = uploads[handle] ?: return@synchronized error("Upload handle is expired or unknown")
            val offset = args["offset"]?.toLongOrNull() ?: upload.received
            require(offset == upload.received) { "Chunk offset does not match the next expected offset" }
            require(upload.received + bytes.size <= upload.totalSize) {
                "Chunk exceeds the declared upload size"
            }
            FileOutputStream(upload.temporary, true).use { output -> output.write(bytes) }
            upload.received += bytes.size
            JSONObject()
                .put("ok", true)
                .put("handle", handle)
                .put("offset", offset)
                .put("nextOffset", upload.received)
                .put("size", upload.totalSize)
                .put("complete", upload.received == upload.totalSize)
        }
    }

    /** Verifies and commits an upload using CodexFileStore's backup policy. */
    fun commit(context: Context, args: Map<String, String>): JSONObject {
        val handle = handleArgument(args)
        return try {
            synchronized(uploads) {
                cleanupExpired()
                val upload = uploads[handle]
                    ?: return@synchronized error("Upload handle is expired or unknown")
                require(upload.received == upload.totalSize) { "Upload is incomplete" }
                val result = CodexFileStore.commitUploadedFile(
                    context = context,
                    relativePath = upload.path,
                    temporary = upload.temporary,
                    totalSize = upload.totalSize,
                    expectedSha256 = args["sha256"],
                    overwrite = upload.overwrite,
                )
                if (result.optBoolean("ok")) {
                    uploads.remove(handle)
                }
                result.put("handle", handle)
            }
        } catch (exception: Exception) {
            error(exception.message ?: exception.javaClass.simpleName).put("handle", handle)
        }
    }

    /** Cancels an upload and removes its temporary file. */
    fun cancel(args: Map<String, String>): JSONObject {
        val handle = handleArgument(args)
        return synchronized(uploads) {
            val upload = uploads.remove(handle)
            val removed = upload?.temporary?.delete() == true
            JSONObject()
                .put("ok", upload != null)
                .put("handle", handle)
                .put("cancelled", upload != null)
                .put("temporaryDeleted", removed)
        }
    }

    private fun handleArgument(args: Map<String, String>): String =
        args["handle"]?.takeIf { it.matches(HANDLE_PATTERN) }
            ?: throw IllegalArgumentException("Missing or invalid upload handle")

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val iterator = uploads.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > TRANSFER_TTL_MS) {
                entry.value.temporary.delete()
                iterator.remove()
            }
        }
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private data class Upload(
        val path: String,
        val totalSize: Long,
        val overwrite: Boolean,
        val temporary: File,
        val createdAt: Long,
        var received: Long = 0,
    )

    private val uploads = mutableMapOf<String, Upload>()
    private val HANDLE_PATTERN = Regex("[0-9a-fA-F-]{36}")
    private const val TRANSFER_DIRECTORY = "codex-transfers"
    private const val TRANSFER_TTL_MS = 30 * 60 * 1000L
}
