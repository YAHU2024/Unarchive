package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        assertThrows(IllegalArgumentException::class.java) {
            AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA, parallelWorkers = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsrConfig(engine = AsrEngineKind.SILICONFLOW_CLOUD, siliconFlowModel = " ")
        }
    }

    @Test
    fun cloudModelChangesSignatureButDoesNotInvalidateLocalConfig() {
        val cloud = AsrConfig(AsrEngineKind.SILICONFLOW_CLOUD)
        val alternateCloud = cloud.copy(siliconFlowModel = "Qwen/ASR-Test")
        assertNotEquals(cloud.signature(), alternateCloud.signature())
        assertNotEquals(
            cloud.signature(),
            cloud.copy(siliconFlowModel = SiliconFlowModelCatalog.LEGACY_DEFAULT_MODEL).signature(),
        )

        val local = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA)
        assertEquals(local.signature(), local.copy(siliconFlowModel = "Qwen/ASR-Test").signature())
    }

    @Test
    fun parallelWorkersAreCappedByRamClassBigCoresAndMaximum() {
        // 8 GB-class device with 4 big cores: request honored.
        assertEquals(2, ParallelWorkers.infer(2, 512, exclusiveCoreCount = 4))
        // Small-RAM device: forced back to a single worker.
        assertEquals(1, ParallelWorkers.infer(2, 128, exclusiveCoreCount = 4))
        assertEquals(1, ParallelWorkers.infer(4, 200, exclusiveCoreCount = 4))
        // 2-big-core SoC (Snapdragon 695): parallel instances contend and
        // measured RTF is worse, so stay single-worker.
        assertEquals(1, ParallelWorkers.infer(2, 512, exclusiveCoreCount = 2))
        assertEquals(1, ParallelWorkers.infer(4, 512, exclusiveCoreCount = 1))
        // Request beyond the sane ceiling is capped.
        assertEquals(4, ParallelWorkers.infer(8, 512, exclusiveCoreCount = 4))
        assertEquals(1, ParallelWorkers.infer(1, 512, exclusiveCoreCount = 4))
        // Unknown core layout (pre-API-33 or OEM returns empty, e.g. OPPO
        // PHQ110): conservative single worker instead of the request.
        assertEquals(1, ParallelWorkers.infer(2, 512, exclusiveCoreCount = 0))
        assertEquals(1, ParallelWorkers.infer(4, 384, exclusiveCoreCount = 0))
    }

    @Test
    fun threadDefaultsPreferExclusiveBigCoresCappedAtFour() {
        // 2 big + 6 little (Snapdragon 695): pool sized to the big cores.
        assertEquals(2, AsrThreadDefaults.infer(8, intArrayOf(0, 1)))
        // 4 big + 4 little (RK3588-class): 4 threads.
        assertEquals(4, AsrThreadDefaults.infer(8, intArrayOf(4, 5, 6, 7)))
        // Broken OEM reports: exclusive list of all cores stays capped.
        assertEquals(4, AsrThreadDefaults.infer(8, (0 until 8).toList().toIntArray()))
        assertEquals(1, AsrThreadDefaults.infer(8, intArrayOf(3)))
    }

    @Test
    fun threadDefaultsFallBackToProcessorCountWithoutExclusiveInfo() {
        // Unknown CPU layout (OEM returns empty): conservative 2 threads.
        assertEquals(2, AsrThreadDefaults.infer(8, null))
        assertEquals(2, AsrThreadDefaults.infer(8, IntArray(0)))
        assertEquals(2, AsrThreadDefaults.infer(2, null))
        assertEquals(1, AsrThreadDefaults.infer(1, null))
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

    @Test
    fun onlySenseVoiceIsMarkedAvailable() {
        assertTrue(AsrEngineKind.SENSE_VOICE_SHERPA.available)
        assertFalse(AsrEngineKind.WHISPER_SHERPA.available)
        assertFalse(AsrEngineKind.WHISPER_CPP.available)
    }
}
