package com.unarchive.android.editor.projection

import com.unarchive.android.editor.session.DocumentSession
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTitleResolverTest {
    @Test fun firstTopLevelH1WinsWithoutChangingMarkdown() {
        val source = "---\ntitle: metadata\n---\n\n# **正式**标题\n\n# 第二标题"
        val session = DocumentSession(source)
        assertEquals("正式标题", DocumentTitleResolver.resolve(session.projection, "fallback"))
        assertEquals(source, session.source)
    }
    @Test fun missingHeadingUsesDisplayFallbackOnly() {
        val session = DocumentSession("正文")
        assertEquals("来源标题", DocumentTitleResolver.resolve(session.projection, "来源标题"))
        assertEquals("正文", session.source)
    }
}
