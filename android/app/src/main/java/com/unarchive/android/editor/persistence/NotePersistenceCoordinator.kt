package com.unarchive.android.editor.persistence

import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteConditionalSaveResult
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteDraftDeleteResult
import com.unarchive.android.card.NoteDraftState
import com.unarchive.android.card.NoteDraftWriteResult
import com.unarchive.android.card.NoteMarkdownWriteback
import com.unarchive.android.card.NoteRecoveryCandidate
import com.unarchive.android.card.NoteRecoveryToken
import com.unarchive.android.card.PendingNoteSourceEdit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Frozen after the native mutation barrier; contains no View/Editable/Bitmap. */
data class SaveSnapshot(
    val base: NoteContent,
    val markdown: String,
    val sessionId: String,
    val documentVersion: Long,
    val assets: List<CardAsset> = base.structuredMetadata.assets,
    val selectionAnchor: Int? = null,
    val selectionFocus: Int? = null,
    val viewportAnchor: Int? = null,
    val pendingSourceEdit: PendingNoteSourceEdit? = null,
    val generation: Long = documentVersion,
    /** Explicit ownership transfer when restoring an older session's draft. */
    val recoveryToken: NoteRecoveryToken? = null,
)

data class NoteOpenSnapshot(val formal: NoteContent, val recovery: List<NoteRecoveryCandidate>)

sealed interface NotePersistenceSaveResult {
    data class Saved(
        val content: NoteContent,
        val changed: Boolean,
        val cleanupWarning: String? = null,
        val projectionWarning: String? = null,
    ) : NotePersistenceSaveResult
    data class Conflict(val latest: NoteContent?) : NotePersistenceSaveResult
    data class Failed(val error: String) : NotePersistenceSaveResult
}

/**
 * Orders snapshots and waits for actual disk completion. Debounce belongs to
 * the caller; flush uses writeDraft with the latest frozen snapshot and awaits
 * its result. All other repository instances share the repository write lock.
 */
class NotePersistenceCoordinator(
    private val repository: FileNoteContentRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val operations = Mutex()

    suspend fun load(cardId: KnowledgeCardId, cardVersion: String): NoteOpenSnapshot? = operations.withLock {
        withContext(dispatcher) {
            repository.find(cardId, cardVersion)?.let {
                NoteOpenSnapshot(it, repository.loadRecoveryState(cardId, cardVersion))
            }
        }
    }

    suspend fun loadRecoveryState(cardId: KnowledgeCardId, cardVersion: String): List<NoteRecoveryCandidate> =
        operations.withLock { withContext(dispatcher) { repository.loadRecoveryState(cardId, cardVersion) } }

    suspend fun writeDraft(snapshot: SaveSnapshot): NoteDraftWriteResult = writeDraft(
        snapshot.base,
        NoteDraftState(
            markdown = snapshot.markdown,
            baseMarkdownRevision = snapshot.base.markdownRevision,
            contentFingerprint = NoteContent.markdownFingerprint(snapshot.markdown),
            updatedAtEpochMs = nowEpochMs().coerceAtLeast(0L),
            baseContentFingerprint = NoteContent.markdownFingerprint(snapshot.base.markdown),
            assets = snapshot.assets.toList(),
            selectionAnchor = snapshot.selectionAnchor,
            selectionFocus = snapshot.selectionFocus,
            viewportAnchor = snapshot.viewportAnchor,
            pendingSourceEdit = snapshot.pendingSourceEdit,
            sessionId = snapshot.sessionId,
            documentVersion = snapshot.documentVersion,
            generation = snapshot.generation,
        ),
        snapshot.recoveryToken,
    )

    suspend fun writeDraft(
        base: NoteContent,
        draft: NoteDraftState,
        expectedToken: NoteRecoveryToken? = null,
    ): NoteDraftWriteResult = operations.withLock {
        withContext(dispatcher) { repository.saveDraftIfBaseMatches(base, draft, expectedToken) }
    }

    suspend fun save(snapshot: SaveSnapshot): NotePersistenceSaveResult = operations.withLock {
        withContext(dispatcher) {
            if (snapshot.pendingSourceEdit != null) {
                return@withContext NotePersistenceSaveResult.Failed("请先应用或放弃源码面板中的修改")
            }
            val writeback = NoteMarkdownWriteback.apply(snapshot.base.structuredMetadata, snapshot.markdown)
            val content = snapshot.base.copy(
                markdown = snapshot.markdown,
                structuredMetadata = writeback.document.copy(assets = snapshot.assets.toList()),
                structuredProjection = writeback.projection,
                projectionStatus = writeback.status,
                lastSaveError = writeback.error?.let { "Markdown 投影需要检查；原文已保留" },
            )
            when (val result = repository.commitIfBaseMatches(
                content, snapshot.base.markdownRevision, NoteContent.markdownFingerprint(snapshot.base.markdown),
            )) {
                is NoteConditionalSaveResult.Committed -> saved(snapshot, result.content, true)
                is NoteConditionalSaveResult.Unchanged -> saved(snapshot, result.content, false)
                is NoteConditionalSaveResult.Conflict -> NotePersistenceSaveResult.Conflict(result.latest)
                is NoteConditionalSaveResult.Failed -> NotePersistenceSaveResult.Failed(result.error)
            }
        }
    }

    suspend fun discard(
        cardId: KnowledgeCardId,
        cardVersion: String,
        token: NoteRecoveryToken,
    ): NoteDraftDeleteResult = operations.withLock {
        withContext(dispatcher) { repository.discardDraftIfMatches(cardId, cardVersion, token) }
    }

    private fun saved(snapshot: SaveSnapshot, content: NoteContent, changed: Boolean): NotePersistenceSaveResult.Saved {
        // Cleanup is conditional on the saved input generation. Another session's
        // or a newer generation's recovery file must remain independently reachable.
        val candidates = repository.loadRecoveryState(content.cardId, content.cardVersion).filter {
            it.token.revision == snapshot.base.markdownRevision &&
                (it.token == snapshot.recoveryToken || it.draft?.let { draft ->
                    draft.sessionId == snapshot.sessionId && draft.generation <= snapshot.generation &&
                        draft.documentVersion <= snapshot.documentVersion
                } == true)
        }
        var cleanupWarning: String? = null
        candidates.forEach {
            when (repository.discardDraftIfMatches(content.cardId, content.cardVersion, it.token)) {
                NoteDraftDeleteResult.Deleted, NoteDraftDeleteResult.Missing -> Unit
                else -> cleanupWarning = "正文已正式保存，旧草稿清理未完成，可稍后重试"
            }
        }
        return NotePersistenceSaveResult.Saved(
            content.copy(draftState = null), changed, cleanupWarning, content.lastSaveError,
        )
    }
}
