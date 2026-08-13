package com.unarchive.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCompletionStatusTest {
    @Test
    fun reportsCacheReuse() {
        assertEquals(
            "Recognition complete. Audio cache reused and result saved locally.",
            videoCompletionStatus(reusedDownload = true, forceRefreshAudio = false),
        )
    }

    @Test
    fun reportsExplicitRedownload() {
        assertEquals(
            "Recognition complete. Audio redownloaded and result saved locally.",
            videoCompletionStatus(reusedDownload = false, forceRefreshAudio = true),
        )
    }

    @Test
    fun reportsOrdinaryDownload() {
        assertEquals(
            "Recognition complete. Audio downloaded and result saved locally.",
            videoCompletionStatus(reusedDownload = false, forceRefreshAudio = false),
        )
    }
}
