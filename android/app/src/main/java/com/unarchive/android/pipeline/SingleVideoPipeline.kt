package com.unarchive.android.pipeline

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.CompletedAsrSegment
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.checkpoint.AmbiguousCheckpointTail
import com.unarchive.android.checkpoint.AudioSourceFingerprint
import com.unarchive.android.checkpoint.CheckpointTailReconciler
import com.unarchive.android.checkpoint.TranscriptionCheckpoint
import com.unarchive.android.checkpoint.TranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.TranscriptionConfigIdentity
import com.unarchive.android.checkpoint.TranscriptionResumePlanner
import com.unarchive.android.checkpoint.TranscriptionSourceIdentity
import com.unarchive.android.platform.AudioDownloader
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.result.VideoResultRepository

enum class SingleVideoStage {
    RESOLVING_REFERENCE,
    FETCHING_METADATA,
    RESOLVING_AUDIO,
    CHECKING_AUDIO_CACHE,
    DOWNLOADING_AUDIO,
    USING_CACHED_AUDIO,
    RESUMING_TRANSCRIPTION,
    TRANSCRIBING,
    COMPLETE,
}

data class SingleVideoProgress(
    val stage: SingleVideoStage,
    val overallProgress: Float,
)

data class SingleVideoResult(
    val metadata: VideoMetadata,
    val benchmark: BenchmarkResult,
    val reusedDownload: Boolean,
    val storedResult: StoredVideoResult? = null,
    val resumedFromCheckpoint: Boolean = false,
)

fun interface SingleVideoProgressListener {
    fun onProgress(progress: SingleVideoProgress)
}

