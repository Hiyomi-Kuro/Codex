package com.kaori.codex.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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

    fun shell(args: Map<String, String>): JSONObject {
        val command = args["command"]?.takeIf { it.isNotBlank() }
            ?: return JSONObject().put("ok", false).put("error", "Missing command argument")
        val serial = args["serial"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERIAL

        return try {
            val output = executeShell(serial, command)
            JSONObject()
                .put("ok", true)
                .put("serial", serial)
                .put("output", output)
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

    private fun executeShell(serial: String, command: String): String {
        Socket().use { socket ->
            connect(socket, SHELL_READ_TIMEOUT_MS)
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            writeRequest(output, "host:transport:" + serial)
            expectOkay(input)

            writeRequest(output, "shell:" + command)
            expectOkay(input)

            return readUntilEof(input)
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

    private fun readUntilEof(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() < MAX_OUTPUT_BYTES) {
            val remaining = MAX_OUTPUT_BYTES - bytes.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) {
                break
            }
            bytes.write(buffer, 0, count)
        }
        val text = bytes.toString(StandardCharsets.UTF_8.name())
        return if (bytes.size() >= MAX_OUTPUT_BYTES) {
            text + "\n[output truncated at " + MAX_OUTPUT_BYTES + " bytes]"
        } else {
            text
        }
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

    private const val SERVER_HOST = "127.0.0.1"
    private const val SERVER_PORT = 5037
    private const val DEFAULT_SERIAL = "127.0.0.1:5555"
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val HOST_READ_TIMEOUT_MS = 2500
    private const val SHELL_READ_TIMEOUT_MS = 15000
    private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
}
