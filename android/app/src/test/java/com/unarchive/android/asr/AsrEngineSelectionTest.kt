package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrEngineSelectionTest {
    @Test
    fun missingOrInvalidPreferenceDefaultsToCloud() {
        assertEquals(AsrEngineKind.SILICONFLOW_CLOUD, AsrEngineSelection.fromPersistedName(null))
        assertEquals(AsrEngineKind.SILICONFLOW_CLOUD, AsrEngineSelection.fromPersistedName("not-an-engine"))
        assertEquals(AsrEngineKind.SILICONFLOW_CLOUD, AsrEngineSelection.fromPersistedName(AsrEngineKind.WHISPER_SHERPA.name))
    }

    @Test
    fun selectableAvailableEngineRoundTrips() {
        assertEquals(
            AsrEngineKind.SENSE_VOICE_SHERPA,
            AsrEngineSelection.fromPersistedName(AsrEngineKind.SENSE_VOICE_SHERPA.name),
        )
    }
}
