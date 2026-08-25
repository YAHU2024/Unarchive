package com.unarchive.android.card

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

enum class NoteContentSavePhase { CONTENT, MARKDOWN, COMMIT, COMPLETE }

data class NoteContentSaveResult(
    val content: NoteContent,
    val phase: NoteContentSavePhase,
    val error: String? = null,
) {
    val isComplete: Boolean get() = phase == NoteContentSavePhase.COMPLETE && error == null
}

fun interface NoteContentAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

/** Isolated schema v3 store. Existing v2 repositories remain untouched. */
class FileNoteContentRepository(
    private val directory: File,
    private val writer: NoteContentAtomicWriter = DefaultNoteContentAtomicWriter,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun list(): List<NoteContent> = synchronized(this) {
        directory.listFiles { it.isDirectory }
            .orEmpty()
            .flatMap { cardDirectory ->
                cardDirectory.listFiles { it.isDirectory }
                    .orEmpty()
                    .flatMap { versionDirectory ->
                        versionDirectory.listFiles { it.isDirectory }
                            .orEmpty()
                            .mapNotNull(::readContent)
                    }
            }
            .groupBy { it.cardId }
            .values
            .mapNotNull { versions -> versions.maxByOrNull { it.markdownRevision } }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    fun listVersions(cardId: KnowledgeCardId): List<NoteContent> = synchronized(this) {
        cardDirectory(cardId).listFiles { it.isDirectory }
            .orEmpty()
            .flatMap { versionDirectory ->
                versionDirectory.listFiles { it.isDirectory }
                    .orEmpty()
                    .mapNotNull(::readContent)
            }
            .sortedByDescending { it.markdownRevision }
    }

    fun find(cardId: KnowledgeCardId, cardVersion: String): NoteContent? = synchronized(this) {
        listVersions(cardId).firstOrNull { it.cardVersion == cardVersion }
    }

    fun save(content: NoteContent): NoteContentSaveResult = synchronized(this) {
        val existing = find(content.cardId, content.cardVersion)
        val changed = existing == null || existing.markdown != content.markdown
        val revision = when {
            existing == null -> content.markdownRevision
            changed -> maxOf(existing.markdownRevision + 1L, content.markdownRevision)
            else -> maxOf(existing.markdownRevision, content.markdownRevision)
        }
        val now = maxOf(nowEpochMs(), content.updatedAtEpochMs, content.createdAtEpochMs)
        val prepared = content.copy(
            markdownRevision = revision,
            draftState = null,
            lastKnownGoodMarkdown = content.markdown,
            lastSaveError = null,
            updatedAtEpochMs = now,
        )
        val target = revisionDirectory(prepared.cardId, prepared.cardVersion, prepared.markdownRevision)
        val jsonTarget = File(target, CONTENT_FILE_NAME)
        try {
            writer.write(jsonTarget, prepared.toJson().toString().toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            return@synchronized NoteContentSaveResult(prepared, NoteContentSavePhase.CONTENT, message(error))
        }
        try {
            writer.write(File(target, MARKDOWN_FILE_NAME), prepared.markdown.toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            return@synchronized NoteContentSaveResult(prepared, NoteContentSavePhase.MARKDOWN, message(error))
        }
        try {
            writer.write(
                File(target, COMMIT_FILE_NAME),
                commitJson(prepared).toString().toByteArray(Charsets.UTF_8),
            )
        } catch (error: Throwable) {
            return@synchronized NoteContentSaveResult(prepared, NoteContentSavePhase.COMMIT, message(error))
        }
        NoteContentSaveResult(prepared, NoteContentSavePhase.COMPLETE)
    }

    fun saveDraft(content: NoteContent, draft: NoteDraftState): NoteContent {
        require(draft.baseMarkdownRevision == content.markdownRevision) {
            "draft base revision must match formal content"
        }
        val target = revisionDirectory(content.cardId, content.cardVersion, content.markdownRevision)
        writer.write(File(target, DRAFT_FILE_NAME), draft.toJsonForRepository().toString().toByteArray(Charsets.UTF_8))
        return content.copy(draftState = draft)
    }

    private fun readContent(revisionDirectory: File): NoteContent? = runCatching {
        val json = File(revisionDirectory, CONTENT_FILE_NAME)
        val markdown = File(revisionDirectory, MARKDOWN_FILE_NAME)
        val commit = File(revisionDirectory, COMMIT_FILE_NAME)
        if (!json.isFile || !markdown.isFile || !commit.isFile) return null
        val content = JSONObject(json.readText(Charsets.UTF_8)).toNoteContent()
        val commitJson = JSONObject(commit.readText(Charsets.UTF_8))
        require(commitJson.optLong("markdown_revision", -1L) == content.markdownRevision)
        require(commitJson.optString("markdown_fingerprint") == NoteContent.markdownFingerprint(content.markdown))
        require(markdown.readText(Charsets.UTF_8) == content.markdown)
        val draft = File(revisionDirectory, DRAFT_FILE_NAME).takeIf(File::isFile)
            ?.let { JSONObject(it.readText(Charsets.UTF_8)).toDraftForRepository() }
        content.copy(draftState = draft)
    }.getOrNull()

    private fun commitJson(content: NoteContent) = JSONObject()
        .put("markdown_revision", content.markdownRevision)
        .put("markdown_fingerprint", NoteContent.markdownFingerprint(content.markdown))

    private fun cardDirectory(cardId: KnowledgeCardId): File =
        File(directory, KnowledgeCard.sha256(cardId.value.toByteArray(Charsets.UTF_8)))

    private fun revisionDirectory(cardId: KnowledgeCardId, cardVersion: String, revision: Long): File =
        File(
            File(cardDirectory(cardId), KnowledgeCard.sha256(cardVersion.toByteArray(Charsets.UTF_8))),
            "r-$revision",
        )

    private fun message(error: Throwable): String = error.message ?: error::class.java.simpleName

    private companion object {
        const val CONTENT_FILE_NAME = "content.json"
        const val MARKDOWN_FILE_NAME = "note.md"
        const val COMMIT_FILE_NAME = "commit.json"
        const val DRAFT_FILE_NAME = "draft.json"
    }
}

private fun NoteDraftState.toJsonForRepository() = JSONObject()
    .put("markdown", markdown)
    .put("base_markdown_revision", baseMarkdownRevision)
    .put("content_fingerprint", contentFingerprint)
    .put("updated_at_epoch_ms", updatedAtEpochMs)

private fun JSONObject.toDraftForRepository() = NoteDraftState(
    markdown = optString("markdown"),
    baseMarkdownRevision = optLong("base_markdown_revision", 0L),
    contentFingerprint = optString("content_fingerprint"),
    updatedAtEpochMs = optLong("updated_at_epoch_ms", 0L),
)

private object DefaultNoteContentAtomicWriter : NoteContentAtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        check(target.parentFile?.isDirectory == true) { "Cannot create content directory: ${target.parentFile}" }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
