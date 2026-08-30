package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkdownWritebackTest {
    @Test
    fun renderedMarkdownRoundTripsAsCurrentAndKeepsBlockIds() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)

        val result = NoteMarkdownWriteback.apply(document, markdown)
        assertEquals(NoteProjectionStatus.CURRENT, result.status)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.projection.unknownMarkdown.isEmpty())
        assertEquals(document.title, result.document.title)
        assertEquals(document.blocks, result.document.blocks)
        assertEquals(document.tags, result.document.tags)
        assertEquals(document.blocks.map { it.id }, result.projection.recognizedBlockIds)
    }

    @Test
    fun supportedFieldsWriteBackWithoutChangingProtectedSourceData() {
        val document = document()
        val originalTranscript = document.sourceTranscript
        val originalAssets = document.assets
        val originalRelations = document.relations
        val markdown = NoteMarkdownProjection.render(document)
            .replace("# 原始标题", "# 新标题")
            .replace("原始摘要", "新摘要")
            .replace("- 原始要点", "- 新要点")
            .replace("### 00:00–00:10 原始章节", "### 00:00–00:10 新章节")
            .replace("原始说明", "新的章节说明")
            .replace("我的想法", "我的想法")
            .replace("原始想法", "新的想法")
            .replace("- 旧标签", "- 新标签")

        val result = NoteMarkdownWriteback.apply(document, markdown)
        assertEquals(NoteProjectionStatus.CURRENT, result.status)
        assertEquals("新标题", result.document.title)
        assertEquals("新摘要", result.document.blocks.first { it.type == NoteBlockType.SUMMARY }.text)
        assertEquals("新要点", result.document.blocks.first { it.type == NoteBlockType.KEY_POINT }.text)
        val chapter = result.document.blocks.first { it.type == NoteBlockType.CHAPTER }
        assertEquals("新章节", chapter.title)
        assertEquals("新的章节说明", chapter.text)
        assertEquals("新的想法", result.document.blocks.first { it.type == NoteBlockType.USER_NOTE }.text)
        assertEquals(listOf("新标签"), result.document.tags)
        assertEquals(originalTranscript, result.document.sourceTranscript)
        assertEquals(originalAssets, result.document.assets)
        assertEquals(originalRelations, result.document.relations)
    }

    @Test
    fun unknownSectionAndCrLfAreRetainedAndMarkedPartial() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)
            .replace("\n", "\r\n") +
            "\r\n## 自定义内容\r\n```kotlin\r\nval answer = 42\r\n```\r\n"

        val result = NoteMarkdownWriteback.apply(document, markdown)
        assertEquals(NoteProjectionStatus.PARTIAL, result.status)
        assertTrue(result.projection.unknownMarkdown.contains("## 自定义内容\r\n"))
        assertTrue(result.projection.unknownMarkdown.contains("val answer = 42\r\n"))
        assertTrue(result.projection.unknownMarkdown.contains("```kotlin\r\n"))
    }

    @Test
    fun unknownFrontMatterAndChangedSourceUrlAreRetainedAndMarkedPartial() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)
            .replace("author: \"作者\"", "author: \"作者\"\ncustom: \"keep me\"")
            .replace("source: https://www.bilibili.com/video/BV_writeback", "source: https://example.invalid/changed")

        val result = NoteMarkdownWriteback.apply(document, markdown)

        assertEquals(NoteProjectionStatus.PARTIAL, result.status)
        assertTrue(result.projection.unknownMarkdown.contains("custom: \"keep me\""))
        assertTrue(result.projection.unknownMarkdown.contains("source: https://example.invalid/changed"))
        assertEquals(document.source.canonicalUrl, result.document.source.canonicalUrl)
    }

    @Test
    fun fencedCodeInsideSupportedSectionIsNotFoldedIntoStructuredText() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)
            .replace("原始摘要", "正文前\n```kotlin\nval answer = 42\n```\n正文后")

        val result = NoteMarkdownWriteback.apply(document, markdown)
        val summary = result.document.blocks.first { it.type == NoteBlockType.SUMMARY }

        assertEquals(NoteProjectionStatus.PARTIAL, result.status)
        assertEquals("正文前\n正文后", summary.text)
        assertTrue(result.projection.unknownMarkdown.contains("```kotlin\nval answer = 42\n```\n"))
    }

    @Test
    fun changedChapterTimestampIsNotWrittenToProtectedSourceRange() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)
            .replace("### 00:00–00:10 原始章节", "### 00:01–00:11 新章节")

        val result = NoteMarkdownWriteback.apply(document, markdown)
        val chapter = result.document.blocks.first { it.type == NoteBlockType.CHAPTER }

        assertEquals(NoteProjectionStatus.PARTIAL, result.status)
        assertEquals(0L, chapter.startMs)
        assertEquals(10_000L, chapter.endMs)
        assertEquals("新章节", chapter.title)
        assertTrue(result.warnings.any { it.contains("时间戳") })
    }

    @Test
    fun clearingTagsRemovesTagBlockWithoutTouchingOtherBlocks() {
        val document = document()
        val markdown = NoteMarkdownProjection.render(document)
            .replace("- 旧标签\r\n", "")
            .replace("- 旧标签\n", "")

        val result = NoteMarkdownWriteback.apply(document, markdown)

        assertEquals(NoteProjectionStatus.CURRENT, result.status)
        assertTrue(result.document.tags.isEmpty())
        assertFalse(result.document.blocks.any { it.type == NoteBlockType.TAG_LIST })
    }

    private fun document(): NoteDocument {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_writeback"),
            cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_writeback",
            title = "原始标题",
            ownerName = "作者",
            videoDurationSeconds = 20,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(
                TranscriptSegment(0L, 5_000L, "转写一"),
                TranscriptSegment(5_000L, 10_000L, "转写二"),
            ),
            analysis = CardAnalysis(
                summary = "原始摘要",
                keyPoints = listOf("原始要点"),
                chapters = listOf(
                    CardChapter("原始章节", 0L, 10_000L, listOf(CardPoint(2_000L, "章节要点"))),
                ),
            ),
            tags = listOf("旧标签"),
            assets = listOf(
                CardAsset(
                    assetId = "chapter-asset",
                    kind = CardAssetKind.CHAPTER_SCREENSHOT,
                    mimeType = "image/jpeg",
                    relativePath = "assets/chapter.jpg",
                    byteCount = 10L,
                    sha256 = "asset-hash",
                    chapterIndex = 0,
                ),
            ),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            markdown = "# 原始标题",
        )
        val migrated = card.toNoteDocument()
        return migrated.copy(
            blocks = migrated.blocks.map { block ->
                if (block.type == NoteBlockType.CHAPTER) block.copy(text = "原始说明") else block
            } + NoteBlock(
                id = "user-note-1",
                type = NoteBlockType.USER_NOTE,
                origin = NoteBlockOrigin.USER,
                text = "原始想法",
            ),
        )
    }
}
