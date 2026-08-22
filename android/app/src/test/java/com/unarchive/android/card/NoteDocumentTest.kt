package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentTest {
    @Test
    fun migrationPreservesStructuredContentAndIsRepeatable() {
        val asset = CardAsset(
            assetId = "chapter-000",
            kind = CardAssetKind.CHAPTER_SCREENSHOT,
            mimeType = "image/jpeg",
            relativePath = "assets/chapter-000.jpg",
            byteCount = 3,
            sha256 = "asset-hash",
            chapterIndex = 0,
            timestampMs = 2_000,
        )
        val card = card(
            analysis = CardAnalysis(
                summary = "视频总结",
                keyPoints = listOf("要点一", "要点二"),
                chapters = listOf(
                    CardChapter(
                        title = "第一章",
                        startMs = 0,
                        endMs = 10_000,
                        points = listOf(CardPoint(2_000, "章节要点")),
                    ),
                ),
            ),
            tags = listOf("跑姿", "运动"),
            assets = listOf(asset),
            analysisState = CardStageState.SUCCEEDED,
            screenshotsState = CardStageState.SUCCEEDED,
        )

        val migrated = card.toNoteDocument()
        val repeated = card.toNoteDocument()

        assertEquals(NoteDocument.SCHEMA_VERSION, 2)
        assertEquals(migrated, repeated)
        assertEquals(card.cardId, migrated.cardId)
        assertEquals(card.transcript, migrated.sourceTranscript)
        assertEquals(listOf("跑姿", "运动"), migrated.tags)
        assertEquals(NoteGenerationState.COMPLETE, migrated.generation.state)
        assertEquals("视频总结", migrated.blocks[0].text)
        assertEquals(NoteBlockOrigin.AI, migrated.blocks[0].origin)

        val chapter = migrated.blocks.first { it.type == NoteBlockType.CHAPTER }
        assertEquals("第一章", chapter.title)
        assertEquals(2_000L, chapter.points.single().timestampMs)
        assertEquals(listOf("chapter-000"), chapter.assetRefs)
        assertEquals(card.canonicalUrl, chapter.sourceRef?.url)
        assertEquals(64, migrated.publishing.markdownProjectionHash.length)
        assertEquals(
            migrated.publishing.markdownProjectionHash,
            KnowledgeCard.sha256(NoteMarkdownProjection.render(migrated).toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun baseOnlyMigrationRetainsTranscriptAsSourceMaterial() {
        val card = card(
            analysis = null,
            analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED,
        )

        val document = card.toNoteDocument()
        val sourceDraft = document.blocks.single { it.type == NoteBlockType.SUMMARY }
        val markdown = NoteMarkdownProjection.render(document)

        assertEquals(NoteGenerationState.BASE_ONLY, document.generation.state)
        assertEquals(NoteBlockOrigin.SOURCE, sourceDraft.origin)
        assertEquals("原始转写待整理", sourceDraft.text)
        assertEquals(card.transcript, document.sourceTranscript)
        assertTrue(markdown.contains("## 转录全文"))
        assertTrue(markdown.contains("原始转写待整理"))
    }

    @Test
    fun projectionIncludesUserBlockRevisionSourceLinksAndAssets() {
        val document = card(
            analysis = CardAnalysis(
                summary = "总结",
                keyPoints = listOf("要点"),
                chapters = listOf(CardChapter("章节", 0, 10_000, emptyList())),
            ),
            assets = listOf(
                CardAsset(
                    assetId = "chapter-000",
                    kind = CardAssetKind.CHAPTER_SCREENSHOT,
                    mimeType = "image/jpeg",
                    relativePath = "assets/chapter-000.jpg",
                    byteCount = 1,
                    sha256 = "hash",
                    chapterIndex = 0,
                ),
            ),
            analysisState = CardStageState.SUCCEEDED,
        ).toNoteDocument().let { migrated ->
            migrated.copy(
                blocks = migrated.blocks.map { block ->
                    if (block.type == NoteBlockType.CHAPTER) block.copy(text = "章节说明") else block
                } + NoteBlock(
                        id = "user-note-1",
                        type = NoteBlockType.USER_NOTE,
                        origin = NoteBlockOrigin.USER,
                        text = "我需要复习这一章",
                    ),
                editing = migrated.editing.copy(contentRevision = 3, dirty = true),
            )
        }

        val markdown = NoteMarkdownProjection.render(document)

        assertTrue(markdown.contains("content_revision: 3"))
        assertTrue(markdown.contains("## 我的想法\n我需要复习这一章"))
        assertTrue(markdown.contains("章节说明"))
        assertTrue(markdown.contains("![](assets/chapter-000.jpg)"))
        assertTrue(markdown.contains("https://www.bilibili.com/video/BV1test?t=0"))
        assertFalse(markdown.contains("data:image"))
    }

    private fun card(
        analysis: CardAnalysis? = CardAnalysis("默认摘要", listOf("默认要点"), emptyList()),
        tags: List<String> = emptyList(),
        assets: List<CardAsset> = emptyList(),
        analysisState: CardStageState = CardStageState.QUEUED,
        screenshotsState: CardStageState = CardStageState.QUEUED,
    ) = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV1test"),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/BV1test",
        title = "测试笔记",
        ownerName = "UP 主",
        videoDurationSeconds = 20,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(
            TranscriptSegment(0, 5_000, "第一段转写"),
            TranscriptSegment(5_000, 10_000, "第二段转写"),
        ),
        analysis = analysis,
        tags = tags,
        assets = assets,
        analysisState = analysisState,
        screenshotsState = screenshotsState,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 2_000,
        markdown = "# 测试笔记",
    )
}
