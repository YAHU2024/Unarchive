package com.unarchive.android.editor.native

import android.content.Context
import android.content.ClipboardManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import com.unarchive.android.editor.session.DocumentSelection
import com.unarchive.android.editor.session.DocumentSession
import com.unarchive.android.editor.session.ImmutableDocumentSnapshot
import com.unarchive.android.editor.session.SessionResult
import com.unarchive.android.editor.session.DocumentFormatCommand
import com.unarchive.android.editor.session.DocumentFormatCommands
import com.unarchive.android.editor.session.EditOrigin
import com.unarchive.android.editor.session.DocumentNode
import com.unarchive.android.editor.clipboard.ClipboardNormalizer
import com.unarchive.android.editor.clipboard.ClipboardPayload
import com.unarchive.android.editor.clipboard.fromClipData
import com.unarchive.android.editor.clipboard.toClipData
import com.unarchive.android.editor.clipboard.validatedInternalFragment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeBridgeState(
    val acceptedVersion: Long = 0, val bufferGeneration: Long = 0,
    val dirtyBuffer: Boolean = false, val pending: Boolean = false,
    val error: String? = null, val scrolling: Boolean = false, val selecting: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val selectionAnchor: Int = 0, val selectionFocus: Int = 0,
    val touchActive: Boolean = false,
    val formatGeneration: Long = 0,
)

