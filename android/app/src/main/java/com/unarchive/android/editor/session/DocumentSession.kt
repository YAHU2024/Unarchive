package com.unarchive.android.editor.session

import java.util.UUID

data class ImmutableDocumentSnapshot(
    val sessionId: String, val source: String, val version: Long,
    val selection: DocumentSelection, val projection: DocumentProjection,
    val canUndo: Boolean, val canRedo: Boolean,
    val activeAssetIds: Set<String> = emptySet(),
)

sealed interface SessionResult {
    data object Unchanged : SessionResult
    data class Applied(val snapshot: ImmutableDocumentSnapshot, val patch: SourceReplacement) : SessionResult
    data class Rejected(val reason: String) : SessionResult
    data class HistoryBarrierRequired(val retainedBytes: Long) : SessionResult
    data class FoldExpanded(val snapshot: ImmutableDocumentSnapshot) : SessionResult
}

data class DocumentFold(val sourceStart: Int, val sourceEnd: Int, val label: String, val collapsed: Boolean = true)

/** Single writer is the bridge's ordered worker. Methods are synchronized for boundary snapshots. */
class DocumentSession(initialSource: String, historyLimit: Int = 128, historyByteBudget: Long = 256 * 1024L) {
    val sessionId: String = UUID.randomUUID().toString()
    private val parser = MarkdownDocumentParser()
    private val history = DocumentHistory(historyLimit, historyByteBudget)
    var source: String = initialSource; private set
    var version: Long = 0; private set
    var selection = DocumentSelection(); private set
    private var nodes = parser.parse(initialSource)
    var projection = MarkdownDocumentParser.project(source, nodes); private set
    val diagnostics get() = parser.diagnostics
    val historyStats get() = history.stats
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    var typingMarks: Set<InlineMark>? = null; private set
    private var inputGroup = 0L
    private var typingSourceOffset: Int? = null
    private var ownedFolds = emptyList<DocumentFold>()
    val foldRanges: List<DocumentFold> get() = ownedFolds
    private var fallbackTitle: String? = null
    private var sourceSummary: String? = null
    private val assetRegistry = linkedMapOf<String, DocumentAssetReference>()
    private var activeAssets: Set<String> = emptySet()
    var assetReferenceScans: Long = 0; private set

    @Synchronized fun initializeAssets(references: List<DocumentAssetReference>, activeIds: Set<String> = references.map { it.id }.toSet()) {
        check(version == 0L && !history.canUndo && !history.canRedo) { "Initial manifest belongs to a fresh session" }
        registerAssets(references)
        require(activeIds.all(assetRegistry::containsKey)) { "Unknown active asset" }
        activeAssets = activeIds.toSet()
    }

    @Synchronized fun registerAssets(references: List<DocumentAssetReference>) {
        references.forEach { reference ->
            val previous = assetRegistry[reference.id]
            require(previous == null || previous.path == reference.path) { "Asset IDs cannot be rebound to another path" }
            assetRegistry[reference.id] = if (previous?.retainWithoutReference == true) reference.copy(retainWithoutReference = true) else reference
        }
    }

    @Synchronized fun applyAssetDelta(delta: AssetReferenceDelta, origin: EditOrigin = EditOrigin.IMAGE,
        allowHistoryBarrier: Boolean = false, expectedVersion: Long = version): SessionResult {
        if (expectedVersion != version) return SessionResult.Rejected("stale-version")
        return commit(SourceReplacement(0, 0, ""), selection, selection, origin, false, allowHistoryBarrier,
            assetDelta = delta)
    }

    @Synchronized fun restoreRecoveredSnapshot(markdown: String, activeIds: Set<String>,
        restoredSelection: DocumentSelection = DocumentSelection()): SessionResult {
        if (!isWellFormedUtf16(markdown)) return SessionResult.Rejected("unicode-boundary")
        if (!activeIds.all(assetRegistry::containsKey)) return SessionResult.Rejected("unknown-recovery-assets")
        val patch = SourceReplacement(0, source.length, markdown)
        val parsed = parser.parse(markdown, nodes, patch)
        source = markdown; nodes = parsed; activeAssets = activeIds.toSet()
        ownedFolds = emptyList(); typingMarks = null; typingSourceOffset = null
        history.clear(); inputGroup++; rebuildProjection(); version++
        selection = DocumentSelection(restoredSelection.anchor.coerceIn(0, projection.text.length), restoredSelection.focus.coerceIn(0, projection.text.length))
        return SessionResult.Applied(captureSnapshot(), patch)
    }

