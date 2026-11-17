package com.unarchive.android.editor.ai

import org.junit.Assert.*
import org.junit.Test

class AiReviewProjectionTest {
    @Test fun completeDiffRetainsChangesBeyondEightLinesAndLineLocations() {
        val old = (1..30).joinToString("\n") { "旧$it" }
        val new = (1..30).joinToString("\n") { "新$it" }
        val result = AiReviewProjection.build(old, new)
        assertEquals(AiReviewMode.DIFF, result.mode)
        assertEquals(30, result.lines.count { it.kind == AiReviewLineKind.ADDED })
        assertEquals("新30", result.lines.last().text)
        assertEquals(30, result.lines.last().candidateLine)
        assertEquals(old, result.original)
        assertEquals(new, result.candidate)
    }

    @Test fun matchingMiddleLinesStayLocatedRatherThanBecomingOneReplacement() {
        val result = AiReviewProjection.build("head\nold\nmiddle\ntail", "head\nnew\nmiddle\ninsert\ntail")
        assertTrue(result.lines.any { it.text == "middle" && it.kind == AiReviewLineKind.UNCHANGED && it.candidateLine == 3 })
        assertTrue(result.lines.any { it.text == "insert" && it.kind == AiReviewLineKind.ADDED && it.candidateLine == 4 })
    }

    @Test fun budgetFallbackKeepsBothCompleteTextsAndTrailingEmptyLine() {
        val old = "a\nb\nc\n"
        val new = "1\n2\n3\n"
        val result = AiReviewProjection.build(old, new, maxCells = 1)
        assertEquals(AiReviewMode.FULL_TEXT, result.mode)
        assertEquals(old, result.originalLines.joinToString("\n"))
        assertEquals(new, result.candidateLines.joinToString("\n"))
        assertEquals("", result.candidateChunks.last().text)
    }

    @Test fun largeParagraphIsSegmentedWithoutSplittingSurrogatePairOrDroppingTail() {
        val source = "x".repeat(2047) + "😀" + "尾".repeat(10000)
        val result = AiReviewProjection.build(source, source + "末尾")
        assertEquals(AiReviewMode.FULL_TEXT, result.mode)
        assertEquals(source + "末尾", result.candidateChunks.joinToString("") { it.text })
        assertTrue(result.candidateChunks.all { it.text.length <= 2048 })
        assertTrue(result.candidateChunks.none { it.text.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test fun missingHistoricalBaseDoesNotPretendCurrentSourceIsOriginal() {
        val result = AiReviewProjection.build(null, "complete candidate")
        assertNull(result.original)
        assertEquals(AiReviewMode.FULL_TEXT, result.mode)
        assertEquals("complete candidate", result.candidateChunks.single().text)
        assertNotNull(result.explanation)
    }
}
