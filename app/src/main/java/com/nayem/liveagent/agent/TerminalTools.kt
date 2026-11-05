package com.nayem.liveagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit

class TerminalTools(private val context: Context) {

    companion object {
        private val DANGEROUS_PATTERNS = listOf(
            "rm -rf",
            "mkfs",
            "dd if=",
            "shutdown",
            "reboot",
            ":(){",
            "format",
            "> /dev/",
            "chmod -r 777 /",
            "chown -r"
        )
    }

    fun isDangerous(command: String): Boolean {
        val lower = command.lowercase()
        return DANGEROUS_PATTERNS.any { lower.contains(it) }
    }

    fun runBuiltIn(command: String, timeoutMs: Long = 30000): ToolResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ToolResult(false, "Command timed out after ${timeoutMs}ms: $command")
            }
            val output = process.inputStream.bufferedReader().readText()
            val truncated = if (output.length > 4000) {
                output.substring(0, 4000) + "\n... (truncated)"
            } else {
                output
            }
            val exitCode = process.exitValue()
            ToolResult(
                success = exitCode == 0,
                detail = "exit=$exitCode\n$truncated"
            )
        } catch (e: Exception) {
            ToolResult(false, "Failed to run command \"$command\": ${e.message}")
        }
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    "com.termux",
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.termux", 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun runTermux(command: String): ToolResult {
        if (!isTermuxInstalled()) {
            return ToolResult(false, "Termux is not installed")
        }
        return try {
            val intent = Intent("com.termux.app.RUN_COMMAND").apply {
                putExtra(
                    "com.termux.app.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash"
                )
                putExtra(
                    "com.termux.app.RUN_COMMAND_ARGUMENTS",
                    arrayOf("-c", command)
                )
                putExtra("com.termux.app.RUN_COMMAND_BACKGROUND", false)
            }
            context.sendBroadcast(intent)
            ToolResult(
                true,
                "Sent to Termux: $command (output appears inside Termux, not in this app)"
            )
        } catch (e: Exception) {
            ToolResult(false, "Failed to send command to Termux: ${e.message}")
        }
    }
}
