package com.unarchive.android.log

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log severity, rendered with a distinct color in the log panel. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One immutable log record. [at] is wall-clock epoch millis. */
data class LogEntry(
    val at: Long,
    val level: LogLevel,
    val tag: String,
    val msg: String,
)

/**
 * Process-wide in-memory log buffer surfaced by the "日志" tab.
 *
 * Writes come from IO threads (ASR decode, download, pipeline) at high
 * frequency, so the buffer is a [SnapshotStateList] guarded by a lock, capped
 * at [MAX_ENTRIES] entries. Every append is also forwarded to logcat under the
 * original tag so `adb logcat` output is unchanged.
 *
 * When [attachFileSink] is called, every entry is additionally appended to a
 * plain-text file so the log survives a crash and can be pulled later with
 * `adb pull` or a file manager.
 *
 * Being an `object` (not per-screen state) means the log survives activity
 * recreation and process-level configuration changes, which is exactly what a
 * diagnostic log should do.
 */
object AppLogger {
    private const val MAX_ENTRIES = 2000

    val entries: SnapshotStateList<LogEntry> = mutableStateListOf()

    @Volatile
    private var fileSink: File? = null
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Enables on-disk persistence to [directory]/app.log. Idempotent; call once
     * during startup. Append-only, so a crash never loses already-written lines.
     */
    fun attachFileSink(directory: File) {
        val file = File(directory, "app.log")
        file.parentFile?.mkdirs()
        fileSink = file
    }

    fun debug(tag: String, msg: String) = append(LogLevel.DEBUG, tag, msg)
    fun info(tag: String, msg: String) = append(LogLevel.INFO, tag, msg)
    fun warn(tag: String, msg: String) = append(LogLevel.WARN, tag, msg)
    fun error(tag: String, msg: String) = append(LogLevel.ERROR, tag, msg)

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun append(level: LogLevel, tag: String, msg: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, msg)
            LogLevel.INFO -> Log.i(tag, msg)
            LogLevel.WARN -> Log.w(tag, msg)
            LogLevel.ERROR -> Log.e(tag, msg)
        }
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeRange(0, entries.size - MAX_ENTRIES + 1)
            }
            entries.add(entry)
            persist(entry)
        }
    }

    private fun persist(entry: LogEntry) {
        val file = fileSink ?: return
        runCatching {
            file.appendText(
                "${fileDateFormat.format(Date(entry.at))} ${entry.level.name} ${entry.tag}: ${entry.msg}\n",
            )
        }
    }
}
