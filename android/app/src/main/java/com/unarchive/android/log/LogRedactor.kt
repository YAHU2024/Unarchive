package com.unarchive.android.log

/** Final privacy guard for every in-app, Logcat, and private-file log entry. */
object LogRedactor {
    fun redact(message: String): String = message
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
        .replace(
            Regex("(?i)(api[-_ ]?key|token|cookie|authorization|sessdata|bili_jct)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=<redacted>",
        )
        .replace(
            Regex("(?i)(videoId|cardId|folderId|noteId|batchId|uname|username|filename)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=<redacted>",
        )
        .replace(Regex("(?i)([A-Z]:\\\\|/data/|/storage/)[^\\s,;]+"), "<path>")
}
