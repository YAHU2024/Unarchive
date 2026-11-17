package com.unarchive.android.editor.ai

import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.NoteContent
import com.unarchive.android.editor.MarkdownAiProposal
import com.unarchive.android.editor.MarkdownAiProposalBuilder
import com.unarchive.android.editor.MarkdownAiProposalDeleteResult
import com.unarchive.android.editor.MarkdownAiProposalGenerator
import com.unarchive.android.editor.MarkdownAiProposalRepository
import com.unarchive.android.editor.MarkdownAiProposalStatus
import com.unarchive.android.editor.markdownAiAssetsFingerprint
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A frozen native-queue boundary. Never construct it from a second editable buffer. */
data class AiDocumentContext(
    val formal: NoteContent,
    val markdown: String,
    val documentVersion: Long,
    val assets: List<CardAsset> = formal.structuredMetadata.assets,
    val hasUnresolvedRecovery: Boolean = false,
) {
    val clean: Boolean get() = markdown == formal.markdown && assets == formal.structuredMetadata.assets
}

enum class AiProposalActivity { IDLE, RESTORING, RUNNING, REVIEWING, APPLYING, REJECTING }

data class AiProposalUiState(
    val proposal: MarkdownAiProposal? = null,
    val activity: AiProposalActivity = AiProposalActivity.IDLE,
    val error: String? = null,
    val review: AiReviewProjection? = null,
    val safetyErrors: List<String> = emptyList(),
    val generationFailed: Boolean = false,
) {
    val canApply: Boolean get() = proposal?.status == MarkdownAiProposalStatus.PENDING &&
        activity == AiProposalActivity.IDLE && safetyErrors.isEmpty()
    val shortStatus: String? get() = when {
        activity == AiProposalActivity.RUNNING -> "生成中"
        proposal?.status == MarkdownAiProposalStatus.EXPIRED -> "候选已过期"
        proposal?.status == MarkdownAiProposalStatus.APPLIED -> "待保存"
        generationFailed -> "生成失败"
        proposal?.status == MarkdownAiProposalStatus.PENDING -> "有待审候选"
        error != null -> "操作失败"
        else -> null
    }
}

/** Caller must compare every expected value again at the session mutation barrier. */
data class AiSourceTransaction(
    val proposalId: String,
    val expectedDocumentVersion: Long,
    val expectedSourceFingerprint: String,
    val markdown: String,
    val expectedAssetsFingerprint: String,
    val expectedFormalRevision: Long,
)

sealed interface AiApplyResult {
    data class Ready(val transaction: AiSourceTransaction) : AiApplyResult
    data class Blocked(val message: String) : AiApplyResult
}

/**
 * One coordinator per opened document. Invoke public methods on the owning UI scope.
 * Closing an AI panel does nothing here; close() is only for leaving the entire document.
 * Repository/parse/asset checks run on workers. No method writes formal note content.
 */
