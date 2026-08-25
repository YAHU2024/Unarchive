package com.unarchive.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiveBlockTest {
    @Test
    fun splitsSupportedAndFallbackBlocksWithoutChangingSource() {
        val markdown = """---
title: "保留"
---

# 标题

正文 **加粗**。

| 列一 | 列二 |
| --- | --- |
| A | B |

```kotlin
println("保留")
```
""".trimEnd()

        val blocks = MarkdownLiveBlockParser.parse(markdown)

        assertEquals(
            listOf(
                MarkdownLiveBlockType.FRONT_MATTER,
                MarkdownLiveBlockType.HEADING,
                MarkdownLiveBlockType.PARAGRAPH,
                MarkdownLiveBlockType.TABLE_SOURCE,
                MarkdownLiveBlockType.CODE,
            ),
            blocks.map { it.type },
        )
        assertFalse(blocks.first().editable)
        blocks.forEach { block ->
            assertEquals(block.markdown, markdown.substring(block.startOffset, block.endOffset))
        }
    }

    @Test
    fun localReplacementPreservesCrLfUnknownHtmlAndSurroundingWhitespace() {
        val markdown = "# 标题\r\n\r\n正文\r\n\r\n<custom value=\"1\">保留</custom>\r\n"
        val paragraph = MarkdownLiveBlockParser.parse(markdown)
            .single { it.type == MarkdownLiveBlockType.PARAGRAPH }

        val replaced = MarkdownLiveBlockParser.replace(markdown, paragraph, "新正文")

        assertEquals("# 标题\r\n\r\n新正文\r\n\r\n<custom value=\"1\">保留</custom>\r\n", replaced)
    }

    @Test
    fun unclosedFenceRemainsOneEditableSourceBlock() {
        val markdown = "# 标题\n\n```kotlin\nval value = 1\n未闭合"
        val blocks = MarkdownLiveBlockParser.parse(markdown)

        assertEquals(MarkdownLiveBlockType.CODE, blocks.last().type)
        assertEquals("```kotlin\nval value = 1\n未闭合", blocks.last().markdown)
    }

    @Test
    fun longUnicodeDocumentSplitsWithoutDroppingContent() {
        val markdown = buildString {
            append("# 长文\n\n")
            repeat(4_000) { append("第 $it 段中文内容。\n\n") }
        }
        assertTrue(markdown.toByteArray(Charsets.UTF_8).size >= 100_000)

        val blocks = MarkdownLiveBlockParser.parse(markdown)

        assertEquals(4_001, blocks.size)
        blocks.forEach { assertEquals(it.markdown, markdown.substring(it.startOffset, it.endOffset)) }
    }
}
