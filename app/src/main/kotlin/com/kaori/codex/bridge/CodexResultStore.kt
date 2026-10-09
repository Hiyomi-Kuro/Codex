package com.kaori.codex.bridge

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Stores larger command results privately and exposes only bounded chunks over broadcasts. */
object CodexResultStore {
    /** Persists captured stdout and stderr and returns an opaque result handle. */
    fun create(context: Context, stdout: ByteArray, stderr: ByteArray): String {
        val directory = File(context.filesDir, RESULT_DIRECTORY)
        require(directory.exists() || directory.mkdirs()) { "Unable to create result directory" }
        cleanup(directory)
        val handle = UUID.randomUUID().toString()
        FileOutputStream(File(directory, handle + ".stdout")).use { it.write(stdout) }
        FileOutputStream(File(directory, handle + ".stderr")).use { it.write(stderr) }
        return handle
    }

    /** Reads one bounded stdout or stderr chunk for a previously returned result handle. */
    fun readChunk(context: Context, args: Map<String, String>): JSONObject {
        val handle = args["handle"]?.takeIf { it.matches(HANDLE_PATTERN) }
            ?: throw IllegalArgumentException("Missing or invalid result handle")
        val stream = args["stream"]?.takeIf { it == "stdout" || it == "stderr" } ?: "stdout"
        val directory = File(context.filesDir, RESULT_DIRECTORY)
        val file = File(directory, handle + "." + stream)
        require(file.exists() && file.isFile) { "Result handle is expired or unknown" }
        val offset = args["offset"]?.toLongOrNull() ?: 0L
        require(offset >= 0L && offset <= file.length()) { "Invalid result chunk offset" }
        val requested = args["length"]?.toIntOrNull() ?: MAX_CHUNK_BYTES
        require(requested in 1..MAX_CHUNK_BYTES) {
            "Chunk length must be between 1 and " + MAX_CHUNK_BYTES + " bytes"
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
            .put("handle", handle)
            .put("stream", stream)
            .put("offset", offset)
            .put("nextOffset", nextOffset)
            .put("size", file.length())
            .put("eof", nextOffset >= file.length())
            .put("content64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun cleanup(directory: File) {
        val cutoff = System.currentTimeMillis() - RESULT_TTL_MS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private val HANDLE_PATTERN = Regex("[0-9a-fA-F-]{36}")
    private const val RESULT_DIRECTORY = "codex-results"
    private const val MAX_CHUNK_BYTES = 32 * 1024
    private const val RESULT_TTL_MS = 30 * 60 * 1000L
}
