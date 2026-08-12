package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AsrModelsTest {
    @Test
    fun configRejectsInvalidAudioParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA, sampleRateHz = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA, contextPaddingMs = -1)
        }
    }

    @Test
    fun transcriptSegmentRequiresOrderedNonNegativeTimestamps() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptSegment(startMs = -1, endMs = 1, text = "invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptSegment(startMs = 2, endMs = 1, text = "invalid")
        }

        val segment = TranscriptSegment(startMs = 1, endMs = 2, text = "valid")
        assertEquals("valid", segment.text)
    }
}
