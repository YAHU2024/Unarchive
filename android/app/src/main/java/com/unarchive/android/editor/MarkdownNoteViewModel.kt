package com.unarchive.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeEditorBridge
import com.unarchive.android.editor.persistence.NotePersistenceCoordinator
import com.unarchive.android.editor.projection.DocumentTitleResolver
import com.unarchive.android.editor.projection.NoteNavigationIndex
import com.unarchive.android.editor.projection.NoteFindHit
import com.unarchive.android.editor.projection.TranscriptRangeResolver
import com.unarchive.android.editor.projection.NoteOutlineEntry
import com.unarchive.android.editor.session.*
import com.unarchive.android.editor.assets.*
import com.unarchive.android.editor.ai.*
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MarkdownNoteViewModel(private val cardId: KnowledgeCardId, private val cardVersion: String, private val repository: FileNoteContentRepository,
    private val savedState: SavedStateHandle? = null) : ViewModel() {
    var bridge: NativeEditorBridge? by mutableStateOf(null); private set
    var sourceScratch: NoteSourceScratch? by mutableStateOf(null); private set
    var sourceError: String? by mutableStateOf(null); private set
    var sourceReadOnly by mutableStateOf(false); private set
    var confirmSourceDiscard by mutableStateOf(false); private set
    var barrierCommand: DocumentFormatCommand? by mutableStateOf(null); private set
    var sourceBarrier by mutableStateOf(false); private set
    var formatState by mutableStateOf<SelectionFormatState?>(null); private set
    var images: ImageImportCoordinator? by mutableStateOf(null); private set
    var pendingPicker: ImageImportRequest? by mutableStateOf(null); private set
    private var importJob: Job? = null
    var findQuery by mutableStateOf(""); private set
    var findHits by mutableStateOf<List<NoteFindHit>>(emptyList()); private set
    var findPosition by mutableStateOf(-1); private set
    var finding by mutableStateOf(false); private set
    private var findJob: Job? = null
    private var findToken = 0L
    private var navigationIndex: NoteNavigationIndex? = null
    private var navigationSession: String? = null
    var outline by mutableStateOf<List<NoteOutlineEntry>>(emptyList()); private set
    var ai: AiProposalCoordinator? by mutableStateOf(null); private set
    var aiSavePrompt by mutableStateOf(false); private set
    var aiBarrier by mutableStateOf(false); private set
    private var generateAfterSave = false
    private val retainedAssets = linkedMapOf<String, CardAsset>()
    var activeNode: DocumentNode? by mutableStateOf(null); private set
    var openLink: String? by mutableStateOf(null); private set
    private var panelScrollY: Int? = null
    val coordinator: NoteEditorCoordinator = NoteEditorCoordinator(cardId, cardVersion, NotePersistenceCoordinator(repository), viewModelScope,
        openDocument = { content ->
            val session = withContext(Dispatchers.Default) {
                DocumentSession(content.markdown).also { document ->
                    document.initializeAssets(content.structuredMetadata.assets.map(::assetReference))
                    TranscriptRangeResolver.resolve(content.markdown, document.projection, content.structuredMetadata)?.let {
                        document.setFold(it.start, it.end, "${it.title} · ${it.paragraphCount} 段")
                    }
                }
            }
            val native = NativeEditorBridge(session, viewModelScope)
            native.onAccepted = { accepted -> publish(accepted, content.structuredMetadata.title) }
            object : NoteDocumentPort {
                override fun activate() {
                    bridge?.dispose(); bridge = native
                    retainedAssets.clear(); content.structuredMetadata.assets.forEach { retainedAssets[it.assetId] = it }
                    native.onHistoryBarrier = { barrierCommand = it }
                    native.onNodeAction = { rememberPanelPosition(); activeNode = it }
                    native.onLink = { openLink = it }
                    native.setFallbackTitle(content.structuredMetadata.title)
                    val sourceInfo = content.structuredMetadata.source
                    val summary = listOf(content.cardId.platform, sourceInfo.ownerName.takeIf { it.isNotBlank() },
                        "${sourceInfo.durationMs / 60_000}:${((sourceInfo.durationMs / 1000) % 60).toString().padStart(2, '0')}")
                        .filterNotNull().joinToString(" · ")
                    native.setSourceSummary(summary.takeUnless { content.markdown.contains(sourceInfo.canonicalUrl) })
                    rebuildNavigation(session.captureSnapshot())
                    viewModelScope.launch {
                        native.state.map { listOf(it.acceptedVersion, it.selectionAnchor.toLong(), it.selectionFocus.toLong(), it.formatGeneration) }
                            .distinctUntilChanged().collectLatest {
                                formatState = withContext(Dispatchers.Default) { DocumentFormatCommands.selectionState(session) }
                            }
                    }
                }
                override fun dispose() { native.dispose() }
                override fun displayTitle(fallback: String) = DocumentTitleResolver.resolve(session.projection, fallback)
                override suspend fun capture(): NoteEditorSnapshot {
                    check(native.finishInputAndAwait()) { "Unaccepted native input" }
                    val snapshot = session.captureSnapshot()
                    reconcileAssets(snapshot)
                    val selection = native.sourceSelection()
                    return NoteEditorSnapshot(snapshot.source, snapshot.version, selection.anchor, selection.focus)
                }
                override suspend fun restore(markdown: String, selectionStart: Int, selectionEnd: Int) {
                    restoreSnapshot(markdown, selectionStart, selectionEnd, coordinator.currentAssets)
                }
                override suspend fun restoreSnapshot(markdown: String, selectionStart: Int, selectionEnd: Int, assets: List<CardAsset>) {
                    check(native.finishInputAndAwait())
                    assets.forEach { retainedAssets[it.assetId] = it }
                    withContext(Dispatchers.Default) {
                        session.registerAssets(assets.map(::assetReference))
                        val result = session.restoreRecoveredSnapshot(markdown, assets.map { it.assetId }.toSet())
                        check(result !is SessionResult.Rejected)
                        session.updateSelection(DocumentSelection(session.projection.projectedOffset(selectionStart),
                            session.projection.projectedOffset(selectionEnd)))
                    }
                    native.refresh()
                    check(native.finishInputAndAwait())
                }
                override suspend fun markFormal(markdown: String) { check(session.source == markdown) }
            }
        })
    val state = coordinator.state
    val effects = coordinator.effects

    init { coordinator.open() }

    private fun publish(snapshot: ImmutableDocumentSnapshot, fallback: String) {
        reconcileAssets(snapshot)
        val selection = snapshot.selection
        coordinator.documentChanged(NoteEditorSnapshot(snapshot.source, snapshot.version,
            snapshot.projection.sourceOffset(selection.anchor), snapshot.projection.sourceOffset(selection.focus)),
            DocumentTitleResolver.resolve(snapshot.projection, fallback), snapshot.canUndo, snapshot.canRedo)
        images?.contextChanged(importContext())
        if (findQuery.isNotEmpty()) find(findQuery)
        else if (state.value.panel == NoteEditorPanel.Outline) rebuildNavigation(snapshot)
        coordinator.formal?.let { formal -> ai?.updateContext(AiDocumentContext(formal, snapshot.source, snapshot.version,
            coordinator.currentAssets, coordinator.recovery.isNotEmpty() || coordinator.pendingSourceEdit != null)) }
    }

    private fun rebuildNavigation(snapshot: ImmutableDocumentSnapshot) {
        viewModelScope.launch {
            val index = withContext(Dispatchers.Default) {
                NoteNavigationIndex(snapshot.projection, snapshot.version, snapshot.source)
            }
            if (bridge?.session?.sessionId == snapshot.sessionId && bridge?.session?.version == snapshot.version) {
                navigationIndex = index; navigationSession = snapshot.sessionId; outline = index.outline
            }
        }
    }

    private fun assetReference(asset: CardAsset) = DocumentAssetReference(asset.assetId, asset.relativePath,
        asset.kind == CardAssetKind.COVER || asset.kind == CardAssetKind.CHAPTER_SCREENSHOT)
    private fun reconcileAssets(snapshot: ImmutableDocumentSnapshot) {
        coordinator.currentAssets.forEach { retainedAssets[it.assetId] = it }
        val assets = snapshot.activeAssetIds.map { checkNotNull(retainedAssets[it]) { "Missing asset metadata in this session" } }
        if (assets != coordinator.currentAssets) coordinator.setAssets(assets)
    }

    fun find(query: String) {
        findQuery = query
        val token = ++findToken
        findJob?.cancel()
        val snapshot = bridge?.session?.captureSnapshot() ?: return
        finding = true
        findJob = viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) {
                val index = if (navigationIndex?.version == snapshot.version && navigationSession == snapshot.sessionId) navigationIndex!!
                    else NoteNavigationIndex(snapshot.projection, snapshot.version, snapshot.source).also {
                        navigationIndex = it; navigationSession = snapshot.sessionId
                    }
                index.find(query)
            }
            if (token != findToken || bridge?.session?.version != snapshot.version) return@launch
            findHits = hits; findPosition = if (hits.isEmpty()) -1 else 0; finding = false
            outline = navigationIndex?.outline.orEmpty()
            hits.firstOrNull()?.let { revealSourceOffset(it.sourceStart) }
        }
    }
    fun nextFind(delta: Int) {
        if (findHits.isEmpty()) return
        findPosition = Math.floorMod(findPosition + delta, findHits.size)
        revealSourceOffset(findHits[findPosition].sourceStart)
    }

    fun revealSourceOffset(offset: Int) {
        // Explicit navigation replaces the return position of an opened panel.
        panelScrollY = null
        bridge?.revealSourceOffset(offset)
    }

    fun onIntent(intent: NoteEditorIntent) {
        if (intent in setOf(NoteEditorIntent.Done, NoteEditorIntent.SaveAndLeave, NoteEditorIntent.SaveThenExternal) && images?.needsResolution == true) {
            coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Import)); return
        }
        when (intent) {
            NoteEditorIntent.Undo -> bridge?.undo()
            NoteEditorIntent.Redo -> bridge?.redo()
            is NoteEditorIntent.OpenPanel -> {
                // Capture the visible position before settling native input:
                // restoring an atomic caret can scroll TextView in the barrier.
                rememberPanelPosition()
                viewModelScope.launch {
                    if (bridge?.finishInputAndAwait() == false) {
                        restorePanelPosition()
                        return@launch
                    }
                    bridge?.hideIme()
                    if (intent.panel == NoteEditorPanel.Source) openSource()
                    else {
                        if (intent.panel == NoteEditorPanel.Outline) bridge?.session?.captureSnapshot()?.let(::rebuildNavigation)
                        coordinator.onIntent(intent)
                    }
                }
            }
            NoteEditorIntent.ClosePanel -> {
                if (state.value.panel == NoteEditorPanel.Source) cancelSource()
                else { coordinator.onIntent(intent); restorePanelPosition() }
            }
            NoteEditorIntent.RequestLeave -> { generateAfterSave = false; coordinator.onIntent(intent) }
            else -> coordinator.onIntent(intent)
        }
    }

    fun configureAi(proposalRepository: MarkdownAiProposalRepository, generator: MarkdownAiProposalGenerator?,
        isManagedAssetAvailable: (CardAsset) -> Boolean) {
        if (ai != null || coordinator.formal == null) return
        val controller = AiProposalCoordinator(viewModelScope, proposalRepository, generator,
            { repository.find(cardId, cardVersion) }, { repository.findRevision(cardId, cardVersion, it) }, isManagedAssetAvailable)
        ai = controller
        viewModelScope.launch { aiContext()?.let { controller.restore(it) } }
        viewModelScope.launch { state.collect { current ->
            if (current.mode == NoteEditorMode.Editing && current.error?.domain == NoteErrorDomain.Save) generateAfterSave = false
        } }
    }

    private suspend fun aiContext(): AiDocumentContext? {
        val native = bridge ?: return null
        if (!native.finishInputAndAwait()) return null
        val formal = coordinator.formal ?: return null
        val snapshot = native.session.captureSnapshot()
        return AiDocumentContext(formal, snapshot.source, snapshot.version, coordinator.currentAssets,
            coordinator.recovery.isNotEmpty() || coordinator.pendingSourceEdit != null || images?.needsResolution == true)
    }

    fun generateAi() {
        viewModelScope.launch {
            val context = aiContext() ?: return@launch
            if (context.hasUnresolvedRecovery) { sourceError = "请先处理恢复草稿、源码暂存或图片导入"; return@launch }
            if (!context.clean) { aiSavePrompt = true; return@launch }
            ai?.generate(context)
        }
    }
    fun saveAndGenerateAi() { aiSavePrompt = false; generateAfterSave = true; onIntent(NoteEditorIntent.Done) }
    fun continueAiEditing() { aiSavePrompt = false; generateAfterSave = false }
    fun formalCommitted(content: NoteContent) {
        ai?.formalCommitted(content)
        if (generateAfterSave) {
            generateAfterSave = false
            coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Ai))
            generateAi()
        }
    }
    fun reviewAi() {
        ai?.openReview()
        coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.AiReview))
    }
    fun applyAi(allowBarrier: Boolean = false) {
        viewModelScope.launch {
            val context = aiContext() ?: return@launch
            val result = ai?.prepareApply(context) ?: return@launch
            if (result is AiApplyResult.Blocked) { sourceError = result.message; return@launch }
            val transaction = (result as AiApplyResult.Ready).transaction
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            val session = native.session
            if (session.version != transaction.expectedDocumentVersion ||
                NoteContent.markdownFingerprint(session.source) != transaction.expectedSourceFingerprint ||
                markdownAiAssetsFingerprint(coordinator.currentAssets) != transaction.expectedAssetsFingerprint ||
                coordinator.formal?.markdownRevision != transaction.expectedFormalRevision ||
                coordinator.recovery.isNotEmpty() || coordinator.pendingSourceEdit != null || images?.needsResolution == true) {
                sourceError = "正文已变化，候选不能应用"; return@launch
            }
            native.setEditing(false)
            val mutation = withContext(Dispatchers.Default) { session.replaceSource(0, session.source.length,
                transaction.markdown, origin = EditOrigin.AI, expectedVersion = transaction.expectedDocumentVersion,
                allowHistoryBarrier = allowBarrier) }
            when (mutation) {
                is SessionResult.HistoryBarrierRequired -> aiBarrier = true
                is SessionResult.Rejected -> sourceError = "候选应用失败，候选仍保留"
                else -> {
                    ai?.applied(transaction.proposalId); aiBarrier = false
                    native.refresh(); native.finishInputAndAwait()
                    coordinator.onIntent(NoteEditorIntent.Edit)
                    coordinator.onIntent(NoteEditorIntent.ClosePanel)
                }
            }
            native.setEditing(state.value.mode == NoteEditorMode.Editing)
        }
    }

    fun configureImages(importer: NoteImageImporter) {
        if (images != null) { restorePickerIfNeeded(); return }
        val controller = ImageImportCoordinator(importer)
        images = controller
        restorePickerIfNeeded()
        viewModelScope.launch { controller.state.collect { state ->
            if (state is ImageImportState.ReadyToInsert) insertImportedImage(state)
            else if (state is ImageImportState.Failed || state is ImageImportState.AwaitingPlacement)
                coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Import))
        } }
    }

    val savedPickerUri: String? get() = savedState?.get<String>("note-picker-uri")
    private fun restorePickerIfNeeded() {
        if (pendingPicker != null || bridge == null || images == null) return
        val values = savedState?.get<Array<String>>("note-picker") ?: return
        if (values.size != 7 || values[5] != cardId.value || values[6] != cardVersion) return
        val session = bridge!!.session
        val exact = NoteContent.markdownFingerprint(session.source) == values[1]
        val anchor = ImageImportAnchor(session.sessionId, if (exact) session.version else session.version + 1,
            values[2].toIntOrNull()?.coerceIn(0, session.source.length) ?: 0,
            values[3].toIntOrNull()?.takeIf { it >= 0 }?.coerceIn(0, session.source.length))
        pendingPicker = ImageImportRequest(values[0], anchor, values[4])
        images?.restorePending(pendingPicker!!)
    }

    fun configureAssetReader(readAsset: (CardAsset) -> ByteArray?) {
        bridge?.resolveImage = { destination, requestedWidth ->
            val asset = coordinator.currentAssets.firstOrNull { it.relativePath == destination }
            val bytes = asset?.let(readAsset)
            if (asset == null || bytes == null || bytes.isEmpty() || KnowledgeCard.sha256(bytes) != asset.sha256) null else {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 24_000_000) null
                else {
                    var sample = 1
                    while (bounds.outWidth / sample > requestedWidth.coerceAtLeast(1) * 2 ||
                        (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 2_000_000) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }
    }
    private fun rememberPanelPosition() {
        if (panelScrollY == null) panelScrollY = bridge?.view?.scrollY
        bridge?.view?.clearFocus()
    }
    private fun restorePanelPosition() {
        val y = panelScrollY ?: return
        panelScrollY = null
        bridge?.view?.let { host -> host.post { host.restorePanelViewport(y) } }
    }
    fun closeNode() { activeNode = null; restorePanelPosition() }
    fun consumeLink() { openLink = null }
    fun selectedLink(): String? {
        val native = bridge ?: return null
        val snapshot = native.session.captureSnapshot()
        return snapshot.projection.nodes.firstNotNullOfOrNull { block ->
            block.node.marks.firstOrNull { it.kind == InlineMark.LINK &&
                snapshot.selection.focus >= block.start + it.start && snapshot.selection.focus <= block.start + it.end }?.destination
        }
    }
    fun openSelectedLink() { selectedLink()?.let { openLink = it } }
    fun openSelectedNode() {
        // Moving from More to the node panel is one panel journey. Keep its
        // original viewport until the final panel closes, without an interim
        // restore followed by a new capture of TextView's focus scroll.
        rememberPanelPosition()
        activeNode = bridge?.nodeAtSelection()
        coordinator.onIntent(NoteEditorIntent.ClosePanel)
    }
    fun toggleTranscript() { bridge?.toggleTranscriptFold() }
    fun renameTitle(title: String) {
        val text = title.replace('\n', ' ').replace('\r', ' ').trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            if (!coordinator.enterEditing()) return@launch
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            val session = native.session
            val heading = session.projection.nodes.firstOrNull { it.node.headingLevel == 1 && !it.node.synthetic }?.node
            val frontMatter = session.projection.nodes.firstOrNull { it.node.kind == "front-matter" }?.node
            val at = heading?.start ?: frontMatter?.end ?: 0
            val replacement = (if (heading == null && at > 0) "\n\n" else "") + "# $text" + if (heading == null) "\n\n" else ""
            val result = withContext(Dispatchers.Default) { session.replaceSource(at, heading?.end ?: at, replacement, origin = EditOrigin.FORMAT) }
            if (result is SessionResult.Applied) {
                native.refresh(); native.finishInputAndAwait()
                coordinator.onIntent(NoteEditorIntent.Edit); coordinator.onIntent(NoteEditorIntent.ClosePanel)
            }
        }
    }
    fun nodeSource() { activeNode?.let { openSource(it.start, it.end) }; activeNode = null }
    fun deleteSelectedNode() {
        val node = activeNode ?: bridge?.nodeAtSelection() ?: return
        viewModelScope.launch {
            if (!coordinator.enterEditing()) return@launch
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            if (node.kind == "folded-transcript") {
                val session = native.session
                session.expandForMutation(DocumentSelection(session.projection.projectedOffset(node.start), session.projection.projectedOffset(node.end)))
                native.refresh(); native.finishInputAndAwait(); activeNode = null
                return@launch
            }
            val result = withContext(Dispatchers.Default) { native.session.replaceSource(node.start, node.end, "", origin = EditOrigin.CUT) }
            if (result is SessionResult.Applied) {
                native.refresh(); native.finishInputAndAwait()
                coordinator.onIntent(NoteEditorIntent.Edit)
            }
            activeNode = null
        }
    }

    private fun importContext(): ImageImportContext {
        val session = checkNotNull(bridge).session
        return ImageImportContext(session.sessionId, session.version, session.source.length)
    }

    fun beginImagePick(replaceStart: Int? = null, replaceEnd: Int? = null, then: (ImageImportRequest) -> Unit) {
        viewModelScope.launch {
            if (coordinator.recovery.isNotEmpty()) {
                coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)); return@launch
            }
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            val session = native.session
            val offset = replaceStart ?: native.sourceSelection().focus
            val request = images?.beginPick(ImageImportAnchor(session.sessionId, session.version, offset, replaceEnd)) ?: return@launch
            pendingPicker = request
            coordinator.flushDraft()
            savedState?.set("note-picker", arrayOf(request.requestId, NoteContent.markdownFingerprint(session.source),
                offset.toString(), (replaceEnd ?: -1).toString(), request.alt, cardId.value, cardVersion))
            coordinator.onIntent(NoteEditorIntent.ClosePanel)
            native.hideIme()
            then(request)
        }
    }

    fun pickerResult(openStream: (() -> InputStream)?, uri: String? = null) {
        if (uri != null) savedState?.set("note-picker-uri", uri)
        if (openStream == null) { savedState?.remove<Array<String>>("note-picker"); savedState?.remove<String>("note-picker-uri") }
        restorePickerIfNeeded()
        val request = pendingPicker ?: return
        pendingPicker = null
        importJob = viewModelScope.launch { images?.pickerResult(request.requestId, openStream, ::importContext) }
    }

    fun existingImage(assetId: String) {
        beginImagePick { request ->
            pendingPicker = null
            importJob = viewModelScope.launch { images?.selectExisting(request.requestId, assetId,
                coordinator.currentAssets, ::importContext) }
        }
    }

    private suspend fun insertImportedImage(ready: ImageImportState.ReadyToInsert) {
        val native = bridge ?: return
        if (!native.finishInputAndAwait()) return
        val session = native.session
        images?.contextChanged(importContext())
        if (images?.state?.value !is ImageImportState.ReadyToInsert) return
        native.setEditing(false)
        val anchor = ready.request.anchor
        // Images occupy a complete source node; boundary newlines share the same undo transaction.
        val prefix = if (anchor.sourceOffset > 0 && !session.source.substring(0, anchor.sourceOffset).endsWith("\n\n")) "\n\n" else ""
        val end = anchor.replaceEnd ?: anchor.sourceOffset
        val suffix = if (end < session.source.length && !session.source.substring(end).startsWith("\n\n")) "\n\n" else ""
        val markdown = prefix + "![${ready.request.alt.replace("]", "\\]")}](${ready.asset.relativePath})" + suffix
        val result = withContext(Dispatchers.Default) {
            session.replaceSource(anchor.sourceOffset, anchor.replaceEnd ?: anchor.sourceOffset, markdown,
                origin = EditOrigin.IMAGE, expectedVersion = anchor.documentVersion,
                assetDelta = AssetReferenceDelta(add = listOf(assetReference(ready.asset))))
        }
        when (result) {
            is SessionResult.HistoryBarrierRequired -> sourceError = "图片插入超过撤销容量，请重新选择位置后处理"
            is SessionResult.Rejected -> sourceError = "图片落点已变化，请选择插入位置"
            else -> {
                retainedAssets[ready.asset.assetId] = ready.asset
                coordinator.setAssets((coordinator.currentAssets + ready.asset).distinctBy { it.assetId })
                images?.markInserted(ready.request.requestId)
                savedState?.remove<Array<String>>("note-picker"); savedState?.remove<String>("note-picker-uri")
                native.refresh(); native.finishInputAndAwait()
                coordinator.onIntent(NoteEditorIntent.Edit)
                coordinator.onIntent(NoteEditorIntent.ClosePanel)
            }
        }
        native.setEditing(state.value.mode == NoteEditorMode.Editing)
    }

    fun placeImportedHere() {
        val waiting = images?.state?.value as? ImageImportState.AwaitingPlacement ?: return
        viewModelScope.launch {
            if (coordinator.recovery.isNotEmpty()) {
                coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Recovery)); return@launch
            }
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            val session = native.session
            images?.placeAt(waiting.request.requestId,
                ImageImportAnchor(session.sessionId, session.version, native.sourceSelection().focus), session.source.length)
        }
    }
    fun retryImport() { importJob = viewModelScope.launch { images?.retry(::importContext) } }
    fun cancelImport() {
        images?.cancel(); pendingPicker = null
        savedState?.remove<Array<String>>("note-picker"); savedState?.remove<String>("note-picker-uri")
        coordinator.onIntent(NoteEditorIntent.ClosePanel)
    }

    fun format(command: DocumentFormatCommand, allowBarrier: Boolean = false) {
        viewModelScope.launch {
            if (!coordinator.enterEditing()) return@launch
            when (bridge?.executeCommand(command, allowBarrier)) {
                is SessionResult.HistoryBarrierRequired -> barrierCommand = command
                is SessionResult.Rejected -> sourceError = "当前范围无法应用此操作，原文仍保留"
                else -> { barrierCommand = null; coordinator.onIntent(NoteEditorIntent.ClosePanel) }
            }
        }
    }

    fun cancelBarrier() { barrierCommand = null; sourceBarrier = false; aiBarrier = false }
    fun acceptBarrier() {
        barrierCommand?.let { format(it, true) }
        if (sourceBarrier) applySource(true)
        if (aiBarrier) applyAi(true)
    }

    fun openSource(start: Int = 0, end: Int? = null) {
        viewModelScope.launch {
            if (coordinator.recovery.isNotEmpty()) { coordinator.enterEditing(); return@launch }
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            rememberPanelPosition()
            val snapshot = native.session.captureSnapshot()
            val pending = coordinator.pendingSourceEdit
            if (pending != null) {
                val validRange = pending.sourceStart >= 0 && pending.sourceEnd <= snapshot.source.length
                val original = if (validRange) snapshot.source.substring(pending.sourceStart, pending.sourceEnd) else ""
                sourceReadOnly = !validRange || NoteContent.markdownFingerprint(original) != pending.originalRangeFingerprint ||
                    NoteContent.markdownFingerprint(snapshot.source) != pending.baseContentFingerprint
                sourceScratch = NoteSourceScratch(pending.sourceStart, pending.sourceEnd, original, pending.text,
                    pending.baseContentFingerprint, snapshot.version)
                sourceError = if (sourceReadOnly) "源码暂存的基版已变化，仅可查看和复制" else null
                coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Source))
                return@launch
            }
            val safeEnd = (end ?: snapshot.source.length).coerceIn(start, snapshot.source.length)
            val text = snapshot.source.substring(start, safeEnd)
            sourceScratch = NoteSourceScratch(start, safeEnd, text, text,
                NoteContent.markdownFingerprint(snapshot.source), snapshot.version)
            sourceError = null
            sourceReadOnly = false
            coordinator.onIntent(NoteEditorIntent.OpenPanel(NoteEditorPanel.Source))
            native.hideIme()
        }
    }

    fun sourceChanged(text: String) {
        if (sourceReadOnly) return
        if (!coordinator.enterEditing()) return
        val scratch = sourceScratch?.copy(text = text) ?: return
        sourceScratch = scratch
        coordinator.setPendingSourceEdit(if (scratch.changed) PendingNoteSourceEdit(scratch.start, scratch.end,
            NoteContent.markdownFingerprint(scratch.original), scratch.baseSourceFingerprint, text) else null)
    }

    fun applySource(allowBarrier: Boolean = false) {
        if (sourceReadOnly) return
        val scratch = sourceScratch ?: return
        viewModelScope.launch {
            if (!coordinator.enterEditing()) return@launch
            val native = bridge ?: return@launch
            if (!native.finishInputAndAwait()) return@launch
            val snapshot = native.session.captureSnapshot()
            if (snapshot.version != scratch.documentVersion || NoteContent.markdownFingerprint(snapshot.source) != scratch.baseSourceFingerprint) {
                sourceError = "正文已变化，此源码暂存仅可复制；请重新打开源码后应用"
                return@launch
            }
            when (withContext(Dispatchers.Default) { native.session.replaceSource(scratch.start, scratch.end, scratch.text,
                allowHistoryBarrier = allowBarrier) }) {
                is SessionResult.HistoryBarrierRequired -> sourceBarrier = true
                is SessionResult.Rejected -> sourceError = "源码无法应用，暂存内容仍保留"
                else -> {
                    coordinator.setPendingSourceEdit(null)
                    sourceScratch = null; sourceBarrier = false; sourceError = null
                    native.refresh()
                    native.finishInputAndAwait()
                    coordinator.onIntent(NoteEditorIntent.Edit)
                    coordinator.onIntent(NoteEditorIntent.ClosePanel)
                }
            }
        }
    }

    fun cancelSource(force: Boolean = false) {
        if (sourceScratch?.changed == true && !force) { confirmSourceDiscard = true; return }
        viewModelScope.launch {
            coordinator.setPendingSourceEdit(null)
            coordinator.flushDraft()
            if (state.value.error?.domain == NoteErrorDomain.Draft) {
                sourceScratch?.let { sourceChanged(it.text) }
                return@launch
            }
            sourceScratch = null; confirmSourceDiscard = false; sourceError = null
            bridge?.session?.captureSnapshot()?.let { publish(it, coordinator.formal?.structuredMetadata?.title ?: "笔记") }
            coordinator.onIntent(NoteEditorIntent.ClosePanel)
            restorePanelPosition()
        }
    }

    fun continueSource() { confirmSourceDiscard = false }

    fun flushBoundary() { viewModelScope.launch { coordinator.flushDraft() } }
    override fun onCleared() { coordinator.close(); ai?.close(); bridge?.dispose() }
}
