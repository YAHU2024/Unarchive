package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCardExportTest {
    @Test
    fun portableEmbedsAssetsAndReportsUtf8Size() {
        val asset = asset("chapter-1", "assets/chapter-1.jpg", byteCount = 3)
        val card = card(
            title = "跑姿: 入门",
            markdown = "# 跑姿\n\n![](assets/chapter-1.jpg)",
            assets = listOf(asset),
        )

        val exported = KnowledgeCardExport.portable(card) { byteArrayOf(1, 2, 3) }

        assertEquals("跑姿 入门.md", exported.fileName)
        assertTrue(exported.markdown.contains("data:image/jpeg;base64,AQID"))
        assertFalse(exported.markdown.contains("assets/chapter-1.jpg"))
        assertEquals(1, exported.assets.embeddedCount)
        assertEquals(3L, exported.assets.embeddedBytes)
        assertEquals(0, exported.assets.missingCount)
        assertTrue(exported.isFullyPortable)
        assertEquals(exported.markdown.toByteArray(Charsets.UTF_8).size.toLong(), exported.markdownBytes)
    }

    @Test
    fun portableKeepsMissingAssetReferenceAndReportsDegradation() {
        val asset = asset("missing", "assets/missing.jpg", byteCount = 42)
        val card = card(markdown = "# Note\n\n![](assets/missing.jpg)", assets = listOf(asset))

        val exported = KnowledgeCardExport.portable(card) { null }

        assertFalse(exported.markdown.contains("assets/missing.jpg"))
        assertEquals(0, exported.assets.embeddedCount)
        assertEquals(0L, exported.assets.embeddedBytes)
        assertEquals(1, exported.assets.missingCount)
        assertEquals(1, exported.assets.requestedCount)
        assertFalse(exported.isFullyPortable)
    }

    @Test
    fun portableWithoutAssetsPreservesMarkdownExactly() {
        val markdown = "---\ntitle: \"中文\"\n---\n\n正文"
        val card = card(title = "中文", markdown = markdown)

        val exported = KnowledgeCardExport.portable(card)

        assertEquals(markdown, exported.markdown)
        assertEquals(0, exported.assets.requestedCount)
        assertEquals(0, exported.assets.embeddedCount)
        assertEquals(0, exported.assets.missingCount)
    }

    private fun asset(assetId: String, path: String, byteCount: Long) = CardAsset(
        assetId = assetId,
        kind = CardAssetKind.CHAPTER_SCREENSHOT,
        mimeType = "image/jpeg",
        relativePath = path,
        byteCount = byteCount,
        sha256 = "hash-$assetId",
    )

    private fun card(
        title: String = "测试卡片",
        markdown: String = "# 测试卡片",
        assets: List<CardAsset> = emptyList(),
    ) = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_EXPORT"),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/BV_EXPORT",
        title = title,
        ownerName = "",
        videoDurationSeconds = 1,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0, 1_000, "内容")),
        assets = assets,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        markdown = markdown,
    )
}
