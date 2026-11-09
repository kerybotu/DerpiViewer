package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque

/** Local, bounded crash context. It never records request URLs, credentials, or user text. */
object CrashDiagnostics {
    private const val TAG = "AppDiagnostics"
    private const val MAX_EVENTS = 80
    private const val MAX_CRASH_BYTES = 96 * 1024
    private val lock = Any()
    private val events = ArrayDeque<String>()
    @Volatile private var applicationContext: Context? = null
    @Volatile private var currentPage: String = "unknown"

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun activityEvent(activity: Activity, event: String) {
        currentPage = activity.javaClass.simpleName
        recordEvent(currentPage, event)
    }

    fun fragmentEvent(activity: Activity, fragmentName: String, event: String) {
        val safeFragment = fragmentName.filter { it.isLetterOrDigit() || it in "_.-" }.take(64)
        currentPage = "${activity.javaClass.simpleName}/$safeFragment"
        recordEvent(currentPage, event)
    }

    fun recordEvent(page: String, event: String) {
        val safePage = page.filter { it.isLetterOrDigit() || it in "_.-" }.take(64).ifBlank { "unknown" }
        val safeEvent = event.filter { it.isLetterOrDigit() || it in "_.:- =,#/@" }.take(120)
        val line = "${System.currentTimeMillis()} thread=${Thread.currentThread().name} page=$safePage event=$safeEvent"
        synchronized(lock) {
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
        Log.d(TAG, line)
    }

    fun recordFailure(page: String, operation: String, error: Exception) {
        recordEvent(page, "$operation failed ${error.javaClass.simpleName}")
        Log.e(TAG, "$page $operation failed: ${error.javaClass.name}: ${redactSensitiveText(error.message.orEmpty())}")
    }

    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrashReport(thread, error)
            } catch (diagnosticError: Throwable) {
                Log.e(TAG, "Unable to persist crash diagnostics", diagnosticError)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        val context = applicationContext ?: return
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val recentEvents = synchronized(lock) { events.joinToString("\n") }
        val report = buildString {
            appendLine("DerpiViewer crash diagnostics")
            appendLine("time=${System.currentTimeMillis()}")
            @Suppress("DEPRECATION")
            appendLine("app=${packageInfo?.versionName ?: "unknown"} (${packageInfo?.versionCode ?: 0})")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
            appendLine("display=${Build.DISPLAY}")
            appendLine("thread=${thread.name}")
            appendLine("page=$currentPage")
            appendLine("exception=${error.javaClass.name}: ${redactSensitiveText(error.message.orEmpty()).take(512)}")
            appendLine("recent_events:")
            appendLine(recentEvents)
            appendLine("stacktrace:")
            append(redactSensitiveText(stack))
        }
        val directory = File(context.filesDir, "diagnostics")
        if (!directory.exists() && !directory.mkdirs()) return
        val file = File(directory, "last-crash.txt")
        FileOutputStream(file, false).use { output ->
            output.write(report.toByteArray(Charsets.UTF_8).take(MAX_CRASH_BYTES).toByteArray())
            output.fd.sync()
        }
    }

    fun lastCrashFile(context: Context): File? =
        File(context.filesDir, "diagnostics/last-crash.txt").takeIf { it.isFile && it.length() > 0L }

    internal fun redactSensitiveText(value: String): String = value
        .replace(Regex("(?i)([?&](?:key|api_key|token|_csrf_token)=)[^&\\s]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(api[_-]?key|authorization|cookie|_csrf_token)(\\s*[:=]\\s*)[^\\s,;]+"), "$1$2[REDACTED]")
}