    @Synchronized fun setFallbackTitle(value: String?) {
        fallbackTitle = value?.takeIf { it.isNotBlank() }
        rebuildProjection()
    }
    @Synchronized fun setSourceSummary(value: String?) {
        sourceSummary = value?.replace('\r', ' ')?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        rebuildProjection()
    }

    @Synchronized fun setFold(sourceStart: Int, sourceEnd: Int, label: String, collapsed: Boolean = true) {
        require(sourceStart < sourceEnd && isUtf16Boundary(source, sourceStart) && isUtf16Boundary(source, sourceEnd))
        ownedFolds = listOf(DocumentFold(sourceStart, sourceEnd, label, collapsed))
        rebuildProjection()
    }
    @Synchronized fun toggleFold() {
        ownedFolds = ownedFolds.map { it.copy(collapsed = !it.collapsed) }
        rebuildProjection()
    }
    @Synchronized fun expandFoldAtSource(offset: Int): Boolean {
        if (ownedFolds.none { it.collapsed && offset in it.sourceStart..it.sourceEnd }) return false
        ownedFolds = ownedFolds.map { if (offset in it.sourceStart..it.sourceEnd) it.copy(collapsed = false) else it }
        rebuildProjection(); return true
    }
    @Synchronized fun expandForMutation(value: DocumentSelection): SessionResult.FoldExpanded? {
        if (value.isCollapsed) return null
        val sourceA = projection.sourceOffset(value.start)
        val sourceB = projection.sourceOffset(value.end, true)
        if (ownedFolds.none { it.collapsed && sourceB > it.sourceStart && sourceA < it.sourceEnd }) return null
        ownedFolds = ownedFolds.map { if (sourceB > it.sourceStart && sourceA < it.sourceEnd) it.copy(collapsed = false) else it }
        rebuildProjection()
        selection = DocumentSelection(projection.projectedOffset(sourceA), projection.projectedOffset(sourceB))
        return SessionResult.FoldExpanded(captureSnapshot())
    }

