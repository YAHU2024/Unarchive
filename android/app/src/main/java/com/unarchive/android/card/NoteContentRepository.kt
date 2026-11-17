package com.unarchive.android.card

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

enum class NoteContentSavePhase { BACKUP, CONTENT, MARKDOWN, COMMIT, COMPLETE, CONFLICT }

data class NoteContentSaveResult(
    val content: NoteContent,
    val phase: NoteContentSavePhase,
    val error: String? = null,
) {
    val isComplete: Boolean get() = phase == NoteContentSavePhase.COMPLETE && error == null
}

sealed interface NoteConditionalSaveResult {
    data class Committed(val content: NoteContent) : NoteConditionalSaveResult
    data class Unchanged(val content: NoteContent) : NoteConditionalSaveResult
    data class Conflict(val latest: NoteContent?) : NoteConditionalSaveResult
    data class Failed(val phase: NoteContentSavePhase, val error: String) : NoteConditionalSaveResult
}

data class NoteRecoveryToken(val revision: Long, val fileFingerprint: String)
enum class NoteRecoveryStatus { AVAILABLE, STALE, CORRUPT }
data class NoteRecoveryCandidate(
    val token: NoteRecoveryToken,
    val status: NoteRecoveryStatus,
    val draft: NoteDraftState?,
    val rawRecovery: String,
    val diagnostic: String? = null,
) {
    val updatedAtEpochMs: Long get() = draft?.updatedAtEpochMs ?: 0L
    /** A stale source panel remains copyable; it cannot be applied to a different range. */
    val sourceScratchApplicable: Boolean get() {
        val value = draft ?: return false
        val scratch = value.pendingSourceEdit ?: return true
        return scratch.sourceEnd <= value.markdown.length &&
            scratch.baseContentFingerprint == NoteContent.markdownFingerprint(value.markdown) &&
            scratch.originalRangeFingerprint == NoteContent.markdownFingerprint(value.markdown.substring(scratch.sourceStart, scratch.sourceEnd))
    }
}

sealed interface NoteDraftWriteResult {
    data class Written(val content: NoteContent, val token: NoteRecoveryToken) : NoteDraftWriteResult
    data class Conflict(val latest: NoteContent?, val reason: String) : NoteDraftWriteResult
    data class Failed(val error: String) : NoteDraftWriteResult
}

sealed interface NoteDraftDeleteResult {
    data object Deleted : NoteDraftDeleteResult
    data object Missing : NoteDraftDeleteResult
    data object Changed : NoteDraftDeleteResult
    data class Failed(val error: String) : NoteDraftDeleteResult
}

fun interface NoteContentAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

/**
 * Complete commit.json is the publication boundary. All instances which point
 * at this store share a lock, including migration and the legacy editor APIs.
 * The lock covers the disk comparison and write; cancelling a UI job cannot
 * move an already running disk write outside this boundary.
 */
