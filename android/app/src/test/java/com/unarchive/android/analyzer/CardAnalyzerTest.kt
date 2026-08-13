package com.unarchive.android.analyzer

import com.unarchive.android.asr.TranscriptSegment
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
}
