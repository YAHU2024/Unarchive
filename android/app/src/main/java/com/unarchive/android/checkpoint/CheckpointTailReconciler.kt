package com.unarchive.android.checkpoint

import com.unarchive.android.asr.TranscriptSegment

class AmbiguousCheckpointTail(message: String) : IllegalStateException(message)

object CheckpointTailReconciler {
    private const val SMALL_OVERLAP_MS = 250L
    private const val SIMILARITY_THRESHOLD = 0.85

    fun reconcile(
        committed: List<TranscriptSegment>,
        regenerated: List<TranscriptSegment>,
        replaceFromMs: Long,
        completedThroughMs: Long,
    ): List<TranscriptSegment> {
        val prefix = committed.filter { it.endMs <= replaceFromMs }
        val oldTail = committed.filter { it.endMs > replaceFromMs && it.startMs < completedThroughMs }
        val newTail = regenerated.filter { it.endMs > replaceFromMs }

        if (oldTail.isNotEmpty() && newTail.isEmpty()) {
            throw AmbiguousCheckpointTail("Resume overlap produced no transcript for a committed tail")
        }
        if (oldTail.isNotEmpty() && newTail.isNotEmpty()) {
            val oldStart = oldTail.first().startMs
            val newStart = newTail.first().startMs
            if (newStart > oldTail.first().endMs || oldStart > newTail.first().endMs) {
                throw AmbiguousCheckpointTail("Resume overlap did not intersect the committed tail")
            }
        }

        return normalizeSegments(prefix + newTail)
    }

    fun normalizeSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        val ordered = segments
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy(TranscriptSegment::startMs, TranscriptSegment::endMs))
        val result = mutableListOf<TranscriptSegment>()
        ordered.forEach { current ->
            val previous = result.lastOrNull()
            if (previous == null || current.startMs >= previous.endMs) {
                result += current
                return@forEach
            }

            val overlapMs = previous.endMs - current.startMs
            if (similar(previous.text, current.text)) {
                result[result.lastIndex] = TranscriptSegment(
                    startMs = minOf(previous.startMs, current.startMs),
                    endMs = maxOf(previous.endMs, current.endMs),
                    text = if (current.text.length >= previous.text.length) current.text else previous.text,
                )
            } else if (overlapMs <= SMALL_OVERLAP_MS) {
                result[result.lastIndex] = previous.copy(endMs = current.startMs)
                if (result.last().endMs <= result.last().startMs) result.removeLast()
                result += current
            } else {
                throw AmbiguousCheckpointTail(
                    "Transcript segments overlap with different text: " +
                        "${previous.startMs}-${previous.endMs} and " +
                        "${current.startMs}-${current.endMs}",
                )
            }
        }
        return result
    }

    private fun similar(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val distance = Array(a.length + 1) { index -> index }
        for (j in b.indices) {
            var diagonal = distance[0]
            distance[0] = j + 1
            for (i in a.indices) {
                val above = distance[i + 1]
                distance[i + 1] = if (a[i] == b[j]) {
                    diagonal
                } else {
                    1 + minOf(diagonal, distance[i], above)
                }
                diagonal = above
            }
        }
        val maxLength = maxOf(a.length, b.length)
        return 1.0 - distance[a.length].toDouble() / maxLength >= SIMILARITY_THRESHOLD
    }

    private fun normalize(text: String) = text
        .lowercase()
        .filterNot(Char::isWhitespace)
}