class FileNoteContentRepository(
    private val directory: File,
    private val writer: NoteContentAtomicWriter = DefaultNoteContentAtomicWriter,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val writeLock = StoreLocks.forDirectory(directory)

    fun list(): List<NoteContent> = synchronized(writeLock) {
        directory.listFiles { it.isDirectory }.orEmpty().flatMap { cardDirectory ->
            cardDirectory.listFiles { it.isDirectory }.orEmpty().flatMap { versionDirectory ->
                versionDirectory.listFiles { it.isDirectory }.orEmpty().mapNotNull(::readContent)
            }
        }.groupBy { it.cardId }.values
            .mapNotNull { versions -> versions.maxWithOrNull(compareBy<NoteContent> { it.updatedAtEpochMs }.thenBy { it.markdownRevision }) }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    fun listVersions(cardId: KnowledgeCardId): List<NoteContent> = synchronized(writeLock) {
        cardDirectory(cardId).listFiles { it.isDirectory }.orEmpty().flatMap { versionDirectory ->
            versionDirectory.listFiles { it.isDirectory }.orEmpty().mapNotNull(::readContent)
        }.sortedByDescending { it.markdownRevision }
    }

    fun find(cardId: KnowledgeCardId, cardVersion: String): NoteContent? = synchronized(writeLock) {
        listVersions(cardId).firstOrNull { it.cardVersion == cardVersion }
    }

    fun findRevision(cardId: KnowledgeCardId, cardVersion: String, revision: Long): NoteContent? =
        synchronized(writeLock) {
            if (revision < 0L) return@synchronized null
            readContent(revisionDirectory(cardId, cardVersion, revision))?.takeIf {
                it.cardId == cardId && it.cardVersion == cardVersion && it.markdownRevision == revision
            }
        }

    /** Legacy entry point now also rejects a stale caller instead of raising its revision. */
    fun save(content: NoteContent): NoteContentSaveResult = synchronized(writeLock) {
        val existing = find(content.cardId, content.cardVersion)
        if (existing == null) return@synchronized publish(content)
        when (val result = commitIfBaseMatches(
            content, content.markdownRevision, NoteContent.markdownFingerprint(content.lastKnownGoodMarkdown),
        )) {
            is NoteConditionalSaveResult.Committed -> NoteContentSaveResult(result.content, NoteContentSavePhase.COMPLETE)
            is NoteConditionalSaveResult.Unchanged -> NoteContentSaveResult(result.content, NoteContentSavePhase.COMPLETE)
            is NoteConditionalSaveResult.Conflict -> NoteContentSaveResult(
                content, NoteContentSavePhase.CONFLICT, "正式版本已变化，当前修改已保留",
            )
            is NoteConditionalSaveResult.Failed -> NoteContentSaveResult(content, result.phase, result.error)
        }
    }

    /** Checks both the base and an unchanged save inside the shared write boundary. */
    fun commitIfBaseMatches(
        content: NoteContent,
        baseRevision: Long,
        baseFingerprint: String,
    ): NoteConditionalSaveResult = synchronized(writeLock) {
        val existing = find(content.cardId, content.cardVersion)
        if (existing == null || existing.markdownRevision != baseRevision ||
            NoteContent.markdownFingerprint(existing.markdown) != baseFingerprint
        ) return@synchronized NoteConditionalSaveResult.Conflict(existing)
        if (!validAssets(content.structuredMetadata.assets)) {
            return@synchronized NoteConditionalSaveResult.Failed(NoteContentSavePhase.CONTENT, "资产清单校验失败，正文未提交")
        }
        if (existing.markdown == content.markdown && existing.structuredMetadata.assets == content.structuredMetadata.assets) {
            return@synchronized NoteConditionalSaveResult.Unchanged(existing)
        }
        // A damaged historical commit is still immutable. Skip its number
        // rather than republishing different bytes over the same identity.
        val sealedRevision = revisionDirectory(content.cardId, content.cardVersion, 0L).parentFile
            ?.listFiles { it.isDirectory && File(it, COMMIT_FILE_NAME).exists() }.orEmpty()
            .mapNotNull { it.name.removePrefix("r-").toLongOrNull() }.maxOrNull() ?: existing.markdownRevision
        val nextRevision = maxOf(existing.markdownRevision, sealedRevision) + 1L
        if (nextRevision <= existing.markdownRevision) {
            return@synchronized NoteConditionalSaveResult.Failed(NoteContentSavePhase.CONTENT, "正式修订号已超出支持范围")
        }
        val saved = publish(content.copy(markdownRevision = nextRevision))
        if (saved.isComplete) NoteConditionalSaveResult.Committed(saved.content)
        else NoteConditionalSaveResult.Failed(saved.phase, saved.error ?: "正式保存未完成")
    }

    /** Never changes files in a complete revision, even if a caller invokes save twice. */
    private fun publish(content: NoteContent): NoteContentSaveResult {
        val target = revisionDirectory(content.cardId, content.cardVersion, content.markdownRevision)
        if (File(target, COMMIT_FILE_NAME).exists()) {
            return NoteContentSaveResult(content, NoteContentSavePhase.CONFLICT, "正式修订已发布，不能原地覆盖")
        }
        val now = maxOf(nowEpochMs(), content.updatedAtEpochMs, content.createdAtEpochMs)
        val prepared = content.copy(
            draftState = null, lastKnownGoodMarkdown = content.markdown,
            lastSaveError = content.lastSaveError?.takeIf { content.projectionStatus == NoteProjectionStatus.FAILED },
            updatedAtEpochMs = now,
        )
        val contentBytes = prepared.toJson().toString().toByteArray(Charsets.UTF_8)
        val writes = listOf(
            Triple(CONTENT_FILE_NAME, contentBytes, NoteContentSavePhase.CONTENT),
            Triple(MARKDOWN_FILE_NAME, prepared.markdown.toByteArray(Charsets.UTF_8), NoteContentSavePhase.MARKDOWN),
            Triple(COMMIT_FILE_NAME, commitJson(prepared, contentBytes).toString().toByteArray(Charsets.UTF_8), NoteContentSavePhase.COMMIT),
        )
        for ((name, bytes, phase) in writes) {
            try {
                writer.write(File(target, name), bytes)
            } catch (_: Exception) {
                // Do not expose arbitrary writer messages: they can contain source or file contents.
                return NoteContentSaveResult(prepared, phase, "本地写入失败（${phase.name.lowercase()}），请重试")
            }
        }
        return NoteContentSaveResult(prepared, NoteContentSavePhase.COMPLETE)
    }

    fun saveMigrated(content: NoteContent, backup: LegacyNoteBackup): NoteContentSaveResult = synchronized(writeLock) {
        val existing = find(content.cardId, content.cardVersion)
        if (existing != null) return@synchronized NoteContentSaveResult(existing, NoteContentSavePhase.COMPLETE)
        val target = revisionDirectory(content.cardId, content.cardVersion, content.markdownRevision)
        try {
            writer.write(File(target, LEGACY_BACKUP_FILE_NAME), backup.toJsonForRepository().toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            return@synchronized NoteContentSaveResult(content, NoteContentSavePhase.BACKUP, "迁移备份写入失败，请重试")
        }
        publish(content)
    }

    fun migrationBackup(cardId: KnowledgeCardId, cardVersion: String): LegacyNoteBackup? = synchronized(writeLock) {
        val file = File(revisionDirectory(cardId, cardVersion, 0L), LEGACY_BACKUP_FILE_NAME)
        runCatching { if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)).toLegacyNoteBackupForRepository() else null }.getOrNull()
    }

    fun saveDraft(content: NoteContent, draft: NoteDraftState): NoteContent =
        when (val result = saveDraftIfBaseMatches(content, draft)) {
            is NoteDraftWriteResult.Written -> result.content
            is NoteDraftWriteResult.Conflict -> error(result.reason)
            is NoteDraftWriteResult.Failed -> error(result.error)
        }

    fun saveDraftIfBaseMatches(
        content: NoteContent,
        draft: NoteDraftState,
        expectedDraft: NoteRecoveryToken? = null,
    ): NoteDraftWriteResult = synchronized(writeLock) {
        val latest = find(content.cardId, content.cardVersion)
        val expectedFingerprint = NoteContent.markdownFingerprint(content.markdown)
        if (latest == null || latest.markdownRevision != content.markdownRevision ||
            NoteContent.markdownFingerprint(latest.markdown) != expectedFingerprint ||
            draft.baseMarkdownRevision != latest.markdownRevision ||
            (draft.baseContentFingerprint != null && draft.baseContentFingerprint != expectedFingerprint)
        ) return@synchronized NoteDraftWriteResult.Conflict(latest, "正式版本已变化，草稿未覆盖新版本")
        if (draft.contentFingerprint != NoteContent.markdownFingerprint(draft.markdown) || !validAssets(draft.assets)) {
            return@synchronized NoteDraftWriteResult.Failed("草稿校验失败，内存中的修改仍保留")
        }
        val target = File(revisionDirectory(content.cardId, content.cardVersion, content.markdownRevision), DRAFT_FILE_NAME)
        if (target.exists()) {
            val token = draftToken(target, content.markdownRevision)
            val previous = runCatching { JSONObject(target.readText(Charsets.UTF_8)).toNoteDraftState() }.getOrNull()
            if (expectedDraft != null && expectedDraft != token) {
                return@synchronized NoteDraftWriteResult.Conflict(latest, "恢复草稿已变化，请先处理恢复内容")
            }
            if (expectedDraft == null && (previous == null || previous.sessionId != draft.sessionId)) {
                return@synchronized NoteDraftWriteResult.Conflict(latest, "已有恢复草稿，请先恢复或明确放弃")
            }
            if (previous?.sessionId == draft.sessionId && previous != null &&
                (previous.generation > draft.generation || previous.documentVersion > draft.documentVersion ||
                    (draft.sessionId == null && previous.updatedAtEpochMs > draft.updatedAtEpochMs))
            ) return@synchronized NoteDraftWriteResult.Conflict(latest, "较新的草稿已保护，本次旧结果已忽略")
        } else if (expectedDraft != null) {
            return@synchronized NoteDraftWriteResult.Conflict(latest, "恢复草稿已变化，请重新打开")
        }
        try {
            val bytes = draft.toDraftJson().toString().toByteArray(Charsets.UTF_8)
            writer.write(target, bytes)
            NoteDraftWriteResult.Written(latest.copy(draftState = draft), NoteRecoveryToken(content.markdownRevision, KnowledgeCard.sha256(bytes)))
        } catch (_: Exception) {
            NoteDraftWriteResult.Failed("草稿保护失败，请重试或复制当前内容")
        }
    }

    /** Includes side files belonging to older complete bases; does not silently discard any. */
    fun loadRecoveryState(cardId: KnowledgeCardId, cardVersion: String): List<NoteRecoveryCandidate> = synchronized(writeLock) {
        val latest = find(cardId, cardVersion) ?: return@synchronized emptyList()
        val versionDirectory = revisionDirectory(cardId, cardVersion, 0L).parentFile ?: return@synchronized emptyList()
        versionDirectory.listFiles { it.isDirectory }.orEmpty().mapNotNull { revisionDirectory ->
            val file = File(revisionDirectory, DRAFT_FILE_NAME)
            if (!file.isFile) return@mapNotNull null
            val base = readContent(revisionDirectory, includeDraft = false) ?: return@mapNotNull null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull NoteRecoveryCandidate(
                NoteRecoveryToken(base.markdownRevision, "unreadable"), NoteRecoveryStatus.CORRUPT, null, "", "草稿文件无法读取",
            )
            val raw = bytes.toString(Charsets.UTF_8)
            val token = NoteRecoveryToken(base.markdownRevision, KnowledgeCard.sha256(bytes))
            val draft = runCatching { JSONObject(raw).toNoteDraftState() }.getOrNull()
            val valid = draft != null && draft.baseMarkdownRevision == base.markdownRevision &&
                draft.contentFingerprint == NoteContent.markdownFingerprint(draft.markdown) && validAssets(draft.assets) &&
                (draft.baseContentFingerprint == null || draft.baseContentFingerprint == NoteContent.markdownFingerprint(base.markdown))
            if (!valid) return@mapNotNull NoteRecoveryCandidate(token, NoteRecoveryStatus.CORRUPT, draft, raw, "草稿损坏或校验失败，可复制恢复文件")
            NoteRecoveryCandidate(
                token,
                if (base.markdownRevision == latest.markdownRevision) NoteRecoveryStatus.AVAILABLE else NoteRecoveryStatus.STALE,
                draft, raw,
                if (base.markdownRevision == latest.markdownRevision) null else "草稿基于旧正式版本，仅供查看或复制",
            )
        }.sortedWith(compareByDescending<NoteRecoveryCandidate> { it.updatedAtEpochMs }.thenByDescending { it.token.revision })
    }

    fun discardDraftIfMatches(
        cardId: KnowledgeCardId,
        cardVersion: String,
        token: NoteRecoveryToken,
    ): NoteDraftDeleteResult = synchronized(writeLock) {
        val file = File(revisionDirectory(cardId, cardVersion, token.revision), DRAFT_FILE_NAME)
        if (!file.exists()) return@synchronized NoteDraftDeleteResult.Missing
        val current = runCatching { draftToken(file, token.revision) }.getOrNull()
            ?: return@synchronized NoteDraftDeleteResult.Failed("无法核对草稿，请重试")
        if (current != token) return@synchronized NoteDraftDeleteResult.Changed
        try {
            if (file.delete() && !file.exists()) NoteDraftDeleteResult.Deleted
            else NoteDraftDeleteResult.Failed("草稿删除失败，恢复内容仍保留")
        } catch (_: Exception) {
            NoteDraftDeleteResult.Failed("草稿删除失败，恢复内容仍保留")
        }
    }

    /** Legacy callers also get checked deletion, never a false-success result. */
    fun discardDraft(content: NoteContent): Boolean = synchronized(writeLock) {
        val file = File(revisionDirectory(content.cardId, content.cardVersion, content.markdownRevision), DRAFT_FILE_NAME)
        if (!file.exists()) return@synchronized true
        val expected = content.draftState
        check(expected != null) { "尚未核对恢复草稿，未删除其它会话的内容" }
        val current = runCatching { JSONObject(file.readText(Charsets.UTF_8)).toNoteDraftState() }.getOrNull()
        check(current == expected) { "草稿已变化，未删除较新的恢复内容" }
        when (discardDraftIfMatches(content.cardId, content.cardVersion, draftToken(file, content.markdownRevision))) {
            NoteDraftDeleteResult.Deleted, NoteDraftDeleteResult.Missing -> true
            else -> error("草稿删除失败，恢复内容仍保留")
        }
    }

    private fun readContent(revisionDirectory: File, includeDraft: Boolean = true): NoteContent? = runCatching {
        val json = File(revisionDirectory, CONTENT_FILE_NAME)
        val markdown = File(revisionDirectory, MARKDOWN_FILE_NAME)
        val commit = File(revisionDirectory, COMMIT_FILE_NAME)
        if (!json.isFile || !markdown.isFile || !commit.isFile) return null
        val contentBytes = json.readBytes()
        val value = JSONObject(contentBytes.toString(Charsets.UTF_8))
        // Draft corruption can never invalidate an otherwise complete formal revision.
        value.remove("draft_state")
        val content = value.toNoteContent()
        val commitJson = JSONObject(commit.readText(Charsets.UTF_8))
        if (commitJson.has("content_file_fingerprint")) {
            require(commitJson.getString("content_file_fingerprint") == KnowledgeCard.sha256(contentBytes))
        }
        require(commitJson.optLong("markdown_revision", -1L) == content.markdownRevision)
        require(commitJson.optString("markdown_fingerprint") == NoteContent.markdownFingerprint(content.markdown))
        require(markdown.readText(Charsets.UTF_8) == content.markdown)
        val draft = if (includeDraft) File(revisionDirectory, DRAFT_FILE_NAME).takeIf(File::isFile)?.let { file ->
            runCatching { JSONObject(file.readText(Charsets.UTF_8)).toNoteDraftState() }.getOrNull()?.takeIf {
                it.baseMarkdownRevision == content.markdownRevision &&
                    it.contentFingerprint == NoteContent.markdownFingerprint(it.markdown) && validAssets(it.assets) &&
                    (it.baseContentFingerprint == null || it.baseContentFingerprint == NoteContent.markdownFingerprint(content.markdown))
            }
        } else null
        content.copy(draftState = draft)
    }.getOrNull()

    private fun draftToken(file: File, revision: Long) = NoteRecoveryToken(revision, KnowledgeCard.sha256(file.readBytes()))
    private fun commitJson(content: NoteContent, contentBytes: ByteArray) = JSONObject()
        .put("markdown_revision", content.markdownRevision)
        .put("markdown_fingerprint", NoteContent.markdownFingerprint(content.markdown))
        .put("content_file_fingerprint", KnowledgeCard.sha256(contentBytes))

    private fun cardDirectory(cardId: KnowledgeCardId) = File(directory, KnowledgeCard.sha256(cardId.value.toByteArray(Charsets.UTF_8)))
    private fun revisionDirectory(cardId: KnowledgeCardId, cardVersion: String, revision: Long) =
        File(File(cardDirectory(cardId), KnowledgeCard.sha256(cardVersion.toByteArray(Charsets.UTF_8))), "r-$revision")

    private fun validAssets(assets: List<CardAsset>?): Boolean = assets == null || assets.all {
        it.assetId.isNotBlank() && it.byteCount >= 0L && it.relativePath.isNotBlank() &&
            !File(it.relativePath).isAbsolute && !it.relativePath.contains('\\') &&
            it.relativePath.split('/').none { part -> part == ".." || part == "." || part.isEmpty() } &&
            !it.relativePath.contains(':')
    }

    private companion object {
        const val CONTENT_FILE_NAME = "content.json"
        const val MARKDOWN_FILE_NAME = "note.md"
        const val COMMIT_FILE_NAME = "commit.json"
        const val DRAFT_FILE_NAME = "draft.json"
        const val LEGACY_BACKUP_FILE_NAME = "legacy-backup.json"
    }
}

private object StoreLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forDirectory(directory: File): Any = locks.getOrPut(directory.canonicalPath.lowercase()) { Any() }
}

private fun LegacyNoteBackup.toJsonForRepository() = JSONObject()
    .put("structured_json", structuredJson).put("markdown", markdown)
private fun JSONObject.toLegacyNoteBackupForRepository() = LegacyNoteBackup(
    structuredJson = if (isNull("structured_json")) null else getString("structured_json"), markdown = getString("markdown"),
)

private object DefaultNoteContentAtomicWriter : NoteContentAtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        check(target.parentFile?.isDirectory == true) { "Cannot create content directory" }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
