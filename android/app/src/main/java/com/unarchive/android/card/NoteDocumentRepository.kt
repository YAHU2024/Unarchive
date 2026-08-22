package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The phase at which a note save stopped, if it did not fully complete. */
enum class NoteDocumentSavePhase {
    STRUCTURED,
    MARKDOWN_PROJECTION,
    MARKDOWN,
    PUBLISHING_METADATA,
    COMPLETE,
}

/** Explicit outcome for a note save. The structured document is authoritative. */
data class NoteDocumentSaveResult(
    val document: NoteDocument,
    val phase: NoteDocumentSavePhase,
    val markdown: String? = null,
    val error: String? = null,
) {
    val structuredPersisted: Boolean get() = phase != NoteDocumentSavePhase.STRUCTURED || error == null
    val markdownPersisted: Boolean get() = phase == NoteDocumentSavePhase.PUBLISHING_METADATA || phase == NoteDocumentSavePhase.COMPLETE
    val isComplete: Boolean get() = phase == NoteDocumentSavePhase.COMPLETE && error == null
}

/** Injectable for deterministic failure tests and future encrypted storage. */
fun interface NoteDocumentAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

interface NoteDocumentRepository {
    fun list(): List<NoteDocument>
    fun listVersions(cardId: KnowledgeCardId): List<NoteDocument>
    fun find(cardId: KnowledgeCardId, cardVersion: String? = null): NoteDocument?
    fun save(document: NoteDocument): NoteDocumentSaveResult
}

/**
 * Versioned file-backed note store. JSON is the editing source; Markdown is a
 * rebuildable projection and is intentionally written after JSON succeeds.
 */
