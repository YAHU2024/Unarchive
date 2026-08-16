package com.unarchive.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecodedAudioCacheTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("cache-test").toFile()

    @Test
    fun cachesByFingerprintDirectory() {
        val cache = DecodedAudioCache(tempDir())

        val file = cache.cachedFile("abc123")

        assertEquals("decoded-16k.wav", file.name)
        assertEquals("abc123", file.parentFile?.name)
        assertFalse(cache.isValid(file))
    }

    @Test
    fun publishesAtomicallyViaPartAndRename() {
        val root = tempDir()
        val cache = DecodedAudioCache(root)
        val target = cache.cachedFile("fp1")

        val sink = cache.newSink("fp1")
        sink.write(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f))
        sink.finish()

        assertTrue(cache.isValid(target))
        assertEquals(0, root.listFiles { f -> f.name.endsWith(".part") }?.size ?: -1)
        // Header (44) + 5 samples * 2 bytes.
        assertEquals(44 + 10, target.length())
    }

    @Test
    fun abortDiscardsPartialFile() {
        val cache = DecodedAudioCache(tempDir())
        val target = cache.cachedFile("fp2")

        val sink = cache.newSink("fp2")
        sink.write(floatArrayOf(0.1f))
        sink.abort()

        assertFalse(target.exists())
    }

    @Test
    fun publishesCompleteExternalFileAtomically() {
        val cache = DecodedAudioCache(tempDir())
        val target = cache.cachedFile("external")
        val sink = cache.newFileSink("external")
        sink.partFile.writeBytes(ByteArray(46) { it.toByte() })

        val published = sink.finish()

        assertEquals(target, published)
        assertTrue(target.isFile)
        assertEquals(46L, target.length())
        assertFalse(sink.partFile.exists())
    }

    @Test
    fun abortingExternalFilePreservesPublishedTarget() {
        val cache = DecodedAudioCache(tempDir())
        val original = cache.newSink("external-abort")
        original.write(floatArrayOf(0.5f))
        original.finish()
        val target = cache.cachedFile("external-abort")
        val originalLength = target.length()

        val replacement = cache.newFileSink("external-abort")
        replacement.partFile.writeBytes(ByteArray(100))
        replacement.abort()

        assertTrue(target.isFile)
        assertEquals(originalLength, target.length())
        assertFalse(replacement.partFile.exists())
    }

    @Test
    fun writtenWavRoundTripsThroughWaveDecoder() {
        val cache = DecodedAudioCache(tempDir())
        val sink = cache.newSink("fp3")
        sink.write(floatArrayOf(0f, 0.5f, -0.5f, 0.25f, -0.25f))
        sink.finish()

        val decoded = WaveDecoder.decode(
            cache.cachedFile("fp3").inputStream(),
            targetSampleRate = 16_000,
        )

        assertEquals(16_000, decoded.sampleRate)
        assertEquals(5, decoded.samples.size)
        assertEquals(0.5f, decoded.samples[1], 0.001f)
        assertEquals(-0.5f, decoded.samples[2], 0.001f)
    }

    @Test
    fun invalidateDeletesEntry() {
        val cache = DecodedAudioCache(tempDir())
        val sink = cache.newSink("fp4")
        sink.write(floatArrayOf(1f))
        sink.finish()
        val file = cache.cachedFile("fp4")
        assertTrue(file.exists())

        cache.invalidate(file)

        assertFalse(file.exists())
    }
}
