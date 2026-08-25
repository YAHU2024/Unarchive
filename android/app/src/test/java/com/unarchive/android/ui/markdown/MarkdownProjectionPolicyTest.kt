package com.unarchive.android.ui.markdown

import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownProjectionPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyApprovedHttpsBilibiliLinksAreAllowed() {
        assertTrue(isAllowedMarkdownUri("https://www.bilibili.com/video/BV1xx"))
        assertTrue(isAllowedMarkdownUri("https://b23.tv/abc?t=120"))
        assertFalse(isAllowedMarkdownUri("http://www.bilibili.com/video/BV1xx"))
        assertFalse(isAllowedMarkdownUri("https://example.com/video"))
        assertFalse(isAllowedMarkdownUri("javascript:alert(1)"))
        assertFalse(isAllowedMarkdownUri("not a uri"))
    }

    @Test
    fun localAssetResolverRejectsTraversalAndRemoteLinks() {
        val root = temporaryFolder.newFolder("assets")
        val resolver = LocalFileMarkdownAssetResolver(root)

        assertFalse(resolver.isAvailable("../private.png"))
        assertFalse(resolver.isAvailable("https://www.bilibili.com/image.png"))
        assertFalse(resolver.isAvailable(File(root, "missing.png").absolutePath))
    }

    @Test
    fun unavailableImagesBecomeReadableTextWithoutRewritingCodeOrLinks() {
        val markdown = """
            # 标题

            ![本地截图](missing-local.png)

            ```markdown
            ![代码中的图片](kept-as-code.png)
            ```

            [来源](https://www.bilibili.com/video/BV1xx)
        """.trimIndent()

        val projected = projectUnavailableImages(markdown, NoOpMarkdownAssetResolver)

        assertTrue(projected.contains("图片不可用：本地截图"))
        assertFalse(projected.contains("![本地截图](missing-local.png)"))
        assertTrue(projected.contains("![代码中的图片](kept-as-code.png)"))
        assertTrue(projected.contains("[来源](https://www.bilibili.com/video/BV1xx)"))
    }

    @Test
    fun imageReferencesOnlyIncludeMarkdownImageNodes() {
        val references = markdownImageReferences(
            "![章节截图](assets/chapter-000.jpg)\n\n" +
                "```markdown\n![代码中的图片](ignored.jpg)\n```",
        )

        assertEquals(
            "refs=$references",
            listOf(MarkdownImageReference("assets/chapter-000.jpg", "章节截图")),
            references,
        )
    }

    @Test
    fun cardAssetResolverOnlyAcceptsOwnedRelativePaths() {
        val asset = CardAsset(
            assetId = "chapter-000",
            kind = CardAssetKind.CHAPTER_SCREENSHOT,
            mimeType = "image/jpeg",
            relativePath = "assets/chapter-000.jpg",
            byteCount = 1,
            sha256 = "hash",
        )
        val resolver = CardAssetMarkdownResolver(listOf(asset)) { null }

        assertFalse(resolver.isAvailable("assets/other.jpg"))
        assertFalse(resolver.isAvailable("../assets/chapter-000.jpg"))
        assertFalse(resolver.isAvailable("https://www.bilibili.com/image.jpg"))
    }

    @Test
    fun cardAssetResolverRejectsUnsupportedMimeBeforeReadingTheFile() {
        val file = temporaryFolder.newFile("chapter.gif").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val asset = CardAsset(
            assetId = "chapter-gif",
            kind = CardAssetKind.CHAPTER_SCREENSHOT,
            mimeType = "image/gif",
            relativePath = "assets/chapter.gif",
            byteCount = file.length(),
            sha256 = "hash",
        )
        val resolver = CardAssetMarkdownResolver(listOf(asset)) { file }

        assertFalse(resolver.isAvailable(asset.relativePath))
        assertFalse(resolver.resolve(asset.relativePath) != null)
    }
}
