package com.unarchive.android.editor.ai

/** Every source line stays reachable, even when a quadratic diff exceeds its budget. */
enum class AiReviewMode { DIFF, FULL_TEXT }
enum class AiReviewLineKind { UNCHANGED, REMOVED, ADDED }

data class AiReviewLine(
    val kind: AiReviewLineKind,
    val text: String,
    val originalLine: Int? = null,
    val candidateLine: Int? = null,
)

data class AiReviewTextChunk(val line: Int, val text: String, val continuation: Boolean)

data class AiReviewProjection(
    val original: String?,
    val candidate: String,
    val mode: AiReviewMode,
    val lines: List<AiReviewLine>,
    val originalLines: List<String>,
    val candidateLines: List<String>,
    val explanation: String? = null,
    val originalChunks: List<AiReviewTextChunk> = textChunks(originalLines),
    val candidateChunks: List<AiReviewTextChunk> = textChunks(candidateLines),
) {
    companion object {
        /** Run on a worker. Source is retained exactly; line strings omit only the LF separator. */
        fun build(original: String?, candidate: String, maxCells: Long = 250_000L, maxLines: Int = 4_000): AiReviewProjection {
            require(maxCells >= 0L && maxLines > 0)
            val old = original?.split('\n') ?: emptyList()
            val new = candidate.split('\n')
            if (original == null) return AiReviewProjection(null, candidate, AiReviewMode.FULL_TEXT, emptyList(), old, new,
                "原正式基版不可用；完整候选仍可查看，不能应用")
            var prefix = 0
            while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < old.size - prefix && suffix < new.size - prefix &&
                old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
            val oldCount = old.size - prefix - suffix
            val newCount = new.size - prefix - suffix
            if (old.size.toLong() + new.size > maxLines || (oldCount + 1L) * (newCount + 1L) > maxCells ||
                old.any { it.length > 8_192 } || new.any { it.length > 8_192 }) {
                return AiReviewProjection(original, candidate, AiReviewMode.FULL_TEXT, emptyList(), old, new,
                    "差异超出计算预算，已切换完整原文与完整候选")
            }
            val lengths = Array(oldCount + 1) { IntArray(newCount + 1) }
            for (a in oldCount - 1 downTo 0) for (b in newCount - 1 downTo 0) {
                lengths[a][b] = if (old[prefix + a] == new[prefix + b]) lengths[a + 1][b + 1] + 1
                else maxOf(lengths[a + 1][b], lengths[a][b + 1])
            }
            val result = ArrayList<AiReviewLine>(old.size + new.size)
            repeat(prefix) { result += AiReviewLine(AiReviewLineKind.UNCHANGED, old[it], it + 1, it + 1) }
            var a = 0
            var b = 0
            while (a < oldCount || b < newCount) {
                if (a < oldCount && b < newCount && old[prefix + a] == new[prefix + b]) {
                    result += AiReviewLine(AiReviewLineKind.UNCHANGED, old[prefix + a], prefix + a + 1, prefix + b + 1)
                    a++; b++
                } else if (a < oldCount && (b == newCount || lengths[a + 1][b] >= lengths[a][b + 1])) {
                    result += AiReviewLine(AiReviewLineKind.REMOVED, old[prefix + a], prefix + a + 1)
                    a++
                } else {
                    result += AiReviewLine(AiReviewLineKind.ADDED, new[prefix + b], candidateLine = prefix + b + 1)
                    b++
                }
            }
            repeat(suffix) { index ->
                result += AiReviewLine(AiReviewLineKind.UNCHANGED, old[old.size - suffix + index],
                    old.size - suffix + index + 1, new.size - suffix + index + 1)
            }
            return AiReviewProjection(original, candidate, AiReviewMode.DIFF, result, old, new)
        }
    }
}

private fun textChunks(lines: List<String>): List<AiReviewTextChunk> = buildList {
    lines.forEachIndexed { index, line ->
        if (line.isEmpty()) add(AiReviewTextChunk(index + 1, "", false))
        var offset = 0
        while (offset < line.length) {
            var end = (offset + 2_048).coerceAtMost(line.length)
            if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
            add(AiReviewTextChunk(index + 1, line.substring(offset, end), offset > 0))
            offset = end
        }
    }
}
