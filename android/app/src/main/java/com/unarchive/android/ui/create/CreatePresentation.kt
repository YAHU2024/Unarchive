package com.unarchive.android.ui.create

import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateUiState

/** Copy only: task ownership and continuation decisions stay in the existing state/events. */
internal data class CreatePresentation(
    val title: String,
    val guidance: String,
    val checkpointMessage: String?,
)

internal fun CreateUiState.presentation(): CreatePresentation {
    val title = when {
        isProcessing -> "正在处理视频"
        else -> when (processing.stage) {
            CreateProcessingStage.COMPLETED -> "本次处理已结束"
            CreateProcessingStage.FAILED -> "处理未完成"
            CreateProcessingStage.CANCELLED -> "处理已取消"
            CreateProcessingStage.RECOVERABLE -> "有可以继续的处理进度"
            else -> "处理状态"
        }
    }
    val guidance = when {
        isProcessing -> "完成后可在笔记库查看本地笔记；需要中断时请使用取消处理。"
        processing.stage == CreateProcessingStage.COMPLETED ->
            "前往笔记库查看结果，确认草稿后可在编辑器正式保存。"
        recoveries.isNotEmpty() || hasRecovery ->
            "请在下方选择未完成任务并确认继续；进入页面不会自动恢复。"
        processing.checkpointSegmentCount > 0 ->
            "已保留转写检查点。确认视频输入后，可主动继续生成笔记草稿。"
        processing.stage == CreateProcessingStage.CANCELLED ->
            "任务已停止。你可以修改视频输入后重新开始，或查看已有笔记和素材。"
        processing.stage == CreateProcessingStage.FAILED ->
            "请根据处理提示检查输入或配置后重试，也可到笔记库查看已保留的素材。"
        else -> "添加视频并开始生成，已有笔记和素材可在笔记库查看。"
    }
    return CreatePresentation(
        title,
        guidance,
        processing.checkpointSegmentCount.takeIf { it > 0 }
            ?.let { "已保留转写检查点：$it 段文本。" },
    )
}
