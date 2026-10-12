package com.example.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * File logging for the "-log" build variant (BuildConfig.ENABLE_FILE_LOGGING).
 *
 * - Captures this process' own logcat lines into noesis-session.log
 *   (no permissions needed; apps can always read their own logs).
 * - Installs an UncaughtExceptionHandler that writes a crash report
 *   (device info + session-log tail + stacktrace) and chains to the
 *   previous handler so the system still shows the crash dialog.
 * - On next start, copies the pending crash report into
 *   Downloads/NoesisLab/ (MediaStore, API 29+), reachable via USB/MTP
 *   and any file manager — no root needed.
 * - Logs live in getExternalFilesDir("logs") — also MTP-visible.
 */
object LogCollector {
    private const val TAG = "NoesisLog"
    private const val SESSION_FILE = "noesis-session.log"
    private const val CRASH_LATEST = "noesis-crash-latest.txt"
    private const val MAX_SESSION_BYTES = 1024L * 1024L

    @Volatile
    private var running = false
    private var logcatThread: Thread? = null

    fun logsDir(context: Context): File {
        val base = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        base.mkdirs()
        return base
    }

    fun sessionLogFile(context: Context): File = File(logsDir(context), SESSION_FILE)

    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext
        installCrashHandler(appContext)
        copyPendingCrashToDownloads(appContext)
        Log.i(TAG, "file logging to ${sessionLogFile(appContext).absolutePath}")
        logcatThread = thread(name = "noesis-logcat", isDaemon = true) {
            try {
                val myPid = android.os.Process.myPid().toString()
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
                val reader = proc.inputStream.bufferedReader()
                val out = sessionLogFile(appContext)
                while (running) {
                    val line = try {
                        reader.readLine()
                    } catch (_: Exception) {
                        null
                    } ?: break
                    if (!isOwnPidLine(line, myPid)) continue
                    try {
                        synchronized(out) {
                            if (out.length() > MAX_SESSION_BYTES) rotate(out)
                            out.appendText(line + "\n")
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat capture failed", e)
            }
        }
    }

    fun stop() {
        running = false
        try {
            logcatThread?.interrupt()
        } catch (_: Exception) {
        }
        logcatThread = null
    }

    /** threadtime format: "MM-DD HH:MM:SS.mmm PID TID PRIO TAG: msg" */
    private fun isOwnPidLine(line: String, myPid: String): Boolean {
        val parts = line.split(" ".toRegex(), limit = 4)
        return parts.size >= 3 && parts[2] == myPid
    }

    private fun rotate(out: File) {
        try {
            val text = out.readText()
            val keepFrom = (text.length - 400 * 1024).coerceAtLeast(0)
            val cut = text.indexOf('\n', keepFrom).takeIf { it >= 0 } ?: keepFrom
            out.writeText(text.substring(cut))
        } catch (_: Exception) {
            try {
                out.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun tail(file: File, maxLines: Int): String {
        return try {
            if (!file.exists()) return "(no session log)"
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            "(unreadable session log)"
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.versionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun installCrashHandler(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = logsDir(context)
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("time=$ts")
                    appendLine("thread=${t.name}")
                    appendLine("brand=${Build.BRAND} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
                    appendLine("appVersion=${appVersion(context)}")
                    appendLine("--- session log tail ---")
                    appendLine(tail(sessionLogFile(context), 200))
                    appendLine("--- stacktrace ---")
                    appendLine(Log.getStackTraceString(e))
                }
                File(dir, "noesis-crash-$ts.txt").writeText(report)
                File(dir, CRASH_LATEST).writeText(report)
                Log.e(TAG, "crash report written", e)
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun copyPendingCrashToDownloads(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pending = File(logsDir(context), CRASH_LATEST)
            if (!pending.exists()) return
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(pending.lastModified()))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "noesis-crash-$ts.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NoesisLab")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                pending.inputStream().use { inp -> inp.copyTo(out) }
            }
            pending.delete()
            Log.i(TAG, "crash report copied to Downloads/NoesisLab")
        } catch (e: Exception) {
            Log.w(TAG, "copy crash to Downloads failed", e)
        }
    }
}
