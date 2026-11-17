package com.unarchive.android.editor.clipboard

import com.unarchive.android.editor.session.*
import org.junit.Assert.*
import org.junit.Test

class ClipboardNormalizerTest {
    @Test fun pastedHtmlKeepsBasicStructureAndDropsScriptsStylingAndRemoteImages() {
        val payload = ClipboardNormalizer.normalize(null,
            "<h2 style='color:red'>标题</h2><p>普通 <strong>粗体</strong> <em>斜体</em> <a href='https://example.com'>链接</a></p>" +
                "<ul><li>第一项</li><li>第二项</li></ul><script>alert('bad')</script><img src='https://private.example/image' alt='说明'>")
        assertTrue(payload.markdown.contains("## 标题"))
        assertTrue(payload.markdown.contains("**粗体**")); assertTrue(payload.markdown.contains("*斜体*"))
        assertTrue(payload.markdown.contains("[链接](https://example.com)")); assertTrue(payload.markdown.contains("- 第一项"))
        assertFalse(payload.markdown.contains("alert")); assertFalse(payload.markdown.contains("private.example"))
        assertFalse(payload.markdown.contains("color")); assertTrue(payload.plainText.contains("说明"))
    }

    @Test fun dangerousLinksBecomePlainLabelsAndEntitiesRemainUnicode() {
        val payload = ClipboardNormalizer.normalize(null, "<p>A &amp; B &#x1F600; <a href='javascript:alert(1)'>保留文字</a></p>")
        assertEquals("A & B 😀 保留文字", payload.plainText)
        assertFalse(payload.markdown.contains("javascript")); assertFalse(payload.markdown.contains("alert"))
    }

    @Test fun externalTableBecomesAllRowsOfPlainText() {
        val payload = ClipboardNormalizer.normalize(null, "<table><tr><th>A</th><th>B</th></tr><tr><td>一</td><td>二</td></tr><tr><td>三</td><td>四</td></tr></table>")
        assertEquals("A\tB\n一\t二\n三\t四", payload.plainText)
        assertFalse(payload.markdown.contains("<table")); assertTrue(payload.markdown.contains("三\t四"))
    }

    @Test fun plainPasteDoesNotInterpretMarkdownTextAndOneUndoRestoresDocument() {
        val session = DocumentSession("正文")
        val payload = ClipboardNormalizer.normalize("# **这只是文字** 😀", "<b>ignored</b>", plainOnly = true)
        val command = DocumentFormatCommand.ReplaceSelection(payload.plainText, markdown = false)
        assertTrue(DocumentFormatCommands.execute(session, command, DocumentSelection(1)) is SessionResult.Applied)
        assertEquals("正# **这只是文字** 😀文", session.projection.text)
        session.undo(); assertEquals("正文", session.source)
    }

    @Test fun formattedPasteIsOneSourceTransaction() {
        val session = DocumentSession("前\n\n后")
        val original = session.source
        val payload = ClipboardNormalizer.normalize(null, "<p><strong>粗体</strong> 与 <a href='https://example.com'>链接</a></p>")
        assertTrue(DocumentFormatCommands.execute(session, DocumentFormatCommand.ReplaceSelection(payload.markdown, true), DocumentSelection(1)) is SessionResult.Applied)
        assertTrue(session.source.contains("**粗体**")); assertTrue(session.source.contains("[链接]"))
        assertEquals(1, session.historyStats.undoCount)
        session.undo(); assertEquals(original, session.source)
    }

    @Test fun copyingMixedComplexBlocksIncludesCompleteContentAndNeverPrivateTokens() {
        val session = DocumentSession("**粗体**\n\n|A|B|\n|---|---|\n|一|二|\n|三|四|\n\n![说明](assets/image.png)")
        val payload = ClipboardNormalizer.selection(session.source, session.projection, DocumentSelection(0, session.projection.text.length))
        assertTrue(payload.markdown.contains("**粗体**")); assertTrue(payload.markdown.contains("![说明](assets/image.png)"))
        assertTrue(payload.plainText.contains("三\t四")); assertTrue(payload.plainText.contains("说明"))
        assertFalse(payload.plainText.contains('\uFFFC')); assertFalse(payload.htmlText.orEmpty().contains('\uFFFC'))
        assertTrue(payload.htmlText.orEmpty().contains("<strong>"))
    }

