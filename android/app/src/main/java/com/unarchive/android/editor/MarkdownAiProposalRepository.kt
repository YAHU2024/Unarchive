package com.unarchive.android.editor

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Durable boundary for one pending Markdown AI candidate per card version. */
interface MarkdownAiProposalRepository {
    fun find(baseCardId: String, baseCardVersion: String): MarkdownAiProposal?

    fun save(proposal: MarkdownAiProposal)

    fun delete(baseCardId: String, baseCardVersion: String)

    /** Implementations with shared storage should override these atomically. */
    fun saveIfMatches(proposal: MarkdownAiProposal, expectedProposalId: String?): Boolean {
        if (find(proposal.baseCardId, proposal.baseCardVersion)?.proposalId != expectedProposalId) return false
        save(proposal)
        return true
    }

    fun deleteIfMatches(baseCardId: String, baseCardVersion: String, proposalId: String): MarkdownAiProposalDeleteResult {
        val current = find(baseCardId, baseCardVersion) ?: return MarkdownAiProposalDeleteResult.MISSING
        if (current.proposalId != proposalId) return MarkdownAiProposalDeleteResult.CHANGED
        return try {
            delete(baseCardId, baseCardVersion)
            if (find(baseCardId, baseCardVersion) == null) MarkdownAiProposalDeleteResult.DELETED
            else MarkdownAiProposalDeleteResult.FAILED
        } catch (_: Exception) { MarkdownAiProposalDeleteResult.FAILED }
    }
}

enum class MarkdownAiProposalDeleteResult { DELETED, MISSING, CHANGED, FAILED }

fun interface MarkdownAiProposalAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

class FileMarkdownAiProposalRepository(
    private val directory: File,
    private val writer: MarkdownAiProposalAtomicWriter = DefaultMarkdownAiProposalAtomicWriter,
) : MarkdownAiProposalRepository {
    private val lock = locks.computeIfAbsent(directory.canonicalPath.lowercase(java.util.Locale.ROOT)) { Any() }

    override fun find(baseCardId: String, baseCardVersion: String): MarkdownAiProposal? = synchronized(lock) {
        runCatching {
            val file = proposalFile(baseCardId, baseCardVersion)
            if (!file.isFile) return@synchronized null
            JSONObject(file.readText(Charsets.UTF_8)).toProposal()?.takeIf {
                it.baseCardId == baseCardId && it.baseCardVersion == baseCardVersion
            }
        }.getOrNull()
    }

    override fun save(proposal: MarkdownAiProposal) = synchronized(lock) {
        require(proposal.status in setOf(MarkdownAiProposalStatus.PENDING, MarkdownAiProposalStatus.EXPIRED)) {
            "Only pending Markdown proposals can be persisted"
        }
        val previous = find(proposal.baseCardId, proposal.baseCardVersion)
        require(previous?.proposalId != proposal.proposalId || previous.status != MarkdownAiProposalStatus.EXPIRED ||
            proposal.status == MarkdownAiProposalStatus.EXPIRED) { "An expired proposal cannot become pending again" }
        val target = proposalFile(proposal.baseCardId, proposal.baseCardVersion)
        writer.write(target, proposal.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    override fun delete(baseCardId: String, baseCardVersion: String) {
        synchronized(lock) {
            val file = proposalFile(baseCardId, baseCardVersion)
            check(!file.exists() || file.delete()) { "Cannot delete Markdown proposal" }
        }
    }

    override fun saveIfMatches(proposal: MarkdownAiProposal, expectedProposalId: String?): Boolean = synchronized(lock) {
        if (find(proposal.baseCardId, proposal.baseCardVersion)?.proposalId != expectedProposalId) return@synchronized false
        save(proposal)
        true
    }

    override fun deleteIfMatches(baseCardId: String, baseCardVersion: String, proposalId: String): MarkdownAiProposalDeleteResult =
        synchronized(lock) {
            val file = proposalFile(baseCardId, baseCardVersion)
            if (!file.exists()) return@synchronized MarkdownAiProposalDeleteResult.MISSING
            val current = find(baseCardId, baseCardVersion)
            if (current?.proposalId != proposalId) return@synchronized MarkdownAiProposalDeleteResult.CHANGED
            if (file.delete()) MarkdownAiProposalDeleteResult.DELETED else MarkdownAiProposalDeleteResult.FAILED
        }

    private fun proposalFile(cardId: String, version: String): File =
        File(directory, "${sha256MarkdownProposalKey("$cardId|$version")}.json")

    private companion object {
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

private const val MARKDOWN_PROPOSAL_SCHEMA_VERSION = 1

private fun MarkdownAiProposal.toJson() = JSONObject()
    .put("schema_version", MARKDOWN_PROPOSAL_SCHEMA_VERSION)
    .put("proposal_id", proposalId)
    .put("base_card_id", baseCardId)
    .put("base_card_version", baseCardVersion)
    .put("base_markdown_revision", baseMarkdownRevision)
    .put("base_content_fingerprint", baseContentFingerprint)
    .put("proposed_markdown", proposedMarkdown)
    .put("model", model)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("status", status.name)
    .put("base_assets_fingerprint", baseAssetsFingerprint)

private fun JSONObject.toProposal(): MarkdownAiProposal? {
    require(optInt("schema_version") == MARKDOWN_PROPOSAL_SCHEMA_VERSION) {
        "Unsupported Markdown proposal schema"
    }
    val status = runCatching {
        MarkdownAiProposalStatus.valueOf(optString("status"))
    }.getOrNull() ?: return null
    if (status !in setOf(MarkdownAiProposalStatus.PENDING, MarkdownAiProposalStatus.EXPIRED)) return null
    return MarkdownAiProposal(
        proposalId = getString("proposal_id"),
        baseCardId = getString("base_card_id"),
        baseCardVersion = getString("base_card_version"),
        baseMarkdownRevision = getLong("base_markdown_revision"),
        baseContentFingerprint = getString("base_content_fingerprint"),
        proposedMarkdown = getString("proposed_markdown"),
        model = getString("model"),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        status = status,
        baseAssetsFingerprint = optString("base_assets_fingerprint").takeIf { it.isNotBlank() && it != "null" },
    )
}

private object DefaultMarkdownAiProposalAtomicWriter : MarkdownAiProposalAtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        check(target.parentFile?.isDirectory == true) {
            "Cannot create Markdown proposal directory: ${target.parentFile}"
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
