package com.unarchive.android.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.editor.*
import com.unarchive.android.editor.session.*
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.IosLikeButton
import com.unarchive.android.ui.components.UnarchiveIconButton
import com.unarchive.android.ui.components.UnarchiveTextButton

/** The native body owns the only vertical scroll surface in either mode. */
@Composable
internal fun MarkdownNoteScreen(
    state: MarkdownNoteUiState,
    onIntent: (NoteEditorIntent) -> Unit,
    editEntryVisible: Boolean,
    imeVisible: Boolean,
    onToggleIme: () -> Unit,
    onFormat: (DocumentFormatCommand) -> Unit = {},
    formatState: SelectionFormatState? = null,
    onCopyVisibleInput: () -> Unit = {},
    body: @Composable (Modifier) -> Unit,
) {
    val editing = state.mode == NoteEditorMode.Editing || state.mode == NoteEditorMode.Saving
    var editFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val accessibility = remember(context) {
        context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    }
    var touchExploration by remember(accessibility) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    Surface(Modifier.fillMaxSize().imePadding().testTag("markdown-note-screen")) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("markdown-note-topbar")) {
                if (!editing) {
                    // Both gutters are sized to the larger, two-action trailing group.
                    Text(state.title, Modifier.align(Alignment.Center).padding(horizontal = 104.dp)
                        .testTag("markdown-note-title"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                }
                UnarchiveIconButton(
                    onClick = { onIntent(if (editing) NoteEditorIntent.Done else NoteEditorIntent.RequestLeave) },
                    enabled = state.mode != NoteEditorMode.Saving,
                    hapticFeedback = true,
                    modifier = Modifier.align(Alignment.CenterStart).testTag(if (editing) "markdown-note-done" else "markdown-note-back"),
                ) {
                    AppIcon(if (editing) AppIcons.Check else AppIcons.Back,
                        if (editing) "完成并保存" else "返回笔记库",
                        tint = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.align(Alignment.CenterEnd)) {
                    if (editing) {
                        NoteAction("撤销", "markdown-note-undo", state.canUndo && state.mode != NoteEditorMode.Saving,
                            { onIntent(NoteEditorIntent.Undo) }) { AppIcon(AppIcons.Undo, null) }
                        NoteAction("重做", "markdown-note-redo", state.canRedo && state.mode != NoteEditorMode.Saving,
                            { onIntent(NoteEditorIntent.Redo) }) { AppIcon(AppIcons.Redo, null) }
                    }
                    NoteAction("大纲", "markdown-note-outline", state.mode in setOf(NoteEditorMode.Reading, NoteEditorMode.Editing),
                        { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Outline)) }) { AppIcon(AppIcons.Outline, null) }
                    if (!editing) NoteAction("更多", "markdown-note-more", state.mode == NoteEditorMode.Reading,
                        { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.More)) }) { AppIcon(AppIcons.More, null) }
                }
            }
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("markdown-note-error"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(error.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    UnarchiveTextButton(onClick = { if (error.domain == NoteErrorDomain.Input) onCopyVisibleInput() else onIntent(when (error.domain) {
                        NoteErrorDomain.Load -> NoteEditorIntent.RetryOpen
                        NoteErrorDomain.Draft -> NoteEditorIntent.RetryDraft
                        NoteErrorDomain.Recovery -> NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)
                        else -> NoteEditorIntent.Done
                    }) }) { Text(if (error.domain == NoteErrorDomain.Input) "复制当前输入" else "重试") }
                }
            }
            if (!editing && state.recoveryCount > 0) {
                UnarchiveTextButton(onClick = { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)) },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("markdown-note-recovery")) {
                    Text("有可恢复的编辑（${state.recoveryCount}）")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.mode) {
                    NoteEditorMode.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("markdown-note-loading"))
                    NoteEditorMode.LoadFailed -> UnarchiveTextButton(
                        onClick = { onIntent(NoteEditorIntent.RetryOpen) }, modifier = Modifier.align(Alignment.Center)) { Text("重新载入") }
                    else -> body(Modifier.fillMaxSize().testTag("markdown-note-body"))
                }
                // Touch exploration keeps its stable virtual accessibility target while scrolling.
                if (state.mode == NoteEditorMode.Reading && (editEntryVisible || editFocused || touchExploration)) {
                    IosLikeButton(text = "编辑", onClick = { onIntent(NoteEditorIntent.Edit) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).onFocusChanged { editFocused = it.isFocused }.testTag("markdown-note-edit"),
                        leadingContent = { AppIcon(AppIcons.Create, null) })
                }
            }
            if (editing) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val showTasks = maxWidth >= 336.dp
                val showLists = maxWidth >= 288.dp
                val showBold = maxWidth >= 240.dp
                val showInsert = maxWidth >= 192.dp
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("markdown-note-toolbar"),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (showInsert) NoteAction("插入", "markdown-note-insert", state.mode != NoteEditorMode.Saving,
                        { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Insert)) }, Modifier.weight(1f)) { AppIcon(AppIcons.Add, null) }
                    NoteAction("标题与正文", "markdown-note-format", state.mode != NoteEditorMode.Saving,
                        { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Formatting)) }, Modifier.weight(1f)) { AppIcon(AppIcons.TextStyle, null) }
                    if (showBold) NoteAction("粗体", "markdown-note-bold", state.mode != NoteEditorMode.Saving,
                        { onFormat(DocumentFormatCommand.ToggleMark(InlineMark.BOLD)) }, Modifier.weight(1f).semantics {
                            stateDescription = when (formatState?.marks?.get(InlineMark.BOLD)) {
                                FormatCoverage.ON -> "已启用"; FormatCoverage.MIXED -> "混合"; else -> "未启用"
                            }
                        }) {
                        AppIcon(AppIcons.Bold, null, tint = if (formatState?.marks?.get(InlineMark.BOLD) == FormatCoverage.ON)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    if (showLists) {
                        NoteAction("列表", "markdown-note-list", state.mode != NoteEditorMode.Saving,
                            { onFormat(DocumentFormatCommand.SetList(DocumentListStyle.UNORDERED)) }, Modifier.weight(1f)) { AppIcon(AppIcons.List, null) }
                    }
                    if (showTasks) NoteAction("任务列表", "markdown-note-task", state.mode != NoteEditorMode.Saving,
                        { onFormat(DocumentFormatCommand.SetList(DocumentListStyle.TASK)) }, Modifier.weight(1f)) { AppIcon(AppIcons.Task, null) }
                    NoteAction("更多", "markdown-note-edit-more", state.mode != NoteEditorMode.Saving,
                        { onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.More)) }, Modifier.weight(1f)) { AppIcon(AppIcons.More, null) }
                    NoteAction(if (imeVisible) "收起键盘" else "展开键盘", "markdown-note-keyboard", state.mode != NoteEditorMode.Saving,
                        onToggleIme, Modifier.weight(1f)) { AppIcon(if (imeVisible) AppIcons.KeyboardHide else AppIcons.KeyboardShow, null) }
                }
                }
            }
        }
    }
}

@Composable
private fun NoteAction(label: String, tag: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    com.unarchive.android.ui.components.CupertinoIconAction(onClick, modifier.testTag(tag), enabled, label,
        hapticFeedback = true, icon = icon)
}
