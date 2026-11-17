package com.unarchive.android.editor.ai

import com.unarchive.android.editor.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiProposalRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun candidate(id: String = "one", status: MarkdownAiProposalStatus = MarkdownAiProposalStatus.PENDING) =
        MarkdownAiProposal(id, "bilibili:BV_ai", "v1", 0L, "a".repeat(64), "candidate", "model", 0L, status)

    @Test fun expiredCandidateRoundTripsAndCannotBecomePendingWithSameId() {
        val store = FileMarkdownAiProposalRepository(temporary.newFolder())
        val expired = candidate(status = MarkdownAiProposalStatus.EXPIRED)
        store.save(expired)
        assertEquals(expired, store.find(expired.baseCardId, expired.baseCardVersion))
        try {
            store.save(expired.copy(status = MarkdownAiProposalStatus.PENDING))
            fail("expired proposal must not revive")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun staleWriterCannotExpireReplaceOrDeleteNewerCandidateAcrossInstances() {
        val directory = temporary.newFolder()
        val first = FileMarkdownAiProposalRepository(directory)
        val second = FileMarkdownAiProposalRepository(directory)
        val a = candidate()
        val b = candidate("two")
        first.save(a)
        assertTrue(second.saveIfMatches(b, a.proposalId))
        assertFalse(first.saveIfMatches(a.copy(status = MarkdownAiProposalStatus.EXPIRED), a.proposalId))
        assertEquals(MarkdownAiProposalDeleteResult.CHANGED, first.deleteIfMatches(a.baseCardId, a.baseCardVersion, a.proposalId))
        assertEquals(b, second.find(b.baseCardId, b.baseCardVersion))
    }

    @Test fun matchingDeleteIsCheckedAndMissingIsIdempotent() {
        val store = FileMarkdownAiProposalRepository(temporary.newFolder())
        val a = candidate()
        store.save(a)
        assertEquals(MarkdownAiProposalDeleteResult.DELETED, store.deleteIfMatches(a.baseCardId, a.baseCardVersion, a.proposalId))
        assertEquals(MarkdownAiProposalDeleteResult.MISSING, store.deleteIfMatches(a.baseCardId, a.baseCardVersion, a.proposalId))
    }

    @Test fun schemaOneWithoutAssetFingerprintRemainsReadableButUnknownStatusIsNotPending() {
        val directory = temporary.newFolder()
        val store = FileMarkdownAiProposalRepository(directory)
        val a = candidate()
        store.save(a)
        val file = directory.listFiles()!!.single()
        val json = JSONObject(file.readText()).apply { remove("base_assets_fingerprint") }
        file.writeText(json.toString())
        assertEquals(a, store.find(a.baseCardId, a.baseCardVersion))
        file.writeText(json.put("status", "UNKNOWN").toString())
        assertNull(store.find(a.baseCardId, a.baseCardVersion))
    }

    @Test fun identityFromFileCannotEscapeHashedLookupKey() {
        val directory = temporary.newFolder()
        val store = FileMarkdownAiProposalRepository(directory)
        val a = candidate()
        store.save(a)
        val file = directory.listFiles()!!.single()
        file.writeText(JSONObject(file.readText()).put("base_card_id", "another:document").toString())
        assertNull(store.find(a.baseCardId, a.baseCardVersion))
    }
}
