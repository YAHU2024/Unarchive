package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.result.VideoResultKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle of one locally persisted card stage. */
enum class CardStageState { QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED, PARTIAL }

enum class CardAssetKind { CHAPTER_SCREENSHOT, OTHER }

data class CardAsset(
    val assetId: String,
    val kind: CardAssetKind,
    val mimeType: String,
    val relativePath: String,
    val byteCount: Long,
    val sha256: String,
    val chapterIndex: Int? = null,
    val timestampMs: Long? = null,
)

enum class CardRelationType { USER_LINK, TAG, SOURCE_VIDEO, FAVORITE_FOLDER }

data class CardRelation(
    val relationId: String,
    val type: CardRelationType,
    val targetId: String,
    val label: String,
    val createdAtEpochMs: Long,
)

/** Stable local card identity. Titles and generated text are intentionally excluded. */
data class KnowledgeCardId(
    val platform: String,
    val videoId: String,
) {
    init {
        require(platform.isNotBlank()) { "platform cannot be blank" }
        require(videoId.isNotBlank()) { "videoId cannot be blank" }
    }

    val value: String get() = "$platform:$videoId"

    companion object {
        fun from(key: VideoResultKey) = KnowledgeCardId(key.platform, key.videoId)
    }
}

/**
 * The local source-of-truth representation of a knowledge card.
 *
 * Markdown is kept alongside this structured record. The structured fields are
 * deliberately sufficient to rebuild the Markdown and indexes later.
 */
