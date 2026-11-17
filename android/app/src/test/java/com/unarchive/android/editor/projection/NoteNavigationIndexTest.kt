package com.unarchive.android.editor.projection

import com.unarchive.android.editor.session.MarkdownDocumentParser
import org.junit.Assert.*
import org.junit.Test

class NoteNavigationIndexTest {
    @Test fun readingOnlyTitleAndSourceSummaryNeverBecomeSourceSearchHitsOrOutlineEntries() {
        val session = com.unarchive.android.editor.session.DocumentSession("实际正文")
        session.setFallbackTitle("回退标题")
        session.setSourceSummary("合成作者 · 2:00")
        val index = NoteNavigationIndex(session.projection, session.version, session.source)
        assertTrue(index.outline.isEmpty())
        assertTrue(index.find("回退标题").isEmpty())
        assertTrue(index.find("合成作者").isEmpty())
        assertEquals(1, index.find("实际正文").size)
    }
    @Test fun inlineImageAltHitsMapToTheCompleteImageSourceRange() {
        val source = "prefix ![inlineAlt](assets/x.png) suffix"
        val parser = MarkdownDocumentParser()
        val projection = MarkdownDocumentParser.project(source, parser.parse(source))
        val hit = NoteNavigationIndex(projection, 1L).find("inlineAlt").single()
        assertEquals("![inlineAlt](assets/x.png)", source.substring(hit.sourceStart, hit.sourceEnd))
    }
    @Test fun repeatedHeadingsAndUnicodeCaseSearchMapBackToOriginalSource() {
        val source = "---\nsecret: hidden\n---\n# 标题\n\n## 标题\n\nİstanbul 中文🙂\n\n**末尾**"
        val parser = MarkdownDocumentParser()
        val projection = MarkdownDocumentParser.project(source, parser.parse(source))
        val index = NoteNavigationIndex(projection, 4L)
        assertEquals(listOf(1, 2), index.outline.map { it.level })
        assertTrue(index.outline[0].nodeId != index.outline[1].nodeId)
        assertTrue(index.find("hidden").isEmpty())
        val hit = index.find("末尾").single()
        assertEquals("末尾", source.substring(hit.sourceStart, hit.sourceEnd))
        val unicode = index.find("STANBUL").single()
        assertEquals("stanbul", source.substring(unicode.sourceStart, unicode.sourceEnd))
        assertTrue(index.find("不存在").isEmpty())
    }
}