    @Test fun malformedHtmlIsBoundedAndRetainsVisibleText() {
        val payload = ClipboardNormalizer.normalize(null, "<div><strong>内容<i>仍然可读</div><script>hidden")
        assertTrue(payload.plainText.contains("内容仍然可读")); assertFalse(payload.plainText.contains("hidden"))
        assertFalse(payload.markdown.contains("<script"))
    }

    @Test fun copyingPartOfMarkCreatesABalancedFragmentAndPastesWithFormatting() {
        val session = DocumentSession("before **abcdef** after")
        val a = session.projection.text.indexOf("abcdef") + 2
        val payload = ClipboardNormalizer.selection(session.source, session.projection, DocumentSelection(a, a + 2))
        assertEquals("cd", payload.plainText)
        assertEquals("**cd**", payload.markdown)
        val target = DocumentSession("目标")
        assertTrue(DocumentFormatCommands.execute(target, DocumentFormatCommand.ReplaceSelection(payload.markdown, true), DocumentSelection(1)) is SessionResult.Applied)
        assertEquals("目**cd**标", target.source)
    }

    @Test fun copyingInlineImageEmitsAltAndWholeImageReference() {
        val session = DocumentSession("前 ![图片说明](assets/one.png) 后")
        val block = session.projection.nodes.first()
        val atom = block.node.inlineAtoms.single()
        val payload = ClipboardNormalizer.selection(session.source, session.projection, DocumentSelection(block.start + atom.start, block.start + atom.end))
        assertEquals("图片说明", payload.plainText)
        assertEquals("![图片说明](assets/one.png)", payload.markdown)
        assertFalse(payload.htmlText.orEmpty().contains('\uFFFC'))
    }

    @Test fun crossSessionHtmlRoundTripKeepsHeadingsListsQuotesAndCode() {
        val source = "# heading\n\n## secondary\n\n- **bold item**\n- second\n\n> quoted text\n\n```kotlin\nval x = 1\n```"
        val session = DocumentSession(source)
        val copied = ClipboardNormalizer.selection(source, session.projection, DocumentSelection(0, session.projection.text.length))
        assertTrue(copied.htmlText.orEmpty().contains("<h1>"))
        assertTrue(copied.htmlText.orEmpty().contains("<ul>")); assertTrue(copied.htmlText.orEmpty().contains("<li>"))
        assertTrue(copied.htmlText.orEmpty().contains("<blockquote>")); assertTrue(copied.htmlText.orEmpty().contains("<pre>"))
        val external = ClipboardNormalizer.normalize(copied.plainText, copied.htmlText)
        val target = DocumentSession(external.markdown)
        assertTrue(target.projection.nodes.any { it.node.headingLevel == 1 && it.node.text == "heading" })
        assertTrue(target.projection.nodes.any { it.node.headingLevel == 2 && it.node.text == "secondary" })
        assertTrue(target.projection.nodes.any { it.node.lines.any { line -> line.listStyle == DocumentListStyle.UNORDERED } })
        assertTrue(target.projection.nodes.any { it.node.lines.any { line -> line.quoteDepth > 0 } })
        assertTrue(target.projection.nodes.any { it.node.kind == "code_fence" && it.node.text.contains("val x = 1") })
        assertTrue(target.projection.nodes.any { it.node.marks.any { mark -> mark.kind == InlineMark.BOLD } })
    }

    @Test fun crossSessionHtmlKeepsNestedListIndentOrderedStartAndTaskCheck() {
        val source = "4. parent\n   - child\n5. next\n\n- [x] checked\n- [ ] unchecked"
        val session = DocumentSession(source)
        val copied = ClipboardNormalizer.selection(source, session.projection, DocumentSelection(0, session.projection.text.length))
        val external = ClipboardNormalizer.normalize(copied.plainText, copied.htmlText)
        assertTrue(external.markdown.contains("4. parent"))
        assertTrue(external.markdown.contains("  - child"))
        assertTrue(external.markdown.contains("- [x] checked")); assertTrue(external.markdown.contains("- [ ] unchecked"))
        assertFalse(external.htmlText.orEmpty().contains("<script"))
        assertFalse(external.plainText.contains('\uFFFC'))
    }
}
