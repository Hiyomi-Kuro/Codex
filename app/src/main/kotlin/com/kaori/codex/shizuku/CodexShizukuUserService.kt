package com.kaori.codex.shizuku

import android.os.SystemClock
import androidx.annotation.Keep
import org.json.JSONObject

/** Runs one explicitly confirmed command in the Shizuku UserService identity. */
@Keep
class CodexShizukuUserService : IShizukuCommandService.Stub() {
    override fun execute(command: String): String {
        val process = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (exception: Exception) {
            return JSONObject().put("ok", false).put("error", exception.message ?: exception.javaClass.simpleName).toString()
        }
        val output = StringBuilder()
        val reader = Thread({
            process.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (output.length < MAX_OUTPUT_CHARS) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_CHARS - output.length))
                    if (count < 0) break
                    output.append(String(buffer, 0, count, Charsets.UTF_8))
                }
            }
        }, "CodexShizukuReader").apply { isDaemon = true }
        reader.start()

        var exitCode: Int? = null
        val deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                exitCode = process.exitValue()
                break
            } catch (notExited: IllegalThreadStateException) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    process.destroy()
                    return JSONObject().put("ok", false).put("error", "Command interrupted").toString()
                }
            }
        }
        val timedOut = exitCode == null
        if (timedOut) process.destroy()
        reader.join(STREAM_JOIN_TIMEOUT_MS)
        return JSONObject()
            .put("ok", !timedOut && exitCode == 0)
            .put("exitCode", exitCode ?: JSONObject.NULL)
            .put("output", output.toString())
            .apply {
                if (timedOut) put("error", "Shizuku command timed out")
                if (output.length >= MAX_OUTPUT_CHARS) put("outputTruncated", true)
            }
            .toString()
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val BUFFER_SIZE = 8192
        const val MAX_OUTPUT_CHARS = 1024 * 1024
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 25L
        const val STREAM_JOIN_TIMEOUT_MS = 1000L
    }
}
