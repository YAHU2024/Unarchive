package com.unarchive.android.analyzer

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.card.CardAnalysis
import com.unarchive.android.card.CardChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAnalyzerTest {

    @Test
    fun buildUserPromptIncludesTimestampsAndJsonRequest() {
        val prompt = CardAnalyzer.buildUserPrompt(
            listOf(
                TranscriptSegment(0, 5_000, "第一句"),
                TranscriptSegment(5_000, 10_000, "第二句"),
            ),
        )

        assertTrue(prompt.contains("[00:00] 第一句"))
        assertTrue(prompt.contains("[00:05] 第二句"))
        assertTrue(prompt.contains("summary"))
        assertTrue(prompt.contains("chapters"))
        assertTrue(prompt.contains("只输出 JSON"))
    }

    @Test
    fun chunkSegmentsKeepsSingleChunkWhenUnderLimit() {
        val segments = listOf(
            TranscriptSegment(0, 1_000, "短句"),
            TranscriptSegment(1_000, 2_000, "另一个短句"),
        )

        val chunks = CardAnalyzer.chunkSegments(segments, maxChars = 100)

        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].size)
    }

    @Test
    fun chunkSegmentsNeverBreaksASegmentInHalf() {
        val segments = listOf(
            TranscriptSegment(0, 1_000, "短"),
            TranscriptSegment(1_000, 2_000, "这一句特别长，超过阈值，但必须保持完整"),
            TranscriptSegment(2_000, 3_000, "尾"),
        )

        val chunks = CardAnalyzer.chunkSegments(segments, maxChars = 10)

        assertEquals(3, chunks.flatten().size)
        assertTrue(chunks.any { chunk -> chunk.any { it.text.startsWith("这一句") } })
    }

    @Test
    fun chunkSegmentsRespectsEmptyInput() {
        assertEquals(0, CardAnalyzer.chunkSegments(emptyList(), maxChars = 100).size)
    }

    @Test
    fun mergeAnalysesSortsChaptersAndCombinesSummaries() {
        val a = CardAnalysis(
            summary = "摘要A",
            keyPoints = listOf("要点1"),
            chapters = listOf(CardChapter("章2", 5_000, 10_000, emptyList())),
        )
        val b = CardAnalysis(
            summary = "摘要B",
            keyPoints = listOf("要点1", "要点2"),
            chapters = listOf(CardChapter("章1", 0, 5_000, emptyList())),
        )

        val merged = CardAnalyzer.mergeAnalyses(listOf(a, b))

        assertEquals(listOf("章1", "章2"), merged.chapters.map { it.title })
        assertEquals(listOf("要点1", "要点2"), merged.keyPoints)
        assertTrue(merged.summary.contains("第 1 段"))
        assertTrue(merged.summary.contains("第 2 段"))
    }

    @Test
    fun mergeAnalysesReturnsSingleAnalysisUnchanged() {
        val single = CardAnalysis(
            summary = "单独摘要",
            keyPoints = listOf("要点"),
            chapters = listOf(CardChapter("章", 0, 1_000, emptyList())),
        )

        val merged = CardAnalyzer.mergeAnalyses(listOf(single))

        assertEquals(single, merged)
    }
}
