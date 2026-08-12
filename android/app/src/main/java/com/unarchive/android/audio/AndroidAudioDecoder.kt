package com.unarchive.android.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.unarchive.android.asr.AsrProgressListener
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class AndroidAudioDecoder(
    private val context: Context,
) {
    suspend fun decode(
        uri: Uri,
        targetSampleRate: Int,
        progressListener: AsrProgressListener,
    ): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = findAudioTrack(extractor)
            extractor.selectTrack(track.index)
            enforceDurationLimit(track.format)
            return decodeTrack(extractor, track.format, targetSampleRate, progressListener)
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        targetSampleRate: Int,
        progressListener: AsrProgressListener,
    ): DecodedAudio {
        val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        val output = ByteArrayOutputStream()
        var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
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
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
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
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = format.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex)).duplicate()
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendPcm16(output, buffer.slice(), pcmEncoding)
                            enforceDecodedSize(output.size(), outputChannels, outputSampleRate)
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

        val pcm = output.toByteArray().toShortArray()
        return DecodedAudio(
            samples = Pcm16Normalizer.toMonoFloat(
                interleavedSamples = pcm,
                channelCount = outputChannels,
                sourceSampleRate = outputSampleRate,
                targetSampleRate = targetSampleRate,
            ),
            sampleRate = targetSampleRate,
        )
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
            "Phase 0 media decoding is limited to 5 minutes until segmented ASR is available"
        }
    }

    private fun enforceDecodedSize(byteCount: Int, channelCount: Int, sampleRate: Int) {
        val maximumBytes = MAX_DURATION_SECONDS.toLong() * sampleRate * channelCount * Short.SIZE_BYTES
        require(byteCount.toLong() <= maximumBytes) {
            "Decoded audio exceeded the 5-minute safety limit"
        }
    }

    private fun appendPcm16(output: ByteArrayOutputStream, buffer: ByteBuffer, encoding: Int) {
        buffer.order(ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
            }
            AudioFormat.ENCODING_PCM_FLOAT -> while (buffer.remaining() >= Float.SIZE_BYTES) {
                val sample = (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                output.write(sample.toInt() and 0xff)
                output.write(sample.toInt().ushr(8) and 0xff)
            }
            else -> throw IllegalArgumentException("Unsupported decoder PCM encoding: $encoding")
        }
    }

    private fun ByteArray.toShortArray(): ShortArray {
        require(size % Short.SIZE_BYTES == 0) { "Decoder returned incomplete PCM16 data" }
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.nativeOrder())
        return ShortArray(size / Short.SIZE_BYTES) { buffer.short }
    }

    private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long =
        if (containsKey(key)) getLong(key) else defaultValue

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private data class AudioTrack(val index: Int, val format: MediaFormat)

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_DURATION_SECONDS = 5 * 60L
        private const val MAX_DURATION_US = MAX_DURATION_SECONDS * 1_000_000L
    }
}
