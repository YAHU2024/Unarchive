package com.unarchive.android.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16WavInspectorTest {
    @Test
    fun acceptsNormalizedNonZeroPcm16Wav() {
        val file = writeWav(floatArrayOf(0f, 0.5f, -0.5f))

        val result = Pcm16WavInspector.inspect(file, expectedSampleRate = 16_000)

        assertEquals(3L, result.sampleCount)
    }

    @Test
    fun rejectsAllZeroPcm() {
        val file = writeWav(FloatArray(100))

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WavInspector.inspect(file, expectedSampleRate = 16_000)
        }
    }

    @Test
    fun rejectsTruncatedPcmData() {
        val file = writeWav(floatArrayOf(0.5f, -0.5f))
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WavInspector.inspect(file, expectedSampleRate = 16_000)
        }
    }

    private fun writeWav(samples: FloatArray): File {
        val root = Files.createTempDirectory("wav-inspector-test").toFile()
        val cache = DecodedAudioCache(root)
        val sink = cache.newSink("sample")
        sink.write(samples)
        sink.finish()
        return cache.cachedFile("sample")
    }
}