class SingleVideoPipeline(
    private val platformAdapter: VideoPlatformAdapter,
    private val audioDownloader: AudioDownloader,
    private val benchmarkRunner: BenchmarkRunner,
    private val resultRepository: VideoResultRepository? = null,
    private val checkpointRepository: TranscriptionCheckpointRepository? = null,
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        input: String,
        config: AsrConfig,
        forceRefreshAudio: Boolean = false,
        progressListener: SingleVideoProgressListener,
        checkpointListener: (Int) -> Unit = {},
    ): SingleVideoResult {
        progressListener.update(SingleVideoStage.RESOLVING_REFERENCE, 0.02f)
        val reference = platformAdapter.resolveReference(input)
        progressListener.update(SingleVideoStage.FETCHING_METADATA, 0.08f)
        val metadata = platformAdapter.fetchMetadata(reference)
        progressListener.update(SingleVideoStage.RESOLVING_AUDIO, 0.15f)
        val stream = platformAdapter.resolveAudio(metadata)
        val key = VideoResultKey(metadata.id.platform, metadata.id.value)
        if (forceRefreshAudio) checkpointRepository?.delete(key)
        progressListener.update(SingleVideoStage.CHECKING_AUDIO_CACHE, 0.18f)
        var downloadStarted = false
        val download = audioDownloader.download(
            metadata,
            stream,
            forceRefreshAudio,
            DownloadProgressListener { downloaded, total ->
                downloadStarted = true
                val fraction = total?.takeIf { it > 0 }
                    ?.let { downloaded.toFloat() / it }
                    ?.coerceIn(0f, 1f)
                    ?: 0f
                progressListener.update(
                    SingleVideoStage.DOWNLOADING_AUDIO,
                    0.2f + fraction * 0.3f,
                )
            },
        )
        if (download.reused) {
            progressListener.update(SingleVideoStage.USING_CACHED_AUDIO, 0.5f)
        } else if (!downloadStarted) {
            progressListener.update(SingleVideoStage.DOWNLOADING_AUDIO, 0.5f)
        }
        val sourceIdentity = TranscriptionSourceIdentity(
            key = key,
            canonicalUrl = metadata.canonicalUrl,
            byteCount = download.file.length(),
            modifiedAtEpochMs = download.file.lastModified(),
            contentFingerprint = AudioSourceFingerprint.calculate(download.file),
        )
        val configIdentity = TranscriptionConfigIdentity.from(config)
        val existingCheckpoint = checkpointRepository?.find(key)
        val resumePlan = TranscriptionResumePlanner.plan(
            existingCheckpoint,
            sourceIdentity,
            configIdentity,
        )
        checkpointListener(resumePlan?.checkpoint?.segments?.size ?: 0)
        if (existingCheckpoint != null && resumePlan == null) checkpointRepository?.delete(key)
        if (resumePlan != null) {
            progressListener.update(SingleVideoStage.RESUMING_TRANSCRIPTION, 0.5f)
        } else {
            progressListener.update(SingleVideoStage.TRANSCRIBING, 0.5f)
        }

        var resumedFromCheckpoint = resumePlan != null
        val benchmark = try {
            runBenchmark(
                download = download,
                metadata = metadata,
                config = config,
                sourceIdentity = sourceIdentity,
                configIdentity = configIdentity,
                resumePlan = resumePlan,
                progressListener = progressListener,
                checkpointListener = checkpointListener,
            )
        } catch (_: AmbiguousCheckpointTail) {
            resumedFromCheckpoint = false
            checkpointRepository?.delete(key)
            checkpointListener(0)
            progressListener.update(SingleVideoStage.TRANSCRIBING, 0.5f)
            runBenchmark(
                download = download,
                metadata = metadata,
                config = config,
                sourceIdentity = sourceIdentity,
                configIdentity = configIdentity,
                resumePlan = null,
                progressListener = progressListener,
                checkpointListener = checkpointListener,
            )
        }
        val pipelineResult = SingleVideoResult(
            metadata = metadata,
            benchmark = benchmark,
            reusedDownload = download.reused,
            resumedFromCheckpoint = resumedFromCheckpoint,
        )
        val storedResult = resultRepository?.let { repository ->
            val now = wallClockEpochMs()
            repository.save(
                StoredVideoResult.fromPipeline(
                    result = pipelineResult,
                    nowEpochMs = now,
                    createdAtEpochMs = repository.find(key)?.createdAtEpochMs ?: now,
                ),
            )
        }
        checkpointRepository?.delete(key)
        checkpointListener(0)
        progressListener.update(SingleVideoStage.COMPLETE, 1f)
        return pipelineResult.copy(storedResult = storedResult)
    }

    private suspend fun runBenchmark(
        download: com.unarchive.android.platform.DownloadedAudio,
        metadata: VideoMetadata,
        config: AsrConfig,
        sourceIdentity: TranscriptionSourceIdentity,
        configIdentity: TranscriptionConfigIdentity,
        resumePlan: com.unarchive.android.checkpoint.ResumePlan?,
        progressListener: SingleVideoProgressListener,
        checkpointListener: (Int) -> Unit,
    ): BenchmarkResult {
        val regenerated = mutableListOf<TranscriptSegment>()
        val prefix = resumePlan?.checkpoint?.segments
            ?.filter { it.endMs <= resumePlan.replaceFromMs }
            .orEmpty()
        var canonicalSegments = prefix
        var crossedCommittedBoundary = resumePlan == null
        val createdAt = resumePlan?.checkpoint?.createdAtEpochMs
            ?: checkpointRepository?.let { wallClockEpochMs() }
            ?: 0L

        fun commit(completed: CompletedAsrSegment) {
            completed.transcript?.let(regenerated::add)
            val previous = resumePlan?.checkpoint
            if (previous != null && completed.endMs < previous.completedThroughMs) return
            crossedCommittedBoundary = true
            val segments = if (previous == null) {
                CheckpointTailReconciler.normalizeSegments(regenerated)
            } else {
                CheckpointTailReconciler.reconcile(
                    committed = previous.segments,
                    regenerated = regenerated,
                    replaceFromMs = resumePlan.replaceFromMs,
                    completedThroughMs = completed.endMs,
                )
            }
            canonicalSegments = segments
            val saved = checkpointRepository?.save(
                TranscriptionCheckpoint(
                    source = sourceIdentity,
                    config = configIdentity,
                    audioDurationMs = metadata.durationSeconds * 1_000L,
                    completedThroughMs = completed.endMs,
                    segments = segments,
                    createdAtEpochMs = createdAt,
                    updatedAtEpochMs = wallClockEpochMs(),
                ),
            )
            saved?.let { checkpointListener(it.segments.size) }
        }

        return benchmarkRunner.run(
            source = AudioSource(
                displayName = download.file.name,
                uri = download.file.toURI().toString(),
                resumeStartMs = resumePlan?.resumeStartMs ?: 0,
                onSegmentCompleted = ::commit,
            ),
            config = config,
            initialSegments = prefix,
            progressListener = AsrProgressListener { asrProgress ->
                progressListener.update(
                    SingleVideoStage.TRANSCRIBING,
                    0.5f + asrProgress.coerceIn(0f, 1f) * 0.5f,
                )
            },
        ).let { result ->
            if (!crossedCommittedBoundary) {
                throw AmbiguousCheckpointTail(
                    "Resume did not reach the last committed segment boundary",
                )
            }
            if (resumePlan == null) result.copy(
                segments = CheckpointTailReconciler.normalizeSegments(result.segments),
            ) else result.copy(
                audioDurationMs = maxOf(
                    result.audioDurationMs + resumePlan.resumeStartMs,
                    metadata.durationSeconds * 1_000L,
                ),
                segments = CheckpointTailReconciler.normalizeSegments(canonicalSegments),
            )
        }
    }

    private fun SingleVideoProgressListener.update(stage: SingleVideoStage, progress: Float) {
        onProgress(SingleVideoProgress(stage, progress.coerceIn(0f, 1f)))
    }
}