/** Main-thread native protocol, one ordered worker, small deltas and explicit barriers. */
class NativeEditorBridge(val session: DocumentSession, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(NativeBridgeState(acceptedVersion = session.version))
    val state: StateFlow<NativeBridgeState> = mutableState
    var onAccepted: ((ImmutableDocumentSnapshot) -> Unit)? = null
    var onDraw: ((version: Long, generation: Long) -> Unit)? = null
    var onLink: ((String) -> Unit)? = null
    var onHistoryBarrier: ((DocumentFormatCommand) -> Unit)? = null
    var onNodeAction: ((DocumentNode) -> Unit)? = null
    var resolveImage: (suspend (destination: String, requestedWidthPx: Int) -> android.graphics.Bitmap?)? = null
        set(value) {
            field = value
            scope.launch(Dispatchers.Main.immediate) {
                if (disposed || resolveImage !== value) return@launch
                view?.let { host ->
                    host.text.getSpans(0, host.length(), NativeDocumentSpans.OwnedAtomic::class.java).forEach { it.imageFailed = false }
                    host.invalidate()
                }
            }
        }
    var view: NativeDocumentEditText? = null; private set
    var documentSetCount: Int = 0; private set
    var editing: Boolean = false; private set
    private var suppress = false
    private var disposed = false
    private var lastGeneration = 0L
    private var generation = 0L
    private var pending: PendingMutation? = null
    private var dispatchPosted = false
    private var settleJob: Job? = null
    private var selection = session.selection
    private var configuredEditing: Boolean? = null
    private var savedScroll = 0
    private var savedNativeText: String? = null
    private var displayedProjection = session.projection
    private var displayFallbackTitle: String? = null
    private var displaySourceSummary: String? = null
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }
    private val requestedImages = mutableSetOf<String>()
    private val imageSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val commandMutex = Mutex()
    private var workerFailure: Throwable? = null
    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.Main.immediate) {
        for (work in queue) {
            try { when (work) {
                is Work.Input -> accept(work)
                is Work.Barrier -> work.answer.complete(workerFailure == null && lastGeneration == generation && pending == null)
                is Work.Replay -> replay(work.undo)
                is Work.Refresh -> refreshProjection()
                is Work.Command -> {
                    if (workerFailure != null) { work.answer.complete(SessionResult.Rejected("native-worker-failed")); continue }
                    if (!editing) { work.answer.complete(SessionResult.Rejected("read-only")); continue }
                    val result = withContext(Dispatchers.Default) {
                        DocumentFormatCommands.execute(session, work.command, work.selection, work.allowBarrier)
                    }
                    when (result) {
                        is SessionResult.Applied -> refreshProjection(result.snapshot)
                        is SessionResult.FoldExpanded -> refreshProjection(result.snapshot)
                        SessionResult.Unchanged -> refreshProjection()
                        else -> Unit
                    }
                    if (result is SessionResult.HistoryBarrierRequired) onHistoryBarrier?.invoke(work.command)
                    work.answer.complete(result)
                }
            } } catch (error: kotlinx.coroutines.CancellationException) {
                when (work) { is Work.Command -> work.answer.cancel(error); is Work.Barrier -> work.answer.cancel(error); else -> Unit }
                throw error
            } catch (error: Exception) {
                // A queued caller always receives a terminal result. The error remains visible
                // and future source mutations are blocked; never leave a suspended save hanging.
                workerFailure = error
                fail("编辑事务未完成，当前可见输入仍保留，请复制后恢复")
                when (work) {
                    is Work.Command -> work.answer.complete(SessionResult.Rejected("native-worker-failed:${error::class.java.simpleName}"))
                    is Work.Barrier -> work.answer.complete(false)
                    else -> Unit
                }
            }
        }
    }

    internal fun attach(nativeView: NativeDocumentEditText) {
        if (view === nativeView) return
        check(view == null) { "One document bridge can own only one native host" }
        view = nativeView; nativeView.bridge = this
        suppress = true
        try {
            nativeView.setText(savedNativeText ?: session.projection.text)
            documentSetCount++
            NativeDocumentSpans.apply(nativeView.text, session.projection, fontSizePx = nativeView.textSize)
            displayedProjection = session.projection
            setEditing(editing)
            // Native mode configuration can reset TextView selection. Reattach the
            // remembered source session's selection after those one-time setters.
            nativeView.setSelection(selection.anchor.coerceIn(0, nativeView.length()), selection.focus.coerceIn(0, nativeView.length()))
            nativeView.post { nativeView.scrollTo(0, savedScroll) }
        } finally { suppress = false }
    }

    internal fun detach(nativeView: NativeDocumentEditText) {
        if (view !== nativeView) return
        flushPending()
        savedNativeText = nativeView.text.toString()
        savedScroll = nativeView.scrollY
        selection = DocumentSelection(nativeView.selectionStart.coerceAtLeast(0), nativeView.selectionEnd.coerceAtLeast(0))
        nativeView.bridge = null; view = null
        configuredEditing = null
    }

    fun setEditing(value: Boolean) {
        val changed = editing != value
        // AndroidView.update runs on every accepted version/selection state. Reassigning
        // keyListener/TextIsSelectable there resets framework selection/action mode.
        if (!changed && configuredEditing == value) return
        if (changed) {
            val sourceSelection = sourceSelection()
            session.setFallbackTitle(if (value) null else displayFallbackTitle)
            session.setSourceSummary(if (value) null else displaySourceSummary)
            session.updateSelection(DocumentSelection(session.projection.projectedOffset(sourceSelection.anchor), session.projection.projectedOffset(sourceSelection.focus)))
        }
        editing = value
        view?.let { host ->
            host.keyListener = if (value) host.editingKeyListener else null
            host.isCursorVisible = value
            host.showSoftInputOnFocus = value
            host.setTextIsSelectable(true)
            host.isFocusableInTouchMode = true
            configuredEditing = value
        }
        if (!value) hideIme()
        if (changed && view != null) refreshProjection()
    }

    fun setFallbackTitle(title: String?) {
        displayFallbackTitle = title
        if (!editing) { session.setFallbackTitle(title); refreshProjection() }
    }
    fun setSourceSummary(summary: String?) {
        displaySourceSummary = summary
        if (!editing) {
            val oldSelection = sourceSelection()
            session.setSourceSummary(summary)
            session.updateSelection(DocumentSelection(session.projection.projectedOffset(oldSelection.anchor),
                session.projection.projectedOffset(oldSelection.focus)))
            refreshProjection()
        }
    }
    fun setTranscriptFold(sourceStart: Int, sourceEnd: Int, label: String = "转录全文", collapsed: Boolean = true) {
        session.setFold(sourceStart, sourceEnd, label, collapsed); refreshProjection()
    }
    fun toggleTranscriptFold() { session.toggleFold(); refreshProjection() }
    fun nodeAtSelection(): DocumentNode? {
        val eligible = session.projection.nodes.filter { !it.node.hidden && !it.node.synthetic }
        fun overlaps(start: Int, end: Int): Boolean = if (selection.isCollapsed) selection.anchor >= start && selection.anchor < end
            else selection.start < end && selection.end > start
        val block = eligible.firstOrNull { it.node.atomic != null && overlaps(it.start, it.end) }
            ?: eligible.firstOrNull { overlaps(it.start, it.end) }
            ?: eligible.lastOrNull { selection.isCollapsed && selection.anchor == it.end }
            ?: return null
        val atom = block.node.inlineAtoms.firstOrNull { overlaps(block.start + it.start, block.start + it.end) }
        return if (atom != null) inlineImageNode(block.node, atom) else block.node
    }
    private fun inlineImageNode(block: DocumentNode, atom: com.unarchive.android.editor.session.DocumentInlineAtom): DocumentNode =
        DocumentNode(-(block.id * 100_000 + atom.start), block.start + atom.sourceStart, block.start + atom.sourceEnd,
            "image", block.raw.substring(atom.sourceStart, atom.sourceEnd), "\uFFFC", listOf(0), atomic = atom.node)

    internal fun activateNodeAt(projectedOffset: Int) {
        val block = session.projection.nodes.firstOrNull { it.node.atomic != null && projectedOffset in it.start..it.end }
            ?: session.projection.nodes.firstOrNull { projectedOffset in it.start until it.end } ?: return
        if (block.node.kind == "folded-transcript") { toggleTranscriptFold(); return }
        if (!editing) {
            val inline = block.node.inlineAtoms.firstOrNull { projectedOffset in (block.start + it.start)..(block.start + it.end) }
            if (inline != null) onNodeAction?.invoke(inlineImageNode(block.node, inline))
            else if (block.node.atomic != null) onNodeAction?.invoke(block.node)
            else if (block.node.kind == "code_fence" || block.node.kind == "code_block") onNodeAction?.invoke(block.node)
            else block.node.marks.firstOrNull { it.kind == com.unarchive.android.editor.session.InlineMark.LINK &&
                projectedOffset >= block.start + it.start && projectedOffset < block.start + it.end }?.destination?.let { onLink?.invoke(it) }
        }
    }

    /** Tools call this before reading source. Native composition must finish before the barrier. */
    suspend fun finishInputAndAwait(): Boolean {
        if (disposed || !worker.isActive) return false
        withContext(Dispatchers.Main.immediate) {
            view?.let { host ->
                // Standard native finish operation; no synthetic candidate text is inserted.
                host.finishCurrentComposition()
                BaseInputConnection.removeComposingSpans(host.text)
            }
            flushPending()
        }
        val answer = CompletableDeferred<Boolean>()
        if (!queue.trySend(Work.Barrier(answer)).isSuccess) return false
        return answer.await()
    }

    fun undo() { if (editing) scope.launch { if (editing && finishInputAndAwait()) queue.send(Work.Replay(true)) } }
    fun redo() { if (editing) scope.launch { if (editing && finishInputAndAwait()) queue.send(Work.Replay(false)) } }
    /** Call after an external session transaction performed behind finishInputAndAwait. */
    fun refresh() { queue.trySend(Work.Refresh) }

    suspend fun executeCommand(command: DocumentFormatCommand, allowHistoryBarrier: Boolean = false): SessionResult =
        executeAtSelection(command, allowHistoryBarrier, null)

    private suspend fun executeAtSelection(command: DocumentFormatCommand, allowHistoryBarrier: Boolean,
        intendedSelection: DocumentSelection?): SessionResult = commandMutex.withLock {
        if (disposed || !worker.isActive) return@withLock SessionResult.Rejected("native-worker-closed")
        if (!editing) return@withLock SessionResult.Rejected("read-only")
        if (!finishInputAndAwait()) return@withLock SessionResult.Rejected("unaccepted-input")
        val answer = CompletableDeferred<SessionResult>()
        queue.send(Work.Command(command, intendedSelection ?: selection, allowHistoryBarrier, answer))
        answer.await()
    }

    suspend fun pasteText(text: String, markdown: Boolean = false, allowHistoryBarrier: Boolean = false): SessionResult =
        executeCommand(DocumentFormatCommand.ReplaceSelection(text, markdown, EditOrigin.PASTE), allowHistoryBarrier)

    fun copySelection(): ClipboardPayload = ClipboardNormalizer.selection(session.source, session.projection, selection)

    internal fun copyToClipboard(cut: Boolean = false) {
        if (cut && !editing) return
        // ActionMode may close and collapse native selection before its asynchronous worker
        // resumes. The copy/cut command must own the actual selected range at activation.
        val selected = view?.let { DocumentSelection(it.selectionStart.coerceAtLeast(0), it.selectionEnd.coerceAtLeast(0)) } ?: selection
        val capturedGeneration = generation
        scope.launch(Dispatchers.Main.immediate) {
            if (!finishInputAndAwait()) return@launch
            if (capturedGeneration != generation) { fail("选区内容已变化，请重新选择后复制"); return@launch }
            val payload = ClipboardNormalizer.selection(session.source, session.projection, selected)
            val host = view ?: return@launch
            val clipboard = host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(payload.toClipData(session.sessionId))
            if (cut) executeAtSelection(DocumentFormatCommand.ReplaceSelection("", false, EditOrigin.CUT), false, selected)
        }
    }

    internal fun pasteFromClipboard(plainOnly: Boolean = false) {
        if (!editing) return
        val host = view ?: return
        val clipboard = host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        scope.launch {
            if (!finishInputAndAwait()) return@launch
            val baseVersion = session.version
            val baseSelection = selection
            val payload = withContext(Dispatchers.Default) {
                if (!plainOnly) ClipboardNormalizer.validatedInternalFragment(clip, session.sessionId)
                    ?: ClipboardNormalizer.fromClipData(host.context, clip)
                else ClipboardNormalizer.fromClipData(host.context, clip, true)
            }
            if (session.version != baseVersion || selection != baseSelection) {
                fail("粘贴位置已变化，请重新粘贴")
                return@launch
            }
            if (plainOnly) pasteText(payload.plainText) else pasteText(payload.markdown, markdown = true)
        }
    }

    internal fun structuralEnter() { scope.launch { executeCommand(DocumentFormatCommand.Enter) } }
    internal fun structuralBackspace() { scope.launch { executeCommand(DocumentFormatCommand.Backspace) } }

    fun showIme() {
        if (!editing) return
        view?.let { host -> host.requestFocus(); host.post { inputManager(host.context).showSoftInput(host, InputMethodManager.SHOW_IMPLICIT) } }
    }
    fun hideIme() { view?.let { inputManager(it.context).hideSoftInputFromWindow(it.windowToken, 0) } }

    fun revealSourceOffset(offset: Int) {
        if (session.expandFoldAtSource(offset)) refreshProjection()
        view?.let { host ->
            val projected = session.projection.projectedOffset(offset).coerceIn(0, host.length())
            host.setSelection(projected)
            host.post {
                val layout = host.layout ?: return@post
                val y = layout.getLineTop(layout.getLineForOffset(projected))
                host.scrollTo(0, (y - host.height / 3).coerceAtLeast(0))
                noteScroll()
            }
        }
    }

    fun sourceSelection(): DocumentSelection {
        fun sourceOffset(value: Int, endAffinity: Boolean): Int {
            val projection = session.projection
            projection.nodes.firstOrNull { it.node.synthetic && value in it.start..it.end }?.let { return it.node.start }
            return projection.sourceOffset(value, endAffinity)
        }
        return DocumentSelection(sourceOffset(selection.anchor, false), sourceOffset(selection.focus, selection.focus > selection.anchor))
    }

    internal fun beforeMutation(text: CharSequence, start: Int, count: Int) {
        if (suppress || disposed) return
        if (pending == null) {
            pending = PendingMutation(start, text.subSequence(start, start + count).toString(), "",
                DocumentSelection(view?.selectionStart?.coerceAtLeast(0) ?: start, view?.selectionEnd?.coerceAtLeast(0) ?: start), generation)
        } else {
            val old = checkNotNull(pending)
            val currentEnd = old.start + old.inserted.length
            val left = minOf(old.start, start)
            val right = maxOf(currentEnd, start + count)
            val prefix = text.subSequence(left, old.start).toString()
            val suffix = text.subSequence(currentEnd, right).toString()
            old.removed = prefix + old.removed + suffix
            old.inserted = prefix + old.inserted + suffix
            old.start = left
        }
    }

    internal fun onMutation(text: CharSequence, start: Int, before: Int, count: Int) {
        if (suppress || disposed) return
        val active = pending ?: return
        val local = start - active.start
        // The first before callback starts with an empty replacement, unlike subsequent edits.
        val existingEnd = (local + before).coerceAtMost(active.inserted.length)
        active.inserted = active.inserted.replaceRange(local.coerceAtMost(active.inserted.length), existingEnd,
            text.subSequence(start, start + count).toString())
        generation++
        mutableState.value = mutableState.value.copy(bufferGeneration = generation, dirtyBuffer = true, pending = true)
        scheduleDispatch()
    }

    internal fun scheduleDispatch() {
        val host = view ?: return
        if (dispatchPosted || suppress) return
        dispatchPosted = true
        host.post {
            dispatchPosted = false
            if (view !== host || suppress) return@post
            val composing = BaseInputConnection.getComposingSpanStart(host.text) >= 0
            if (!composing) flushPending()
            noteSelection(host.selectionStart, host.selectionEnd)
        }
    }

    private fun flushPending() {
        val mutation = pending ?: return
        val host = view
        if (host != null && BaseInputConnection.getComposingSpanStart(host.text) >= 0) return
        pending = null
        val after = if (host == null) selection else DocumentSelection(host.selectionStart.coerceAtLeast(0), host.selectionEnd.coerceAtLeast(0))
        queue.trySend(Work.Input(mutation.start, mutation.removed, mutation.inserted, mutation.before, after, mutation.fromGeneration, generation))
    }

    private suspend fun accept(input: Work.Input) {
        if (input.fromGeneration != lastGeneration) { fail("输入版本不连续，请保留当前正文后重试"); return }
        val oldProjection = session.projection
        if (input.start + input.removed.length > oldProjection.text.length ||
            !oldProjection.text.regionMatches(input.start, input.removed, 0, input.removed.length)) { fail("输入范围已变化，请保留当前正文"); return }
        val result = withContext(Dispatchers.Default) {
            session.replaceProjectedRange(input.start, input.start + input.removed.length, input.inserted, input.before, input.after)
        }
        when (result) {
            is SessionResult.FoldExpanded -> {
                lastGeneration = input.toGeneration
                mutableState.value = mutableState.value.copy(acceptedVersion = session.version, dirtyBuffer = false,
                    pending = false, error = null)
                refreshProjection()
            }
            is SessionResult.Rejected -> fail("无法映射输入：${result.reason}")
            is SessionResult.HistoryBarrierRequired -> fail("本次修改超过撤销容量，请在源码面板确认")
            else -> {
                lastGeneration = input.toGeneration
                val snapshot = session.captureSnapshot()
                mutableState.value = mutableState.value.copy(acceptedVersion = session.version,
                    dirtyBuffer = lastGeneration != generation, pending = lastGeneration != generation,
                    error = null, canUndo = session.canUndo, canRedo = session.canRedo)
                if (lastGeneration == generation && pending == null) {
                    val host = view
                    if (host != null) {
                        // Session validates the exact intended projection. Never feed accepted input back.
                        NativeDocumentSpans.apply(host.text, snapshot.projection, input.start,
                            input.start + input.inserted.length, fontSizePx = host.textSize)
                        displayedProjection = snapshot.projection
                        host.invalidate()
                    }
                }
                onAccepted?.invoke(snapshot)
                if (lastGeneration == generation && input.inserted.endsWith(' ') && pending == null) {
                    val shortcut = withContext(Dispatchers.Default) { DocumentFormatCommands.execute(session, DocumentFormatCommand.ApplyShortcut, input.after) }
                    if (shortcut is SessionResult.Applied) refreshProjection()
                }
            }
        }
    }

    private suspend fun replay(undo: Boolean) {
        if (!editing) return
        val result = withContext(Dispatchers.Default) { if (undo) session.undo() else session.redo() }
        if (result is SessionResult.Rejected) { fail(result.reason); return }
        refreshProjection((result as? SessionResult.Applied)?.snapshot ?: session.captureSnapshot())
    }

    private fun refreshProjection(snapshot: ImmutableDocumentSnapshot = session.captureSnapshot()) {
        view?.let { host -> patchNative(host, snapshot.projection.text, snapshot.selection, preserveScroll = true) }
        savedNativeText = snapshot.projection.text
        mutableState.value = mutableState.value.copy(acceptedVersion = snapshot.version, canUndo = snapshot.canUndo, canRedo = snapshot.canRedo,
            selectionAnchor = snapshot.selection.anchor, selectionFocus = snapshot.selection.focus,
            formatGeneration = mutableState.value.formatGeneration + 1, error = null)
        onAccepted?.invoke(snapshot)
    }

    private fun patchNative(host: NativeDocumentEditText, target: String, targetSelection: DocumentSelection, preserveScroll: Boolean) {
        val current = host.text
        val layoutBefore = host.layout
        val visibleLine = layoutBefore?.getLineForVertical(host.scrollY.coerceAtLeast(0)) ?: 0
        val visibleOffset = layoutBefore?.getLineStart(visibleLine)?.coerceIn(0, displayedProjection.text.length) ?: 0
        val sourceAnchor = displayedProjection.sourceOffset(visibleOffset)
        val oldNode = displayedProjection.nodes.firstOrNull { visibleOffset in it.start..it.end && !it.node.hidden && !it.node.synthetic }
        val relativeSource = sourceAnchor - (oldNode?.node?.start ?: 0)
        val lineFraction = if (layoutBefore == null) 0f else {
            val height = (layoutBefore.getLineBottom(visibleLine) - layoutBefore.getLineTop(visibleLine)).coerceAtLeast(1)
            (host.scrollY - layoutBefore.getLineTop(visibleLine)).toFloat() / height
        }
        var prefix = 0
        while (prefix < current.length && prefix < target.length && current[prefix] == target[prefix]) prefix++
        var suffix = 0
        while (suffix < current.length - prefix && suffix < target.length - prefix && current[current.length - suffix - 1] == target[target.length - suffix - 1]) suffix++
        val y = host.scrollY
        suppress = true
        host.beginBatchEdit()
        try {
            if (prefix != current.length || prefix != target.length) {
                // A former one-character ReplacementSpan must never cover expanded paragraphs
                // while DynamicLayout processes the replacement. Clear it before text surgery.
                NativeDocumentSpans.clearOwnedInRange(current, prefix, current.length - suffix)
                current.replace(prefix, current.length - suffix, target.substring(prefix, target.length - suffix))
            }
            NativeDocumentSpans.apply(current, session.projection, fontSizePx = host.textSize)
            host.setSelection(targetSelection.anchor.coerceIn(0, current.length), targetSelection.focus.coerceIn(0, current.length))
            selection = targetSelection
            if (preserveScroll) host.scrollTo(0, y)
        } finally { host.endBatchEdit(); suppress = false }
        host.requestLayout()
        host.invalidate()
        displayedProjection = session.projection
        val appliedProjection = session.projection
        if (preserveScroll) host.post {
            if (view !== host || session.projection !== appliedProjection || mutableState.value.touchActive) return@post
            val layout = host.layout ?: return@post
            val rebound = oldNode?.node?.id?.let { id -> session.projection.nodes.firstOrNull { it.node.id == id } }
            val anchoredSource = rebound?.let { (it.node.start + relativeSource).coerceIn(it.node.start, it.node.end) } ?: sourceAnchor.coerceIn(0, session.source.length)
            val offset = session.projection.projectedOffset(anchoredSource).coerceIn(0, host.length())
            val line = layout.getLineForOffset(offset)
            val height = (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(1)
            host.scrollTo(0, (layout.getLineTop(line) + height * lineFraction).toInt().coerceAtLeast(0))
        }
    }

    internal fun noteSelection(start: Int, end: Int) {
        if (start < 0 || end < 0 || suppress) return
        selection = DocumentSelection(start, end)
        mutableState.value = mutableState.value.copy(selecting = start != end, selectionAnchor = start, selectionFocus = end)
        if (generation == lastGeneration) session.updateSelection(selection)
    }
    internal fun noteScroll() {
        mutableState.value = mutableState.value.copy(scrolling = true)
        settleJob?.cancel()
        settleJob = scope.launch { delay(300); if (!mutableState.value.touchActive) mutableState.value = mutableState.value.copy(scrolling = false) }
    }
    internal fun noteTouch(active: Boolean) {
        mutableState.value = mutableState.value.copy(touchActive = active)
        if (active) settleJob?.cancel() else noteScroll()
    }
    /** Apply cache before TextView draws; after-draw only reports the accepted frame. */
    internal fun prepareDraw() { updateVisibleImages() }
    internal fun noteDraw() { onDraw?.invoke(mutableState.value.acceptedVersion, generation) }

    private fun publishImage(host: NativeDocumentEditText, span: NativeDocumentSpans.OwnedAtomic, image: android.graphics.Bitmap) {
        if (span.bitmap === image) return
        val text = host.text
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        val flags = text.getSpanFlags(span)
        span.bitmap = image
        // TextView's hardware text render nodes cache ReplacementSpan.draw results.
        // Mutating a plain Kotlin bitmap field + View.invalidate leaves that display
        // list valid. A bounded span notification invalidates text/metric caches too.
        host.beginBatchEdit()
        try {
            text.removeSpan(span)
            text.setSpan(span, start, end, flags)
        } finally { host.endBatchEdit() }
        host.requestLayout()
        host.postInvalidateOnAnimation()
    }
    private fun updateVisibleImages() {
        val host = view ?: return
        val resolver = resolveImage ?: return
        val layout = host.layout ?: return
        val startLine = layout.getLineForVertical(host.scrollY.coerceAtLeast(0))
        val endLine = layout.getLineForVertical((host.scrollY + host.height).coerceAtLeast(0))
        val visibleStart = layout.getLineStart(startLine); val visibleEnd = layout.getLineEnd(endLine)
        val visible = host.text.getSpans(visibleStart, visibleEnd, NativeDocumentSpans.OwnedAtomic::class.java).filter { it.node.kind == "image" }
        host.text.getSpans(0, host.length(), NativeDocumentSpans.OwnedAtomic::class.java).forEach { if (it !in visible && it.node.kind == "image") it.bitmap = null }
        val width = (host.width - host.totalPaddingLeft - host.totalPaddingRight).coerceAtLeast(1)
        visible.forEach { span ->
            span.availableWidth = width
            val destination = span.node.destination ?: return@forEach
            val key = "$destination@$width"
            val cached = imageCache.get(key)
            if (cached != null) { publishImage(host, span, cached); return@forEach }
            if (span.imageFailed) return@forEach
            if (!requestedImages.add(key)) return@forEach
            val token = session.sessionId
            scope.launch(Dispatchers.Main.immediate) {
                val image = try {
                    imageSemaphore.acquire()
                    try { withContext(Dispatchers.IO) { resolver(destination, width) } }
                    finally { imageSemaphore.release() }
                }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { null }
                finally { requestedImages.remove(key) }
                if (disposed || session.sessionId != token || resolveImage !== resolver) return@launch
                if (image != null && image.allocationByteCount <= 12 * 1024 * 1024) {
                    imageCache.put(key, image)
                    if (view === host) {
                        host.text.getSpans(0, host.length(), NativeDocumentSpans.OwnedAtomic::class.java)
                            .filter { it.node.destination == destination }.forEach { publishImage(host, it, image) }
                        host.postInvalidateOnAnimation()
                    }
                } else if (view === host) {
                    host.text.getSpans(0, host.length(), NativeDocumentSpans.OwnedAtomic::class.java)
                        .filter { it.node.destination == destination }.forEach { it.imageFailed = true }
                    host.postInvalidateOnAnimation()
                }
            }
        }
    }
    private fun fail(message: String) { mutableState.value = mutableState.value.copy(error = message, pending = false) }
    fun dispose() {
        disposed = true; settleJob?.cancel(); queue.close(); worker.cancel()
        while (true) {
            val pendingWork = queue.tryReceive().getOrNull() ?: break
            when (pendingWork) {
                is Work.Command -> pendingWork.answer.complete(SessionResult.Rejected("native-worker-closed"))
                is Work.Barrier -> pendingWork.answer.complete(false)
                else -> Unit
            }
        }
        imageCache.evictAll(); view?.bridge = null; view = null
    }
    private fun inputManager(context: Context) = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private data class PendingMutation(var start: Int, var removed: String, var inserted: String,
        val before: DocumentSelection, val fromGeneration: Long)
    private sealed interface Work {
        data class Input(val start: Int, val removed: String, val inserted: String,
            val before: DocumentSelection, val after: DocumentSelection,
            val fromGeneration: Long, val toGeneration: Long) : Work
        data class Barrier(val answer: CompletableDeferred<Boolean>) : Work
        data class Replay(val undo: Boolean) : Work
        data object Refresh : Work
        data class Command(val command: DocumentFormatCommand, val selection: DocumentSelection,
            val allowBarrier: Boolean, val answer: CompletableDeferred<SessionResult>) : Work
    }
}
