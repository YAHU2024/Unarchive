package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class DocumentSessionTest {
    @Test fun localInputPreservesFrontMatterUnknownSyntaxAndCrlf() {
        val original = "---\r\nprivate: untouched\r\n---\r\n\r\n# 标题\r\n\r\n正文**粗体**结束。\r\n\r\n:::custom untouched\r\n"
        val session = DocumentSession(original)
        val offset = session.projection.text.indexOf("正文") + 1
        assertTrue(session.replaceProjectedRange(offset, offset, "新") is SessionResult.Applied)
        assertEquals(original.replace("正文", "正新文"), session.source)
        assertFalse(session.projection.text.contains("private:"))
        assertTrue(session.projection.text.contains(":::custom untouched"))
    }

    @Test fun unicodeBoundaryRejectedWithoutChangingSourceOrHistory() {
        val session = DocumentSession("中文😀尾部")
        assertTrue(session.replaceProjectedRange(3, 3, "x") is SessionResult.Rejected)
        assertEquals("中文😀尾部", session.source)
        assertFalse(session.canUndo)
        assertTrue(session.replaceProjectedRange(4, 4, "🙂") is SessionResult.Applied)
        assertEquals("中文😀🙂尾部", session.source)
    }

    @Test fun emptyBodyIsAValidTransactionAndUndoRestoresExactSource() {
        val session = DocumentSession("# 标题\n\n正文")
        assertTrue(session.replaceSource(0, session.source.length, "") is SessionResult.Applied)
        assertEquals("", session.source)
        assertTrue(session.undo() is SessionResult.Applied)
        assertEquals("# 标题\n\n正文", session.source)
        assertTrue(session.redo() is SessionResult.Applied)
        assertEquals("", session.source)
        assertEquals(3L, session.version)
    }

    @Test fun staleDeltaCannotOverwriteNewerText() {
        val session = DocumentSession("正文")
        assertTrue(session.replaceProjectedRange(1, 1, "甲", expectedVersion = 0) is SessionResult.Applied)
        assertTrue(session.replaceProjectedRange(1, 1, "乙", expectedVersion = 0) is SessionResult.Rejected)
        assertEquals("正甲文", session.source)
    }

    @Test fun undoRestoresActualBeforeSelectionAndNeverDecreasesVersion() {
        val session = DocumentSession("first\n\nsecond")
        val before = DocumentSelection(1, 8)
        assertTrue(session.replaceProjectedRange(1, 8, "合并", before, DocumentSelection(3)) is SessionResult.Applied)
        assertTrue(session.undo() is SessionResult.Applied)
        assertEquals(before, session.selection)
        assertEquals("first\n\nsecond", session.source)
        assertEquals(2L, session.version)
    }

    @Test fun longDocumentHeadMiddleTailUseIncrementalParserAndKeepTail() {
        val source = buildString { repeat(3600) { append("第${it}段，原文保留。持续中文输入与😀。\n\n") }; append("最终末尾标记") }
        val session = DocumentSession(source)
        val initialParses = session.diagnostics.fullParses
        repeat(3) { index ->
            val target = when (index) { 0 -> 0; 1 -> session.projection.text.length / 2; else -> session.projection.text.length }
            var safe = target.coerceAtMost(session.projection.text.length)
            if (!isUtf16Boundary(session.projection.text, safe)) safe++
            assertTrue(session.replaceProjectedRange(safe, safe, "测") is SessionResult.Applied)
        }
        assertEquals(initialParses, session.diagnostics.fullParses)
        assertEquals(3L, session.diagnostics.incrementalParses)
        assertTrue(session.source.contains("最终末尾标记"))
        assertTrue(session.diagnostics.lastParsedCharacters < 300)
        repeat(3) { assertTrue(session.undo() is SessionResult.Applied) }
        assertEquals(source, session.source)
    }

    @Test fun hugeEditRequiresExplicitHistoryBarrierBeforeMutation() {
        val session = DocumentSession("正文", historyByteBudget = 128)
        assertTrue(session.replaceSource(0, 2, "大".repeat(100)) is SessionResult.HistoryBarrierRequired)
        assertEquals("正文", session.source)
        assertEquals(0L, session.version)
        assertTrue(session.replaceSource(0, 2, "大".repeat(100), allowHistoryBarrier = true) is SessionResult.Applied)
        assertFalse(session.canUndo)
    }

    @Test fun deletingCompleteBoldOwnsMarkersWithoutRewritingAdjacentSource() {
        val session = DocumentSession("before **bold** after\n\n[ref]: https://example.com")
        val offset = session.projection.text.indexOf("bold")
        assertTrue(session.replaceProjectedRange(offset, offset + 4, "", DocumentSelection(offset, offset + 4)) is SessionResult.Applied)
        assertEquals("before  after\n\n[ref]: https://example.com", session.source)
        session.undo()
        assertEquals("before **bold** after\n\n[ref]: https://example.com", session.source)
    }

    @Test fun unchangedSnapshotsDoNotCreateRevisionOrHistory() {
        val session = DocumentSession("正文")
        assertEquals(SessionResult.Unchanged, session.replaceProjectedRange(0, 2, "正文"))
        assertEquals(0L, session.version)
        assertFalse(session.canUndo)
    }

    @Test fun literalMarkdownPunctuationIsNotLostOrConvertedToFormatting() {
        val session = DocumentSession("正文")
        val inserted = "*#_[样例](url)\\"
        assertTrue(session.replaceProjectedRange(1, 1, inserted) is SessionResult.Applied)
        assertEquals("正${inserted}文", session.projection.text)
        session.undo()
        assertEquals("正文", session.source)
    }

    @Test fun productionLongFixtureInputOnBlankLineUsesLocalWindowAndFullOracle() {
        val source = buildString {
            append("生产长文验收\n\n")
            repeat(2_860) { index -> append("第${index}段。中文🙂连续阅读编辑的合成正文，用于首中尾输入与完整末尾验证。\n\n") }
            append("长文最后一行END")
        }
        assertTrue(source.codePointCount(0, source.length) >= 110_000)
        val session = DocumentSession(source)
        val initial = session.diagnostics.fullParses
        for (location in listOf("head", "middle", "tail")) {
            val text = session.projection.text
            val offset = when (location) { "head" -> 0; "middle" -> text.indexOf('\n', text.length / 2) + 1; else -> text.length }
            assertTrue("$location must apply", session.replaceProjectedRange(offset, offset, "中", DocumentSelection(offset), DocumentSelection(offset + 1)) is SessionResult.Applied)
            assertEquals("$location must not full reparse", initial, session.diagnostics.fullParses)
            val oracle = DocumentSession(session.source)
            assertEquals(oracle.projection.text, session.projection.text)
            assertEquals(oracle.projection.nodes.map { it.node.kind to it.node.raw }, session.projection.nodes.map { it.node.kind to it.node.raw })
        }
        assertTrue(session.source.endsWith("长文最后一行END中"))
        assertEquals(3L, session.diagnostics.incrementalParses)
    }
}
