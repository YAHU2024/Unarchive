package com.unarchive.android.ui.state

import com.unarchive.android.sync.ImaFolder
import com.unarchive.android.sync.ImaKnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationUiStateTest {
    @Test
    fun targetOptionsDeduplicateIdsAndUseSafeFallbackNames() {
        val knowledgeBases = destinationKnowledgeBaseOptions(
            listOf(
                ImaKnowledgeBase("kb-1", ""),
                ImaKnowledgeBase("kb-1", "重复项"),
                ImaKnowledgeBase("", "无效项"),
            ),
        )
        val folders = destinationFolderOptions(
            listOf(
                ImaFolder("folder-1", "第一章", depth = 1, displayPath = "课程 / 第一章"),
                ImaFolder("folder-1", "重复项"),
                ImaFolder("folder-2", "", depth = -1, displayPath = ""),
            ),
        )

        assertEquals(listOf(DestinationKnowledgeBaseOption("kb-1", "未命名知识库")), knowledgeBases)
        assertEquals(2, folders.size)
        assertEquals("课程 / 第一章", folders[0].displayPath)
        assertEquals(1, folders[0].depth)
        assertEquals("未命名文件夹", folders[1].displayPath)
        assertEquals(0, folders[1].depth)
    }
}
