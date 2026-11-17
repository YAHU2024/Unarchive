package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class DocumentHistoryTest {
    @Test fun undoAndRedoShareOneBoundedBudget() {
        val session = DocumentSession("", historyLimit = 8)
        repeat(20) { session.endInputGroup(); session.replaceProjectedRange(session.projection.text.length, session.projection.text.length, "中") }
        assertEquals(8, session.historyStats.undoCount)
        assertEquals(12L, session.historyStats.evicted)
        repeat(8) { assertTrue(session.undo() is SessionResult.Applied) }
        assertEquals(8, session.historyStats.redoCount)
        assertEquals("中".repeat(12), session.source)
        repeat(8) { assertTrue(session.redo() is SessionResult.Applied) }
        assertEquals("中".repeat(20), session.source)
        assertTrue(session.historyStats.retainedBytes <= 262144)
    }

    @Test fun newInputAfterUndoDropsRedoWithoutKeepingFullDocuments() {
        val session = DocumentSession("原")
        session.replaceProjectedRange(1, 1, "甲")
        session.undo()
        session.replaceProjectedRange(1, 1, "乙")
        assertFalse(session.canRedo)
        assertEquals("原乙", session.source)
        assertTrue(session.historyStats.retainedBytes < 200)
    }
}
