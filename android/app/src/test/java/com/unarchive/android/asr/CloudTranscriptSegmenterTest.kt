package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTranscriptSegmenterTest {
    @Test
    fun splitsChineseAndEnglishSentenceBoundaries() {
        assertEquals(
            listOf("你好。世界！", "How are you? Fine."),
            CloudTranscriptSegmenter.split("你好。世界！How are you? Fine."),
        )
    }

    @Test
    fun keepsTextWithoutPunctuationAsOnePart() {
        assertEquals(listOf("没有标点的文本"), CloudTranscriptSegmenter.split("没有标点的文本"))
    }

    @Test
    fun allocatesContiguousEstimatedTimingAndAlignsEnd() {
        val segments = CloudTranscriptSegmenter.withEstimatedTiming("这是一个完整的较长句子。那是另一个完整的较长句子。", 1_000)
        assertEquals(2, segments.size)
        assertEquals(0L, segments.first().startMs)
        assertEquals(segments.first().endMs, segments.last().startMs)
        assertEquals(1_000L, segments.last().endMs)
    }

    @Test
    fun mergesShortFragmentsWithoutChangingText() {
        assertEquals(
            listOf("其实它反映的是。实际。", "电路模型是一个概念。"),
            CloudTranscriptSegmenter.split("其实它反映的是。实际。电路模型是一个概念。"),
        )
    }

    @Test
    fun mergedTimingUsesOuterBoundaries() {
        val segments = CloudTranscriptSegmenter.withEstimatedTiming("这是一个完整的句子。那是另一个完整的句子。", 1_000)
        assertEquals(2, segments.size)
        assertEquals(0L, segments.first().startMs)
        assertEquals(1_000L, segments.last().endMs)
    }
}
