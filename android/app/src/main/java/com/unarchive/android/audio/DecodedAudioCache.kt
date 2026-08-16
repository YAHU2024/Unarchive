package com.unarchive.android.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Decoded-audio cache: persists the normalized 16 kHz mono PCM16 WAV of a
 * source so a rerun skips the expensive container decode (measured ~30 s of
 * software AAC for a 12-minute M4A on the OPPO PHQ110).
 *
 * The cache pattern follows SubtitleEditforAndroid's quick-transcription
 * cache (EditorTranscribeController): a per-source fingerprint directory, a
 * validity check, and atomic publication via a `.part` file + rename. The
 * decode itself stays on Unarchive's own decoder path; this class only owns
 * the files.
 */
class DecodedAudioCache(private val cacheDirectory: File) {

    init {
        require(cacheDirectory.isDirectory || !cacheDirectory.exists()) {
            "Cache path is not a directory: ${cacheDirectory.absolutePath}"
        }
    }

    /** The published cache file for [fingerprint], without touching the disk. */
    fun cachedFile(fingerprint: String): File =
        File(File(cacheDirectory, fingerprint), "decoded-16k.wav")

    /** True when [file] looks like a completed PCM16 WAV (header + payload). */
    fun isValid(file: File): Boolean =
        file.isFile && file.length() > WAV_HEADER_SIZE

    /** Deletes an invalid cache entry so the next run rewrites it. */
    fun invalidate(file: File) {
        if (file.exists()) file.delete()
    }

    /**
     * Creates a fresh `.part` sink for [fingerprint]; call [Pcm16WavSink.finish]
     * to publish it atomically, or [Pcm16WavSink.abort] to discard it.
     */
    fun newSink(fingerprint: String): Pcm16WavSink {
        val (part, target) = newEntryFiles(fingerprint)
        return Pcm16WavSink(part, target)
    }

    /** Atomic file target for a decoder that writes a complete WAV itself. */
    fun newFileSink(fingerprint: String): AtomicCacheFileSink {
        val (part, target) = newEntryFiles(fingerprint)
        return AtomicCacheFileSink(part, target)
    }

    private fun newEntryFiles(fingerprint: String): Pair<File, File> {
        val directory = File(cacheDirectory, fingerprint)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create decoded-audio cache: $directory")
        }
        require(directory.isDirectory) { "Cache path is not a directory: $directory" }
        return File(directory, ".decoded-${UUID.randomUUID()}.wav.part") to cachedFile(fingerprint)
    }

    companion object {
        private const val WAV_HEADER_SIZE = 44L
    }
}

/** Publishes a complete externally-written cache file atomically. */
class AtomicCacheFileSink internal constructor(
    val partFile: File,
    private val targetFile: File,
) {
    private var finished = false

    fun finish(): File {
        check(!finished) { "AtomicCacheFileSink is already finished" }
        check(partFile.isFile) { "Decoded-audio partial file is missing: $partFile" }
        finished = true
        try {
            publishAtomically(partFile, targetFile)
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        }
        return targetFile
    }

    fun abort() {
        if (finished) return
        finished = true
        partFile.delete()
    }
}

/**
 * Streaming PCM16 WAV writer with atomic publication.
 *
 * The RIFF sizes are patched on [finish] before the `.part` file is renamed
 * over the target, so a crash mid-write never publishes a partial cache.
 */
class Pcm16WavSink internal constructor(
    private val partFile: File,
    private val targetFile: File,
) {
    private val output = RandomAccessFile(partFile, "rw")
    private var sampleCount = 0L
    private var finished = false

    init {
        output.write(riffHeader())
    }

    /** Appends normalized [-1, 1] samples as PCM16. */
    fun write(samples: FloatArray) {
        check(!finished) { "Pcm16WavSink is already finished" }
        val bytes = ByteArray(samples.size * Short.SIZE_BYTES)
        samples.forEachIndexed { index, sample ->
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            val short = value.toShort()
            bytes[index * 2] = (short.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = (short.toInt() ushr 8 and 0xff).toByte()
        }
        output.write(bytes)
        sampleCount += samples.size
    }

    /** Patches the WAV sizes and atomically publishes the file. */
    fun finish() {
        check(!finished) { "Pcm16WavSink is already finished" }
        finished = true
        val dataBytes = sampleCount * Short.SIZE_BYTES
        output.seek(4)
        writeLe32((36 + dataBytes).toInt())
        output.seek(40)
        writeLe32(dataBytes.toInt())
        output.fd.sync()
        output.close()
        publishAtomically(partFile, targetFile)
    }

    /** Discards the partial file without publishing. */
    fun abort() {
        if (finished) return
        finished = true
        runCatching { output.close() }
        partFile.delete()
    }

    private fun writeLe32(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTE_RATE = SAMPLE_RATE * Short.SIZE_BYTES
        private const val BLOCK_ALIGN = Short.SIZE_BYTES
        private const val BITS_PER_SAMPLE = 16

        fun riffHeader(): ByteArray = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0, // RIFF size, patched on finish
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0, // fmt chunk size
            1, 0, // PCM
            1, 0, // mono
            (SAMPLE_RATE and 0xff).toByte(), (SAMPLE_RATE ushr 8 and 0xff).toByte(), 0, 0,
            (BYTE_RATE and 0xff).toByte(), (BYTE_RATE ushr 8 and 0xff).toByte(),
            (BYTE_RATE ushr 16 and 0xff).toByte(), (BYTE_RATE ushr 24 and 0xff).toByte(),
            BLOCK_ALIGN.toByte(), 0,
            BITS_PER_SAMPLE.toByte(), 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            0, 0, 0, 0, // data size, patched on finish
        )

        val RIFF_HEADER: ByteArray = riffHeader()
    }
}

private fun publishAtomically(partFile: File, targetFile: File) {
    if (!partFile.renameTo(targetFile)) {
        targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            throw IOException("Cannot publish decoded-audio cache: $partFile")
        }
    }
}
