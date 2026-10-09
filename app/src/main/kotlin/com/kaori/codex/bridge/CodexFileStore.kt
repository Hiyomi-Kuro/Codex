package com.kaori.codex.bridge

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Provides recoverable file operations inside Codex app-specific storage. */
object CodexFileStore {
    /** Reads a file inline when it fits the Binder-safe response limit. */
    fun read(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val file = targetFile(context, relativePath)
        requireRegularFile(file)
        requireSize(file)
        val bytes = file.readBytes()
        val result = JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("size", bytes.size)
            .put("sha256", sha256(bytes))
        if (bytes.size <= MAX_INLINE_BYTES) {
            result.put("content64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } else {
            result
                .put("chunked", true)
                .put("chunkSize", MAX_CHUNK_BYTES)
                .put("maxInlineBytes", MAX_INLINE_BYTES)
        }
        return result
    }

    /** Reads a bounded app-private image for in-memory visual analysis. */
    fun readForAnalysis(context: android.content.Context, relativePath: String): ByteArray {
        val file = targetFile(context, relativePath)
        requireRegularFile(file)
        require(file.length() <= MAX_ANALYSIS_BYTES) {
            "Analysis image exceeds $MAX_ANALYSIS_BYTES bytes"
        }
        return file.readBytes()
    }
    /** Reads one bounded file chunk without placing the complete file in a broadcast result. */
    fun readChunk(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val file = targetFile(context, relativePath)
        requireRegularFile(file)
        requireSize(file)
        val offset = args["offset"]?.toLongOrNull() ?: 0L
        require(offset >= 0L && offset <= file.length()) { "Invalid file chunk offset" }
        val requested = args["length"]?.toIntOrNull() ?: MAX_CHUNK_BYTES
        require(requested in 1..MAX_CHUNK_BYTES) {
            "Chunk length must be between 1 and $MAX_CHUNK_BYTES bytes"
        }
        val count = minOf(requested.toLong(), file.length() - offset).toInt()
        val bytes = ByteArray(count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var position = 0
            while (position < count) {
                val read = input.read(bytes, position, count - position)
                if (read < 0) break
                position += read
            }
        }
        val nextOffset = offset + count
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("offset", offset)
            .put("nextOffset", nextOffset)
            .put("size", file.length())
            .put("eof", nextOffset >= file.length())
            .put("sha256", sha256(file.readBytes()))
            .put("content64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    /** Adds a file inline when it fits the Binder-safe response limit. */
    fun write(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val content64 = args["content64"] ?: throw IllegalArgumentException("Missing content64 argument")
        val content = Base64.decode(content64, Base64.DEFAULT)
        require(content.size <= MAX_FILE_BYTES) { "File exceeds $MAX_FILE_BYTES bytes" }
        if (content.size > MAX_INLINE_BYTES) {
            return error("File is larger than the inline Binder-safe limit; use file.write_begin and file.write_chunk")
                .put("requiresChunking", true)
                .put("size", content.size)
                .put("maxInlineBytes", MAX_INLINE_BYTES)
        }
        return writeContent(context, relativePath, content, args["overwrite"] == "true")
    }

    private fun writeContent(
        context: android.content.Context,
        relativePath: String,
        content: ByteArray,
        overwrite: Boolean,
    ): JSONObject {
        val file = targetFile(context, relativePath)
        if (file.exists() && file.isDirectory) {
            throw IllegalArgumentException("Target is a directory: $relativePath")
        }
        if (file.exists() && !overwrite) {
            return error("File exists; set overwrite=true after reviewing the target")
        }

        val backup = if (file.exists()) {
            backupExisting(context, relativePath, file)
        } else {
            null
        }
        val temporary = File(file.parentFile, ".codex-" + UUID.randomUUID() + ".tmp")
        return try {
            temporary.parentFile?.mkdirs()
            FileOutputStream(temporary).use { output -> output.write(content) }
            require(temporary.length() == content.size.toLong()) { "Temporary file verification failed" }
            if (!temporary.renameTo(file)) {
                throw IOException("Unable to replace file: $relativePath")
            }
            JSONObject()
                .put("ok", true)
                .put("path", relativePath)
                .put("size", content.size)
                .put("sha256", sha256(content))
                .put("created", backup == null)
                .put("backup", backupJson(backup))
        } catch (exception: Exception) {
            temporary.delete()
            error((exception.message ?: exception.javaClass.simpleName) + backupSuffix(backup))
        }
    }

    /** Commits a verified chunked upload while preserving the normal backup policy. */
    fun commitUploadedFile(
        context: android.content.Context,
        relativePath: String,
        temporary: File,
        totalSize: Long,
        expectedSha256: String?,
        overwrite: Boolean,
    ): JSONObject {
        require(totalSize in 0..MAX_FILE_BYTES) { "Invalid uploaded file size" }
        requireRegularFile(temporary)
        require(temporary.length() == totalSize) { "Uploaded file is incomplete" }
        val actualSha256 = sha256(temporary.readBytes())
        if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return error("Uploaded file SHA-256 does not match expectedSha256")
                .put("expectedSha256", expectedSha256)
                .put("actualSha256", actualSha256)
        }
        val target = targetFile(context, relativePath)
        if (target.exists() && target.isDirectory) {
            return error("Target is a directory: $relativePath")
        }
        if (target.exists() && !overwrite) {
            return error("File exists; set overwrite=true after reviewing the target")
        }
        val backup = if (target.exists()) {
            backupExisting(context, relativePath, target)
        } else {
            null
        }
        return try {
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to replace file: $relativePath")
            }
            JSONObject()
                .put("ok", true)
                .put("path", relativePath)
                .put("size", totalSize)
                .put("sha256", actualSha256)
                .put("created", backup == null)
                .put("backup", backupJson(backup))
                .put("chunked", true)
        } catch (exception: Exception) {
            error((exception.message ?: exception.javaClass.simpleName) + backupSuffix(backup))
        }
    }


    /** Deletes a file only after creating a verified recoverable backup. */
    fun delete(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val file = targetFile(context, relativePath)
        requireRegularFile(file)
        val backup = backupExisting(context, relativePath, file)
        if (!file.delete()) {
            return error("Unable to delete file; backup retained at ${backup.dataFile}")
        }
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("deleted", true)
            .put("backup", backupJson(backup))
    }

    /** Restores a backed-up file; replacing an existing target requires explicit confirmation. */
    fun restore(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val target = targetFile(context, relativePath)
        val replacing = target.exists()
        if (replacing && args["confirm"] != "true") {
            return error("Restore would replace the current file; set confirm=true")
        }
        val backup = backupPaths(context, relativePath)
        requireRegularFile(backup.dataFile)
        requireSize(backup.dataFile)
        val temporary = File(target.parentFile, ".codex-" + UUID.randomUUID() + ".tmp")
        val displaced = if (replacing) {
            File(target.parentFile, ".codex-" + UUID.randomUUID() + ".current")
        } else {
            null
        }
        var targetDisplaced = false
        return try {
            copyVerified(backup.dataFile, temporary)
            if (displaced != null) {
                if (!target.renameTo(displaced)) {
                    throw IOException("Unable to preserve the current file before restore: $relativePath")
                }
                targetDisplaced = true
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to restore file: $relativePath")
            }
            if (displaced != null && !displaced.delete()) {
                throw IOException("Restored file but could not remove the temporary displaced copy")
            }
            JSONObject()
                .put("ok", true)
                .put("path", relativePath)
                .put("restored", true)
                .put("backup", backupJson(backup))
        } catch (exception: Exception) {
            temporary.delete()
            if (targetDisplaced && !target.exists() && displaced?.exists() == true) {
                displaced.renameTo(target)
            }
            error(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /** Commits a backup removal only after the caller explicitly confirms it. */
    fun commit(context: android.content.Context, args: Map<String, String>): JSONObject {
        if (args["confirm"] != "true") {
            return error("Backup commit requires confirm=true")
        }
        val relativePath = pathArgument(args)
        val backup = backupPaths(context, relativePath)
        requireRegularFile(backup.dataFile)
        if (!backup.metadataFile.delete() && backup.metadataFile.exists()) {
            return error("Unable to remove backup metadata")
        }
        if (!backup.dataFile.delete() && backup.dataFile.exists()) {
            return error("Unable to remove backup data")
        }
        return JSONObject().put("ok", true).put("path", relativePath).put("backupCommitted", true)
    }

    /** Imports one user-selected URI into app-specific storage with the normal backup policy. */
    fun importUri(
        context: android.content.Context,
        uri: android.net.Uri,
        relativePath: String,
        overwrite: Boolean,
    ): JSONObject {
        val target = targetFile(context, relativePath)
        if (target.exists() && !overwrite) {
            return error("File exists; set overwrite=true after reviewing the target")
        }
        val content = try {
            context.contentResolver.openInputStream(uri)?.use(::readLimited)
                ?: return error("Selected URI could not be opened")
        } catch (exception: Exception) {
            return error(exception.message ?: exception.javaClass.simpleName)
        }
        return writeContent(context, relativePath, content, overwrite)
    }

    /** Copies one app-specific file to a user-selected URI and removes partial output on failure. */
    fun exportToUri(
        context: android.content.Context,
        sourcePath: String,
        destination: android.net.Uri,
    ): JSONObject {
        val source = targetFile(context, sourcePath)
        requireRegularFile(source)
        requireSize(source)
        return try {
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: return error("Selected destination URI could not be opened")
            val digest = output.use { copyToOutput(source, it) }
            JSONObject()
                .put("ok", true)
                .put("sourcePath", sourcePath)
                .put("size", digest.size)
                .put("sha256", digest.hash)
        } catch (exception: Exception) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            error(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun backupExisting(context: android.content.Context, relativePath: String, source: File): Backup {
        val backup = backupPaths(context, relativePath)
        if (backup.dataFile.exists() || backup.metadataFile.exists()) {
            throw IllegalStateException("A backup already exists; restore or commit it before another destructive operation")
        }
        backup.dataFile.parentFile?.mkdirs()
        val digest = copyVerified(source, backup.dataFile)
        val metadata = JSONObject()
            .put("originalPath", relativePath)
            .put("backupPath", backup.dataFile.toString())
            .put("createdAt", System.currentTimeMillis())
            .put("size", digest.size)
            .put("sha256", digest.hash)
        backup.metadataFile.writeText(metadata.toString(), Charsets.UTF_8)
        return backup
    }

    private fun targetFile(context: android.content.Context, relativePath: String): File {
        val root = File(context.filesDir, FILE_DIRECTORY)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create app-specific file directory")
        }
        return containedFile(root, relativePath)
    }

    private fun backupPaths(context: android.content.Context, relativePath: String): Backup {
        val root = File(context.filesDir, BACKUP_DIRECTORY)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create backup directory")
        }
        val data = containedFile(root, "$relativePath.codex-backup")
        val metadata = containedFile(root, "$relativePath.codex-backup.json")
        return Backup(data, metadata)
    }

    private fun containedFile(root: File, relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        val parts = normalized.split('/')
        require(normalized.isNotBlank() && parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Invalid relative file path"
        }
        require(!normalized.startsWith('/') && !normalized.contains('\u0000')) {
            "Invalid relative file path"
        }
        val canonicalRoot = root.canonicalFile
        val file = File(root, normalized).canonicalFile
        require(file.path == canonicalRoot.path || file.path.startsWith(canonicalRoot.path + File.separator)) {
            "File path escapes app-specific storage"
        }
        return file
    }

    private fun pathArgument(args: Map<String, String>): String =
        args["path"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing path argument")

    private fun requireRegularFile(file: File) {
        require(file.exists() && file.isFile) { "File not found: ${file.name}" }
    }

    private fun requireSize(file: File) {
        require(file.length() <= MAX_FILE_BYTES) { "File exceeds $MAX_FILE_BYTES bytes" }
    }

    private fun copyVerified(source: File, destination: File): Digest {
        requireRegularFile(source)
        requireSize(source)
        destination.parentFile?.mkdirs()
        var size = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_FILE_BYTES) { "File exceeds " + MAX_FILE_BYTES + " bytes" }
                    output.write(buffer, 0, count)
                }
            }
        }
        val digest = Digest(size, sha256(destination.readBytes()))
        require(destination.length() == size && digest.hash == sha256(source.readBytes())) {
            "Backup verification failed"
        }
        return digest
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_FILE_BYTES) { "File exceeds " + MAX_FILE_BYTES + " bytes" }
        }
        return output.toByteArray()
    }

    private fun copyToOutput(source: File, output: OutputStream): Digest {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= MAX_FILE_BYTES) { "File exceeds " + MAX_FILE_BYTES + " bytes" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        return Digest(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun backupJson(backup: Backup?): Any =
        backup?.let { JSONObject().put("data", it.dataFile.toString()).put("metadata", it.metadataFile.toString()) }
            ?: JSONObject.NULL

    private fun backupSuffix(backup: Backup?): String =
        backup?.let { "; backup retained at ${it.dataFile}" } ?: ""

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private data class Backup(val dataFile: File, val metadataFile: File)
    private data class Digest(val size: Long, val hash: String)

    private const val FILE_DIRECTORY = "codex-files"
    private const val BACKUP_DIRECTORY = "codex-backups"
    internal const val MAX_FILE_BYTES = 8 * 1024 * 1024
    internal const val MAX_CHUNK_BYTES = 32 * 1024
    private const val MAX_INLINE_BYTES = 32 * 1024
    private const val MAX_ANALYSIS_BYTES = 512 * 1024
    private const val BUFFER_SIZE = 8192
}
