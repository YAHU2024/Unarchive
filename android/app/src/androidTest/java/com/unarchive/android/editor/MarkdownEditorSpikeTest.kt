package com.unarchive.android.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileKnowledgeCardRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver
import com.unarchive.android.ui.markdown.cardAssetMarkdownResolver
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownEditorSpikeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultsToLivePreviewAndKeepsImageReferenceVisible() {
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = "# 截图笔记\n\n![](assets/chapter-000.jpg)",
                    onMarkdownChange = {},
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-preview").assertIsDisplayed()
        composeRule.onNodeWithText("截图笔记").assertIsDisplayed()
        composeRule.onNodeWithText("图片不可用：图片").assertIsDisplayed()
    }

    @Test
    fun sourceChangeIsAcceptedWithoutReplacingTheCallerOwnedText() {
        var source = "# 原标题"
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = source,
                    onMarkdownChange = { source = it },
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        composeRule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 新标题")
        assert(source == "# 新标题")
    }

    @Test
    fun exposesControlledLocalScreenshotInsertion() {
        var source = "# 笔记"
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = source,
                    onMarkdownChange = { source = it },
                    assetResolver = NoOpMarkdownAssetResolver,
                    imageOptions = listOf(
                        MarkdownEditorImageOption("assets/chapter-000.jpg", "章节截图"),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        composeRule.onNodeWithTag("markdown-editor-image-actions").assertIsDisplayed()
        composeRule.onNodeWithText("章节截图").performClick()
        assert(source.contains("![章节截图](assets/chapter-000.jpg)"))
    }

    @Test
    fun derivesInsertionOptionsFromCardAssetsWithoutCover() {
        val options = markdownImageOptions(
            listOf(
                CardAsset("cover", CardAssetKind.COVER, "image/jpeg", "assets/cover.jpg", 1, "hash"),
                CardAsset("chapter-001", CardAssetKind.CHAPTER_SCREENSHOT, "image/jpeg", "assets/chapter-001.jpg", 1, "hash", chapterIndex = 1),
                CardAsset("chapter-000", CardAssetKind.CHAPTER_SCREENSHOT, "image/jpeg", "assets/chapter-000.jpg", 1, "hash", chapterIndex = 0),
            ),
        )

        assert(options.map { it.link } == listOf("assets/chapter-000.jpg", "assets/chapter-001.jpg"))
    }

    @Test
    fun resolvesRealJpegAndPngAssetsFromThePersistedCardRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "markdown-editor-spike-${System.nanoTime()}")
        val repository = FileKnowledgeCardRepository(directory)
        val card = card()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        try {
            repository.save(card)
            val jpeg = repository.saveAsset(
                card.cardId,
                card.cardVersion,
                "chapter-jpeg",
                bitmapBytes(bitmap, Bitmap.CompressFormat.JPEG),
                chapterIndex = 0,
            )
            val png = repository.saveAsset(
                card.cardId,
                card.cardVersion,
                "chapter-png",
                bitmapBytes(bitmap, Bitmap.CompressFormat.PNG),
                chapterIndex = 1,
                mimeType = "image/png",
            )
            val persisted = card.copy(assets = listOf(jpeg, png))
            repository.save(persisted)

            val resolver = cardAssetMarkdownResolver(persisted, repository)
            assertTrue(resolver.isAvailable(jpeg.relativePath))
            assertTrue(resolver.isAvailable(png.relativePath))
            assertNotNull(resolver.resolve(jpeg.relativePath))
            assertNotNull(resolver.resolve(png.relativePath))
            assertTrue(!resolver.isAvailable("assets/not-owned.jpg"))
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun previewUsesPersistedImageAndExposesLargeImageAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "markdown-editor-preview-${System.nanoTime()}")
        val repository = FileKnowledgeCardRepository(directory)
        val card = card()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        try {
            repository.save(card)
            val asset = repository.saveAsset(
                card.cardId,
                card.cardVersion,
                "chapter-preview",
                bitmapBytes(bitmap, Bitmap.CompressFormat.JPEG),
                chapterIndex = 0,
            )
            val persisted = card.copy(assets = listOf(asset))
            repository.save(persisted)
            val resolver = cardAssetMarkdownResolver(persisted, repository)

            composeRule.setContent {
                UnarchiveTheme {
                    MarkdownEditorSpike(
                        markdown = "# 预览\n\n![章节截图](${asset.relativePath})",
                        onMarkdownChange = {},
                        assetResolver = resolver,
                        initialMode = MarkdownEditorSpikeMode.PREVIEW,
                    )
                }
            }

            composeRule.onNodeWithTag("markdown-editor-preview-images").assertIsDisplayed()
            composeRule.onNodeWithText("查看大图：章节截图").assertIsDisplayed()
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun longPreviewScrollsAndReturnsToSourceAtTwoXFontScale() {
        val markdown = longMarkdown()
        check(markdown.toByteArray(Charsets.UTF_8).size >= 100_000)

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                UnarchiveTheme {
                    MarkdownEditorSpike(
                        markdown = markdown,
                        onMarkdownChange = {},
                        assetResolver = NoOpMarkdownAssetResolver,
                        initialMode = MarkdownEditorSpikeMode.PREVIEW,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("markdown-editor-preview").assertIsDisplayed()
        composeRule.onNodeWithText("长文标题").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-editor-preview").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        composeRule.onNodeWithTag("markdown-editor-source").assertIsDisplayed()
    }

    @Test
    fun previewUpdatesAfterSourceStopsChangingWithinFiveHundredMilliseconds() {
        var source by mutableStateOf("# 旧标题")
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = source,
                    onMarkdownChange = { source = it },
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        composeRule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 新标题")
        composeRule.onNodeWithTag("markdown-editor-preview-tab").performClick()
        composeRule.waitUntil(timeoutMillis = 500) {
            composeRule.onAllNodesWithText("新标题").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun editModeExposesPreviewHintForAssistiveTechnology() {
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = "# 标题",
                    onMarkdownChange = {},
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        composeRule.onNodeWithContentDescription("实时预览提示").assertIsDisplayed()
        composeRule.onNodeWithText("Markdown 源码").assertIsDisplayed()
    }

    private fun bitmapBytes(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(format, 90, output))
            output.toByteArray()
        }

    private fun card() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_markdown_spike"),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/BV_markdown_spike",
        title = "Markdown Spike",
        ownerName = "测试作者",
        videoDurationSeconds = 2,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(),
        baseState = CardStageState.SUCCEEDED,
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.QUEUED,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        markdown = "# Markdown Spike",
    )

    private fun longMarkdown(): String = buildString {
        append("# 长文标题\n\n")
        repeat(25_000) {
            append("这是用于预览阶段验收的脱敏长文段落，内容只用于验证滚动和返回流程。\n\n")
        }
    }
}
