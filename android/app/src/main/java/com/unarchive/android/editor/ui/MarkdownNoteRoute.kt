package com.unarchive.android.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unarchive.android.card.*
import com.unarchive.android.editor.*
import com.unarchive.android.editor.native.NativeDocumentHost
import com.unarchive.android.ui.components.UnarchiveTextButton
import com.unarchive.android.editor.clipboard.fromClipData
import com.unarchive.android.editor.assets.NoteImageImporter

@Composable
internal fun MarkdownNoteMigrationRoute(document: NoteDocument, repository: FileNoteContentRepository,
    documentRepository: NoteDocumentRepository, onBack: () -> Unit, onFormalCommitted: (NoteContent) -> Unit = {},
    onOpenExternal: (FormalOperationRequest) -> Unit = {}, imageImporter: NoteImageImporter? = null,
    aiRepository: MarkdownAiProposalRepository? = null, aiGenerator: MarkdownAiProposalGenerator? = null,
    isManagedAssetAvailable: (CardAsset) -> Boolean = { false }, readAsset: (CardAsset) -> ByteArray? = { null }) {
    val migration: MarkdownEditorMigrationViewModel = viewModel(
        key = "markdown-note-migration-${document.cardId.value}-${document.generation.cardVersion}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MarkdownEditorMigrationViewModel(document, NoteContentSynchronizer(repository, documentRepository)) as T
        })
    val state by migration.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        MarkdownEditorMigrationUiState.Ready -> MarkdownNoteRoute(document.cardId, document.generation.cardVersion,
            repository, onBack, onFormalCommitted, onOpenExternal, imageImporter, aiRepository, aiGenerator, isManagedAssetAvailable, readAsset)
        else -> Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(when (current) {
                MarkdownEditorMigrationUiState.Loading -> "正在准备笔记…"
                is MarkdownEditorMigrationUiState.Conflict -> "原文与结构化笔记存在差异，请选择保留版本"
                else -> "迁移未完成，原笔记仍保留"
            })
            if (current is MarkdownEditorMigrationUiState.Conflict) {
                NoteContentMigrationChoice.entries.forEach { choice ->
                    UnarchiveTextButton(onClick = { migration.onEvent(MarkdownEditorMigrationEvent.Resolve(choice)) }) {
                        Text(if (choice == NoteContentMigrationChoice.KEEP_LEGACY_MARKDOWN) "保留原 Markdown" else "使用结构化笔记") }
                }
                androidx.compose.foundation.text.selection.SelectionContainer { Text(current.legacyMarkdown) }
            }
            if (current is MarkdownEditorMigrationUiState.Failed) {
                Text(current.message)
                current.legacyMarkdown?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it) } }
                UnarchiveTextButton(onClick = { migration.onEvent(MarkdownEditorMigrationEvent.Retry) }) { Text("重试") }
            }
            UnarchiveTextButton(onClick = onBack) { Text("返回") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MarkdownNoteRoute(cardId: KnowledgeCardId, cardVersion: String, repository: FileNoteContentRepository,
    onBack: () -> Unit, onFormalCommitted: (NoteContent) -> Unit = {}, onOpenExternal: (FormalOperationRequest) -> Unit = {},
    imageImporter: NoteImageImporter? = null, aiRepository: MarkdownAiProposalRepository? = null,
    aiGenerator: MarkdownAiProposalGenerator? = null, isManagedAssetAvailable: (CardAsset) -> Boolean = { false },
    readAsset: (CardAsset) -> ByteArray? = { null }) {
    val vm: MarkdownNoteViewModel = viewModel(key = "markdown-note-${cardId.value}-$cardVersion",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MarkdownNoteViewModel(cardId, cardVersion, repository) as T
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                MarkdownNoteViewModel(cardId, cardVersion, repository, runCatching { extras.createSavedStateHandle() }.getOrNull()) as T
        })
    val state by vm.state.collectAsStateWithLifecycle()
    val bridge = vm.bridge
    val nativeState = bridge?.state?.collectAsStateWithLifecycle()?.value
    val imeVisible = WindowInsets.isImeVisible
    val clipboard = LocalClipboardManager.current
    val latestBack by rememberUpdatedState(onBack)
    val latestCommitted by rememberUpdatedState(onFormalCommitted)
    val latestExternal by rememberUpdatedState(onOpenExternal)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm, imageImporter, bridge) {
        imageImporter?.let(vm::configureImages)
        vm.savedPickerUri?.let { savedUri ->
            vm.pickerResult({ checkNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(savedUri))) })
        }
    }
    LaunchedEffect(vm, bridge) { vm.configureAssetReader(readAsset) }
    LaunchedEffect(vm, bridge, aiRepository, state.formalRevision) {
        aiRepository?.let { vm.configureAi(it, aiGenerator, isManagedAssetAvailable) }
    }
    val aiState = vm.ai?.state?.collectAsStateWithLifecycle()?.value
    val imageState = vm.images?.state?.collectAsStateWithLifecycle()?.value
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.pickerResult(uri?.let { selected -> { checkNotNull(context.contentResolver.openInputStream(selected)) } }, uri?.toString())
    }
    LaunchedEffect(vm.openLink) {
        val destination = vm.openLink ?: return@LaunchedEffect
        try {
            if (destination.startsWith("#")) {
                bridge?.session?.captureSnapshot()?.let { snapshot ->
                    val title = destination.removePrefix("#").replace('-', ' ')
                    snapshot.projection.nodes.firstOrNull { it.node.text.equals(title, true) }?.let { bridge?.revealSourceOffset(it.node.start) }
                }
            } else if (NoteLinkPolicy.isAllowed(destination)) {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(destination)))
            }
        } catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "没有可打开此链接的应用", android.widget.Toast.LENGTH_SHORT).show() }
        finally { vm.consumeLink() }
    }
    LaunchedEffect(state.mode, bridge) {
        if (state.mode == NoteEditorMode.Editing) bridge?.showIme()
    }
    LaunchedEffect(vm) {
        vm.effects.collect { effect -> when (effect) {
            NoteEditorEffect.Leave -> latestBack()
            is NoteEditorEffect.FormalCommitted -> { vm.formalCommitted(effect.content); latestCommitted(effect.content) }
            is NoteEditorEffect.CopyText -> clipboard.setText(AnnotatedString(effect.text))
            is NoteEditorEffect.OpenExternal -> latestExternal(effect.request)
        } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) vm.flushBoundary()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.flushBoundary() }
    }
    BackHandler {
        when {
            bridge?.view?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true || imeVisible -> bridge?.hideIme()
            state.panel != NoteEditorPanel.None -> vm.onIntent(NoteEditorIntent.ClosePanel)
            else -> vm.onIntent(NoteEditorIntent.RequestLeave)
        }
    }
    MarkdownNoteScreen(if (nativeState?.error != null) state.copy(error = NoteEditorError(NoteErrorDomain.Input, nativeState.error!!)) else state, vm::onIntent,
        editEntryVisible = nativeState?.let { !it.scrolling && !it.selecting && !it.touchActive } ?: false,
        imeVisible = imeVisible, onToggleIme = { if (imeVisible) bridge?.hideIme() else bridge?.showIme() },
        onFormat = { vm.format(it) },
        onCopyVisibleInput = {
            bridge?.view?.text?.let { editable ->
                val builder = StringBuilder(editable.toString())
                editable.getSpans(0, editable.length, com.unarchive.android.editor.native.NativeDocumentSpans.OwnedAtomic::class.java)
                    .sortedByDescending { editable.getSpanStart(it) }.forEach { span ->
                        val from = editable.getSpanStart(span); val end = editable.getSpanEnd(span)
                        if (from >= 0 && end <= builder.length) builder.replace(from, end, span.node.text)
                    }
                clipboard.setText(AnnotatedString(builder.toString().replace("\uFFFC", "")))
            }
        },
        formatState = vm.formatState) { modifier ->
        if (bridge != null) NativeDocumentHost(bridge, state.mode == NoteEditorMode.Editing && state.panel != NoteEditorPanel.Source, modifier,
            textColor = MaterialTheme.colorScheme.onSurface, backgroundColor = MaterialTheme.colorScheme.background)
    }
    val headings = vm.outline.map { "  ".repeat((it.level - 1).coerceAtLeast(0)) + it.title to it.sourceOffset }
    NoteEditorPanels(state, vm.coordinator.formal, vm.coordinator.recovery, headings,
        onReveal = vm::revealSourceOffset, onIntent = vm::onIntent, onFormat = { vm.format(it) },
        onPastePlain = {
            bridge?.pasteFromClipboard(true)
            vm.onIntent(NoteEditorIntent.ClosePanel)
        }, onPickPhoto = if (vm.images != null) { { vm.beginImagePick { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } } } else null,
        imageAssets = vm.coordinator.currentAssets, onExistingImage = vm::existingImage,
        importState = imageState, onPlaceImport = vm::placeImportedHere, onRetryImport = vm::retryImport, onCancelImport = vm::cancelImport,
        aiStatus = aiState?.shortStatus, onSelectedNode = vm::openSelectedNode,
        onTranscriptToggle = if (bridge?.session?.foldRanges?.isNotEmpty() == true) { {
            vm.toggleTranscript(); vm.onIntent(NoteEditorIntent.ClosePanel)
        } } else null, selectedLink = if (state.panel == NoteEditorPanel.More || state.panel == NoteEditorPanel.Link) vm.selectedLink() else null,
        onOpenLink = { vm.openSelectedLink(); vm.onIntent(NoteEditorIntent.ClosePanel) }, formatState = vm.formatState)
    if (state.panel == NoteEditorPanel.Ai) AlertDialog(onDismissRequest = { vm.onIntent(NoteEditorIntent.ClosePanel) },
        title = { Text("AI 整理") }, text = { Column {
            Text(aiState?.shortStatus ?: "根据最后正式保存的正文生成候选")
            aiState?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (aiState?.proposal != null) UnarchiveTextButton(onClick = vm::reviewAi) { Text("查看候选") }
            UnarchiveTextButton(onClick = vm::generateAi,
                enabled = vm.ai != null && aiState?.activity != com.unarchive.android.editor.ai.AiProposalActivity.RUNNING) { Text("生成候选") }
        } }, confirmButton = { UnarchiveTextButton(onClick = { vm.onIntent(NoteEditorIntent.ClosePanel) }) { Text("关闭") } })
    if (state.panel == NoteEditorPanel.AiReview && aiState != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { vm.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Ai)) },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            AiReviewScreen(aiState, onBack = { vm.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Ai)) },
                onApply = { vm.applyAi() }, onReject = { vm.ai?.reject() }, onRegenerate = {
                    vm.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Ai)); vm.generateAi()
                })
        }
    }
    if (vm.aiSavePrompt) AlertDialog(onDismissRequest = vm::continueAiEditing,
        title = { Text("保存后生成候选？") }, text = { Text("AI 整理使用正式保存的正文。") },
        confirmButton = { UnarchiveTextButton(onClick = vm::saveAndGenerateAi) { Text("保存并生成") } },
        dismissButton = { UnarchiveTextButton(onClick = vm::continueAiEditing) { Text("继续编辑") } })
    vm.activeNode?.let { node -> NoteNodePanel(node, state.mode == NoteEditorMode.Editing,
        vm::closeNode, vm::nodeSource, vm::deleteSelectedNode,
        onReplace = if (node.atomic?.kind == "image" && vm.images != null) { {
            vm.closeNode(); vm.beginImagePick(node.start, node.end) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        } } else null,
        onCopy = { clipboard.setText(AnnotatedString(bridge?.session?.source?.substring(node.start, node.end).orEmpty())) },
        resolveImage = bridge?.resolveImage) }
    if (state.panel == NoteEditorPanel.Find) AlertDialog(onDismissRequest = { vm.onIntent(NoteEditorIntent.ClosePanel) },
        title = { Text("查找") }, text = { Column {
            androidx.compose.material3.OutlinedTextField(vm.findQuery, vm::find, label = { Text("在正文中查找") }, singleLine = true)
            Text(if (vm.finding) "正在查找…" else if (vm.findHits.isEmpty()) "无结果" else "${vm.findPosition + 1} / ${vm.findHits.size}")
            androidx.compose.foundation.layout.Row {
                UnarchiveTextButton(onClick = { vm.nextFind(-1) }, enabled = vm.findHits.isNotEmpty()) { Text("上一个") }
                UnarchiveTextButton(onClick = { vm.nextFind(1) }, enabled = vm.findHits.isNotEmpty()) { Text("下一个") }
            }
        } }, confirmButton = { UnarchiveTextButton(onClick = { vm.onIntent(NoteEditorIntent.ClosePanel) }) { Text("关闭") } })
    if (state.panel == NoteEditorPanel.Title) {
        var title by remember { mutableStateOf(state.title) }
        AlertDialog(onDismissRequest = { vm.onIntent(NoteEditorIntent.ClosePanel) }, title = { Text("修改标题") },
            text = { androidx.compose.material3.OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("标题") }) },
            confirmButton = { UnarchiveTextButton(onClick = { vm.renameTitle(title) }, enabled = title.isNotBlank()) { Text("应用到草稿") } },
            dismissButton = { UnarchiveTextButton(onClick = { vm.onIntent(NoteEditorIntent.ClosePanel) }) { Text("取消") } })
    }
    (state.panel as? NoteEditorPanel.RecoveryReview)?.let { panel ->
        vm.coordinator.recovery.getOrNull(panel.index)?.let { candidate ->
            val text = candidate.draft?.let { draft -> draft.markdown +
                draft.pendingSourceEdit?.let { "\n\n—— 未应用的源码暂存 ——\n${it.text}" }.orEmpty() } ?: candidate.rawRecovery
            NoteRecoveryReview(text,
                onBack = { vm.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)) },
                onCopy = { clipboard.setText(AnnotatedString(text)) })
        }
    }
    if (state.panel == NoteEditorPanel.Source) vm.sourceScratch?.let { scratch ->
        NoteSourcePanel(scratch, vm::sourceChanged, { vm.applySource() }, { vm.cancelSource() },
            { clipboard.setText(AnnotatedString(scratch.text)) }, vm.sourceReadOnly, vm.sourceError)
    }
    if (vm.confirmSourceDiscard) AlertDialog(onDismissRequest = vm::continueSource, title = { Text("放弃源码面板修改？") },
        text = { Text("未应用的源码修改尚未加入正文。") },
        confirmButton = { UnarchiveTextButton(onClick = { vm.cancelSource(true) }) { Text("放弃面板修改") } },
        dismissButton = { UnarchiveTextButton(onClick = vm::continueSource) { Text("继续编辑") } })
    if (vm.barrierCommand != null || vm.sourceBarrier || vm.aiBarrier) AlertDialog(onDismissRequest = vm::cancelBarrier,
        title = { Text("本次修改超过撤销容量") }, text = { Text("继续将清空此前的撤销记录。") },
        confirmButton = { UnarchiveTextButton(onClick = vm::acceptBarrier) { Text("继续并清空历史") } },
        dismissButton = { UnarchiveTextButton(onClick = vm::cancelBarrier) { Text("取消") } })
    if (state.panel != NoteEditorPanel.Source) vm.sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
