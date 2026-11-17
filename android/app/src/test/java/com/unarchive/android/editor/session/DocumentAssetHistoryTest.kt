package com.unarchive.android.editor.session

import org.junit.Assert.*
import org.junit.Test

class DocumentAssetHistoryTest {
    private val cover = DocumentAssetReference("cover", "assets/cover.jpg", retainWithoutReference = true)
    private val first = DocumentAssetReference("editor-first", "assets/first.png")
    private val second = DocumentAssetReference("editor-second", "assets/second.png")

    @Test fun formalManifestInitializesWithoutScanningOrDroppingUnreferencedAssets() {
        val session = DocumentSession("正文")
        session.initializeAssets(listOf(cover, first))
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertEquals(0L, session.version); assertFalse(session.canUndo); assertEquals(0L, session.assetReferenceScans)
        session.replaceProjectedRange(1, 1, "普通文字")
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertEquals(0L, session.assetReferenceScans)
        session.undo(); assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
    }

    @Test fun insertingImageSourceAndItsManifestIsOneTransactionAndUndoRedoSnapshot() {
        val session = DocumentSession("正文")
        session.initializeAssets(listOf(cover))
        val image = "\n\n![替代文字](assets/first.png)"
        assertTrue(session.replaceSource(2, 2, image, origin = EditOrigin.IMAGE,
            assetDelta = AssetReferenceDelta(add = listOf(first))) is SessionResult.Applied)
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertEquals(1, session.historyStats.undoCount)
        session.undo(); assertEquals("正文", session.source); assertEquals(setOf(cover.id), session.captureSnapshot().activeAssetIds)
        session.redo(); assertEquals("正文$image", session.source); assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
    }

    @Test fun nativeWholeImageDeletionDropsOnlyItsReferencedImportedAsset() {
        val source = "正文\n\n![首图](assets/first.png)\n\n![受保护封面](assets/cover.jpg)"
        val session = DocumentSession(source)
        session.initializeAssets(listOf(cover, first, second))
        val image = session.projection.nodes.single { it.node.atomic?.destination == first.path }
        assertTrue(session.replaceProjectedRange(image.start, image.end, "", DocumentSelection(image.start, image.end),
            DocumentSelection(image.start)) is SessionResult.Applied)
        assertEquals(setOf(cover.id, second.id), session.captureSnapshot().activeAssetIds)
        session.undo(); assertEquals(source, session.source); assertEquals(setOf(cover.id, first.id, second.id), session.captureSnapshot().activeAssetIds)
        session.redo(); assertEquals(setOf(cover.id, second.id), session.captureSnapshot().activeAssetIds)
    }

    @Test fun repeatedReferenceImageDeletionRetainsAssetUntilTheLastReferenceIsRemoved() {
        val source = "![一][shared]\n\n![二][shared]\n\n[shared]: assets/first.png"
        val session = DocumentSession(source)
        session.initializeAssets(listOf(first))
        val image = session.projection.nodes.first { it.node.atomic?.kind == "image" }
        assertTrue(session.replaceProjectedRange(image.start, image.end, "", DocumentSelection(image.start, image.end), DocumentSelection(image.start)) is SessionResult.Applied)
        assertEquals(setOf(first.id), session.captureSnapshot().activeAssetIds)
        val remaining = session.projection.nodes.single { it.node.atomic?.kind == "image" }
        assertTrue(session.replaceProjectedRange(remaining.start, remaining.end, "", DocumentSelection(remaining.start, remaining.end), DocumentSelection(remaining.start)) is SessionResult.Applied)
        assertTrue(session.captureSnapshot().activeAssetIds.isEmpty())
        session.undo(); assertEquals(setOf(first.id), session.captureSnapshot().activeAssetIds)
        session.undo(); assertEquals(source, session.source)
    }

    @Test fun assetOnlyChangeIsHistoryAndCountedButProtectedAssetsCannotBeRemoved() {
        val session = DocumentSession("原文")
        session.initializeAssets(listOf(cover))
        assertTrue(session.applyAssetDelta(AssetReferenceDelta(add = listOf(first))) is SessionResult.Applied)
        assertEquals("原文", session.source); assertEquals(1L, session.version)
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertTrue(session.historyStats.retainedBytes > 112)
        assertTrue(session.applyAssetDelta(AssetReferenceDelta(removeIds = setOf(cover.id))) is SessionResult.Rejected)
        session.undo(); assertEquals(setOf(cover.id), session.captureSnapshot().activeAssetIds)
        session.redo(); assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
    }

    @Test fun sourceAndAiReplacementRestoreExactAssetsOnUndoAndKeepProtectedCover() {
        val source = "![首图](assets/first.png)\n\n正文"
        val session = DocumentSession(source)
        session.initializeAssets(listOf(cover, first)); session.registerAssets(listOf(second))
        assertTrue(session.replaceSource(0, source.length, "![次图](assets/second.png)\n\nAI候选", origin = EditOrigin.AI) is SessionResult.Applied)
        assertEquals(setOf(cover.id, second.id), session.captureSnapshot().activeAssetIds)
        session.undo(); assertEquals(source, session.source); assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        session.redo(); assertEquals(setOf(cover.id, second.id), session.captureSnapshot().activeAssetIds)
    }

    @Test fun recoveredSourceAndManifestAreRestoredTogetherWithoutRestartingHistory() {
        val session = DocumentSession("正式正文")
        session.initializeAssets(listOf(cover)); session.registerAssets(listOf(first))
        session.replaceProjectedRange(0, 0, "旧历史")
        val restored = "恢复正文\n\n![图](assets/first.png)"
        assertTrue(session.restoreRecoveredSnapshot(restored, setOf(cover.id, first.id)) is SessionResult.Applied)
        assertEquals(restored, session.source)
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertEquals(2L, session.version); assertFalse(session.canUndo); assertFalse(session.canRedo)
    }

    @Test fun plainInputAmongManyRegisteredAssetsDoesNotRunReferenceScansOrRetainManifestCopies() {
        val session = DocumentSession("正文\n\n尾部")
        val registry = (0 until 2_000).map { DocumentAssetReference("asset-$it", "assets/image-$it.png") }
        session.initializeAssets(registry)
        repeat(10) { index ->
            session.endInputGroup()
            assertTrue(session.replaceProjectedRange(index, index, "中") is SessionResult.Applied)
        }
        assertEquals(0L, session.assetReferenceScans)
        assertEquals(2_000, session.captureSnapshot().activeAssetIds.size)
        assertTrue(session.historyStats.retainedBytes < 2_000)
    }

    @Test fun oversizedAssetHistoryRequestsConfirmationBeforeSourceOrManifestMutation() {
        val session = DocumentSession("正文", historyByteBudget = 180)
        session.initializeAssets(listOf(cover))
        assertTrue(session.applyAssetDelta(AssetReferenceDelta(add = listOf(first))) is SessionResult.HistoryBarrierRequired)
        assertEquals("正文", session.source); assertEquals(setOf(cover.id), session.captureSnapshot().activeAssetIds); assertEquals(0L, session.version)
        assertTrue(session.applyAssetDelta(AssetReferenceDelta(add = listOf(first)), allowHistoryBarrier = true) is SessionResult.Applied)
        assertEquals(setOf(cover.id, first.id), session.captureSnapshot().activeAssetIds)
        assertFalse(session.canUndo)
    }
}
