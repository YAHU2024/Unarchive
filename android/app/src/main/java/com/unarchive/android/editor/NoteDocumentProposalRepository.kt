package com.unarchive.android.editor

import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.toJson
import com.unarchive.android.card.toNoteDocument
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable boundary for a pending AI proposal. Only pending proposals are stored. */
interface NoteDocumentProposalRepository {
    fun find(baseCardId: String, baseCardVersion: String): NoteDocumentProposal?

    fun save(proposal: NoteDocumentProposal)

    fun delete(baseCardId: String, baseCardVersion: String)
}

fun interface NoteDocumentProposalAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

class FileNoteDocumentProposalRepository(
    private val directory: File,
    private val writer: NoteDocumentProposalAtomicWriter = DefaultNoteDocumentProposalAtomicWriter,
) : NoteDocumentProposalRepository {
    override fun find(baseCardId: String, baseCardVersion: String): NoteDocumentProposal? = synchronized(this) {
        runCatching {
            val file = proposalFile(baseCardId, baseCardVersion)
            if (!file.isFile) return@synchronized null
            JSONObject(file.readText(Charsets.UTF_8)).toProposal()
        }.getOrNull()
    }

    override fun save(proposal: NoteDocumentProposal) = synchronized(this) {
        require(proposal.status == NoteProposalStatus.PENDING) {
            "Only pending proposals can be persisted"
        }
        val target = proposalFile(proposal.baseCardId, proposal.baseCardVersion)
        writer.write(target, proposal.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    override fun delete(baseCardId: String, baseCardVersion: String) {
        synchronized(this) {
            proposalFile(baseCardId, baseCardVersion).delete()
        }
    }

    private fun proposalFile(baseCardId: String, baseCardVersion: String): File =
        File(directory, "${sha256("$baseCardId|$baseCardVersion")}.json")
}

private const val PROPOSAL_SCHEMA_VERSION = 1

private fun NoteDocumentProposal.toJson() = JSONObject()
    .put("schema_version", PROPOSAL_SCHEMA_VERSION)
    .put("proposal_id", proposalId)
    .put("base_card_id", baseCardId)
    .put("base_card_version", baseCardVersion)
    .put("base_content_revision", baseContentRevision)
    .put("proposed", proposed.toJson())
    .put("changes", JSONArray().apply { changes.forEach { put(it.toJson()) } })
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("base_content_fingerprint", baseContentFingerprint)
    .put("status", status.name)

private fun NoteProposalChange.toJson() = JSONObject()
    .put("block_id", blockId)
    .put("kind", kind.name)
    .put("current_preview", currentPreview)
    .put("proposed_preview", proposedPreview)

private fun JSONObject.toProposal(): NoteDocumentProposal? {
    require(optInt("schema_version") == PROPOSAL_SCHEMA_VERSION) { "Unsupported proposal schema" }
    val status = enumOrDefault("status", NoteProposalStatus.PENDING)
    if (status != NoteProposalStatus.PENDING) return null
    val changesJson = getJSONArray("changes")
    return NoteDocumentProposal(
        proposalId = getString("proposal_id"),
        baseCardId = getString("base_card_id"),
        baseCardVersion = getString("base_card_version"),
        baseContentRevision = getLong("base_content_revision"),
        proposed = getJSONObject("proposed").toNoteDocument(),
        changes = buildList {
            repeat(changesJson.length()) { add(changesJson.getJSONObject(it).toChange()) }
        },
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        baseContentFingerprint = getString("base_content_fingerprint"),
        status = status,
    )
}

private fun JSONObject.toChange() = NoteProposalChange(
    blockId = getString("block_id"),
    kind = enumOrDefault("kind", NoteProposalChangeKind.UPDATED),
    currentPreview = nullableString("current_preview"),
    proposedPreview = nullableString("proposed_preview"),
)

private inline fun <reified T : Enum<T>> JSONObject.enumOrDefault(name: String, fallback: T): T =
    runCatching { enumValueOf<T>(optString(name)) }.getOrDefault(fallback)

private fun JSONObject.nullableString(name: String): String? =
    takeUnless { isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() }

private object DefaultNoteDocumentProposalAtomicWriter : NoteDocumentProposalAtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        check(target.parentFile?.isDirectory == true) {
            "Cannot create proposal directory: ${target.parentFile}"
        }
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

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
