package com.unarchive.android.pipeline

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
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
    DOWNLOADING_AUDIO,
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
)

fun interface SingleVideoProgressListener {
    fun onProgress(progress: SingleVideoProgress)
}

class SingleVideoPipeline(
    private val platformAdapter: VideoPlatformAdapter,
    private val audioDownloader: AudioDownloader,
    private val benchmarkRunner: BenchmarkRunner,
    private val resultRepository: VideoResultRepository? = null,
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        input: String,
        config: AsrConfig,
        progressListener: SingleVideoProgressListener,
    ): SingleVideoResult {
        progressListener.update(SingleVideoStage.RESOLVING_REFERENCE, 0.02f)
        val reference = platformAdapter.resolveReference(input)
        progressListener.update(SingleVideoStage.FETCHING_METADATA, 0.08f)
        val metadata = platformAdapter.fetchMetadata(reference)
        progressListener.update(SingleVideoStage.RESOLVING_AUDIO, 0.15f)
        val stream = platformAdapter.resolveAudio(metadata)
        progressListener.update(SingleVideoStage.DOWNLOADING_AUDIO, 0.2f)
        val download = audioDownloader.download(
            metadata,
            stream,
            DownloadProgressListener { downloaded, total ->
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
        progressListener.update(SingleVideoStage.TRANSCRIBING, 0.5f)
        val benchmark = benchmarkRunner.run(
            source = AudioSource(download.file.name, download.file.toURI().toString()),
            config = config,
            progressListener = AsrProgressListener { asrProgress ->
                progressListener.update(
                    SingleVideoStage.TRANSCRIBING,
                    0.5f + asrProgress.coerceIn(0f, 1f) * 0.5f,
                )
            },
        )
        val pipelineResult = SingleVideoResult(metadata, benchmark, download.reused)
        val storedResult = resultRepository?.let { repository ->
            val now = wallClockEpochMs()
            val key = VideoResultKey(metadata.id.platform, metadata.id.value)
            repository.save(
                StoredVideoResult.fromPipeline(
                    result = pipelineResult,
                    nowEpochMs = now,
                    createdAtEpochMs = repository.find(key)?.createdAtEpochMs ?: now,
                ),
            )
        }
        progressListener.update(SingleVideoStage.COMPLETE, 1f)
        return pipelineResult.copy(storedResult = storedResult)
    }

    private fun SingleVideoProgressListener.update(stage: SingleVideoStage, progress: Float) {
        onProgress(SingleVideoProgress(stage, progress.coerceIn(0f, 1f)))
    }
}
