package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class DocumentFoldTest {
    @Test fun foldingAndRevealAreDisplayOnlyAndKeepSourceVersionHistory() {
        val source = "# 标题\n\n摘要\n\n## 转录全文\n\n转录第一段\n\n转录第二段\n\n## 后续\n\n尾部"
        val session = DocumentSession(source)
        val from = source.indexOf("## 转录全文")
        val to = source.indexOf("## 后续")
        session.setFold(from, to, "转录全文")
        assertTrue(session.projection.nodes.any { it.node.kind == "folded-transcript" })
        assertFalse(session.projection.text.contains("转录第一段"))
        assertEquals(source, session.source); assertEquals(0L, session.version); assertFalse(session.canUndo)
        assertTrue(session.expandFoldAtSource(source.indexOf("转录第二段")))
        assertTrue(session.projection.text.contains("转录第二段"))
        assertEquals(0L, session.version); assertFalse(session.canUndo)
    }

    @Test fun firstDestructiveSelectionExpandsAndReselectsWithoutDeletingHiddenContent() {
        val source = "摘要\n\n## 转录全文\n\n隐藏内容\n\n尾部"
        val session = DocumentSession(source)
        val from = source.indexOf("## 转录全文"); val to = source.indexOf("尾部")
        session.setFold(from, to, "转录全文")
        val node = session.projection.nodes.single { it.node.kind == "folded-transcript" }
        val result = session.replaceProjectedRange(node.start, node.end, "", DocumentSelection(node.start, node.end), DocumentSelection(node.start))
        assertTrue(result is SessionResult.FoldExpanded)
        assertEquals(source, session.source); assertEquals(0L, session.version); assertFalse(session.canUndo)
        assertTrue(session.projection.text.contains("隐藏内容")); assertFalse(session.selection.isCollapsed)
        assertTrue(session.replaceProjectedRange(session.selection.start, session.selection.end, "", session.selection, DocumentSelection(session.selection.start)) is SessionResult.Applied)
        assertFalse(session.source.contains("隐藏内容"))
        session.undo(); assertEquals(source, session.source)
    }

    @Test fun editingBeforeFoldMovesSourceRangeAndUndoRestoresIt() {
        val source = "摘要\n\n## 转录全文\n\n隐藏\n\n尾部"
        val session = DocumentSession(source)
        val from = source.indexOf("## 转录全文"); val to = source.indexOf("尾部")
        session.setFold(from, to, "转录全文")
        assertTrue(session.replaceProjectedRange(1, 1, "新") is SessionResult.Applied)
        assertEquals(from + 1, session.foldRanges.single().sourceStart)
        assertFalse(session.projection.text.contains("隐藏"))
        session.undo(); assertEquals(from, session.foldRanges.single().sourceStart)
        assertEquals(source, session.source)
    }

    @Test fun missingH1GetsAnUnpersistedReadingHeadingOnly() {
        val session = DocumentSession("原始正文")
        session.setFallbackTitle("来源标题")
        assertEquals("来源标题\n\n原始正文", session.projection.text)
        assertTrue(session.projection.nodes.first().node.synthetic)
        assertEquals("原始正文", session.source); assertEquals(0L, session.version)
        session.setFallbackTitle(null)
        assertEquals("原始正文", session.projection.text)
    }

    @Test fun sourceSummaryAppearsAfterTheRealHeadingWithoutChangingSourceOrHistory() {
        val source = "# 完整标题\n\n正文 **粗体**\n\n末尾"
        val session = DocumentSession(source)
        session.setSourceSummary("作者 · 哔哩哔哩 · 10 秒")
        assertEquals("# 完整标题\n\n正文 **粗体**\n\n末尾", session.source)
        assertTrue(session.projection.text.startsWith("完整标题\n作者 · 哔哩哔哩 · 10 秒\n\n正文"))
        val synthetic = session.projection.nodes.single { it.node.kind == "synthetic-source-summary" }
        assertTrue(synthetic.node.synthetic)
        assertEquals(0L, session.version); assertFalse(session.canUndo)
        val bodyOffset = source.indexOf("正文")
        assertEquals(bodyOffset, session.projection.sourceOffset(session.projection.projectedOffset(bodyOffset)))
        session.setSourceSummary(null)
        assertEquals("完整标题\n\n正文 粗体\n\n末尾", session.projection.text)
    }

    @Test fun sourceSummaryFollowsFallbackHeadingAndDoesNotDuplicateRealSourceLine() {
        val session = DocumentSession("正文")
        session.setFallbackTitle("来源标题"); session.setSourceSummary("作者 · 10 秒")
        assertEquals("来源标题\n作者 · 10 秒\n\n正文", session.projection.text)
        val existing = DocumentSession("# 标题\n\n作者 · 10 秒\n\n正文")
        existing.setSourceSummary("作者 · 10 秒")
        assertEquals(1, Regex("作者 · 10 秒").findAll(existing.projection.text).count())
        assertTrue(existing.projection.nodes.none { it.node.kind == "synthetic-source-summary" })
    }
}
