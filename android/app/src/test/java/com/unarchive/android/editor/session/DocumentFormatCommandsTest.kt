package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class DocumentFormatCommandsTest {
    private fun apply(session: DocumentSession, command: DocumentFormatCommand, selection: DocumentSelection = session.selection): SessionResult {
        val result = DocumentFormatCommands.execute(session, command, selection)
        assertTrue("$command returned $result", result is SessionResult.Applied || result == SessionResult.Unchanged)
        val oracle = DocumentSession(session.source)
        assertEquals("full-parser oracle", oracle.projection.text, session.projection.text)
        assertEquals(oracle.projection.nodes.map { it.node.kind to it.node.text }, session.projection.nodes.map { it.node.kind to it.node.text })
        return result
    }

    @Test fun headingsOneThroughSixAndParagraphKeepTheOriginalBody() {
        val session = DocumentSession("原文\n\nuntouched **body**\r\n")
        for (level in 1..6) {
            apply(session, DocumentFormatCommand.SetBlockStyle(level), DocumentSelection(1))
            assertTrue(session.source.startsWith("#".repeat(level) + " 原文"))
            assertTrue(session.source.endsWith("untouched **body**\r\n"))
            assertEquals(level, DocumentFormatCommands.selectionState(session, DocumentSelection(1)).headingLevel)
        }
        apply(session, DocumentFormatCommand.SetBlockStyle(0), DocumentSelection(1))
        assertEquals("原文\n\nuntouched **body**\r\n", session.source)
    }

    @Test fun allInlineFormatsRoundTripWithOneUndoEach() {
        for (mark in listOf(InlineMark.BOLD, InlineMark.ITALIC, InlineMark.STRIKE, InlineMark.CODE)) {
            val source = "前文 chosen 后文\n\n[unused]: https://example.com"
            val session = DocumentSession(source)
            val start = session.projection.text.indexOf("chosen")
            apply(session, DocumentFormatCommand.ToggleMark(mark), DocumentSelection(start, start + 6))
            assertEquals(FormatCoverage.ON, DocumentFormatCommands.selectionState(session, DocumentSelection(start, start + 6)).marks[mark])
            assertTrue(session.source.endsWith("[unused]: https://example.com"))
            session.undo(); assertEquals(source, session.source)
        }
    }

    @Test fun mixedBoldSelectionBecomesUniformThenRemovesWithoutLosingItalic() {
        val session = DocumentSession("plain **bold** *italic* tail")
        val selection = DocumentSelection(0, session.projection.text.length)
        assertEquals(FormatCoverage.MIXED, DocumentFormatCommands.selectionState(session, selection).marks[InlineMark.BOLD])
        val before = session.projection.text
        apply(session, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), selection)
        assertEquals(before, session.projection.text)
        assertEquals(FormatCoverage.ON, DocumentFormatCommands.selectionState(session, selection).marks[InlineMark.BOLD])
        apply(session, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), selection)
        assertEquals("plain bold *italic* tail", session.source)
    }

    @Test fun partialBoldRemovalSplitsOnlyTheSelectedRange() {
        val session = DocumentSession("**abcdef**")
        apply(session, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), DocumentSelection(2, 4))
        assertEquals("**ab**cd**ef**", session.source)
        assertEquals("abcdef", session.projection.text)
        assertEquals(FormatCoverage.OFF, DocumentFormatCommands.selectionState(session, DocumentSelection(2, 4)).marks[InlineMark.BOLD])
    }

    @Test fun caretStyleOnlyBecomesDirtyOnTheNextRealInput() {
        val session = DocumentSession("正文")
        apply(session, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), DocumentSelection(1))
        assertEquals("正文", session.source); assertEquals(0L, session.version)
        assertTrue(session.replaceProjectedRange(1, 1, "新", DocumentSelection(1), DocumentSelection(2)) is SessionResult.Applied)
        assertEquals("正**新**文", session.source)
        assertEquals("正新文", session.projection.text)
        assertTrue(session.replaceProjectedRange(2, 2, "文", DocumentSelection(2), DocumentSelection(3)) is SessionResult.Applied)
        assertEquals("正**新文**文", session.source)
        session.undo()
        session.undo(); assertEquals("正文", session.source)
    }

    @Test fun caretCanDisableInheritedBoldWithoutChangingOtherText() {
        val session = DocumentSession("**粗体文字**")
        apply(session, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), DocumentSelection(2))
        assertTrue(session.replaceProjectedRange(2, 2, "普通", DocumentSelection(2), DocumentSelection(4)) is SessionResult.Applied)
        assertEquals("**粗体**普通**文字**", session.source)
        assertTrue(session.replaceProjectedRange(4, 4, "内容", DocumentSelection(4), DocumentSelection(6)) is SessionResult.Applied)
        assertEquals("**粗体**普通内容**文字**", session.source)
        assertEquals("粗体普通内容文字", session.projection.text)
    }

    @Test fun caretDisablingBoldAtEitherBoundaryDoesNotCreateEmptyMarkerPairs() {
        val right = DocumentSession("**粗体**")
        apply(right, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), DocumentSelection(2))
        assertTrue(right.replaceProjectedRange(2, 2, "普通", DocumentSelection(2), DocumentSelection(4)) is SessionResult.Applied)
        assertEquals("**粗体**普通", right.source)
        val left = DocumentSession("**粗体**")
        apply(left, DocumentFormatCommand.ToggleMark(InlineMark.BOLD), DocumentSelection(0))
        assertTrue(left.replaceProjectedRange(0, 0, "普通", DocumentSelection(0), DocumentSelection(2)) is SessionResult.Applied)
        assertEquals("普通**粗体**", left.source)
    }

    @Test fun sharedReferenceDefinitionIsNeverModifiedWhenOneLinkChanges() {
        val source = "[第一][shared] 与 [第二][shared]\n\n[shared]: https://example.com"
        val session = DocumentSession(source)
        assertEquals("第一 与 第二\n\n", session.projection.text)
        apply(session, DocumentFormatCommand.SetLink("https://new.example"), DocumentSelection(0, 2))
        assertTrue(session.source.startsWith("[第一](https://new.example) 与 [第二][shared]"))
        assertTrue(session.source.endsWith("[shared]: https://example.com"))
    }

    @Test fun literalInputInsideCodeDoesNotEscapeOrRunShortcuts() {
        val session = DocumentSession("```kotlin\nval x = 1\n```")
        val offset = session.projection.text.indexOf("val") + 3
        assertTrue(session.replaceProjectedRange(offset, offset, " **[x]", DocumentSelection(offset), DocumentSelection(offset + 6)) is SessionResult.Applied)
        assertTrue(session.source.contains("val **[x] x"))
        assertEquals(SessionResult.Unchanged, DocumentFormatCommands.execute(session, DocumentFormatCommand.ApplyShortcut))
    }

    @Test fun listsContinueExitAndRetainTaskTogglesInOneHistory() {
        val session = DocumentSession("first\nsecond")
        apply(session, DocumentFormatCommand.SetList(DocumentListStyle.TASK), DocumentSelection(0, session.projection.text.length))
        assertEquals("- [ ] first\n- [ ] second", session.source)
        assertEquals("first\nsecond", session.projection.text)
        apply(session, DocumentFormatCommand.ToggleTask, DocumentSelection(1))
        assertTrue(session.source.startsWith("- [x] first"))
        apply(session, DocumentFormatCommand.Enter, DocumentSelection(session.projection.text.length))
        assertTrue(session.source.endsWith("second\n- [ ] "))
        apply(session, DocumentFormatCommand.Enter, session.selection)
        assertFalse(session.source.endsWith("- [ ] "))
        session.undo(); assertTrue(session.source.endsWith("- [ ] "))
    }

    @Test fun orderedContinuationAndBackspaceAtItemStartAreStructural() {
        val session = DocumentSession("8. eighth")
        apply(session, DocumentFormatCommand.Enter, DocumentSelection(session.projection.text.length))
        assertEquals("8. eighth\n9. ", session.source)
        val second = session.projection.text.length
        apply(session, DocumentFormatCommand.Backspace, DocumentSelection(second))
        assertEquals("8. eighth\n", session.source)
    }

    @Test fun quoteAndCodeKeepTheExactUntouchedParagraphAndLanguage() {
        val session = DocumentSession("引用\r\n\r\nuntouched\r\n")
        apply(session, DocumentFormatCommand.SetQuote, DocumentSelection(1))
        assertTrue(session.source.startsWith("> 引用\r\n"))
        assertEquals("引用\n\nuntouched\n", session.projection.text)
        apply(session, DocumentFormatCommand.SetQuote, DocumentSelection(1))
        assertEquals("引用\r\n\r\nuntouched\r\n", session.source)
        apply(session, DocumentFormatCommand.SetCodeBlock, DocumentSelection(1))
        assertTrue(session.source.startsWith("```\r\n引用\r\n```"))
        assertTrue(session.source.endsWith("untouched\r\n"))
        val language = DocumentSession("```kotlin\nval x = 1\n```\n\n正文")
        val end = language.projection.text.indexOf("正文")
        language.replaceProjectedRange(end, end, "新")
        assertTrue(language.source.startsWith("```kotlin\nval x = 1\n```"))
    }

    @Test fun linksUseExplicitSafePolicyAndRemovalKeepsTheLabel() {
        val session = DocumentSession("label\n\nuntouched")
        apply(session, DocumentFormatCommand.SetLink("https://example.com/path"), DocumentSelection(0, 5))
        assertTrue(session.source.startsWith("[label](https://example.com/path)"))
        assertEquals("label\n\nuntouched", session.projection.text)
        apply(session, DocumentFormatCommand.RemoveLink, DocumentSelection(2))
        assertEquals("label\n\nuntouched", session.source)
        val result = DocumentFormatCommands.execute(session, DocumentFormatCommand.SetLink("javascript:alert(1)"), DocumentSelection(0, 5))
        assertTrue(result is SessionResult.Rejected)
        assertEquals("label\n\nuntouched", session.source)
    }

    @Test fun continuousTypingMergesButSelectionAndFormattingBreakTheGroup() {
        val session = DocumentSession("")
        for (char in "abc") {
            val offset = session.projection.text.length
            session.replaceProjectedRange(offset, offset, char.toString(), DocumentSelection(offset), DocumentSelection(offset + 1))
        }
        assertEquals(1, session.historyStats.undoCount)
        session.undo(); assertEquals("", session.source)
        session.redo(); assertEquals("abc", session.source)
        session.updateSelection(DocumentSelection(0))
        session.replaceProjectedRange(0, 0, "中", DocumentSelection(0), DocumentSelection(1))
        assertEquals(2, session.historyStats.undoCount)
    }

    @Test fun atomicTablesAndImagesHaveCompleteOwnedSourceRanges() {
        val source = "| A | B |\n|---|---|\n| one | two |\n| three | four |\n\n![alt](assets/image.png)\n\n末尾"
        val session = DocumentSession(source)
        assertTrue(session.projection.nodes.any { it.node.atomic?.rows?.size == 3 })
        val image = session.projection.nodes.single { it.node.atomic?.kind == "image" }
        assertTrue(session.replaceProjectedRange(image.start, image.end, "", DocumentSelection(image.start, image.end), DocumentSelection(image.start)) is SessionResult.Applied)
        assertFalse(session.source.contains("![alt]")); assertTrue(session.source.contains("| three | four |"))
        session.undo(); assertEquals(source, session.source)
    }

    @Test fun inlineImageIsOneOwnedRangeAndDeleteUndoPreservesAdjacentText() {
        val source = "before ![替代文字](assets/image.png) after\n\nuntouched"
        val session = DocumentSession(source)
        val block = session.projection.nodes.first()
        val atom = block.node.inlineAtoms.single()
        assertEquals("image", atom.node.kind)
        assertEquals("替代文字", atom.node.text)
        assertEquals("before \uFFFC after\n\nuntouched", session.projection.text)
        val selection = DocumentSelection(block.start + atom.start, block.start + atom.end)
        assertTrue(session.replaceProjectedRange(selection.start, selection.end, "", selection, DocumentSelection(selection.start)) is SessionResult.Applied)
        assertEquals("before  after\n\nuntouched", session.source)
        session.undo(); assertEquals(source, session.source)
    }

    @Test fun shortcutTriggerAndConversionUndoTogetherAndCodeIsExcluded() {
        val session = DocumentSession("")
        assertTrue(session.replaceProjectedRange(0, 0, "#", DocumentSelection(0), DocumentSelection(1)) is SessionResult.Applied)
        assertTrue(session.replaceProjectedRange(1, 1, " ", DocumentSelection(1), DocumentSelection(2)) is SessionResult.Applied)
        apply(session, DocumentFormatCommand.ApplyShortcut)
        assertEquals("# ", session.source)
        assertEquals(1, session.historyStats.undoCount)
        session.undo(); assertEquals("", session.source)
    }

    @Test fun nestedEmptyListExitsExactlyOneLevelAndCanThenExitTheTopLevel() {
        val session = DocumentSession("- parent\n  - ")
        apply(session, DocumentFormatCommand.Enter, DocumentSelection(session.projection.text.length))
        assertEquals("- parent\n- ", session.source)
        apply(session, DocumentFormatCommand.Enter, session.selection)
        assertEquals("- parent\n", session.source)
        session.undo(); assertEquals("- parent\n- ", session.source)
        session.undo(); assertEquals("- parent\n  - ", session.source)
    }

    @Test fun nestedTaskOutdentsToTheParentOrderedMarkerAndBackspaceReducesOneQuoteLevel() {
        val ordered = DocumentSession("4. parent\n   - [ ] ")
        apply(ordered, DocumentFormatCommand.Enter, DocumentSelection(ordered.projection.text.length))
        assertEquals("4. parent\n5. ", ordered.source)
        val quote = DocumentSession("> > quoted")
        apply(quote, DocumentFormatCommand.Backspace, DocumentSelection(0))
        assertEquals("> quoted", quote.source)
        apply(quote, DocumentFormatCommand.Backspace, DocumentSelection(0))
        assertEquals("quoted", quote.source)
    }

    @Test fun backspaceAtQuotedTaskStartDropsOnlyTheTaskContainer() {
        val session = DocumentSession("> - [ ] task")
        apply(session, DocumentFormatCommand.Backspace, DocumentSelection(0))
        assertEquals("> task", session.source)
        assertEquals("task", session.projection.text)
    }
}
