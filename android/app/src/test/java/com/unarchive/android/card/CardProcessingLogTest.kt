package com.unarchive.android.card

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardProcessingLogTest {
    @Test
    fun formatsStableSafeMetadataWithoutSpacesOrEquals() {
        val line = CardProcessingLog.formatEvent(
            operationId = "op-1",
            stage = CardProcessingLog.Stage.EXPORT,
            state = CardProcessingLog.State.COMPLETED,
            cardId = KnowledgeCardId("bilibili", "BV1"),
            elapsedMs = 12,
            metadata = mapOf("assetCount" to 2, "note" to "a value=x"),
        )

        assertTrue(line.contains("operationId=op-1"))
        assertTrue(line.contains("stage=卡片导出"))
        assertTrue(line.contains("state=完成"))
        assertTrue(line.contains("cardId=bilibili:BV1"))
        assertTrue(line.contains("assetCount=2"))
        assertTrue(line.contains("note=a_value:x"))
    }

    @Test
    fun safeErrorRedactsUrlsAndCredentialValues() {
        val safe = CardProcessingLog.safeError(
            IllegalStateException("request failed apiKey=secret https://example.test/a"),
        )

        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("https://example.test"))
        assertTrue(safe.contains("<redacted>"))
        assertTrue(safe.contains("<url>"))
    }
}
