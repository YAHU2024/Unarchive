package com.unarchive.android.ui.markdown

import java.io.File
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
}
