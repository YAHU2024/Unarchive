package com.unarchive.android.editor.assets

import com.unarchive.android.card.PortableImageFixtures
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ImageImportPolicyTest {
    @Test fun boundedCopyAcceptsExactLimitAndClosesNoCallerOwnedStream() {
        val policy = ImageImportPolicy(maxInputBytes = 3L)
        val output = ByteArrayOutputStream()
        assertEquals(3L, policy.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test fun oversizedInputStopsBeforeWritingTheOverflowByte() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(ImageImportException::class.java) {
            ImageImportPolicy(maxInputBytes = 3L).copyBounded(ByteArrayInputStream(ByteArray(10)), output)
        }
        assertEquals(ImageImportFailureReason.TooLarge, error.reason)
        assertTrue(output.size() <= 3)
    }

    @Test fun emptyAndOversizedDimensionsAreRejectedBeforeDecode() {
        val policy = ImageImportPolicy()
        assertEquals(ImageImportFailureReason.Corrupt, assertThrows(ImageImportException::class.java) { policy.validateBounds(0, 1) }.reason)
        assertEquals(ImageImportFailureReason.TooManyPixels, assertThrows(ImageImportException::class.java) { policy.validateBounds(Int.MAX_VALUE, Int.MAX_VALUE) }.reason)
        policy.validateBounds(6_000, 4_000)
    }

    @Test fun decodeSampleAndOutputSizeAreBoundedWithoutUpscaling() {
        val policy = ImageImportPolicy()
        val sample = policy.sampleSize(6_000, 4_000)
        assertTrue(((6_000L + sample - 1) / sample) * ((4_000L + sample - 1) / sample) <= 4_000_000L)
        assertEquals(2560 to 1280, policy.targetSize(4000, 2000))
        assertEquals(120 to 80, policy.targetSize(120, 80))
    }

    @Test fun lowSpaceAndEmptyStreamsHaveSpecificRecoverableFailures() {
        val policy = ImageImportPolicy()
        assertEquals(ImageImportFailureReason.LowSpace, assertThrows(ImageImportException::class.java) { policy.ensureFreeSpace(0L) }.reason)
        assertEquals(ImageImportFailureReason.Corrupt, assertThrows(ImageImportException::class.java) {
            policy.copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        }.reason)
    }
}
