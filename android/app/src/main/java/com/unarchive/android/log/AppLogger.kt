package com.unarchive.android.log

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** Version of the on-disk JSONL diagnostic contract. */
internal const val LOG_SCHEMA_VERSION = 1

/** Log severity, rendered with a distinct color in the log panel. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One privacy-filtered diagnostic event. */
data class LogEntry(
    val at: Long,
    val level: LogLevel,
    val tag: String,
    val msg: String,
    val schemaVersion: Int = LOG_SCHEMA_VERSION,
    val category: String = tag,
    val eventName: String = "message",
    val sessionId: String = "",
    val operationId: String? = null,
    val stage: String? = null,
    val result: String? = null,
    val durationMs: Long? = null,
    val retryCount: Int? = null,
    val provider: String? = null,
    val httpStatus: Int? = null,
    val errorType: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("timestamp", at)
        put("level", level.name)
        put("tag", tag)
        put("message", msg)
        put("category", category)
        put("eventName", eventName)
        put("sessionId", sessionId)
        operationId?.let { put("operationId", it) }
        stage?.let { put("stage", it) }
        result?.let { put("result", it) }
        durationMs?.let { put("durationMs", it) }
        retryCount?.let { put("retryCount", it) }
        provider?.let { put("provider", it) }
        httpStatus?.let { put("httpStatus", it) }
        errorType?.let { put("errorType", it) }
    }

    companion object {
        internal fun fromJson(json: JSONObject): LogEntry? {
            val level = runCatching { LogLevel.valueOf(json.optString("level")) }.getOrNull() ?: return null
            return LogEntry(
                at = json.optLong("timestamp", json.optLong("at", 0L)),
                level = level,
                tag = json.optString("tag", "App"),
                msg = json.optString("message", json.optString("msg")),
                schemaVersion = json.optInt("schemaVersion", LOG_SCHEMA_VERSION),
                category = json.optString("category", json.optString("tag", "App")),
                eventName = json.optString("eventName", "message"),
                sessionId = json.optString("sessionId"),
                operationId = json.optString("operationId").takeIf(String::isNotBlank),
                stage = json.optString("stage").takeIf(String::isNotBlank),
                result = json.optString("result").takeIf(String::isNotBlank),
                durationMs = json.optionalLong("durationMs"),
                retryCount = json.optionalInt("retryCount"),
                provider = json.optString("provider").takeIf(String::isNotBlank),
                httpStatus = json.optionalInt("httpStatus"),
                errorType = json.optString("errorType").takeIf(String::isNotBlank),
            )
        }

        private fun JSONObject.optionalLong(name: String): Long? =
            if (has(name) && !isNull(name)) optLong(name) else null

        private fun JSONObject.optionalInt(name: String): Int? =
            if (has(name) && !isNull(name)) optInt(name) else null
    }
}

data class CrashSummary(
    val at: Long,
    val thread: String,
    val errorType: String,
    val message: String,
)

/** Local, privacy-aware diagnostic logger with bounded rolling JSONL files. */
object AppLogger {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_ARCHIVED_FILES = 3
    private const val MAX_MESSAGE_CHARS = 1_200

    val entries: SnapshotStateList<LogEntry> = mutableStateListOf()

    private val lock = Any()
    private val sessionId = UUID.randomUUID().toString()
    private var fileSink: File? = null
    private var loadedDirectory: String? = null
    private var crashDirectory: File? = null
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandlerInstalled = false

    @Volatile
    var lastCrashSummary: CrashSummary? = null
        private set

    @Volatile
    var droppedWriteCount: Long = 0
        private set

    /** Enables persistence and restores the recent tail from this directory. */
    fun attachFileSink(directory: File) {
        synchronized(lock) {
            directory.mkdirs()
            val file = File(directory, "app.log")
            rotateIfNeededLocked(file)
            fileSink = file
            crashDirectory = directory
            val path = directory.absolutePath
            if (loadedDirectory != path) {
                entries.clear()
                loadEntriesLocked(directory)
                lastCrashSummary = readCrashSummaryLocked(directory)
                loadedDirectory = path
            }
            installCrashHandlerLocked(directory)
        }
    }

    fun debug(tag: String, msg: String) = append(LogLevel.DEBUG, tag, msg)
    fun info(tag: String, msg: String) = append(LogLevel.INFO, tag, msg)
    fun warn(tag: String, msg: String) = append(LogLevel.WARN, tag, msg)
    fun error(tag: String, msg: String) = append(LogLevel.ERROR, tag, msg)

    /** Adds one event with the stable structured fields used by diagnostics. */
    fun event(
        level: LogLevel,
        tag: String,
        message: String,
        category: String = tag,
        eventName: String = "message",
        operationId: String? = null,
        stage: String? = null,
        result: String? = null,
        durationMs: Long? = null,
        retryCount: Int? = null,
        provider: String? = null,
        httpStatus: Int? = null,
        errorType: String? = null,
    ) = append(
        level = level,
        tag = tag,
        msg = message,
        category = category,
        eventName = eventName,
        operationId = operationId,
        stage = stage,
        result = result,
        durationMs = durationMs,
        retryCount = retryCount,
        provider = provider,
        httpStatus = httpStatus,
        errorType = errorType,
    )

