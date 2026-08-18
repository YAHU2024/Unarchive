package com.unarchive.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCompletionStatusTest {
    @Test
    fun reportsCacheReuse() {
        assertEquals(
            "识别完成。已复用音频缓存，结果已保存到本地。",
            videoCompletionStatus(reusedDownload = true, forceRefreshAudio = false),
        )
    }

    @Test
    fun reportsExplicitRedownload() {
        assertEquals(
            "识别完成。已重新下载音频，结果已保存到本地。",
            videoCompletionStatus(reusedDownload = false, forceRefreshAudio = true),
        )
    }

    @Test
    fun reportsOrdinaryDownload() {
        assertEquals(
            "识别完成。音频已下载，结果已保存到本地。",
            videoCompletionStatus(reusedDownload = false, forceRefreshAudio = false),
        )
    }
}