data class KnowledgeCard(
    val cardId: KnowledgeCardId,
    val cardVersion: String,
    val canonicalUrl: String,
    val title: String,
    val ownerName: String,
    val videoDurationSeconds: Long,
    val timingAccuracy: TranscriptTimingAccuracy,
    val transcript: List<TranscriptSegment>,
    val analysis: CardAnalysis? = null,
    val tags: List<String> = emptyList(),
    val assets: List<CardAsset> = emptyList(),
    val relations: List<CardRelation> = emptyList(),
    val baseState: CardStageState = CardStageState.SUCCEEDED,
    val analysisState: CardStageState = CardStageState.QUEUED,
    val screenshotsState: CardStageState = CardStageState.QUEUED,
    val lastError: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val markdown: String,
) {
    init {
        require(cardVersion.isNotBlank()) { "cardVersion cannot be blank" }
        require(canonicalUrl.isNotBlank()) { "canonicalUrl cannot be blank" }
        require(markdown.isNotBlank()) { "markdown cannot be blank" }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Computes a deterministic version from card content and generation inputs. */
        fun version(
            canonicalUrl: String,
            title: String,
            ownerName: String,
            transcript: List<TranscriptSegment>,
            analysis: CardAnalysis?,
            tags: List<String>,
            generationSignature: String,
            assetHashes: List<String> = emptyList(),
        ): String {
            val normalized = buildString {
                append(canonicalUrl.trim()).append('\n')
                append(title.trim()).append('\n')
                append(ownerName.trim()).append('\n')
                transcript.forEach {
                    append(it.startMs).append('|').append(it.endMs).append('|').append(it.text.trim()).append('\n')
                }
                analysis?.let {
                    append(it.summary.trim()).append('\n')
                    it.keyPoints.forEach { point -> append(point.trim()).append('\n') }
                    it.chapters.forEach { chapter ->
                        append(chapter.startMs).append('|').append(chapter.endMs).append('|').append(chapter.title.trim()).append('\n')
                        chapter.points.forEach { point ->
                            append(point.timestampMs).append('|').append(point.text.trim()).append('\n')
                        }
                    }
                }
                tags.map(String::trim).filter(String::isNotEmpty).sorted().forEach { append(it).append('\n') }
                assetHashes.map(String::trim).filter(String::isNotEmpty).sorted().forEach { append(it).append('\n') }
                append(generationSignature.trim())
            }
            return sha256(normalized.toByteArray(Charsets.UTF_8))
        }

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

interface KnowledgeCardRepository {
    fun list(): List<KnowledgeCard>
    fun listVersions(cardId: KnowledgeCardId): List<KnowledgeCard>
    fun find(cardId: KnowledgeCardId, cardVersion: String? = null): KnowledgeCard?
    fun save(card: KnowledgeCard): KnowledgeCard
    fun saveAsset(
        cardId: KnowledgeCardId,
        cardVersion: String,
        assetId: String,
        bytes: ByteArray,
        kind: CardAssetKind = CardAssetKind.CHAPTER_SCREENSHOT,
        mimeType: String = "image/jpeg",
        chapterIndex: Int? = null,
        timestampMs: Long? = null,
    ): CardAsset
    fun assetFile(card: KnowledgeCard, asset: CardAsset): File?
}

/** File-backed local card store. Content is durable under filesDir, not cacheDir. */
class FileKnowledgeCardRepository(private val directory: File) : KnowledgeCardRepository {
    override fun list(): List<KnowledgeCard> = synchronized(this) {
        directory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { cardDirectory ->
                cardDirectory.listFiles { file -> file.isDirectory }
                    .orEmpty()
                    .mapNotNull { versionDirectory -> readCard(File(versionDirectory, CARD_FILE_NAME)) }
                    .maxByOrNull { it.updatedAtEpochMs }
            }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override fun listVersions(cardId: KnowledgeCardId): List<KnowledgeCard> = synchronized(this) {
        val cardDirectory = File(directory, folderName(cardId))
        cardDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { versionDirectory -> readCard(File(versionDirectory, CARD_FILE_NAME)) }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override fun find(cardId: KnowledgeCardId, cardVersion: String?): KnowledgeCard? = synchronized(this) {
        val cardDirectory = File(directory, folderName(cardId))
        val versions = if (cardVersion == null) {
            cardDirectory.listFiles { file -> file.isDirectory }.orEmpty().sortedByDescending { it.name }
        } else {
            listOf(versionDirectory(cardId, cardVersion))
        }
        versions.asSequence()
            .map { File(it, CARD_FILE_NAME) }
            .mapNotNull(::readCard)
            .maxByOrNull { it.updatedAtEpochMs }
    }

    override fun save(card: KnowledgeCard): KnowledgeCard = synchronized(this) {
        val targetDirectory = versionDirectory(card.cardId, card.cardVersion)
        targetDirectory.mkdirs()
        check(targetDirectory.isDirectory) { "Cannot create card directory: $targetDirectory" }
        atomicWrite(File(targetDirectory, MARKDOWN_FILE_NAME), card.markdown)
        atomicWrite(File(targetDirectory, CARD_FILE_NAME), card.toJson().toString())
        card
    }

    override fun saveAsset(
        cardId: KnowledgeCardId,
        cardVersion: String,
        assetId: String,
        bytes: ByteArray,
        kind: CardAssetKind,
        mimeType: String,
        chapterIndex: Int?,
        timestampMs: Long?,
    ): CardAsset = synchronized(this) {
        require(assetId.isNotBlank()) { "assetId cannot be blank" }
        require(bytes.isNotEmpty()) { "asset bytes cannot be empty" }
        require(mimeType == "image/jpeg") { "Only JPEG card assets are supported in A0" }
        val cardDirectory = versionDirectory(cardId, cardVersion)
        check(File(cardDirectory, CARD_FILE_NAME).isFile) { "Card version does not exist: $cardId@$cardVersion" }
        val assetsDirectory = File(cardDirectory, ASSETS_DIRECTORY)
        assetsDirectory.mkdirs()
        val fileName = "${KnowledgeCard.sha256(assetId.toByteArray(Charsets.UTF_8))}.jpg"
        val target = File(assetsDirectory, fileName)
        atomicWriteBytes(target, bytes)
        CardAsset(
            assetId = assetId,
            kind = kind,
            mimeType = mimeType,
            relativePath = "$ASSETS_DIRECTORY/$fileName",
            byteCount = bytes.size.toLong(),
            sha256 = KnowledgeCard.sha256(bytes),
            chapterIndex = chapterIndex,
            timestampMs = timestampMs,
        )
    }

    override fun assetFile(card: KnowledgeCard, asset: CardAsset): File? {
        val cardDirectory = versionDirectory(card.cardId, card.cardVersion)
        val candidate = File(cardDirectory, asset.relativePath)
        val root = cardDirectory.canonicalFile
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!resolved.toPath().startsWith(root.toPath())) return null
        return resolved.takeIf { it.isFile }
    }

    private fun versionDirectory(cardId: KnowledgeCardId, version: String): File =
        File(
            File(directory, folderName(cardId)),
            KnowledgeCard.sha256(version.toByteArray(Charsets.UTF_8)),
        )

    private fun folderName(cardId: KnowledgeCardId): String =
        KnowledgeCard.sha256(cardId.value.toByteArray(Charsets.UTF_8))

    private fun readCard(file: File): KnowledgeCard? = runCatching {
        if (!file.isFile) return null
        JSONObject(file.readText(Charsets.UTF_8)).toKnowledgeCard(
            File(file.parentFile ?: error("Card JSON has no parent"), "note.md").readText(Charsets.UTF_8),
        )
    }.getOrNull()

    private fun atomicWrite(target: File, content: String) {
        atomicWriteBytes(target, content.toByteArray(Charsets.UTF_8))
    }

    private fun atomicWriteBytes(target: File, content: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(content)
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

    private companion object {
        const val CARD_FILE_NAME = "card.json"
        const val MARKDOWN_FILE_NAME = "note.md"
        const val ASSETS_DIRECTORY = "assets"

    }
}

enum class KnowledgeSyncState {
    NOT_SYNCED, CREATING, CREATED, ASSOCIATING, SYNCED,
    RETRYABLE_FAILURE, PERMANENT_FAILURE, BLOCKED,
}

data class KnowledgeSyncKey(
    val cardId: KnowledgeCardId,
    val cardVersion: String,
    val targetType: String,
    val targetId: String,
    val folderId: String = "",
) {
    init {
        require(cardVersion.isNotBlank()) { "cardVersion cannot be blank" }
        require(targetType.isNotBlank()) { "targetType cannot be blank" }
        require(targetId.isNotBlank()) { "targetId cannot be blank" }
    }
}

data class KnowledgeSyncRecord(
    val key: KnowledgeSyncKey,
    val state: KnowledgeSyncState = KnowledgeSyncState.NOT_SYNCED,
    val remoteNoteId: String? = null,
    val kbAdded: Boolean = false,
    val lastError: String? = null,
    val updatedAtEpochMs: Long,
)

interface KnowledgeSyncRepository {
    fun list(): List<KnowledgeSyncRecord>
    fun find(key: KnowledgeSyncKey): KnowledgeSyncRecord?
    fun save(record: KnowledgeSyncRecord): KnowledgeSyncRecord
}

/** Atomic JSON state store isolated by card version and sync target. */
class FileKnowledgeSyncRepository(private val file: File) : KnowledgeSyncRepository {
    override fun list(): List<KnowledgeSyncRecord> = synchronized(this) {
        val json = loadJson()
        buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val value = json.optJSONObject(keys.next()) ?: continue
                runCatching { value.toSyncRecord() }.getOrNull()?.let(::add)
            }
        }
    }

    override fun find(key: KnowledgeSyncKey): KnowledgeSyncRecord? = synchronized(this) {
        loadJson().optJSONObject(key.storageKey())?.toSyncRecord()
    }

    override fun save(record: KnowledgeSyncRecord): KnowledgeSyncRecord = synchronized(this) {
        val json = loadJson().put(record.key.storageKey(), record.toJson())
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString(), Charsets.UTF_8)
        try {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        record
    }

    private fun loadJson(): JSONObject = runCatching {
        if (!file.isFile) JSONObject() else JSONObject(file.readText(Charsets.UTF_8))
    }.getOrElse { JSONObject() }
}

private fun KnowledgeCard.toJson() = JSONObject()
    .put("schema_version", KnowledgeCard.SCHEMA_VERSION)
    .put("card_id", cardId.value)
    .put("platform", cardId.platform)
    .put("video_id", cardId.videoId)
    .put("card_version", cardVersion)
    .put("canonical_url", canonicalUrl)
    .put("title", title)
    .put("owner_name", ownerName)
    .put("video_duration_seconds", videoDurationSeconds)
    .put("timing_accuracy", timingAccuracy.name)
    .put("transcript", JSONArray().apply { transcript.forEach { put(it.toJson()) } })
    .put("analysis", analysis?.toJson())
    .put("tags", JSONArray(tags))
    .put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })
    .put("relations", JSONArray().apply { relations.forEach { put(it.toJson()) } })
    .put("base_state", baseState.name)
    .put("analysis_state", analysisState.name)
    .put("screenshots_state", screenshotsState.name)
    .put("last_error", lastError)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("updated_at_epoch_ms", updatedAtEpochMs)
    .put("markdown_sha256", KnowledgeCard.sha256(markdown.toByteArray(Charsets.UTF_8)))

