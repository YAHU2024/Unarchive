package com.unarchive.android.card

import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownCardRendererTest {

    private fun result(
        title: String = "4种常见错误跑姿",
        ownerName: String = "奥运冠军王丽萍",
        canonicalUrl: String = "https://www.bilibili.com/video/BV1PS42197aM",
        segments: List<TranscriptSegment> = listOf(
            TranscriptSegment(0, 5_000, "今天讲四个常见的错误跑姿"),
            TranscriptSegment(5_000, 10_000, "第一种是过度跨步"),
        ),
    ) = StoredVideoResult(
        key = VideoResultKey("bilibili", "BV1PS42197aM"),
        canonicalUrl = canonicalUrl,
        title = title,
        ownerName = ownerName,
        videoDurationSeconds = 34,
        engine = AsrEngineKind.SENSE_VOICE_SHERPA,
        processingDurationMs = 10_742,
        audioDurationMs = 33_645,
        segments = segments,
        createdAtEpochMs = 1_700_000_000_000,
        updatedAtEpochMs = 1_700_000_000_000,
    )

    @Test
    fun rendersFrontmatterTitleAndTranscript() {
        val markdown = MarkdownCardRenderer.render(result())

        assertTrue(markdown.startsWith("---\n"))
        assertTrue(markdown.contains("title: \"4种常见错误跑姿\"\n"))
        assertTrue(markdown.contains("author: \"奥运冠军王丽萍\"\n"))
        assertTrue(markdown.contains("source: https://www.bilibili.com/video/BV1PS42197aM\n"))
        assertTrue(markdown.contains("# 4种常见错误跑姿\n"))
        assertTrue(markdown.contains("## 转录全文\n"))
    }

    @Test
    fun rendersClickableTimestampLinks() {
        val markdown = MarkdownCardRenderer.render(result())

        assertTrue(markdown.contains("[00:00](https://www.bilibili.com/video/BV1PS42197aM?t=0) 今天讲四个常见的错误跑姿"))
        assertTrue(markdown.contains("[00:05](https://www.bilibili.com/video/BV1PS42197aM?t=5) 第一种是过度跨步"))
    }

    @Test
    fun timestampLinkUsesAmpersandWhenUrlAlreadyHasQuery() {
        val markdown = MarkdownCardRenderer.render(
            result(canonicalUrl = "https://www.bilibili.com/video/BV1PS42197aM?spm_id_from=333.337"),
        )

        assertTrue(markdown.contains("?spm_id_from=333.337&t=0)"))
    }

    @Test
    fun fileNameSanitizesIllegalCharacters() {
        val markdown = MarkdownCardRenderer.fileName(
            result(title = "标题: 含/非法\\字符?"),
        )

        assertEquals("标题 含 非法 字符.md", markdown)
    }

    @Test
    fun fileNameFallsBackWhenTitleIsBlank() {
        assertEquals("untitled.md", MarkdownCardRenderer.fileName(result(title = "   ")))
    }

    @Test
    fun frontmatterEscapesQuotesInTitle() {
        val markdown = MarkdownCardRenderer.render(result(title = "说\"好\"的"))

        assertTrue(markdown.contains("title: \"说\\\"好\\\"的\"\n"))
    }

    @Test
    fun transcribedDateMatchesIsoLocalDate() {
        val markdown = MarkdownCardRenderer.render(result())
        val line = markdown.lineSequence().first { it.startsWith("transcribed:") }
        val date = line.removePrefix("transcribed: ").trim()

        assertTrue(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun emptySegmentsStillRenderHeader() {
        val markdown = MarkdownCardRenderer.render(result(segments = emptyList()))

        assertTrue(markdown.contains("## 转录全文"))
        assertFalse(markdown.contains("[]"))
    }

    @Test
    fun rendersSummaryStoryLineAndKeyPointsWhenAnalysisPresent() {
        val analysis = CardAnalysis(
            summary = "视频讲了四个跑姿错误",
            keyPoints = listOf("要点1", "要点2"),
            chapters = listOf(
                CardChapter(
                    title = "错误一：过度跨步",
                    startMs = 0,
                    endMs = 5_000,
                    points = listOf(CardPoint(2_000, "跨步过大")),
                ),
            ),
        )

        val markdown = MarkdownCardRenderer.render(result(), analysis)

        assertTrue(markdown.contains("## 摘要\n视频讲了四个跑姿错误\n"))
        assertTrue(markdown.contains("## 故事线\n"))
        assertTrue(markdown.contains("### 00:00–00:05 错误一：过度跨步\n"))
        assertTrue(markdown.contains("- [00:02](https://www.bilibili.com/video/BV1PS42197aM?t=2) 跨步过大\n"))
        assertTrue(markdown.contains("## 关键要点\n- 要点1\n- 要点2\n"))
    }

    @Test
    fun rendersOnlyBaseLayerWhenAnalysisNull() {
        val markdown = MarkdownCardRenderer.render(result())

        assertFalse(markdown.contains("## 摘要"))
        assertFalse(markdown.contains("## 故事线"))
        assertFalse(markdown.contains("## 关键要点"))
    }
}
