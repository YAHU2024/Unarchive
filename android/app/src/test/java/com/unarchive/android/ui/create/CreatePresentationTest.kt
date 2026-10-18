package com.unarchive.android.ui.create

import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateProcessingUiState
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.RecoveryUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatePresentationTest {
    @Test
    fun everyStageHasReadableGuidanceWithoutRawStatus() {
        CreateProcessingStage.entries.forEach { stage ->
            val presentation = state(stage).presentation()
            assertTrue(presentation.title.isNotBlank())
            assertTrue(presentation.guidance.isNotBlank())
            assertEquals(null, presentation.checkpointMessage)
        }
    }

    @Test
    fun cancellationWithoutCheckpointDoesNotPromiseRetainedTranscript() {
        val presentation = state(CreateProcessingStage.CANCELLED).presentation()
        assertTrue(presentation.guidance.contains("重新开始"))
        assertFalse(presentation.guidance.contains("已保留转写"))
        assertEquals(null, presentation.checkpointMessage)
    }

    @Test
    fun interruptedCheckpointRequiresExplicitContinuation() {
        val presentation = state(CreateProcessingStage.RECOVERABLE).copy(
            processing = CreateProcessingUiState(CreateProcessingStage.RECOVERABLE, checkpointSegmentCount = 3),
        ).presentation()
        assertTrue(presentation.guidance.contains("主动继续"))
        assertEquals("已保留转写检查点：3 段文本。", presentation.checkpointMessage)
    }

    @Test
    fun recoveryRecordTakesPrecedenceOverGenericRetry() {
        val presentation = state(CreateProcessingStage.FAILED).copy(
            recoveries = listOf(RecoveryUiItem(
                "operation", "bilibili:test", "base", "测试", "AI 分析",
                SingleCardRecoveryState.FAILED, true,
            )),
        ).presentation()
        assertTrue(presentation.guidance.contains("确认继续"))
        assertTrue(presentation.guidance.contains("不会自动恢复"))
    }

    private fun state(stage: CreateProcessingStage) = CreateUiState(
        "", false, "", false, 0, processing = CreateProcessingUiState(stage),
    )
}
