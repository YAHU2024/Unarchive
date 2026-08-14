package com.unarchive.android.audio

import java.io.File

/**
 * Helpers for exporting cached audio out of the app (e.g. into other
 * transcription tools). Pure file/string logic so it stays unit-testable
 * on the JVM without Android framework dependencies.
 */

/** Maximum length kept from the video title when building an export file name. */
const val EXPORT_FILE_NAME_MAX_TITLE_LENGTH = 60

private val FORBIDDEN_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\u0000-\u001F]+")
private val WHITESPACE_RUNS = Regex("\\s+")
private val UNDERSCORE_RUNS = Regex("_+")

/** Media extensions the audio cache may contain (Bilibili downloader output). */
private val CACHED_AUDIO_EXTENSIONS = setOf("m4a", "webm", "mp4", "audio")

/**
 * Find the cached audio file for [videoId] inside [cacheDirectory].
 *
 * Cache entries are named `{videoId}.{ext}` with a `{videoId}.{ext}.json`
 * metadata file and transient `.part`/`.backup` files beside them; only the
 * actual audio file (non-empty, known extension) is considered. Returns
 * `null` when nothing reusable is cached.
 */
fun resolveCachedAudio(cacheDirectory: File, videoId: String): File? {
    if (!cacheDirectory.isDirectory) return null
    return cacheDirectory
        .listFiles { file ->
            file.isFile &&
                file.length() > 0 &&
                file.name.startsWith("$videoId.") &&
                file.extension.lowercase() in CACHED_AUDIO_EXTENSIONS
        }
        ?.maxByOrNull { it.length() }
}

/**
 * Build a readable export file name, e.g.
 * `BV1PS42197aM_4种常见错误跑姿.m4a`. Path-hostile characters and whitespace
 * runs in the title are replaced; Chinese and other non-ASCII characters are
 * preserved so the file stays recognizable in other apps.
 */
fun exportAudioFileName(videoId: String, title: String, extension: String): String {
    val id = sanitizeFileNamePart(videoId, maxLength = 32).ifBlank { "video" }
    val safeTitle = sanitizeFileNamePart(title).ifBlank { "audio" }
    val ext = extension.lowercase().ifBlank { "audio" }
    return "${id}_$safeTitle.$ext"
}

/**
 * Replace characters that are invalid in file names (and on most share
 * targets) with underscores, collapse whitespace runs, and trim trailing
 * separators. Length is capped at [maxLength].
 */
fun sanitizeFileNamePart(input: String, maxLength: Int = EXPORT_FILE_NAME_MAX_TITLE_LENGTH): String {
    require(maxLength > 0) { "maxLength must be positive" }
    val collapsed = FORBIDDEN_FILE_NAME_CHARS.replace(
        WHITESPACE_RUNS.replace(input.trim(), "_"),
        "_",
    )
    val cleaned = collapsed.replace(UNDERSCORE_RUNS, "_").trim('_', '.', ' ')
    return cleaned.take(maxLength).trim('_', '.', ' ')
}

/** MIME type used when sharing an exported audio file by extension. */
fun audioMimeType(extension: String): String = when (extension.lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "webm" -> "audio/webm"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    else -> "audio/*"
}