    private fun rebuildProjection() {
        projection = presentedProjection(source, nodes, ownedFolds)
    }
    private fun presentedProjection(source: String, nodes: List<DocumentNode>, folds: List<DocumentFold>): DocumentProjection {
        val foldNodes = nodes.toMutableList()
        folds.filter { it.collapsed }.forEach { fold ->
            val affected = foldNodes.filter { it.end > fold.sourceStart && it.start < fold.sourceEnd }
            if (affected.isNotEmpty() && affected.all { it.start >= fold.sourceStart && it.end <= fold.sourceEnd }) {
                val index = foldNodes.indexOf(affected.first())
                foldNodes.removeAll(affected.toSet())
                foldNodes.add(index, DocumentNode(affected.first().id, fold.sourceStart, fold.sourceEnd, "folded-transcript",
                    source.substring(fold.sourceStart, fold.sourceEnd), "\uFFFC", listOf(0),
                    atomic = DocumentAtomicNode("fold", "${fold.label}，已折叠，点按展开")))
            }
        }
        var next = MarkdownDocumentParser.project(source, foldNodes)
        val title = fallbackTitle
        if (title != null && nodes.none { it.headingLevel == 1 && it.text.isNotBlank() }) {
            val node = DocumentNode(-1, 0, 0, "synthetic-title", "", title, List(title.length) { 0 }, headingLevel = 1, synthetic = true)
            val prefix = title + "\n\n"
            next = next.copy(text = prefix + next.text,
                nodes = listOf(ProjectedNode(node, 0, title.length)) + next.nodes.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) },
                characterSources = List(prefix.length) { 0 } + next.characterSources)
        }
        sourceSummary?.let { summary ->
            val alreadyPresent = next.nodes.any { !it.node.synthetic && !it.node.hidden && it.node.text.trim() == summary }
            if (!alreadyPresent) {
                val heading = next.nodes.firstOrNull { it.node.headingLevel == 1 && it.node.text.isNotBlank() }
                val insertAt = heading?.end ?: 0
                val anchor = heading?.node?.end ?: 0
                val before = if (heading != null) "\n" else ""
                val after = if (next.text.getOrNull(insertAt) == '\n') "" else "\n\n"
                val inserted = before + summary + after
                val node = DocumentNode(-2, anchor, anchor, "synthetic-source-summary", "", summary,
                    List(summary.length) { 0 }, synthetic = true)
                val newNode = ProjectedNode(node, insertAt + before.length, insertAt + before.length + summary.length)
                val shifted = next.nodes.map { if (it.start >= insertAt && it !== heading) it.copy(start = it.start + inserted.length, end = it.end + inserted.length) else it }
                val position = shifted.indexOfFirst { it.start >= newNode.start }.let { if (it < 0) shifted.size else it }
                next = next.copy(text = next.text.substring(0, insertAt) + inserted + next.text.substring(insertAt),
                    nodes = shifted.toMutableList().apply { add(position, newNode) },
                    characterSources = next.characterSources.take(insertAt) + List(inserted.length) { anchor } + next.characterSources.drop(insertAt))
            }
        }
        return next
    }

    private fun shiftedFolds(patch: SourceReplacement): List<DocumentFold> = ownedFolds.mapNotNull { fold ->
            when {
                patch.end <= fold.sourceStart -> fold.copy(sourceStart = fold.sourceStart + patch.delta, sourceEnd = fold.sourceEnd + patch.delta)
                patch.start >= fold.sourceEnd -> fold
                patch.start >= fold.sourceStart && patch.end <= fold.sourceEnd -> fold.copy(sourceEnd = fold.sourceEnd + patch.delta, collapsed = false)
                else -> null
            }
        }
    private fun updateFolds(patch: SourceReplacement) { ownedFolds = shiftedFolds(patch) }

    @Synchronized fun captureSnapshot() = ImmutableDocumentSnapshot(sessionId, source, version, selection, projection, canUndo, canRedo, activeAssets)
    @Synchronized fun updateSelection(value: DocumentSelection) {
        if (isUtf16Boundary(projection.text, value.anchor) && isUtf16Boundary(projection.text, value.focus)) {
            if (value != selection) { typingMarks = null; typingSourceOffset = null; inputGroup++ }
            selection = value
        }
    }
    @Synchronized fun setTypingMarks(value: Set<InlineMark>?) { typingMarks = value?.minus(InlineMark.LINK); typingSourceOffset = null; inputGroup++ }
    @Synchronized fun endInputGroup() { inputGroup++ }

    @Synchronized fun replaceProjectedRange(
        start: Int, end: Int, replacement: String,
        selectionBefore: DocumentSelection = selection,
        selectionAfter: DocumentSelection = DocumentSelection(start + replacement.length),
        expectedVersion: Long = version,
        origin: EditOrigin = EditOrigin.INPUT,
        allowHistoryBarrier: Boolean = false,
        assetDelta: AssetReferenceDelta = AssetReferenceDelta(),
    ): SessionResult {
        if (expectedVersion != version) return SessionResult.Rejected("stale-version")
        expandForMutation(DocumentSelection(start, end))?.let { return it }
        var patch = SourceTransactionMapper.plainTextReplacement(projection, start, end, replacement, source)
            ?: return SessionResult.Rejected("invalid-source-range")
        val explicit = typingMarks
        var nextTypingOffset: Int? = null
        if (start == end && replacement.isNotEmpty() && explicit != null) {
            typingSourceOffset?.takeIf { isUtf16Boundary(source, it) }?.let { patch = patch.copy(start = it, end = it) }
            if (typingSourceOffset == null) {
                val boundaryMarks = projection.nodes.flatMap { block -> block.node.marks.map { block to it } }
                    .filter { (block, mark) -> mark.kind !in explicit && mark.kind != InlineMark.LINK &&
                        (start == block.start + mark.start || start == block.start + mark.end) }
                val closing = boundaryMarks.filter { (block, mark) -> start == block.start + mark.end }
                val opening = boundaryMarks.filter { (block, mark) -> start == block.start + mark.start }
                val relocated = closing.maxOfOrNull { (block, mark) -> block.node.start + mark.sourceEnd }
                    ?: opening.minOfOrNull { (block, mark) -> block.node.start + mark.sourceStart }
                if (relocated != null) patch = patch.copy(start = relocated, end = relocated)
            }
            val inherited = projection.nodes.flatMap { block -> block.node.marks.filter {
                it.kind != InlineMark.LINK && start >= block.start + it.start && start <= block.start + it.end &&
                    patch.start >= block.node.start + it.contentStart && patch.start <= block.node.start + it.contentEnd
            } }.map { it.kind }.toSet()
            fun marker(mark: InlineMark): String = when (mark) { InlineMark.BOLD -> "**"; InlineMark.ITALIC -> "*"; InlineMark.STRIKE -> "~~"; InlineMark.CODE -> "`"; InlineMark.LINK -> "" }
            val removed = inherited - explicit
            val added = explicit - inherited
            val close = removed.reversed().joinToString("") { marker(it) }
            val open = added.joinToString("") { marker(it) }
            nextTypingOffset = patch.start + close.length + open.length + patch.replacement.length
            patch = patch.copy(replacement = close + open + patch.replacement + added.reversed().joinToString("") { marker(it) } + removed.joinToString("") { marker(it) })
        }
        val result = commit(patch, selectionBefore, selectionAfter, origin, false, allowHistoryBarrier,
            ProjectedChange(start, end, replacement), assetDelta)
        if (result is SessionResult.Applied && explicit != null) typingSourceOffset = nextTypingOffset
        return result
    }

    @Synchronized fun replaceSource(
        start: Int, end: Int, replacement: String,
        origin: EditOrigin = EditOrigin.SOURCE, expectedVersion: Long = version,
        selectionBefore: DocumentSelection = selection,
        selectionAfter: DocumentSelection = selection,
        allowHistoryBarrier: Boolean = false,
        requiresFullContext: Boolean = origin == EditOrigin.SOURCE || origin == EditOrigin.AI,
        assetDelta: AssetReferenceDelta = AssetReferenceDelta(),
    ): SessionResult {
        if (expectedVersion != version) return SessionResult.Rejected("stale-version")
        return commit(SourceReplacement(start, end, replacement), selectionBefore, selectionAfter, origin, requiresFullContext, allowHistoryBarrier,
            assetDelta = assetDelta)
    }

    private fun commit(patch: SourceReplacement, before: DocumentSelection, after: DocumentSelection,
        origin: EditOrigin, fullContext: Boolean, allowBarrier: Boolean, projectedChange: ProjectedChange? = null,
        assetDelta: AssetReferenceDelta = AssetReferenceDelta()): SessionResult {
        if (patch.start > patch.end || !isUtf16Boundary(source, patch.start) || !isUtf16Boundary(source, patch.end) ||
            !isWellFormedUtf16(patch.replacement)) return SessionResult.Rejected("unicode-boundary")
        val removed = source.substring(patch.start, patch.end)
        val sourceChanged = removed != patch.replacement
        if (!sourceChanged && assetDelta.add.isEmpty() && assetDelta.removeIds.isEmpty()) { updateSelection(after); return SessionResult.Unchanged }
        if (assetDelta.add.any { next -> assetRegistry[next.id]?.let { it.path != next.path } == true }) return SessionResult.Rejected("asset-id-rebound")
        if (assetDelta.removeIds.any { assetRegistry[it]?.retainWithoutReference == true }) return SessionResult.Rejected("protected-asset")
        val minimumCharge = (removed.length.toLong() + patch.replacement.length.toLong()) * 2 + 112
        if (!allowBarrier && minimumCharge > history.maximumBytes) return SessionResult.HistoryBarrierRequired(minimumCharge)
        val updated = source.replaceRange(patch.start, patch.end, patch.replacement)
        val updatedNodes = if (!sourceChanged) nodes else if (fullContext) parser.parse(updated, nodes, patch) else parser.update(updated, nodes, patch)
        val projected = presentedProjection(updated, updatedNodes, shiftedFolds(patch))
        if (projectedChange != null) {
            val change = projectedChange
            val old = projection.text
            val next = projected.text
            if (next.length != old.length - (change.end - change.start) + change.replacement.length ||
                !next.regionMatches(0, old, 0, change.start) ||
                !next.regionMatches(change.start, change.replacement, 0, change.replacement.length) ||
                !next.regionMatches(change.start + change.replacement.length, old, change.end, old.length - change.end)) {
                return SessionResult.Rejected("projection-mismatch")
            }
        }
        val reconcile = sourceChanged && touchesImageReferences(patch, removed, origin)
        val explicitAssetChange = assetDelta.add.isNotEmpty() || assetDelta.removeIds.isNotEmpty()
        val registry: Map<String, DocumentAssetReference> = if (assetDelta.add.isEmpty()) assetRegistry
            else LinkedHashMap(assetRegistry).apply { assetDelta.add.forEach { put(it.id, it) } }
        val nextAssets = if (reconcile) {
            assetReferenceScans++
            val beforePaths = referencedPaths(nodes)
            val afterPaths = referencedPaths(updatedNodes)
            activeAssets.filterTo(linkedSetOf()) { id ->
                val reference = registry[id]
                reference != null && (reference.retainWithoutReference || reference.path !in beforePaths || reference.path in afterPaths)
            }.apply { registry.values.filter { it.path in afterPaths }.forEach { add(it.id) } }
        } else if (explicitAssetChange) activeAssets.toMutableSet() else null
        if (nextAssets != null) {
            nextAssets.addAll(assetDelta.add.map { it.id }); nextAssets.removeAll(assetDelta.removeIds)
            registry.values.filter { it.retainWithoutReference && it.id in activeAssets }.forEach { nextAssets.add(it.id) }
        }
        val assetsChanged = nextAssets != null && nextAssets != activeAssets
        if (!sourceChanged && !assetsChanged) { updateSelection(after); return SessionResult.Unchanged }
        if (origin != EditOrigin.INPUT || '\n' in patch.replacement || '\n' in removed || !before.isCollapsed || assetsChanged) inputGroup++
        val entry = HistoryEntry(patch, removed, before, after, origin, fullContext, inputGroup,
            assetIdsBefore = if (assetsChanged) activeAssets else null,
            assetIdsAfter = if (assetsChanged) nextAssets!!.toSet() else null)
        if (history.wouldExceedSingleEntry(entry) && !allowBarrier) return SessionResult.HistoryBarrierRequired(entry.byteCharge)
        if (!history.record(entry, allowBarrier)) return SessionResult.HistoryBarrierRequired(entry.byteCharge)
        if (registry !== assetRegistry) { assetRegistry.clear(); assetRegistry.putAll(registry) }
        activeAssets = if (assetsChanged) nextAssets!!.toSet() else activeAssets
        source = updated; nodes = updatedNodes; updateFolds(patch); rebuildProjection(); version++
        selection = after.copy(anchor = after.anchor.coerceIn(0, projected.text.length), focus = after.focus.coerceIn(0, projected.text.length))
        return SessionResult.Applied(captureSnapshot(), patch)
    }

    @Synchronized fun undo(): SessionResult = replay(true)
    @Synchronized fun redo(): SessionResult = replay(false)
    private fun replay(undo: Boolean): SessionResult {
        inputGroup++
        val entry = (if (undo) history.peekUndo() else history.peekRedo()) ?: return SessionResult.Unchanged
        val expectedAssets = if (undo) entry.assetIdsAfter else entry.assetIdsBefore
        if (expectedAssets != null && activeAssets != expectedAssets) return SessionResult.Rejected("history-asset-mismatch")
        val expected = if (undo) entry.patch.replacement else entry.removed
        val replacement = if (undo) entry.removed else entry.patch.replacement
        val patch = SourceReplacement(entry.patch.start, entry.patch.start + expected.length, replacement)
        if (patch.end > source.length || !source.regionMatches(patch.start, expected, 0, expected.length)) return SessionResult.Rejected("history-source-mismatch")
        val updated = source.replaceRange(patch.start, patch.end, replacement)
        val parsed = if (expected == replacement) nodes else if (entry.fullContext) parser.parse(updated, nodes, patch) else parser.update(updated, nodes, patch)
        if (!(if (undo) history.commitUndo(entry) else history.commitRedo(entry))) return SessionResult.Rejected("history-changed")
        source = updated; nodes = parsed
        (if (undo) entry.assetIdsBefore else entry.assetIdsAfter)?.let { activeAssets = it }
        updateFolds(patch); rebuildProjection(); version++
        val target = if (undo) entry.before else entry.after
        selection = DocumentSelection(target.anchor.coerceIn(0, projection.text.length), target.focus.coerceIn(0, projection.text.length))
        return SessionResult.Applied(captureSnapshot(), patch)
    }

    private data class ProjectedChange(val start: Int, val end: Int, val replacement: String)

    private fun referencedPaths(documentNodes: List<DocumentNode>): Set<String> = buildSet {
        documentNodes.forEach { node ->
            node.atomic?.takeIf { it.kind == "image" }?.destination?.let(::add)
            node.inlineAtoms.filter { it.node.kind == "image" }.forEach { it.node.destination?.let(::add) }
        }
    }

    private fun touchesImageReferences(patch: SourceReplacement, removed: String, origin: EditOrigin): Boolean {
        if (origin in setOf(EditOrigin.IMAGE, EditOrigin.SOURCE, EditOrigin.AI) || "![" in removed || "![" in patch.replacement) return true
        var low = 0
        var high = nodes.size
        while (low < high) { val middle = (low + high) ushr 1; if (nodes[middle].end < patch.start) low = middle + 1 else high = middle }
        var index = low
        while (index < nodes.size && nodes[index].start <= patch.end) {
            val node = nodes[index++]
            if (node.kind == "link_definition" && patch.start <= node.end && patch.end >= node.start ||
                node.atomic?.kind == "image" && patch.start <= node.end && patch.end >= node.start ||
                node.inlineAtoms.any { atom -> patch.start <= node.start + atom.sourceEnd && patch.end >= node.start + atom.sourceStart }) return true
        }
        return false
    }
}