private fun JSONObject.toKnowledgeCard(markdown: String): KnowledgeCard {
    require(getInt("schema_version") == KnowledgeCard.SCHEMA_VERSION) { "Unsupported card schema" }
    require(
        getString("markdown_sha256") == KnowledgeCard.sha256(markdown.toByteArray(Charsets.UTF_8)),
    ) { "Card Markdown checksum mismatch" }
    val transcriptJson = getJSONArray("transcript")
    val transcript = buildList {
        repeat(transcriptJson.length()) { add(transcriptJson.getJSONObject(it).toTranscriptSegment()) }
    }
    fun <T> enumOrDefault(name: String, fallback: T, values: Array<T>): T =
        values.firstOrNull { it.toString() == optString(name) } ?: fallback
    return KnowledgeCard(
        cardId = KnowledgeCardId(getString("platform"), getString("video_id")),
        cardVersion = getString("card_version"),
        canonicalUrl = getString("canonical_url"),
        title = getString("title"),
        ownerName = optString("owner_name"),
        videoDurationSeconds = getLong("video_duration_seconds"),
        timingAccuracy = runCatching { TranscriptTimingAccuracy.valueOf(optString("timing_accuracy")) }
            .getOrDefault(TranscriptTimingAccuracy.EXACT),
        transcript = transcript,
        analysis = optJSONObject("analysis")?.toCardAnalysis(getLong("video_duration_seconds") * 1_000L),
        tags = jsonStringList(optJSONArray("tags")),
        assets = jsonAssetList(optJSONArray("assets")),
        relations = jsonRelationList(optJSONArray("relations")),
        baseState = enumOrDefault("base_state", CardStageState.FAILED, CardStageState.entries.toTypedArray()),
        analysisState = enumOrDefault("analysis_state", CardStageState.QUEUED, CardStageState.entries.toTypedArray()),
        screenshotsState = enumOrDefault("screenshots_state", CardStageState.QUEUED, CardStageState.entries.toTypedArray()),
        lastError = optString("last_error").takeIf { it.isNotBlank() },
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
        markdown = markdown,
    )
}

