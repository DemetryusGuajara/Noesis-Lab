package com.example.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * App-level log: constant watch with in-memory ring buffer + session file
 * append + logcat mirror. Works even if the background logcat capture dies,
 * because every breadcrumb is written explicitly.
 *
 * File writes only when init() enables them (DEV variant); the ring buffer
 * and logcat mirror are always active (cheap).
 */
object AppLog {
    private const val TAG = "NoesisApp"
    private const val MAX_RING = 800

    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_RING + 1)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var fileEnabled = false

    fun init(context: Context, enableFile: Boolean) {
        appContext = context.applicationContext
        fileEnabled = enableFile
    }

    fun sessionFile(): File? {
        val ctx = appContext ?: return null
        return try {
            LogCollector.sessionLogFile(ctx)
        } catch (_: Exception) {
            null
        }
    }

    private fun record(level: String, tag: String, msg: String) {
        val line = "${TimeFmt.now()} $level/$tag: $msg"
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_RING) ring.removeFirst()
        }
        try {
            when (level) {
                "E" -> Log.e(tag, msg)
                "W" -> Log.w(tag, msg)
                else -> Log.i(tag, msg)
            }
        } catch (_: Exception) {
        }
        if (fileEnabled) {
            try {
                val f = sessionFile() ?: return
                synchronized(f) {
                    if (f.length() > 1024 * 1024) {
                        val text = f.readText()
                        val cut = text.indexOf('\n', (text.length - 400 * 1024).coerceAtLeast(0))
                        f.writeText(if (cut >= 0) text.substring(cut) else text.takeLast(400 * 1024))
                    }
                    f.appendText(line + "\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun d(tag: String, msg: String) = record("D", tag, msg)
    fun i(tag: String, msg: String) = record("I", tag, msg)
    fun w(tag: String, msg: String) = record("W", tag, msg)
    fun e(tag: String, msg: String) = record("E", tag, msg)

    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /** Fresh `logcat -d` dump filtered to this process + our tags (no Context needed). */
    fun dumpFilteredLogcat(maxLines: Int = 2500): List<String> {
        return try {
            val myPid = android.os.Process.myPid().toString()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            val out = proc.inputStream.bufferedReader().readLines()
            out.filter { line ->
                val parts = line.split(" ".toRegex(), limit = 4)
                (parts.size >= 3 && parts[2] == myPid) ||
                    line.contains("NoesisLog") || line.contains("NoesisApp")
            }.takeLast(maxLines)
        } catch (_: Exception) {
            listOf("(logcat indisponível)")
        }
    }

    private object TimeFmt {
        private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        fun now(): String = try {
            synchronized(fmt) { fmt.format(java.util.Date()) }
        } catch (_: Exception) {
            "?"
        }
    }
}
