package com.unarchive.android.checkpoint

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.result.VideoResultKey
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckpointTailReconcilerTest {
    @Test
    fun keepsPrefixAndReplacesOverlappingTail() {
        val result = CheckpointTailReconciler.reconcile(
            committed = listOf(
                TranscriptSegment(0, 1_000, "first"),
                TranscriptSegment(2_000, 3_000, "old"),
            ),
            regenerated = listOf(
                TranscriptSegment(1_900, 2_900, "new"),
                TranscriptSegment(3_100, 4_000, "tail"),
            ),
            replaceFromMs = 1_500,
            completedThroughMs = 3_000,
        )

        assertEquals(listOf("first", "new", "tail"), result.map { it.text })
    }

    @Test
    fun keepsCommittedSegmentsReadOnlyForDecoderOverlap() {
        val result = CheckpointTailReconciler.reconcile(
            committed = listOf(
                TranscriptSegment(0, 1_000, "prefix"),
                TranscriptSegment(2_000, 4_000, "context-only"),
                TranscriptSegment(5_000, 8_000, "affected"),
            ),
            regenerated = listOf(
                TranscriptSegment(4_900, 6_000, "regenerated"),
                TranscriptSegment(6_000, 8_000, "tail"),
            ),
            replaceFromMs = 5_000,
            completedThroughMs = 8_000,
        )

        assertEquals(
            listOf("prefix", "context-only", "regenerated", "tail"),
            result.map { it.text },
        )
    }

    @Test
    fun mergesSimilarOverlappingSegments() {
        val result = CheckpointTailReconciler.normalizeSegments(
            listOf(
                TranscriptSegment(0, 8_000, "hello world"),
                TranscriptSegment(7_000, 10_000, "hello  world"),
            ),
        )

        assertEquals(listOf(TranscriptSegment(0, 10_000, "hello  world")), result)
    }

    @Test
    fun trimsOnlySmallDifferentTextBoundaryOverlap() {
        val result = CheckpointTailReconciler.normalizeSegments(
            listOf(
                TranscriptSegment(0, 8_000, "first"),
                TranscriptSegment(7_900, 10_000, "second"),
            ),
        )

        assertEquals(
            listOf(TranscriptSegment(0, 7_900, "first"), TranscriptSegment(7_900, 10_000, "second")),
            result,
        )
    }

    @Test
    fun rejectsLargeDifferentTextOverlap() {
        assertThrows(AmbiguousCheckpointTail::class.java) {
            CheckpointTailReconciler.normalizeSegments(
                listOf(
                    TranscriptSegment(0, 8_000, "first"),
                    TranscriptSegment(7_000, 10_000, "different"),
                ),
            )
        }
    }

    @Test
    fun rejectsMissingOverlapTail() {
        assertThrows(AmbiguousCheckpointTail::class.java) {
            CheckpointTailReconciler.reconcile(
                committed = listOf(TranscriptSegment(2_000, 3_000, "old")),
                regenerated = emptyList(),
                replaceFromMs = 1_500,
                completedThroughMs = 3_000,
            )
        }
    }

    @Test
    fun resumePlannerRejectsSourceOrConfigMismatch() {
        val key = VideoResultKey("bilibili", "BV1PS42197aM")
        val source = TranscriptionSourceIdentity(key, "https://example", 10, 20, "abc")
        val config = TranscriptionConfigIdentity("SENSE", "auto", 16_000, true, 500)
        val checkpoint = TranscriptionCheckpoint(source, config, 10_000, 5_000, emptyList(), 1, 2)

        assertEquals(2_000L, TranscriptionResumePlanner.plan(checkpoint, source, config)?.resumeStartMs)
        assertNull(TranscriptionResumePlanner.plan(checkpoint, source.copy(byteCount = 11), config))
        assertNull(TranscriptionResumePlanner.plan(checkpoint, source, config.copy(language = "zh")))
    }

    @Test
    fun resumePlannerStartsBeforeEntireAffectedSegment() {
        val key = VideoResultKey("local", "sample")
        val source = TranscriptionSourceIdentity(key, "content://sample", 10, 20, "abc")
        val config = TranscriptionConfigIdentity("SENSE", "auto", 16_000, true, 500)
        val checkpoint = TranscriptionCheckpoint(
            source = source,
            config = config,
            audioDurationMs = 20_000,
            completedThroughMs = 8_000,
            segments = listOf(TranscriptSegment(0, 8_000, "complete first segment")),
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )

        assertEquals(0L, TranscriptionResumePlanner.plan(checkpoint, source, config)?.resumeStartMs)
    }

    @Test
    fun resumePlannerKeepsDecoderOverlapBeforeAffectedSegment() {
        val key = VideoResultKey("local", "sample")
        val source = TranscriptionSourceIdentity(key, "content://sample", 10, 20, "abc")
        val config = TranscriptionConfigIdentity("SENSE", "auto", 16_000, true, 500)
        val checkpoint = TranscriptionCheckpoint(
            source = source,
            config = config,
            audioDurationMs = 20_000,
            completedThroughMs = 8_000,
            segments = listOf(
                TranscriptSegment(0, 1_000, "prefix"),
                TranscriptSegment(5_000, 8_000, "affected"),
            ),
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )

        assertEquals(2_000L, TranscriptionResumePlanner.plan(checkpoint, source, config)?.resumeStartMs)
    }

    @Test
    fun repositoryIgnoresCorruptAndAtomicallyRoundTrips() {
        val directory = Files.createTempDirectory("checkpoint-test").toFile()
        val repository = FileTranscriptionCheckpointRepository(directory)
        val key = VideoResultKey("bilibili", "BV1PS42197aM")
        val checkpoint = TranscriptionCheckpoint(
            source = TranscriptionSourceIdentity(key, "https://example", 10, 20, "abc"),
            config = TranscriptionConfigIdentity("SENSE", "auto", 16_000, true, 500),
            audioDurationMs = 10_000,
            completedThroughMs = 5_000,
            segments = listOf(TranscriptSegment(0, 1_000, "hello")),
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )
        repository.save(checkpoint)
        assertEquals(checkpoint, repository.find(key))
        File(directory, "broken.json").writeText("{")
        assertTrue(repository.find(key) != null)
        repository.delete(key)
        assertNull(repository.find(key))
        directory.deleteRecursively()
    }
}
