package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class CrossFormatReplacementTest {
    private fun replace(source: String, start: Int, end: Int, inserted: String = "替换"): DocumentSession {
        val session = DocumentSession(source)
        val originalVisible = session.projection.text
        val result = session.replaceProjectedRange(start, end, inserted, DocumentSelection(start, end), DocumentSelection(start + inserted.length))
        assertTrue("$source [$start,$end] -> $result", result is SessionResult.Applied)
        assertEquals(originalVisible.replaceRange(start, end, inserted), session.projection.text)
        assertEquals(DocumentSession(session.source).projection.text, session.projection.text)
        session.undo(); assertEquals(source, session.source)
        session.redo(); assertEquals(originalVisible.replaceRange(start, end, inserted), session.projection.text)
        return session
    }

    @Test fun partialBoldThroughPartialHeadingClosesTheSurvivingMark() {
        val source = "one **bold**\n\n# heading\n\ntail"
        val projection = DocumentSession(source).projection.text
        val session = replace(source, projection.indexOf("bold") + 1, projection.indexOf("heading") + 2)
        assertEquals("one **b**替换ading\n\ntail", session.source)
    }

    @Test fun plainIntoPartialBoldReopensOnlyTheSurvivingMark() {
        val source = "plain\n\n**bold** tail\n\nuntouched"
        val text = DocumentSession(source).projection.text
        val session = replace(source, 2, text.indexOf("bold") + 2)
        assertEquals("pl替换**ld** tail\n\nuntouched", session.source)
    }

    @Test fun crossingNestedInlineMarksRetainsAllSurvivingTextAndUntouchedSuffix() {
        val source = "**bold *nested***\n\n~~strike~~ tail\n\n[ref]: assets/unchanged.png"
        val text = DocumentSession(source).projection.text
        val session = replace(source, text.indexOf("nested") + 2, text.indexOf("strike") + 3)
        assertTrue(session.source.endsWith("[ref]: assets/unchanged.png"))
        assertEquals(text.replaceRange(text.indexOf("nested") + 2, text.indexOf("strike") + 3, "替换"), session.projection.text)
    }

    @Test fun crossingListAndQuoteContainersKeepsTheJoinedVisibleText() {
        val sources = listOf("- list first\n- list second\n\n> quote last", "> quoted **bold**\n\n1. numbered item\n2. next")
        sources.forEach { source ->
            val text = DocumentSession(source).projection.text
            replace(source, 3, text.lastIndexOf(' ') + 2)
        }
    }

    @Test fun partialFencedCodeIntoParagraphPreservesSurvivingCodeLiteral() {
        val source = "```kotlin\nval x = **literal**\nnext line\n```\n\nparagraph tail\n\nuntouched"
        val text = DocumentSession(source).projection.text
        val session = replace(source, text.indexOf("literal") + 3, text.indexOf("paragraph") + 4)
        assertTrue(session.source.endsWith("\n\nuntouched"))
        assertTrue(session.projection.text.contains("val x = **lit替换graph tail"))
    }

    @Test fun paragraphIntoPartialFencedCodeKeepsRemainingCodeAndFollowingSource() {
        val source = "paragraph\n\n```lang\ncode **literal** tail\nsecond\n```\n\nuntouched"
        val text = DocumentSession(source).projection.text
        replace(source, 3, text.indexOf("literal") + 2)
    }

    @Test fun selectionEndingAtAnUnselectedImageDoesNotConsumeItsSource() {
        val source = "first\n\n![alt](assets/x.png)\n\nlast"
        val text = DocumentSession(source).projection.text
        val session = replace(source, 2, text.indexOf('\uFFFC'))
        assertTrue(session.source.contains("![alt](assets/x.png)"))
    }

    @Test fun crossingAWholeImageAndTableRetainsAllOtherSourceAndAssets() {
        val source = "before **bold**\n\n![alt](assets/x.png)\n\n|A|B|\n|---|---|\n|one|two|\n\n# after\n\nuntouched"
        val text = DocumentSession(source).projection.text
        val session = replace(source, text.indexOf("bold") + 1, text.indexOf("after") + 2)
        assertFalse(session.source.contains("assets/x.png")); assertTrue(session.source.endsWith("untouched"))
    }
}
