package com.unarchive.android.checkpoint

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.CompletedAsrSegment
import com.unarchive.android.asr.TranscriptSegment

class LocalAudioCheckpointRunner(
    private val benchmarkRunner: BenchmarkRunner,
    private val repository: TranscriptionCheckpointRepository,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        source: AudioSource,
        sourceIdentity: TranscriptionSourceIdentity,
        config: AsrConfig,
        progressListener: AsrProgressListener,
        checkpointListener: (Int) -> Unit = {},
    ): LocalAudioRunResult {
        val configIdentity = TranscriptionConfigIdentity.from(config)
        val existing = repository.find(sourceIdentity.key)
        val resume = TranscriptionResumePlanner.plan(existing, sourceIdentity, configIdentity)
        checkpointListener(resume?.checkpoint?.segments?.size ?: 0)
        return tryRun(
            source, sourceIdentity, config, configIdentity, resume, progressListener,
            checkpointListener,
        )
    }

    private suspend fun tryRun(
        source: AudioSource,
        identity: TranscriptionSourceIdentity,
        config: AsrConfig,
        configIdentity: TranscriptionConfigIdentity,
        resume: ResumePlan?,
        progressListener: AsrProgressListener,
        checkpointListener: (Int) -> Unit,
    ): LocalAudioRunResult {
        return try {
            runOnce(
                source, identity, config, configIdentity, resume, progressListener,
                checkpointListener,
            )
        } catch (_: AmbiguousCheckpointTail) {
            repository.delete(identity.key)
            checkpointListener(0)
            runOnce(
                source.copy(resumeStartMs = 0), identity, config, configIdentity, null,
                progressListener, checkpointListener,
            )
        }
    }

    private suspend fun runOnce(
        source: AudioSource,
        identity: TranscriptionSourceIdentity,
        config: AsrConfig,
        configIdentity: TranscriptionConfigIdentity,
        resume: ResumePlan?,
        progressListener: AsrProgressListener,
        checkpointListener: (Int) -> Unit,
    ): LocalAudioRunResult {
        val overlapStart = resume?.resumeStartMs ?: 0L
        val replaceFrom = resume?.replaceFromMs ?: 0L
        val prefix = resume?.checkpoint?.segments?.filter { it.endMs <= replaceFrom }.orEmpty()
        val regenerated = mutableListOf<TranscriptSegment>()
        var canonical = prefix
        var crossedBoundary = resume == null
        val callback = callback@{ completed: CompletedAsrSegment ->
            completed.transcript?.let(regenerated::add)
            if (resume != null && completed.endMs < resume.checkpoint.completedThroughMs) return@callback
            crossedBoundary = true
            canonical = if (resume == null) {
                CheckpointTailReconciler.normalizeSegments(regenerated)
            } else {
                CheckpointTailReconciler.reconcile(
                    resume.checkpoint.segments, regenerated, replaceFrom, completed.endMs,
                )
            }
            val saved = repository.save(
                TranscriptionCheckpoint(
                    source = identity,
                    config = configIdentity,
                    audioDurationMs = 0,
                    completedThroughMs = completed.endMs,
                    segments = canonical,
                    createdAtEpochMs = resume?.checkpoint?.createdAtEpochMs ?: nowEpochMs(),
                    updatedAtEpochMs = nowEpochMs(),
                ),
            )
            checkpointListener(saved.segments.size)
        }
        val result = benchmarkRunner.run(
            source = source.copy(
                resumeStartMs = overlapStart,
                onSegmentCompleted = callback,
            ),
            config = config,
            initialSegments = prefix,
            progressListener = progressListener,
        )
        if (!crossedBoundary) {
            throw AmbiguousCheckpointTail("Resume did not reach the last committed segment boundary")
        }
        repository.delete(identity.key)
        checkpointListener(0)
        return LocalAudioRunResult(
            benchmark = if (resume == null) {
                result.copy(segments = CheckpointTailReconciler.normalizeSegments(result.segments))
            } else {
                result.copy(segments = CheckpointTailReconciler.normalizeSegments(canonical))
            },
            resumed = resume != null,
        )
    }
}

data class LocalAudioRunResult(
    val benchmark: BenchmarkResult,
    val resumed: Boolean,
)
