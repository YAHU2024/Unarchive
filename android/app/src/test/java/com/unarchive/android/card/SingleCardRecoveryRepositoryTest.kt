package com.unarchive.android.card

import com.unarchive.android.result.VideoResultKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingleCardRecoveryRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndRestoresRecoveryRecordAtomically() {
        val repository = FileSingleCardRecoveryRepository(temporaryFolder.newFolder("recovery"))
        val record = SingleCardRecoveryRecord(
            operationId = "operation-1",
            cardId = KnowledgeCardId("bilibili", "BVrecover"),
            cardVersion = "base-version",
            resultKey = VideoResultKey("bilibili", "BVrecover"),
            stage = SingleCardRecoveryStage.PUBLISH,
            state = SingleCardRecoveryState.RECOVERABLE,
            configSignature = "deepseek-thinking=true",
            attemptCount = 1,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            finalCardVersion = "final-version",
            lastError = "temporary failure",
        )

        repository.save(record)

        assertEquals(listOf(record), repository.list())
        assertEquals(record, repository.list().single())
        assertEquals(
            setOf("operation-1.json"),
            temporaryFolder.getRoot().resolve("recovery").list()!!.toSet(),
        )
    }

    @Test
    fun ignoresMalformedRecordWithoutHidingValidRecord() {
        val directory = temporaryFolder.newFolder("recovery")
        val repository = FileSingleCardRecoveryRepository(directory)
        val record = SingleCardRecoveryRecord(
            operationId = "operation-2",
            cardId = KnowledgeCardId("bilibili", "BVrecover"),
            cardVersion = "base-version",
            resultKey = VideoResultKey("bilibili", "BVrecover"),
            stage = SingleCardRecoveryStage.BASE,
            state = SingleCardRecoveryState.RECOVERABLE,
            configSignature = "base-only",
            attemptCount = 0,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
        )
        repository.save(record)
        File(directory, "broken.json").writeText("{broken", Charsets.UTF_8)

        assertEquals(listOf(record), repository.list())
    }
}