class AiProposalCoordinator(
    private val scope: CoroutineScope,
    private val repository: MarkdownAiProposalRepository,
    private val generator: MarkdownAiProposalGenerator?,
    private val loadFormal: suspend () -> NoteContent?,
    private val loadRevision: suspend (Long) -> NoteContent?,
    private val isManagedAssetAvailable: (CardAsset) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val newProposalId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(AiProposalUiState())
    val state = mutableState.asStateFlow()
    private val storage = Mutex()
    private var context: AiDocumentContext? = null
    private var contextEpoch = 0L
    private var lifetime = 0L
    private var closed = false
    private var generation: Job? = null
    private var restoration: Job? = null
    private var reviewing: Job? = null
    private var rejection: Job? = null
    private var appliedId: String? = null

    fun updateContext(value: AiDocumentContext) {
        if (closed) return
        val prior = context
        require(prior == null || sameIdentity(prior.formal, value.formal)) { "AI coordinator belongs to one document" }
        if (prior == null || prior.documentVersion != value.documentVersion || prior.markdown != value.markdown ||
            prior.assets != value.assets || !sameFormal(prior.formal, value.formal) ||
            prior.hasUnresolvedRecovery != value.hasUnresolvedRecovery) contextEpoch++
        context = value
        val proposal = state.value.proposal ?: return
        if (proposal.status == MarkdownAiProposalStatus.PENDING && !matches(proposal, value)) {
            expire(proposal, "正文或正式版本已变化，候选仅可查看，请重新生成")
        }
    }

    fun restore(value: AiDocumentContext): Job {
        check(!closed) { "Document is closed" }
        updateContext(value)
        restoration?.cancel()
        val token = lifetime
        mutableState.update { it.copy(activity = AiProposalActivity.RESTORING) }
        return scope.launch {
            try {
                val restored = disk { repository.find(value.formal.cardId.value, value.formal.cardVersion) }
                if (!alive(token) || generation?.isActive == true) return@launch
                val current = context ?: return@launch
                val candidate = restored?.let {
                    if (it.status == MarkdownAiProposalStatus.EXPIRED || !matches(it, current))
                        it.copy(status = MarkdownAiProposalStatus.EXPIRED) else it
                }
                if (candidate != null && candidate != restored) disk { repository.saveIfMatches(candidate, candidate.proposalId) }
                if (alive(token)) mutableState.value = AiProposalUiState(proposal = candidate,
                    error = if (candidate?.status == MarkdownAiProposalStatus.EXPIRED) "候选基版已变化，可只读查看" else null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (alive(token)) mutableState.update { it.copy(activity = AiProposalActivity.IDLE,
                error = "候选恢复失败，请重试") } }
        }.also { restoration = it }
    }

    /** Fails closed for dirty/recovery contexts even when the caller forgot its save gate. */
    fun generate(value: AiDocumentContext): Job? {
        if (closed || generation?.isActive == true || state.value.activity != AiProposalActivity.IDLE) return null
        updateContext(value)
        if (!value.clean || value.hasUnresolvedRecovery) {
            mutableState.update { it.copy(error = "请先处理恢复稿并正式保存当前修改，再生成候选", generationFailed = false) }
            return null
        }
        val adapter = generator
        if (adapter == null) {
            mutableState.update { it.copy(error = "当前未配置 Markdown AI 服务", generationFailed = true) }
            return null
        }
        val token = lifetime
        val epoch = contextEpoch
        val previousId = state.value.proposal?.proposalId
        mutableState.update { it.copy(activity = AiProposalActivity.RUNNING, error = null, generationFailed = false) }
        return scope.launch {
            try {
                val diskBase = withContext(ioDispatcher) { loadFormal() }
                if (!sameFormal(value.formal, diskBase)) error("stale-base")
                val generated = adapter.generate(value.formal.copy(draftState = null))
                ensureActive()
                if (!alive(token)) return@launch
                val latest = withContext(ioDispatcher) { loadFormal() }
                val expired = epoch != contextEpoch || !sameFormal(value.formal, latest) ||
                    context?.let { !it.clean || !sameFormal(value.formal, it.formal) } != false
                val candidate = MarkdownAiProposalBuilder.create(value.formal, generated.markdown,
                    newProposalId(), generated.model, nowEpochMs().coerceAtLeast(0L)).let {
                    if (expired) it.copy(status = MarkdownAiProposalStatus.EXPIRED) else it
                }
                val stored = disk { repository.saveIfMatches(candidate, previousId) }
                if (!stored) error("candidate-storage-conflict")
                if (!alive(token)) return@launch
                // Input may have changed while the write was in progress.
                val displayed = if (epoch != contextEpoch) candidate.copy(status = MarkdownAiProposalStatus.EXPIRED) else candidate
                if (displayed != candidate) disk { repository.saveIfMatches(displayed, displayed.proposalId) }
                mutableState.value = AiProposalUiState(proposal = displayed,
                    error = if (displayed.status == MarkdownAiProposalStatus.EXPIRED) "生成期间正文或基版已变化，候选仅可查看" else null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (alive(token)) mutableState.update { it.copy(activity = AiProposalActivity.IDLE,
                error = "生成候选失败，已有候选仍保留，可重试", generationFailed = true) } }
        }.also { generation = it }
    }

    fun openReview(): Job? {
        if (closed) return null
        val candidate = state.value.proposal ?: return null
        reviewing?.cancel()
        val token = lifetime
        val previousActivity = state.value.activity
        if (previousActivity != AiProposalActivity.RUNNING) mutableState.update { it.copy(activity = AiProposalActivity.REVIEWING) }
        return scope.launch {
            try {
                val base = withContext(ioDispatcher) { loadRevision(candidate.baseMarkdownRevision) }
                    ?.takeIf { baseMatches(candidate, it) }
                val review = withContext(workerDispatcher) { AiReviewProjection.build(base?.markdown, candidate.proposedMarkdown) }
                val errors = withContext(workerDispatcher) {
                    AiMarkdownSafety.validate(candidate.proposedMarkdown, base?.structuredMetadata?.assets.orEmpty(),
                        isManagedAssetAvailable).errors
                }
                if (alive(token) && state.value.proposal?.proposalId == candidate.proposalId) {
                    mutableState.update { it.copy(review = review, safetyErrors = errors,
                        activity = if (generation?.isActive == true) AiProposalActivity.RUNNING else AiProposalActivity.IDLE) }
                    if (base == null && state.value.proposal?.status == MarkdownAiProposalStatus.PENDING)
                        expire(candidate, "原正式基版不可用，候选仅可查看")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (alive(token)) mutableState.update { it.copy(activity = AiProposalActivity.IDLE,
                error = "候选审阅载入失败，请重试") } }
        }.also { reviewing = it }
    }

    suspend fun prepareApply(value: AiDocumentContext): AiApplyResult {
        if (closed) return AiApplyResult.Blocked("文档已关闭")
        updateContext(value)
        val proposal = state.value.proposal ?: return AiApplyResult.Blocked("没有待审候选")
        if (state.value.activity != AiProposalActivity.IDLE || proposal.status != MarkdownAiProposalStatus.PENDING)
            return AiApplyResult.Blocked("当前候选不可应用")
        if (!value.clean || value.hasUnresolvedRecovery) return blocked("请先处理恢复稿并正式保存当前修改")
        val token = lifetime
        val epoch = contextEpoch
        mutableState.update { it.copy(activity = AiProposalActivity.APPLYING, error = null) }
        try {
            val diskBase = withContext(ioDispatcher) { loadFormal() }
            if (!sameFormal(value.formal, diskBase) || !matches(proposal, value)) {
                expire(proposal, "正式版本已变化，候选仅可查看")
                return blocked("候选基版已变化，请重新生成")
            }
            val errors = withContext(workerDispatcher) {
                AiMarkdownSafety.validate(proposal.proposedMarkdown, value.assets, isManagedAssetAvailable).errors
            }
            if (!alive(token) || epoch != contextEpoch || state.value.proposal?.proposalId != proposal.proposalId)
                return blocked("正文已变化，未应用候选")
            if (errors.isNotEmpty()) {
                mutableState.update { it.copy(safetyErrors = errors) }
                return blocked("候选未通过安全或图片检查：${errors.joinToString("；")}")
            }
            // A filesystem save can race the worker safety check; check the formal snapshot again.
            val rechecked = withContext(ioDispatcher) { loadFormal() }
            if (!alive(token) || epoch != contextEpoch || !sameFormal(value.formal, rechecked)) {
                expire(proposal, "正式版本或正文已变化，候选仅可查看")
                return blocked("版本已变化，未应用候选")
            }
            return AiApplyResult.Ready(AiSourceTransaction(proposal.proposalId, value.documentVersion,
                NoteContent.markdownFingerprint(value.markdown), proposal.proposedMarkdown,
                markdownAiAssetsFingerprint(value.assets), value.formal.markdownRevision))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return blocked("候选检查失败，未修改正文，请重试") }
        finally { if (alive(token)) mutableState.update { it.copy(activity = AiProposalActivity.IDLE) } }
    }

    fun applied(proposalId: String) {
        val proposal = state.value.proposal?.takeIf { it.proposalId == proposalId } ?: return
        appliedId = proposalId
        mutableState.update { it.copy(proposal = proposal.copy(status = MarkdownAiProposalStatus.APPLIED), error = null) }
    }

    fun formalCommitted(content: NoteContent) {
        if (closed) return
        val prior = context ?: return
        updateContext(prior.copy(formal = content))
        val proposal = state.value.proposal ?: return
        if (proposal.proposalId == appliedId) {
            // The blue check already committed. A failed cleanup must not say "待保存".
            mutableState.update { it.copy(proposal = proposal.copy(status = MarkdownAiProposalStatus.EXPIRED)) }
            deleteCandidate(proposal)
        }
    }

    fun reject(): Job? {
        if (closed || rejection?.isActive == true || generation?.isActive == true) return null
        val proposal = state.value.proposal ?: return null
        return deleteCandidate(proposal)
    }

    private fun deleteCandidate(proposal: MarkdownAiProposal): Job {
        val token = lifetime
        mutableState.update { it.copy(activity = AiProposalActivity.REJECTING) }
        return scope.launch {
            val deleted = try { disk { repository.deleteIfMatches(proposal.baseCardId, proposal.baseCardVersion, proposal.proposalId) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { MarkdownAiProposalDeleteResult.FAILED }
            if (!alive(token) || state.value.proposal?.proposalId != proposal.proposalId) return@launch
            if (deleted in setOf(MarkdownAiProposalDeleteResult.DELETED, MarkdownAiProposalDeleteResult.MISSING)) {
                appliedId = null
                mutableState.value = AiProposalUiState()
            } else mutableState.update { it.copy(activity = AiProposalActivity.IDLE,
                error = "候选清理失败，候选仍保留，请重试") }
        }.also { rejection = it }
    }

    fun close() {
        closed = true
        lifetime++
        generation?.cancel(); restoration?.cancel(); reviewing?.cancel(); rejection?.cancel()
    }

    private fun blocked(message: String): AiApplyResult.Blocked {
        mutableState.update { it.copy(error = message) }
        return AiApplyResult.Blocked(message)
    }

    private fun expire(proposal: MarkdownAiProposal, message: String) {
        if (proposal.status != MarkdownAiProposalStatus.PENDING) return
        val expired = proposal.copy(status = MarkdownAiProposalStatus.EXPIRED)
        mutableState.update { it.copy(proposal = expired, error = message) }
        scope.launch {
            try { disk { repository.saveIfMatches(expired, proposal.proposalId) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (!closed) mutableState.update { it.copy(error = "候选已过期，状态保护失败，请重试") } }
        }
    }

    private suspend fun <T> disk(action: () -> T): T = storage.withLock { withContext(ioDispatcher) { action() } }
    private fun alive(token: Long) = !closed && token == lifetime
    private fun matches(proposal: MarkdownAiProposal, value: AiDocumentContext) = value.clean &&
        !value.hasUnresolvedRecovery && baseMatches(proposal, value.formal)

    private fun baseMatches(proposal: MarkdownAiProposal, content: NoteContent) =
        proposal.baseCardId == content.cardId.value && proposal.baseCardVersion == content.cardVersion &&
            proposal.baseMarkdownRevision == content.markdownRevision &&
            proposal.baseContentFingerprint == NoteContent.markdownFingerprint(content.markdown) &&
            (proposal.baseAssetsFingerprint == null ||
                proposal.baseAssetsFingerprint == markdownAiAssetsFingerprint(content.structuredMetadata.assets))

    private fun sameIdentity(a: NoteContent, b: NoteContent) = a.cardId == b.cardId && a.cardVersion == b.cardVersion
    private fun sameFormal(a: NoteContent, b: NoteContent?) = b != null && sameIdentity(a, b) &&
        a.markdownRevision == b.markdownRevision && a.markdown == b.markdown &&
        a.structuredMetadata.assets == b.structuredMetadata.assets
}
