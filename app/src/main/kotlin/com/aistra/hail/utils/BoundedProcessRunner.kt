package com.aistra.hail.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProcessExecution(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false
)

/** Drains even discarded output. A child cannot fill its pipe while we wait for exit. */
internal object BoundedProcessRunner {
    fun run(program: List<String>, command: String, timeoutMillis: Long = 30_000,
            maxOutputBytes: Int = 256 * 1024, startProcess: (() -> Process)? = null): ProcessExecution {
        require(program.isNotEmpty() && timeoutMillis > 0 && maxOutputBytes in 1..1024 * 1024)
        require(command.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Command is too large" }
        if (Thread.currentThread().isInterrupted) return ProcessExecution(130, "Cancelled", cancelled = true)
        val output = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val ioFailed = AtomicBoolean(false)
        var process: Process? = null
        var reader: Thread? = null
        var writer: Thread? = null
        var errorReader: Thread? = null
        var timedOut = false
        var cancelled = false
        var exit = 1
        try {
            val child = startProcess?.invoke() ?: ProcessBuilder(program).redirectErrorStream(true).start()
            process = child
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            fun drain(inputStream: java.io.InputStream, name: String) = Thread({
                try {
                    inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            synchronized(output) {
                                val kept = minOf(count, maxOutputBytes - output.size())
                                if (kept > 0) output.write(buffer, 0, kept)
                                if (kept < count) truncated.set(true)
                            }
                        }
                    }
                } catch (_: IOException) { ioFailed.set(true) }
            }, name).apply { isDaemon = true; start() }
            reader = drain(child.inputStream, "hail-shell-output")
            errorReader = drain(child.errorStream, "hail-shell-error")
            writer = Thread({
                try { child.outputStream.use { it.write((command + "\n").toByteArray(Charsets.UTF_8)) } }
                catch (_: IOException) { ioFailed.set(true) }
            }, "hail-shell-input").apply { isDaemon = true; start() }

            while (true) {
                val code = try { child.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (code != null && !reader.isAlive && !errorReader.isAlive && !writer.isAlive) {
                    exit = if (code == 0 && ioFailed.get()) 1 else code
                    break
                }
                if (System.nanoTime() >= deadline) { timedOut = true; exit = 124; break }
                Thread.sleep(10)
            }
        } catch (_: InterruptedException) {
            cancelled = true; exit = 130
        } catch (error: IOException) {
            synchronized(output) {
                val bytes = (error.javaClass.simpleName + ": process could not be started").toByteArray()
                output.write(bytes, 0, minOf(bytes.size, maxOutputBytes))
            }
        } finally {
            // Clear interrupt just for bounded cleanup, then restore the caller's cancellation.
            if (Thread.interrupted()) cancelled = true
            process?.let { child ->
                runCatching { child.destroy() }
                // Available on JVM/Android O+. Reflection preserves the Android 6 floor.
                runCatching { Process::class.java.getMethod("destroyForcibly").invoke(child) }
                runCatching { child.outputStream.close() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
            }
            try { reader?.join(200); errorReader?.join(200); writer?.join(200) }
            catch (_: InterruptedException) { cancelled = true }
            if (cancelled) Thread.currentThread().interrupt()
        }
        val text = synchronized(output) { String(output.toByteArray(), Charsets.UTF_8) }
        return ProcessExecution(if (cancelled) 130 else exit, text, timedOut, cancelled, truncated.get())
    }
}
