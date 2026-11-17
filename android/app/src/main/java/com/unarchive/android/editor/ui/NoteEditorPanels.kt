package com.unarchive.android.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.*
import com.unarchive.android.editor.*
import com.unarchive.android.editor.session.*
import com.unarchive.android.editor.assets.ImageImportState
import androidx.compose.runtime.*
import com.unarchive.android.ui.components.UnarchiveTextButton

@Composable
internal fun NoteEditorPanels(state: MarkdownNoteUiState, content: NoteContent?, recovery: List<NoteRecoveryCandidate>,
    headings: List<Pair<String, Int>>, onReveal: (Int) -> Unit, onIntent: (NoteEditorIntent) -> Unit,
    onFormat: (DocumentFormatCommand) -> Unit = {}, onPastePlain: () -> Unit = {},
    onPickPhoto: (() -> Unit)? = null, imageAssets: List<CardAsset> = emptyList(), onExistingImage: (String) -> Unit = {},
    importState: ImageImportState? = null, onPlaceImport: () -> Unit = {}, onRetryImport: () -> Unit = {}, onCancelImport: () -> Unit = {},
    aiStatus: String? = null, onSelectedNode: () -> Unit = {}, onTranscriptToggle: (() -> Unit)? = null,
    selectedLink: String? = null, onOpenLink: () -> Unit = {}, formatState: SelectionFormatState? = null) {
    val close = { onIntent(NoteEditorIntent.ClosePanel) }
    when (state.panel) {
        NoteEditorPanel.None -> Unit
        NoteEditorPanel.Leave -> AlertDialog(onDismissRequest = close, title = { Text("保存修改后离开？") },
            text = { Text("这篇笔记有未保存的修改。") },
            confirmButton = { UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.SaveAndLeave) },
                modifier = Modifier.testTag("markdown-note-save-leave")) { Text("保存并离开") } },
            dismissButton = { Row {
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.DiscardAndLeave) },
                    modifier = Modifier.testTag("markdown-note-discard-leave")) { Text("放弃修改") }
                UnarchiveTextButton(onClick = close) { Text("继续编辑") }
            } })
        NoteEditorPanel.More -> AlertDialog(onDismissRequest = close, title = { Text("笔记操作") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (state.mode == NoteEditorMode.Reading) UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.Edit) }) { Text("编辑笔记") }
                if (state.mode == NoteEditorMode.Editing) {
                    Text("格式", style = MaterialTheme.typography.labelMedium)
                    UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Formatting)) }) { Text("标题与正文及列表") }
                    FormatMarkChoice(InlineMark.BOLD, formatState, onFormat)
                    FormatMarkChoice(InlineMark.ITALIC, formatState, onFormat)
                    FormatMarkChoice(InlineMark.STRIKE, formatState, onFormat)
                    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetList(DocumentListStyle.UNORDERED)) }) { Text("列表") }
                    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetList(DocumentListStyle.TASK)) }) { Text("任务列表") }
                    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetQuote) }) { Text("引用") }
                    FormatMarkChoice(InlineMark.CODE, formatState, onFormat)
                    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetCodeBlock) }) { Text("代码块") }
                    UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Link)) }) { Text("链接") }
                    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.InsertDivider) }) { Text("分隔线") }
                    Text("笔记操作", style = MaterialTheme.typography.labelMedium)
                }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Info)) }) { Text("笔记信息") }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Title)) }) { Text("修改标题") }
                UnarchiveTextButton(onClick = onSelectedNode) { Text("查看当前内容") }
                if (selectedLink != null) UnarchiveTextButton(onClick = onOpenLink) { Text("打开当前链接") }
                onTranscriptToggle?.let { action -> UnarchiveTextButton(onClick = action) { Text("展开或折叠转录全文") } }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Find)) }) { Text("查找") }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Source)) }) { Text("全文源码") }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.RequestExternal) }) { Text("分享与同步") }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Ai)) }) {
                    Text("AI 整理" + aiStatus?.let { " · $it" }.orEmpty()) }
                if (state.mode == NoteEditorMode.Editing) UnarchiveTextButton(onClick = onPastePlain) { Text("粘贴为纯文本") }
                if (state.mode == NoteEditorMode.Editing) UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Insert)) }) { Text("插入") }
                if (recovery.isNotEmpty()) UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)) }) { Text("恢复编辑") }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.RequestLeave) }) { Text("返回笔记库") }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("关闭") } })
        NoteEditorPanel.Recovery -> AlertDialog(onDismissRequest = close, title = { Text("恢复编辑") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                recovery.forEachIndexed { index, candidate ->
                    Text(when (candidate.status) {
                        NoteRecoveryStatus.AVAILABLE -> "未保存的编辑"
                        NoteRecoveryStatus.STALE -> "基版已变化，可查看或复制"
                        NoteRecoveryStatus.CORRUPT -> "恢复稿损坏，正式正文不受影响"
                    })
                    Text((candidate.draft?.markdown ?: candidate.rawRecovery).take(300))
                    UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.RecoveryReview(index))) }) { Text("查看完整内容") }
                    Row {
                        if (candidate.status == NoteRecoveryStatus.AVAILABLE) UnarchiveTextButton(onClick = {
                            onIntent(NoteEditorIntent.RestoreRecovery(index)) }, modifier = Modifier.testTag("markdown-note-restore-$index")) { Text("恢复") }
                        UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.CopyRecovery(index)) }) { Text("复制") }
                        UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.DiscardRecovery(index)) },
                            modifier = Modifier.testTag("markdown-note-discard-recovery-$index")) { Text("放弃此稿") }
                    }
                }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("保留正式版阅读") } })
        NoteEditorPanel.Info -> AlertDialog(onDismissRequest = close, title = { Text(state.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                content?.let { Text("来源：${it.structuredMetadata.source.canonicalUrl}")
                    Text("作者：${it.structuredMetadata.source.ownerName}")
                    Text("时长：${it.structuredMetadata.source.durationMs / 1000} 秒")
                    Text("更新于：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.updatedAtEpochMs))}")
                    Text("正式修订：${it.markdownRevision}") }
                state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("关闭") } })
        NoteEditorPanel.Outline -> AlertDialog(onDismissRequest = close, title = { Text("大纲") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (headings.isEmpty()) Text("暂无标题")
                headings.forEach { (title, offset) -> UnarchiveTextButton(onClick = { onReveal(offset); close() }) {
                    Text(title.trimStart(), Modifier.padding(start = ((title.length - title.trimStart().length) * 6).dp),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("关闭") } })
        NoteEditorPanel.Formatting -> AlertDialog(onDismissRequest = close, title = { Text("格式") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                (0..6).forEach { level -> UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetBlockStyle(level)) }) {
                    Text(if (level == 0) "正文" else "标题 H$level") } }
                InlineMark.entries.filter { it != InlineMark.LINK }.forEach { mark -> FormatMarkChoice(mark, formatState, onFormat) }
                DocumentListStyle.entries.forEach { style -> UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetList(style)) }) {
                    Text(when (style) { DocumentListStyle.UNORDERED -> "无序列表"; DocumentListStyle.ORDERED -> "有序列表"; else -> "任务列表" }) } }
                UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.ToggleTask) }) { Text("切换任务勾选") }
                UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetQuote) }) { Text("引用") }
                UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetCodeBlock) }) { Text("代码块") }
            } },
            confirmButton = { UnarchiveTextButton(onClick = close) { Text("关闭") } })
        NoteEditorPanel.Insert -> AlertDialog(onDismissRequest = close, title = { Text("插入") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                onPickPhoto?.let { action -> UnarchiveTextButton(onClick = action, modifier = Modifier.testTag("markdown-note-pick-photo")) { Text("从相册选择图片") } }
                imageAssets.forEachIndexed { index, asset -> UnarchiveTextButton(onClick = { onExistingImage(asset.assetId) }) { Text("插入已有图片 ${index + 1}") } }
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Link)) }) { Text("链接") }
                UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.InsertDivider) }) { Text("分隔线") }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("关闭") } })
        NoteEditorPanel.Link -> {
            var destination by remember { mutableStateOf(selectedLink.orEmpty()) }
            var label by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = close, title = { Text("链接") }, text = { Column {
                OutlinedTextField(label, { label = it }, label = { Text("文字（可选）") })
                OutlinedTextField(destination, { destination = it }, label = { Text("网址") })
                UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.RemoveLink) }) { Text("移除链接") }
            } }, confirmButton = { UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.SetLink(destination, label.takeIf { it.isNotEmpty() })) },
                enabled = destination.isNotBlank()) { Text("应用") } }, dismissButton = { UnarchiveTextButton(onClick = close) { Text("取消") } })
        }
        NoteEditorPanel.Source, NoteEditorPanel.Find, NoteEditorPanel.Ai, NoteEditorPanel.AiReview, NoteEditorPanel.Title -> Unit
        is NoteEditorPanel.RecoveryReview -> Unit
        NoteEditorPanel.Conflict -> AlertDialog(onDismissRequest = close, title = { Text("正式版本已变化") },
            text = { Text("当前修改仍在本页，可先复制。放弃后会载入最新正式版，不自动合并。") },
            confirmButton = { UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.CopyCurrent) }) { Text("复制当前修改") } },
            dismissButton = { Column {
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.LoadLatestAfterDiscard) }) { Text("放弃当前修改并载入最新正式版") }
                UnarchiveTextButton(onClick = close) { Text("继续编辑") }
            } })
        NoteEditorPanel.External -> AlertDialog(onDismissRequest = close, title = { Text("选择分享的版本") },
            text = { Text("当前有未保存的修改。") },
            confirmButton = { UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.SaveThenExternal) }) { Text("保存后继续") } },
            dismissButton = { Column {
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.UseFormalExternal) }) { Text("使用上次正式版本") }
                UnarchiveTextButton(onClick = close) { Text("取消") }
            } })
        NoteEditorPanel.Import -> AlertDialog(onDismissRequest = close, title = { Text("图片导入") },
            text = { Column {
                Text(when (importState) {
                    is ImageImportState.Picking -> "请完成或取消图片选择"
                    is ImageImportState.Copying -> "正在导入图片…"
                    is ImageImportState.AwaitingPlacement -> "正文位置已变化。请选择插入位置，再插入到当前光标。"
                    is ImageImportState.Failed -> importState.reason.message
                    else -> "请先处理图片导入再保存"
                })
                if (importState is ImageImportState.AwaitingPlacement) UnarchiveTextButton(onClick = onPlaceImport) { Text("插入到当前光标") }
                if (importState is ImageImportState.Failed) UnarchiveTextButton(onClick = onRetryImport) { Text("重试导入") }
                UnarchiveTextButton(onClick = onCancelImport) { Text("取消导入") }
            } }, confirmButton = { UnarchiveTextButton(onClick = close) { Text("返回正文") } })
    }
}

@Composable
private fun FormatMarkChoice(mark: InlineMark, state: SelectionFormatState?, onFormat: (DocumentFormatCommand) -> Unit) {
    val coverage = state?.marks?.get(mark) ?: FormatCoverage.OFF
    val label = when (mark) { InlineMark.BOLD -> "粗体"; InlineMark.ITALIC -> "斜体"; InlineMark.STRIKE -> "删除线"; else -> "行内代码" }
    UnarchiveTextButton(onClick = { onFormat(DocumentFormatCommand.ToggleMark(mark)) }, modifier = Modifier.semantics {
        stateDescription = when (coverage) { FormatCoverage.ON -> "已启用"; FormatCoverage.MIXED -> "混合"; else -> "未启用" }
    }) {
        Text(label, color = if (coverage == FormatCoverage.ON) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (coverage != FormatCoverage.OFF) Text(if (coverage == FormatCoverage.MIXED) " · 混合" else " · 已启用")
    }
}