private fun TranscriptSegment.toJson() = JSONObject().put("start_ms", startMs).put("end_ms", endMs).put("text", text)
private fun JSONObject.toTranscriptSegment() = TranscriptSegment(getLong("start_ms"), getLong("end_ms"), getString("text"))

private fun CardAsset.toJson() = JSONObject()
    .put("asset_id", assetId).put("kind", kind.name).put("mime_type", mimeType).put("relative_path", relativePath)
    .put("byte_count", byteCount).put("sha256", sha256).put("chapter_index", chapterIndex).put("timestamp_ms", timestampMs)

private fun CardRelation.toJson() = JSONObject()
    .put("relation_id", relationId).put("type", type.name).put("target_id", targetId)
    .put("label", label).put("created_at_epoch_ms", createdAtEpochMs)

private fun KnowledgeSyncKey.storageKey(): String = listOf(
    cardId.platform, cardId.videoId, cardVersion, targetType, targetId, folderId,
).joinToString(separator = "") { value -> "${value.length}:$value" }
    .toByteArray(Charsets.UTF_8)
    .let(KnowledgeCard::sha256)

private fun KnowledgeSyncRecord.toJson() = JSONObject()
    .put("platform", key.cardId.platform).put("video_id", key.cardId.videoId)
    .put("card_version", key.cardVersion).put("target_type", key.targetType)
    .put("target_id", key.targetId).put("folder_id", key.folderId)
    .put("state", state.name).put("remote_note_id", remoteNoteId).put("kb_added", kbAdded)
    .put("last_error", lastError).put("updated_at_epoch_ms", updatedAtEpochMs)

