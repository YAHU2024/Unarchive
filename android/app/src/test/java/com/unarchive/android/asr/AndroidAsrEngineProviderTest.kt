package com.unarchive.android.asr

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidAsrEngineProviderTest {
    /**
     * Unavailable engines must fail loudly instead of silently returning the
     * preview stub. The guard is a pure companion function so it is testable
     * without a device context.
     */
    @Test
    fun rejectsUnavailableEngineKinds() {
        assertThrows(IllegalStateException::class.java) {
            AndroidAsrEngineProvider.requireAvailable(AsrEngineKind.WHISPER_SHERPA)
        }
        assertThrows(IllegalStateException::class.java) {
            AndroidAsrEngineProvider.requireAvailable(AsrEngineKind.WHISPER_CPP)
        }
    }

    @Test
    fun acceptsAvailableEngineKind() {
        AndroidAsrEngineProvider.requireAvailable(AsrEngineKind.SENSE_VOICE_SHERPA)
    }
}