    fun clear() {
        synchronized(lock) {
            entries.clear()
            fileSink?.let { file ->
                runCatching {
                    file.writeText("", StandardCharsets.UTF_8)
                    (1..MAX_ARCHIVED_FILES).forEach { index -> File(file.parentFile, "app.log.$index").delete() }
                }.onFailure { droppedWriteCount++ }
            }
        }
    }

    private fun append(
        level: LogLevel,
        tag: String,
        msg: String,
        category: String = tag,
        eventName: String = "message",
        operationId: String? = null,
        stage: String? = null,
        result: String? = null,
        durationMs: Long? = null,
        retryCount: Int? = null,
        provider: String? = null,
        httpStatus: Int? = null,
        errorType: String? = null,
    ) {
        val safeMessage = LogRedactor.redact(msg).take(MAX_MESSAGE_CHARS)
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, safeMessage)
            LogLevel.INFO -> Log.i(tag, safeMessage)
            LogLevel.WARN -> Log.w(tag, safeMessage)
            LogLevel.ERROR -> Log.e(tag, safeMessage)
        }
        val entry = LogEntry(
            at = System.currentTimeMillis(),
            level = level,
            tag = tag,
            msg = safeMessage,
            category = LogRedactor.redact(category).take(80),
            eventName = LogRedactor.redact(eventName).take(80),
            sessionId = sessionId,
            operationId = operationId?.let { LogRedactor.redact(it).take(80) },
            stage = stage?.let { LogRedactor.redact(it).take(80) },
            result = result?.let { LogRedactor.redact(it).take(80) },
            durationMs = durationMs,
            retryCount = retryCount,
            provider = provider?.let { LogRedactor.redact(it).take(80) },
            httpStatus = httpStatus,
            errorType = errorType?.let { LogRedactor.redact(it).take(120) },
        )
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
            entries.add(entry)
            persistLocked(entry)
        }
    }

    private fun persistLocked(entry: LogEntry) {
        val file = fileSink ?: return
        runCatching {
            val line = (entry.toJson().toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            if (file.length() + line.size > MAX_FILE_BYTES) rotateLocked(file)
            file.appendBytes(line)
        }.onFailure { droppedWriteCount++ }
    }

    private fun rotateIfNeededLocked(file: File) {
        if (file.length() > MAX_FILE_BYTES) rotateLocked(file)
    }

    private fun rotateLocked(file: File) {
        runCatching {
            for (index in MAX_ARCHIVED_FILES downTo 1) {
                val current = File(file.parentFile, "app.log.$index")
                if (index == MAX_ARCHIVED_FILES) current.delete()
                else File(file.parentFile, "app.log.${index + 1}").delete().also {
                    current.renameTo(File(file.parentFile, "app.log.${index + 1}"))
                }
            }
            if (file.isFile) file.renameTo(File(file.parentFile, "app.log.1"))
            file.writeText("", StandardCharsets.UTF_8)
        }.onFailure { droppedWriteCount++ }
    }

    private fun loadEntriesLocked(directory: File) {
        val files = (MAX_ARCHIVED_FILES downTo 1).map { File(directory, "app.log.$it") } + File(directory, "app.log")
        files.filter(File::isFile).forEach { file ->
            runCatching {
                file.forEachLine(StandardCharsets.UTF_8) { line ->
                    val parsed = runCatching { LogEntry.fromJson(JSONObject(line)) }.getOrNull()
                    if (parsed != null) {
                        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
                        entries.add(parsed)
                    }
                }
            }
        }
    }

    private fun installCrashHandlerLocked(directory: File) {
        if (crashHandlerInstalled) return
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val summary = CrashSummary(
                at = System.currentTimeMillis(),
                thread = thread.name.take(80),
                errorType = throwable::class.java.simpleName.orEmpty().ifBlank { "Exception" },
                message = LogRedactor.redact(throwable.message.orEmpty()).lineSequence().firstOrNull().orEmpty().take(240),
            )
            synchronized(lock) {
                runCatching {
                    File(requireNotNull(crashDirectory), "last_crash.json").writeText(
                        JSONObject()
                            .put("timestamp", summary.at)
                            .put("thread", summary.thread)
                            .put("errorType", summary.errorType)
                            .put("message", summary.message)
                            .toString(),
                        StandardCharsets.UTF_8,
                    )
                }.onFailure { droppedWriteCount++ }
                lastCrashSummary = summary
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
        crashHandlerInstalled = true
    }

    private fun readCrashSummaryLocked(directory: File): CrashSummary? = runCatching {
        val file = File(directory, "last_crash.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText(StandardCharsets.UTF_8))
        CrashSummary(
            at = json.optLong("timestamp"),
            thread = json.optString("thread", "unknown"),
            errorType = json.optString("errorType", "Exception"),
            message = json.optString("message"),
        )
    }.getOrNull()
}