private fun JSONObject.toSyncRecord() = KnowledgeSyncRecord(
    key = KnowledgeSyncKey(
        cardId = KnowledgeCardId(getString("platform"), getString("video_id")),
        cardVersion = getString("card_version"), targetType = getString("target_type"),
        targetId = getString("target_id"), folderId = optString("folder_id"),
    ),
    state = runCatching { KnowledgeSyncState.valueOf(optString("state")) }.getOrDefault(KnowledgeSyncState.NOT_SYNCED),
    remoteNoteId = optString("remote_note_id").takeIf { it.isNotBlank() },
    kbAdded = optBoolean("kb_added", false),
    lastError = optString("last_error").takeIf { it.isNotBlank() },
    updatedAtEpochMs = optLong("updated_at_epoch_ms", 0L),
)

private fun jsonStringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) { array.optString(it).trim().takeIf(String::isNotEmpty)?.let(::add) }
}

private fun jsonAssetList(array: JSONArray?): List<CardAsset> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) {
        val item = array.optJSONObject(it) ?: return@repeat
        add(CardAsset(
            assetId = item.getString("asset_id"),
            kind = runCatching { CardAssetKind.valueOf(item.optString("kind")) }.getOrDefault(CardAssetKind.OTHER),
            mimeType = item.optString("mime_type", "image/jpeg"),
            relativePath = item.getString("relative_path"), byteCount = item.getLong("byte_count"),
            sha256 = item.getString("sha256"), chapterIndex = item.optInt("chapter_index").takeIf { !item.isNull("chapter_index") },
            timestampMs = item.optLong("timestamp_ms").takeIf { !item.isNull("timestamp_ms") },
        ))
    }
}

private fun jsonRelationList(array: JSONArray?): List<CardRelation> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) {
        val item = array.optJSONObject(it) ?: return@repeat
        add(CardRelation(
            relationId = item.getString("relation_id"),
            type = runCatching { CardRelationType.valueOf(item.optString("type")) }.getOrDefault(CardRelationType.USER_LINK),
            targetId = item.getString("target_id"), label = item.optString("label"),
            createdAtEpochMs = item.getLong("created_at_epoch_ms"),
        ))
    }
}

private fun CardAnalysis.toJson() = JSONObject()
    .put("summary", summary).put("key_points", JSONArray(keyPoints))
    .put("chapters", JSONArray().apply { chapters.forEach { put(it.toJson()) } })

private fun CardChapter.toJson() = JSONObject()
    .put("title", title).put("start_ms", startMs).put("end_ms", endMs)
    .put("points", JSONArray().apply { points.forEach { put(JSONObject().put("timestamp_ms", it.timestampMs).put("text", it.text)) } })

private fun JSONObject.toCardAnalysis(durationMs: Long): CardAnalysis = CardAnalysis.fromJson(this, durationMs)
