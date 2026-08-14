package com.unarchive.android.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.unarchive.android.asr.AsrProgressListener
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

class AndroidAudioDecoder(
    private val context: Context,
) {
    suspend fun decode(
        uri: Uri,
        targetSampleRate: Int,
        progressListener: AsrProgressListener,
    ): DecodedAudio {
        val output = FloatArrayCollector()
        decodeChunks(
            uri = uri,
            targetSampleRate = targetSampleRate,
            progressListener = progressListener,
            onSamples = output::addAll,
        )
        return DecodedAudio(output.toArray(), targetSampleRate)
    }

    suspend fun decodeChunks(
        uri: Uri,
        targetSampleRate: Int,
        progressListener: AsrProgressListener,
        startAtMs: Long = 0,
        onResolvedStartMs: (Long) -> Unit = {},
        onSamples: (FloatArray) -> Unit,
    ): Long {
        require(startAtMs >= 0) { "startAtMs cannot be negative" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = findAudioTrack(extractor)
            extractor.selectTrack(track.index)
            enforceDurationLimit(track.format)
            if (startAtMs > 0) {
                extractor.seekTo(startAtMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            onResolvedStartMs(extractor.sampleTime.coerceAtLeast(0L) / 1_000L)
            return decodeTrack(
                extractor = extractor,
                inputFormat = track.format,
                targetSampleRate = targetSampleRate,
                progressListener = progressListener,
                onSamples = onSamples,
            )
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        targetSampleRate: Int,
        progressListener: AsrProgressListener,
        onSamples: (FloatArray) -> Unit,
    ): Long {
        val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val output = MediaCodecPcmAccumulator(
            targetSampleRate = targetSampleRate,
            maximumDurationSeconds = MAX_DURATION_SECONDS,
            onSamples = onSamples,
            collectOutput = false,
        )
        output.updateFormat(outputFormat(outputSampleRate, outputChannels, pcmEncoding))
        var inputEnded = false
        var outputEnded = false
        val info = MediaCodec.BufferInfo()
        val durationUs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    // Feed every available input buffer so the decoder never
                    // starves. Decoding one sample per 10 ms poll starved the
                    // codec and multiplied wall time by ~3 (measured: 337 s
                    // M4A took 71 s to decode vs 24 s WAV before this fix).
                    var inputIndex = codec.dequeueInputBuffer(0)
                    while (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                            break
                        }
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            extractor.sampleFlags,
                        )
                        extractor.advance()
                        inputIndex = codec.dequeueInputBuffer(0)
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, BLOCKING_OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = format.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        output.updateFormat(outputFormat(outputSampleRate, outputChannels, pcmEncoding))
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Blocking-mode call returned nothing only at the tail
                        // (EOS in flight); a short pause avoids a busy spin.
                        Thread.sleep(POLL_WAIT_MS)
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex)).duplicate()
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            output.push(buffer.slice())
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (durationUs > 0 && info.presentationTimeUs >= 0) {
                            progressListener.onProgress(
                                (0.05f + 0.45f * info.presentationTimeUs.toFloat() / durationUs)
                                    .coerceIn(0.05f, 0.5f),
                            )
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        return output.finishStreaming()
    }

    private fun findAudioTrack(extractor: MediaExtractor): AudioTrack {
        repeat(extractor.trackCount) { index ->
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return AudioTrack(index, format)
            }
        }
        throw IllegalArgumentException("Selected media has no audio track")
    }

    private fun enforceDurationLimit(format: MediaFormat) {
        val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
        require(durationUs <= MAX_DURATION_US || durationUs <= 0) {
            "Media decoding is limited to ${MAX_DURATION_SECONDS / 60} minutes"
        }
    }

    private fun outputFormat(sampleRate: Int, channelCount: Int, encoding: Int) =
        MediaCodecPcmAccumulator.PcmOutputFormat(
            sampleRate = sampleRate,
            channelCount = channelCount,
            encoding = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> MediaCodecPcmAccumulator.PcmEncoding.PCM_16BIT
                AudioFormat.ENCODING_PCM_FLOAT -> MediaCodecPcmAccumulator.PcmEncoding.PCM_FLOAT
                else -> throw IllegalArgumentException("Unsupported decoder PCM encoding: $encoding")
            },
        )

    private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long =
        if (containsKey(key)) getLong(key) else defaultValue

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private data class AudioTrack(val index: Int, val format: MediaFormat)

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val POLL_WAIT_MS = 1L
        // Blocking-mode output drain: absorbs the decoder's per-frame latency
        // instead of converting it into poll+sleep wall time.
        private const val BLOCKING_OUTPUT_TIMEOUT_US = 50_000L
        private const val MAX_DURATION_SECONDS = 4 * 60 * 60L
        private const val MAX_DURATION_US = MAX_DURATION_SECONDS * 1_000_000L
    }
}
