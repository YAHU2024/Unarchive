package com.unarchive.android.asr

/** Splits cloud plain text into readable sentences; timestamps remain estimates. */
object CloudTranscriptSegmenter {
    private const val MAX_CHARS = 120
    private const val MIN_CHINESE_CHARS = 8
    private const val MIN_ENGLISH_WORDS = 4
    private val primary = setOf('。', '！', '？', '；', '.', '!', '?', ';')
    private val secondary = setOf('，', '、', ',', ':', '：')

    fun split(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").trim().split('\n')
        return lines.flatMap { mergeShortFragments(splitRaw(it)) }
    }

    fun rawSegmentCount(text: String): Int = text.replace("\r\n", "\n").trim()
        .split('\n').sumOf { splitRaw(it).size }

    private fun splitRaw(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty()) result += value
            current = StringBuilder()
        }
        for (char in normalized) {
            current.append(char)
            if (char in primary) {
                flush()
            } else if (current.length >= MAX_CHARS && char in secondary) {
                flush()
            }
        }
        flush()
        return result.flatMap { chunk ->
            if (chunk.length <= MAX_CHARS) listOf(chunk)
            else chunk.chunked(MAX_CHARS)
        }
    }

    fun withEstimatedTiming(text: String, durationMs: Long): List<TranscriptSegment> {
        val parts = split(text)
        if (parts.isEmpty()) return emptyList()
        val totalChars = parts.sumOf(String::length).coerceAtLeast(1)
        var start = 0L
        return parts.mapIndexed { index, part ->
            val end = if (index == parts.lastIndex) durationMs.coerceAtLeast(start)
            else (durationMs.coerceAtLeast(0L) * (startCharCount(parts, index + 1).toDouble() / totalChars)).toLong()
            TranscriptSegment(start, end.coerceAtLeast(start), part).also { start = it.endMs }
        }
    }

    private fun startCharCount(parts: List<String>, count: Int): Int = parts.take(count).sumOf(String::length)

    private fun mergeShortFragments(parts: List<String>): List<String> {
        if (parts.size < 2) return parts
        val merged = mutableListOf<String>()
        var index = 0
        while (index < parts.size) {
            val current = parts[index]
            if (index < parts.lastIndex && isShort(current)) {
                val next = parts[index + 1]
                val separator = if (containsAsciiWord(current) && containsAsciiWord(next)
                ) " " else ""
                val combined = current + separator + next
                if (combined.length <= MAX_CHARS) {
                    merged += combined
                    index += 2
                    continue
                }
            }
            merged += current
            index++
        }
        if (merged.size >= 2 && isShort(merged.last())) {
            val last = merged.removeAt(merged.lastIndex)
            val previous = merged.removeAt(merged.lastIndex)
            val separator = if (containsAsciiWord(previous) && containsAsciiWord(last)
            ) " " else ""
            val combined = previous + separator + last
            if (combined.length <= MAX_CHARS) merged += combined else merged += previous + last
        }
        return merged
    }

    private fun isShort(text: String): Boolean {
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        if (chineseChars > 0) return chineseChars < MIN_CHINESE_CHARS
        return text.trim().split(Regex("\\s+")).count { it.isNotBlank() } < MIN_ENGLISH_WORDS
    }

    private fun isAsciiWordChar(char: Char?): Boolean = char != null &&
        ((char in 'a'..'z') || (char in 'A'..'Z') || (char in '0'..'9'))

    private fun containsAsciiWord(text: String): Boolean = text.any(::isAsciiWordChar)
}
