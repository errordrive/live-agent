package com.nayem.liveagent.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-crash log (v1.2.0). A global UncaughtExceptionHandler (installed in
 * MainActivity) writes the stacktrace + timestamp + app version to a file in
 * private internal storage, so the next crash report carries the real
 * stacktrace instead of "the app crashed at some point". Reading it never
 * throws; an empty/absent file means "no crash recorded".
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun file(context: Context): File =
        File(File(context.filesDir, "live_agent"), FILE_NAME)

    fun save(context: Context, thread: Thread, throwable: Throwable, version: String) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val trace = throwable.stackTraceToString().take(8000)
            f.writeText(
                "Live Agent $version — crash at $time (thread: ${thread.name})\n" +
                    "${throwable.javaClass.name}: ${throwable.message}\n$trace"
            )
        } catch (_: Exception) {
            // Never crash the app to report a crash.
        }
    }

    fun read(context: Context): String? = try {
        val f = file(context)
        if (!f.exists()) null else f.readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }
}
