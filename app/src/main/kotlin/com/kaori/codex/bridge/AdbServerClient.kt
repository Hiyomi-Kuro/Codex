package com.kaori.codex.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Minimal ADB-server client for the Termux Ubuntu adb server on localhost.
 *
 * The app itself remains an ordinary Android UID. A shell command sent through this
 * client is executed by the selected adbd transport as Android's shell UID.
 */
object AdbServerClient {
    fun status(): JSONObject {
        return try {
            val response = queryHost("host:devices-l")
            val devices = JSONArray()
            response.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val fields = line.split(Regex("\\s+"), limit = 3)
                    devices.put(
                        JSONObject()
                            .put("serial", fields.getOrNull(0) ?: JSONObject.NULL)
                            .put("state", fields.getOrNull(1) ?: JSONObject.NULL)
                            .put("details", fields.getOrNull(2) ?: JSONObject.NULL),
                    )
                }
            JSONObject()
                .put("ok", true)
                .put("server", SERVER_HOST + ":" + SERVER_PORT)
                .put("defaultSerial", DEFAULT_SERIAL)
                .put("devices", devices)
        } catch (exception: Exception) {
            JSONObject()
                .put("ok", false)
                .put("server", SERVER_HOST + ":" + SERVER_PORT)
                .put("defaultSerial", DEFAULT_SERIAL)
                .put("error", exception.message ?: exception.javaClass.simpleName)
        }
    }

    /** Executes a command through ADB shell v2 and returns bounded inline output plus a handle. */
    fun shell(context: Context, args: Map<String, String>): JSONObject {
        val command = args["command"]?.takeIf { it.isNotBlank() }
            ?: return JSONObject().put("ok", false).put("error", "Missing command argument")
        val serial = args["serial"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERIAL

        return try {
            val shell = executeShell(serial, command)
            val stdout = shell.stdout
            val stderr = shell.stderr
            val combined = combine(stdout, stderr)
            val needsHandle = stdout.size + stderr.size > MAX_INLINE_OUTPUT_BYTES || shell.truncated
            val result = JSONObject()
                .put("ok", shell.exitCode == 0 && !shell.timedOut)
                .put("serial", serial)
                .put("output", String(combined, StandardCharsets.UTF_8))
                .put("stdout", inlineText(stdout))
                .put("stderr", inlineText(stderr))
                .put("exitCode", shell.exitCode ?: JSONObject.NULL)
                .put("timedOut", shell.timedOut)
                .put("truncated", shell.truncated)
                .put("outputTruncated", stdout.size + stderr.size > combined.size)
        if (needsHandle) {
                result.put("outputHandle", CodexResultStore.create(context, stdout, stderr))
            }
            when {
                shell.timedOut -> result.put("error", "ADB shell timed out")
                shell.exitCode == null -> result.put("error", "ADB shell ended without an exit code")
                shell.exitCode != 0 -> result.put("error", "ADB shell exited with code " + shell.exitCode)
            }
            result
        } catch (exception: Exception) {
            JSONObject()
                .put("ok", false)
                .put("serial", serial)
                .put("error", exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun queryHost(request: String): String {
        Socket().use { socket ->
            connect(socket, HOST_READ_TIMEOUT_MS)
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            writeRequest(output, request)
            expectOkay(input)
            return readLengthPrefixedPayload(input)
        }
    }

    private fun executeShell(serial: String, command: String): ShellResult {
        Socket().use { socket ->
            connect(socket, SHELL_READ_TIMEOUT_MS)
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            writeRequest(output, "host:transport:" + serial)
            expectOkay(input)

            writeRequest(output, "shell,v2,TERM=dumb:" + command)
            expectOkay(input)

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exitCode: Int? = null
            var timedOut = false
            var truncated = false
            try {
                while (true) {
                    val streamId = input.read()
                    if (streamId < 0) {
                        break
                    }
                    val length = littleEndianInt(readExactly(input, 4))
                    require(length >= 0 && length <= MAX_PACKET_BYTES) {
                        "ADB shell packet is too large"
                    }
                    when (streamId) {
                        STDOUT_ID -> truncated = readPayload(
                            input,
                            length,
                            stdout,
                            MAX_OUTPUT_BYTES - stdout.size() - stderr.size(),
                        ) || truncated
                        STDERR_ID -> truncated = readPayload(
                            input,
                            length,
                            stderr,
                            MAX_OUTPUT_BYTES - stdout.size() - stderr.size(),
                        ) || truncated
                        EXIT_ID -> {
                            val payload = readExactly(input, length)
                            exitCode = payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                            break
                        }
                        else -> skipExactly(input, length)
                    }
                }
            } catch (_: SocketTimeoutException) {
                timedOut = true
            }
            return ShellResult(
                stdout = stdout.toByteArray(),
                stderr = stderr.toByteArray(),
                exitCode = exitCode,
                timedOut = timedOut,
                truncated = truncated,
            )
        }
    }

    private fun connect(socket: Socket, readTimeoutMs: Int) {
        socket.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS)
        socket.soTimeout = readTimeoutMs
    }

    private fun writeRequest(output: BufferedOutputStream, request: String) {
        val payload = request.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= 0xffff) { "ADB request is too large" }
        val header = payload.size.toString(16).uppercase().padStart(4, '0')
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }

    private fun expectOkay(input: BufferedInputStream) {
        val status = String(readExactly(input, 4), StandardCharsets.US_ASCII)
        when (status) {
            "OKAY" -> return
            "FAIL" -> throw IOException(
                "ADB server rejected request: " + readLengthPrefixedPayload(input),
            )
            else -> throw IOException("Unexpected ADB server status: " + status)
        }
    }

    private fun readLengthPrefixedPayload(input: BufferedInputStream): String {
        val header = String(readExactly(input, 4), StandardCharsets.US_ASCII)
        val length = header.toIntOrNull(16)
            ?: throw IOException("Invalid ADB payload length: " + header)
        return String(readExactly(input, length), StandardCharsets.UTF_8)
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) {
                throw EOFException("ADB server closed the connection")
            }
            offset += count
        }
        return bytes
    }

    private fun readPayload(
        input: BufferedInputStream,
        length: Int,
        target: ByteArrayOutputStream,
        available: Int,
    ): Boolean {
        var remaining = length
        var kept = 0
        var truncated = false
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) {
                throw EOFException("ADB server closed the shell stream")
            }
            val copy = minOf(count, maxOf(0, available - kept))
            if (copy > 0) {
                target.write(buffer, 0, copy)
                kept += copy
            }
            if (copy < count) {
                truncated = true
            }
            remaining -= count
        }
        return truncated
    }

    private fun skipExactly(input: BufferedInputStream, length: Int) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) {
                throw EOFException("ADB server closed the shell stream")
            }
            remaining -= count
        }
    }

    private fun littleEndianInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)

    private fun combine(stdout: ByteArray, stderr: ByteArray): ByteArray {
        val count = minOf(MAX_INLINE_OUTPUT_BYTES, stdout.size + stderr.size)
        val combined = ByteArray(count)
        val stdoutCount = minOf(stdout.size, count)
        stdout.copyInto(combined, endIndex = stdoutCount)
        if (stdoutCount < count) {
            stderr.copyInto(combined, destinationOffset = stdoutCount, endIndex = stdoutCount + (count - stdoutCount))
        }
        return combined
    }

    private fun inlineText(bytes: ByteArray): String =
        String(bytes.copyOf(minOf(bytes.size, MAX_INLINE_OUTPUT_BYTES)), StandardCharsets.UTF_8)

    private data class ShellResult(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int?,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    private const val SERVER_HOST = "127.0.0.1"
    private const val SERVER_PORT = 5037
    private const val DEFAULT_SERIAL = "127.0.0.1:5555"
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val HOST_READ_TIMEOUT_MS = 2500
    private const val SHELL_READ_TIMEOUT_MS = 15000
    private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    private const val MAX_INLINE_OUTPUT_BYTES = 32 * 1024
    private const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    private const val BUFFER_SIZE = 8192
    private const val STDOUT_ID = 1
    private const val STDERR_ID = 2
    private const val EXIT_ID = 3
}
