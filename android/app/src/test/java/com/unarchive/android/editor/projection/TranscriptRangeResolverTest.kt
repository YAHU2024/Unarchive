package com.unarchive.android.editor.projection

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.session.DocumentSession
import org.junit.Assert.*
import org.junit.Test

class TranscriptRangeResolverTest {
    @Test fun exactGeneratedTranscriptHasOneSourceRangeAndKeepsTimestampLinks() {
        val metadata = metadata()
        val source = NoteMarkdownProjection.render(metadata)
        val session = DocumentSession(source)
        val range = TranscriptRangeResolver.resolve(source, session.projection, metadata)!!
        assertEquals("## 转录全文", source.substring(range.start).lineSequence().first())
        assertEquals(2, range.paragraphCount)
        assertTrue(source.substring(range.start, range.end).contains("?t=5"))
        assertTrue(source.substring(range.start, range.end).contains("第二段逐字转录"))
    }

    @Test fun sameHeadingWithoutMatchingOriginalTranscriptIsNeverFolded() {
        val metadata = metadata()
        val source = "# 笔记\n\n## 转录全文\n\n这是用户写的同名章节"
        assertNull(TranscriptRangeResolver.resolve(source, DocumentSession(source).projection, metadata))
    }

    @Test fun duplicateTranscriptHeadingsRemainFullyVisible() {
        val metadata = metadata()
        val source = NoteMarkdownProjection.render(metadata) + "\n\n## 转录全文\n\n用户补充"
        assertNull(TranscriptRangeResolver.resolve(source, DocumentSession(source).projection, metadata))
    }

    @Test fun editedTextOrTimestampMakesTheRangeUncertainAndDoesNotFold() {
        val metadata = metadata()
        val canonical = NoteMarkdownProjection.render(metadata)
        val changed = listOf(canonical.replace("第二段逐字转录", "手改的第二段"), canonical.replace("?t=5", "?t=9"))
        changed.forEach { source -> assertNull(TranscriptRangeResolver.resolve(source, DocumentSession(source).projection, metadata)) }
    }

    @Test fun crlfAndFollowingSiblingSectionKeepAnExactBoundedRange() {
        val metadata = metadata()
        val source = (NoteMarkdownProjection.render(metadata) + "\n\n## 用户后记\n\n不是转录").replace("\n", "\r\n")
        val range = TranscriptRangeResolver.resolve(source, DocumentSession(source).projection, metadata)!!
        assertEquals(source.indexOf("## 用户后记"), range.end)
        assertFalse(source.substring(range.start, range.end).contains("不是转录"))
    }

    @Test fun emptyOriginalTranscriptNeverClaimsUserContent() {
        val metadata = metadata().copy(sourceTranscript = emptyList())
        val source = "## 转录全文\n\n用户正文"
        assertNull(TranscriptRangeResolver.resolve(source, DocumentSession(source).projection, metadata))
    }

    @Test fun estimatedTimestampNoticeBelongsToTheVerifiedFoldAndSearchCanFindAllText() {
        val metadata = metadata().let { it.copy(source = it.source.copy(timingAccuracy = TranscriptTimingAccuracy.ESTIMATED)) }
        val source = NoteMarkdownProjection.render(metadata)
        val session = DocumentSession(source)
        val range = TranscriptRangeResolver.resolve(source, session.projection, metadata)!!
        assertTrue(source.substring(range.start, range.end).contains("时间戳为估算值"))
        session.setFold(range.start, range.end, range.title)
        assertFalse(session.projection.text.contains("第二段逐字转录"))
        val index = NoteNavigationIndex(session.projection, session.version, source)
        val hit = index.find("第二段逐字转录").single()
        assertEquals("第二段逐字转录", source.substring(hit.sourceStart, hit.sourceEnd))
        assertTrue(session.expandFoldAtSource(hit.sourceStart))
        assertTrue(session.projection.text.contains("第二段逐字转录"))
        assertEquals(0L, session.version)
    }

    private fun metadata(): NoteDocument = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_transcript_synthetic"), cardVersion = "synthetic-v1",
        canonicalUrl = "https://www.bilibili.com/video/BV_transcript_synthetic", title = "转录合成夹具",
        ownerName = "合成作者", videoDurationSeconds = 15, timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0, 5_000, "第一段逐字转录"), TranscriptSegment(5_000, 10_000, "第二段逐字转录")),
        analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000, updatedAtEpochMs = 1_000, markdown = "# 合成标题",
    ).toNoteDocument()
}
