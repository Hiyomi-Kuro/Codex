package com.kaori.codex.bridge

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Provides recoverable file operations inside Codex app-specific storage. */
object CodexFileStore {
    /** Reads a file from the app-specific Codex file directory. */
    fun read(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val file = targetFile(context, relativePath)
        requireRegularFile(file)
        requireSize(file)
        val bytes = file.readBytes()
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("size", bytes.size)
            .put("sha256", sha256(bytes))
            .put("content64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    /** Adds a file or replaces an existing file after creating a verified backup. */
    fun write(context: android.content.Context, args: Map<String, String>): JSONObject {
        val relativePath = pathArgument(args)
        val content64 = args["content64"] ?: throw IllegalArgumentException("Missing content64 argument")
        val content = Base64.decode(content64, Base64.DEFAULT)
        require(content.size <= MAX_FILE_BYTES) { "File exceeds $MAX_FILE_BYTES bytes" }
        val file = targetFile(context, relativePath)
        if (file.exists() && file.isDirectory) {
            throw IllegalArgumentException("Target is a directory: $relativePath")
        }
        val overwrite = args["overwrite"] == "true"
        if (file.exists() && !overwrite) {
            return error("File exists; set overwrite=true after reviewing the target")
        }

        val backup = if (file.exists()) {
            backupExisting(context, relativePath, file)
        } else {
            null
        }
        val temporary = File(file.parentFile, ".codex-${UUID.randomUUID()}.tmp")
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
                    require(size <= MAX_FILE_BYTES) { "File exceeds $MAX_FILE_BYTES bytes" }
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
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val BUFFER_SIZE = 8192
}