class FileNoteDocumentRepository(
    private val directory: File,
    private val projector: (NoteDocument) -> String = NoteMarkdownProjection::render,
    private val writer: NoteDocumentAtomicWriter = DefaultNoteDocumentAtomicWriter,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : NoteDocumentRepository {
    override fun list(): List<NoteDocument> = synchronized(this) {
        directory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .flatMap { cardDirectory ->
                cardDirectory.listFiles { file -> file.isDirectory }
                    .orEmpty()
                    .mapNotNull { versionDirectory -> readDocument(File(versionDirectory, JSON_FILE_NAME)) }
            }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override fun listVersions(cardId: KnowledgeCardId): List<NoteDocument> = synchronized(this) {
        cardDirectory(cardId).listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { versionDirectory -> readDocument(File(versionDirectory, JSON_FILE_NAME)) }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override fun find(cardId: KnowledgeCardId, cardVersion: String?): NoteDocument? = synchronized(this) {
        val versions = if (cardVersion == null) {
            cardDirectory(cardId).listFiles { file -> file.isDirectory }
                .orEmpty()
                .sortedByDescending { it.name }
        } else {
            listOf(versionDirectory(cardId, cardVersion))
        }
        versions.asSequence()
            .map { File(it, JSON_FILE_NAME) }
            .mapNotNull(::readDocument)
            .maxByOrNull { it.updatedAtEpochMs }
    }

    override fun save(document: NoteDocument): NoteDocumentSaveResult = synchronized(this) {
        val existing = find(document.cardId, document.generation.cardVersion)
        val prepared = prepareForSave(document, existing)
        val jsonTarget = File(versionDirectory(prepared.cardId, prepared.generation.cardVersion), JSON_FILE_NAME)

        try {
            writer.write(jsonTarget, prepared.toJson().toString().toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            return@synchronized NoteDocumentSaveResult(
                document = prepared,
                phase = NoteDocumentSavePhase.STRUCTURED,
                error = error.message ?: error::class.java.simpleName,
            )
        }

        val markdown = try {
            projector(prepared)
        } catch (error: Throwable) {
            return@synchronized projectionFailure(prepared, jsonTarget, error)
        }

        val markdownTarget = File(jsonTarget.parentFile, MARKDOWN_FILE_NAME)
        try {
            writer.write(markdownTarget, markdown.toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            return@synchronized failureResult(
                prepared,
                jsonTarget,
                NoteDocumentSavePhase.MARKDOWN,
                markdown,
                error,
            )
        }

        val published = prepared.copy(
            publishing = prepared.publishing.copy(
                markdownProjectionHash = sha256(markdown.toByteArray(Charsets.UTF_8)),
                updatedAtEpochMs = prepared.updatedAtEpochMs,
            ),
        )
        try {
            writer.write(jsonTarget, published.toJson().toString().toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            return@synchronized failureResult(
                prepared,
                jsonTarget,
                NoteDocumentSavePhase.PUBLISHING_METADATA,
                markdown,
                error,
            )
        }
        NoteDocumentSaveResult(published, NoteDocumentSavePhase.COMPLETE, markdown)
    }

    private fun projectionFailure(
        document: NoteDocument,
        jsonTarget: File,
        error: Throwable,
    ): NoteDocumentSaveResult = failureResult(
        document,
        jsonTarget,
        NoteDocumentSavePhase.MARKDOWN_PROJECTION,
        null,
        error,
    )

    /** Record a projection/write error without discarding the already saved content. */
    private fun failureResult(
        document: NoteDocument,
        jsonTarget: File,
        phase: NoteDocumentSavePhase,
        markdown: String?,
        error: Throwable,
    ): NoteDocumentSaveResult {
        val message = error.message ?: error::class.java.simpleName
        val failedDocument = document.copy(
            editing = document.editing.copy(lastSaveError = message),
        )
        val persistedFailureState = runCatching {
            writer.write(jsonTarget, failedDocument.toJson().toString().toByteArray(Charsets.UTF_8))
        }.isSuccess
        return NoteDocumentSaveResult(
            document = if (persistedFailureState) failedDocument else document,
            phase = phase,
            markdown = markdown,
            error = message,
        )
    }

    private fun prepareForSave(document: NoteDocument, existing: NoteDocument?): NoteDocument {
        val contentChanged = existing == null || contentFingerprint(existing) != contentFingerprint(document)
        val revision = when {
            existing == null -> document.editing.contentRevision
            contentChanged -> maxOf(existing.editing.contentRevision + 1L, document.editing.contentRevision)
            else -> maxOf(existing.editing.contentRevision, document.editing.contentRevision)
        }
        val now = maxOf(nowEpochMs(), document.updatedAtEpochMs, document.createdAtEpochMs)
        return document.copy(
            editing = document.editing.copy(
                contentRevision = revision,
                dirty = false,
                lastSavedAtEpochMs = now,
                lastSaveError = null,
            ),
            updatedAtEpochMs = now,
        )
    }

    private fun contentFingerprint(document: NoteDocument): String = sha256(
        document.toJson(includeMutableState = false).toString().toByteArray(Charsets.UTF_8),
    )

    private fun cardDirectory(cardId: KnowledgeCardId): File =
        File(directory, sha256(cardId.value.toByteArray(Charsets.UTF_8)))

    private fun versionDirectory(cardId: KnowledgeCardId, cardVersion: String): File =
        File(cardDirectory(cardId), sha256(cardVersion.toByteArray(Charsets.UTF_8)))

    private fun readDocument(file: File): NoteDocument? = runCatching {
        if (!file.isFile) return null
        JSONObject(file.readText(Charsets.UTF_8)).toNoteDocument()
    }.getOrNull()

    private companion object {
        const val JSON_FILE_NAME = "note.json"
        const val MARKDOWN_FILE_NAME = "note.md"
    }
}

private object DefaultNoteDocumentAtomicWriter : NoteDocumentAtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        check(target.parentFile?.isDirectory == true) { "Cannot create note directory: ${target.parentFile}" }
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

private fun NoteDocument.toJson(includeMutableState: Boolean = true): JSONObject = JSONObject()
    .put("schema_version", NoteDocument.SCHEMA_VERSION)
    .put("platform", cardId.platform)
    .put("video_id", cardId.videoId)
    .put("title", title)
    .put("source", source.toJson())
    .put("source_transcript", JSONArray().apply { sourceTranscript.forEach { put(it.toJson()) } })
    .put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
    .put("tags", JSONArray(tags))
    .put("relations", JSONArray().apply { relations.forEach { put(it.toJson()) } })
    .put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })
    .put("generation", generation.toJson())
    .apply {
        if (includeMutableState) {
            put("created_at_epoch_ms", createdAtEpochMs)
            put("updated_at_epoch_ms", updatedAtEpochMs)
        }
        if (includeMutableState) {
            put("editing", editing.toJson())
            put("publishing", publishing.toJson())
        }
    }

private fun JSONObject.toNoteDocument(): NoteDocument {
    require(getInt("schema_version") == NoteDocument.SCHEMA_VERSION) { "Unsupported note schema" }
    val sourceJson = getJSONObject("source")
    val blocksJson = getJSONArray("blocks")
    val relationsJson = getJSONArray("relations")
    val assetsJson = getJSONArray("assets")
    return NoteDocument(
        cardId = KnowledgeCardId(getString("platform"), getString("video_id")),
        title = getString("title"),
        source = sourceJson.toNoteSource(),
        sourceTranscript = getJSONArray("source_transcript").toTranscriptSegments(),
        blocks = buildList { repeat(blocksJson.length()) { add(blocksJson.getJSONObject(it).toNoteBlock()) } },
        tags = jsonStringList(optJSONArray("tags")),
        relations = buildList { repeat(relationsJson.length()) { add(relationsJson.getJSONObject(it).toCardRelation()) } },
        assets = buildList { repeat(assetsJson.length()) { add(assetsJson.getJSONObject(it).toCardAsset()) } },
        generation = getJSONObject("generation").toNoteGeneration(),
        editing = optJSONObject("editing")?.toNoteEditingState() ?: NoteEditingState(),
        publishing = optJSONObject("publishing")?.toNotePublishingState() ?: NotePublishingState(),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
    )
}

private fun NoteSource.toJson() = JSONObject()
    .put("canonical_url", canonicalUrl).put("owner_name", ownerName)
    .put("duration_ms", durationMs).put("timing_accuracy", timingAccuracy.name)

private fun JSONObject.toNoteSource() = NoteSource(
    canonicalUrl = getString("canonical_url"), ownerName = optString("owner_name"),
    durationMs = getLong("duration_ms"),
    timingAccuracy = enumOrDefault("timing_accuracy", TranscriptTimingAccuracy.EXACT),
)

private fun NoteBlock.toJson() = JSONObject()
    .put("id", id).put("type", type.name).put("origin", origin.name)
    .put("text", text).put("title", title)
    .put("start_ms", startMs).put("end_ms", endMs)
    .put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
    .put("source_ref", sourceRef?.toJson())
    .put("asset_refs", JSONArray(assetRefs)).put("tags", JSONArray(tags))

private fun JSONObject.toNoteBlock(): NoteBlock = NoteBlock(
    id = getString("id"), type = enumOrDefault("type", NoteBlockType.USER_NOTE),
    origin = enumOrDefault("origin", NoteBlockOrigin.USER), text = optString("text"),
    title = optString("title"), startMs = nullableLong("start_ms"), endMs = nullableLong("end_ms"),
    points = getJSONArray("points").toNotePoints(),
    sourceRef = optJSONObject("source_ref")?.toNoteSourceRef(),
    assetRefs = jsonStringList(optJSONArray("asset_refs")), tags = jsonStringList(optJSONArray("tags")),
)

private fun NoteSourceRef.toJson() = JSONObject()
    .put("url", url).put("start_ms", startMs).put("end_ms", endMs)
    .put("timing_accuracy", timingAccuracy.name)

private fun JSONObject.toNoteSourceRef() = NoteSourceRef(
    url = getString("url"), startMs = nullableLong("start_ms"), endMs = nullableLong("end_ms"),
    timingAccuracy = enumOrDefault("timing_accuracy", TranscriptTimingAccuracy.EXACT),
)

private fun NotePoint.toJson() = JSONObject().put("id", id).put("timestamp_ms", timestampMs).put("text", text)

private fun JSONArray.toNotePoints() = buildList {
    repeat(length()) { add(getJSONObject(it).toNotePoint()) }
}

private fun JSONObject.toNotePoint() = NotePoint(getString("id"), getLong("timestamp_ms"), getString("text"))

private fun NoteGeneration.toJson() = JSONObject()
    .put("card_version", cardVersion).put("model", model).put("signature", signature)
    .put("state", state.name).put("base_state", baseState.name)
    .put("analysis_state", analysisState.name).put("screenshots_state", screenshotsState.name)
    .put("last_error", lastError)

private fun JSONObject.toNoteGeneration() = NoteGeneration(
    cardVersion = getString("card_version"), model = nullableString("model"), signature = nullableString("signature"),
    state = enumOrDefault("state", NoteGenerationState.NOT_STARTED),
    baseState = enumOrDefault("base_state", CardStageState.QUEUED),
    analysisState = enumOrDefault("analysis_state", CardStageState.QUEUED),
    screenshotsState = enumOrDefault("screenshots_state", CardStageState.QUEUED),
    lastError = nullableString("last_error"),
)

private fun NoteEditingState.toJson() = JSONObject()
    .put("content_revision", contentRevision).put("dirty", dirty)
    .put("last_saved_at_epoch_ms", lastSavedAtEpochMs).put("last_save_error", lastSaveError)

private fun JSONObject.toNoteEditingState() = NoteEditingState(
    contentRevision = optLong("content_revision", 0L), dirty = optBoolean("dirty", false),
    lastSavedAtEpochMs = nullableLong("last_saved_at_epoch_ms"), lastSaveError = nullableString("last_save_error"),
)

private fun NotePublishingState.toJson() = JSONObject()
    .put("markdown_projection_hash", markdownProjectionHash).put("updated_at_epoch_ms", updatedAtEpochMs)

private fun JSONObject.toNotePublishingState() = NotePublishingState(
    markdownProjectionHash = optString("markdown_projection_hash"),
    updatedAtEpochMs = optLong("updated_at_epoch_ms", 0L),
)

private fun TranscriptSegment.toJson() = JSONObject().put("start_ms", startMs).put("end_ms", endMs).put("text", text)

private fun JSONArray.toTranscriptSegments() = buildList {
    repeat(length()) { add(getJSONObject(it).toTranscriptSegment()) }
}

private fun JSONObject.toTranscriptSegment() = TranscriptSegment(getLong("start_ms"), getLong("end_ms"), getString("text"))

private fun CardAsset.toJson() = JSONObject()
    .put("asset_id", assetId).put("kind", kind.name).put("mime_type", mimeType)
    .put("relative_path", relativePath).put("byte_count", byteCount).put("sha256", sha256)
    .put("chapter_index", chapterIndex).put("timestamp_ms", timestampMs)

private fun JSONObject.toCardAsset() = CardAsset(
    assetId = getString("asset_id"), kind = enumOrDefault("kind", CardAssetKind.OTHER),
    mimeType = getString("mime_type"), relativePath = getString("relative_path"),
    byteCount = getLong("byte_count"), sha256 = getString("sha256"),
    chapterIndex = nullableInt("chapter_index"), timestampMs = nullableLong("timestamp_ms"),
)

private fun CardRelation.toJson() = JSONObject()
    .put("relation_id", relationId).put("type", type.name).put("target_id", targetId)
    .put("label", label).put("created_at_epoch_ms", createdAtEpochMs)

private fun JSONObject.toCardRelation() = CardRelation(
    relationId = getString("relation_id"), type = enumOrDefault("type", CardRelationType.USER_LINK),
    targetId = getString("target_id"), label = optString("label"), createdAtEpochMs = getLong("created_at_epoch_ms"),
)

private fun jsonStringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) { array.optString(it).trim().takeIf(String::isNotEmpty)?.let(::add) }
}

private inline fun <reified T : Enum<T>> JSONObject.enumOrDefault(name: String, fallback: T): T =
    runCatching { enumValueOf<T>(optString(name)) }.getOrDefault(fallback)

private fun JSONObject.nullableString(name: String): String? =
    takeUnless { isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() }

private fun JSONObject.nullableLong(name: String): Long? =
    takeUnless { isNull(name) }?.optLong(name)

private fun JSONObject.nullableInt(name: String): Int? =
    takeUnless { isNull(name) }?.optInt(name)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
