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
}

fun interface MarkdownAiProposalAtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

class FileMarkdownAiProposalRepository(
    private val directory: File,
    private val writer: MarkdownAiProposalAtomicWriter = DefaultMarkdownAiProposalAtomicWriter,
) : MarkdownAiProposalRepository {
    override fun find(baseCardId: String, baseCardVersion: String): MarkdownAiProposal? = synchronized(this) {
        runCatching {
            val file = proposalFile(baseCardId, baseCardVersion)
            if (!file.isFile) return@synchronized null
            JSONObject(file.readText(Charsets.UTF_8)).toProposal()
        }.getOrNull()
    }

    override fun save(proposal: MarkdownAiProposal) = synchronized(this) {
        require(proposal.status == MarkdownAiProposalStatus.PENDING) {
            "Only pending Markdown proposals can be persisted"
        }
        val target = proposalFile(proposal.baseCardId, proposal.baseCardVersion)
        writer.write(target, proposal.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    override fun delete(baseCardId: String, baseCardVersion: String) {
        synchronized(this) {
            proposalFile(baseCardId, baseCardVersion).delete()
        }
    }

    private fun proposalFile(cardId: String, version: String): File =
        File(directory, "${sha256MarkdownProposalKey("$cardId|$version")}.json")
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

private fun JSONObject.toProposal(): MarkdownAiProposal? {
    require(optInt("schema_version") == MARKDOWN_PROPOSAL_SCHEMA_VERSION) {
        "Unsupported Markdown proposal schema"
    }
    val status = runCatching {
        MarkdownAiProposalStatus.valueOf(optString("status"))
    }.getOrDefault(MarkdownAiProposalStatus.PENDING)
    if (status != MarkdownAiProposalStatus.PENDING) return null
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
