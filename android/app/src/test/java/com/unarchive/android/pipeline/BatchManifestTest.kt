package com.unarchive.android.pipeline

import com.unarchive.android.card.CardStageState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndLoadsManifestAtomicallyWithAllItemStates() {
        val repository = BatchManifestRepository(temporaryFolder.newFolder("batch"))
        val manifest = sampleManifest().copy(
            items = BatchItemState.entries.mapIndexed { index, state ->
                BatchManifestItem("BV$index", "https://www.bilibili.com/video/BV$index", "t$index", state)
            },
        )

        repository.save(manifest)

        assertEquals(manifest, repository.load())
        assertTrue(requireNotNull(repository.load()).unfinished())
    }

    @Test
    fun ignoresCorruptAndUnsupportedManifest() {
        val directory = temporaryFolder.newFolder("batch")
        val repository = BatchManifestRepository(directory)
        val target = File(directory, "batch-manifest.json")

        target.writeText("not-json")
        assertNull(repository.load())

        target.writeText("{\"schema_version\":99}")
        assertNull(repository.load())
    }

    @Test
    fun terminalManifestDoesNotNeedRecovery() {
        val manifest = sampleManifest().copy(
            items = listOf(BatchManifestItem("BV1", "https://x", "done", BatchItemState.SUCCEEDED)),
        )

        assertEquals(false, manifest.unfinished())
    }

    @Test
    fun folderTitleIsOptionalAndRoundTrips() {
        val repository = BatchManifestRepository(temporaryFolder.newFolder("titled-batch"))
        val manifest = sampleManifest().copy(folderTitle = "我的收藏夹")
        repository.save(manifest)
        assertEquals("我的收藏夹", repository.load()?.folderTitle)
    }

    @Test
    fun cardStageAndIdentityRoundTripAndKeepRecoveryOpen() {
        val repository = BatchManifestRepository(temporaryFolder.newFolder("card-stage"))
        val manifest = sampleManifest().copy(
            items = listOf(
                BatchManifestItem(
                    "BV1", "https://x", "card", BatchItemState.SUCCEEDED,
                    cardState = CardStageState.PARTIAL,
                    cardId = "bilibili:BV1",
                    cardVersion = "version-1",
                ),
            ),
        )

        repository.save(manifest)

        assertEquals(manifest, repository.load())
        assertTrue(requireNotNull(repository.load()).unfinished())
    }

    @Test
    fun imaStageAndTargetRoundTripKeepBatchRecoverable() {
        val repository = BatchManifestRepository(temporaryFolder.newFolder("ima-stage"))
        val manifest = sampleManifest().copy(
            imaKnowledgeBaseId = "kb-test",
            imaFolderId = "folder-test",
            items = listOf(
                BatchManifestItem(
                    "BV1", "https://x", "ima", BatchItemState.SUCCEEDED,
                    cardState = CardStageState.SUCCEEDED,
                    cardId = "bilibili:BV1", cardVersion = "version-1",
                    imaState = ImaBatchStageState.RETRYABLE_FAILURE,
                    imaNoteId = "note-1", imaErrorMessage = "网络超时",
                ),
            ),
        )

        repository.save(manifest)

        val restored = requireNotNull(repository.load())
        assertEquals("kb-test", restored.imaKnowledgeBaseId)
        assertEquals("folder-test", restored.imaFolderId)
        assertEquals(ImaBatchStageState.RETRYABLE_FAILURE, restored.items.single().imaState)
        assertEquals("note-1", restored.items.single().imaNoteId)
        assertTrue(restored.unfinished())
    }

    @Test
    fun legacyV1ManifestLoadsWithSkippedImaStage() {
        val directory = temporaryFolder.newFolder("legacy-v1")
        val repository = BatchManifestRepository(directory)
        File(directory, "batch-manifest.json").writeText("""{
            "schema_version":1, "batch_id":"b", "folder_id":"f",
            "config_signature":"c", "created_at_epoch_ms":1, "updated_at_epoch_ms":1,
            "items":[{"video_id":"BV1","canonical_url":"https://x","title":"t","state":"SUCCEEDED"}]
        }""".trimIndent())

        val restored = requireNotNull(repository.load())
        assertEquals(ImaBatchStageState.SKIPPED, restored.items.single().imaState)
        assertEquals(2, restored.schemaVersion)
    }

    @Test
    fun classifiesRetryableAndDeterministicFailures() {
        assertEquals(BatchFailureRetryability.RETRYABLE, BatchFailureClassifier.classify("云端转写失败：HTTP 500"))
        assertEquals(BatchFailureRetryability.RETRYABLE, BatchFailureClassifier.classify("读取超时"))
        assertEquals(BatchFailureRetryability.NON_RETRYABLE, BatchFailureClassifier.classify("云端转写返回空文本（EMPTY_TEXT）"))
        assertEquals(BatchFailureRetryability.NON_RETRYABLE, BatchFailureClassifier.classify("云端转写失败：HTTP 401"))
        assertEquals(BatchFailureRetryability.UNKNOWN, BatchFailureClassifier.classify("其他失败"))
    }

    private fun sampleManifest() = BatchManifest(
        batchId = "batch",
        folderId = "7",
        configSignature = "config",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        items = emptyList(),
    )
}
