package com.mali.nbeta.system

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.mali.nbeta.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small on-device log the user can read and share from Settings → Logs, for phones without developer options.
 *
 * Warnings, errors, blocked actions ("Permission Denial…") and crashes are appended to files/diag.log on a background
 * thread (crashes synchronously, before the process dies). Nothing leaves the phone unless the user shares it.
 * Message text is ours (component names, error messages); notification or message contents are never logged.
 */
object DiagLog {
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val stamp get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** Cheap: no file access, just remembers the path and chains the crash handler. */
    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { append("E", "CRASH", "Uncaught exception on ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        Log.w(tag, msg, tr)
        record("W", tag, msg, tr)
        return 0
    }

    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        Log.e(tag, msg, tr)
        record("E", tag, msg, tr)
        return 0
    }

    /** Informational events worth seeing in a report (an action that was blocked and worked around, etc.). */
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        record("I", tag, msg, null)
    }

    private fun record(level: String, tag: String, msg: String, tr: Throwable?) {
        if (file == null) return
        val time = System.currentTimeMillis()
        io.execute { runCatching { append(level, tag, msg, tr, time) } }
    }

    @Synchronized
    private fun append(level: String, tag: String, msg: String, tr: Throwable?, time: Long = System.currentTimeMillis()) {
        val f = file ?: return
        if (f.length() > MAX_BYTES) {
            // Keep the newer half.
            val text = f.readText()
            f.writeText(text.substring(text.length / 2).substringAfter('\n'))
        }
        val trace = tr?.stackTraceToString()?.lineSequence()?.take(30)?.joinToString("\n") { "    $it" }
        f.appendText(buildString {
            append(stamp.format(Date(time))).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (trace != null) append(trace).append('\n')
        })
    }

    @Synchronized
    fun events(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear() {
        file?.delete()
    }

    /**
     * This app's own lines from the system log (apps may read their own logs without any permission). Includes the
     * system's messages about this app's process, but not other apps' or the system server's lines.
     */
    fun systemLog(maxLines: Int = 800): String {
        fun run(vararg args: String): String? = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString(), *args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
            out.takeIf { p.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()
        return run("--uid", Process.myUid().toString()) ?: run("--pid", Process.myPid().toString()).orEmpty()
    }

    /** Everything for a bug report, newest last, capped so it fits a share sheet. */
    fun report(includeSystem: Boolean): String = buildString {
        append("Nbeta ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Report time: ").append(stamp.format(Date())).append("\n\n== Nbeta events ==\n")
        append(events().ifBlank { "(none)\n" })
        if (includeSystem) append("\n== System log (this app only) ==\n").append(systemLog().ifBlank { "(unavailable)\n" })
    }.let { if (it.length > 180_000) "…(older lines cut)\n" + it.takeLast(180_000) else it }
}
