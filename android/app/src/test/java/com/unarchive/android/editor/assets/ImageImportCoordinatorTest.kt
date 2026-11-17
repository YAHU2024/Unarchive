package com.unarchive.android.editor.assets

import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.PortableImageFixtures
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageImportCoordinatorTest {
    @Test fun pickerCancellationDoesNotReadOrProduceAnInsertion() = runTest {
        val importer = FakeImporter()
        val coordinator = ImageImportCoordinator(importer, StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor())
        coordinator.pickerResult(request.requestId, null) { context() }
        assertTrue(coordinator.state.value is ImageImportState.Cancelled)
        assertFalse(coordinator.needsResolution)
        assertEquals(0, importer.reads)
    }

    @Test fun successfulImportIsReadyAndOnlyExplicitSessionInsertionCompletesIt() = runTest {
        val coordinator = ImageImportCoordinator(FakeImporter(), StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor(), "图片说明")
        coordinator.pickerResult(request.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { context() }
        val ready = coordinator.state.value as ImageImportState.ReadyToInsert
        assertEquals(request, ready.request)
        assertTrue(coordinator.needsResolution)
        assertTrue(coordinator.markInserted(request.requestId))
        assertTrue(coordinator.state.value is ImageImportState.Inserted)
        assertFalse(coordinator.needsResolution)
        assertFalse(coordinator.markInserted(request.requestId))
    }

    @Test fun changesDuringCopyKeepTheImportedImageAwaitingExplicitNewPlacement() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = ImageImportCoordinator(FakeImporter(gate), StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor())
        var current = context()
        val task = launch { coordinator.pickerResult(request.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { current } }
        runCurrent()
        assertTrue(coordinator.state.value is ImageImportState.Copying)
        current = current.copy(documentVersion = 4L)
        gate.complete(Unit); task.join()
        assertTrue(coordinator.state.value is ImageImportState.AwaitingPlacement)
        assertFalse(coordinator.placeAt(request.requestId, anchor().copy(sessionId = "other"), 10))
        assertTrue(coordinator.placeAt(request.requestId, anchor().copy(documentVersion = 4L, sourceOffset = 8), 10))
        assertEquals(8, (coordinator.state.value as ImageImportState.ReadyToInsert).request.anchor.sourceOffset)
    }

    @Test fun cancelledOrDifferentSessionCallbacksCannotPublishAnImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = ImageImportCoordinator(FakeImporter(gate), StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor())
        val task = launch { coordinator.pickerResult(request.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { context() } }
        runCurrent(); coordinator.cancel(); gate.complete(Unit); task.join()
        assertTrue(coordinator.state.value is ImageImportState.Cancelled)
        val next = coordinator.beginPick(anchor())
        coordinator.pickerResult(next.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { context().copy(sessionId = "other") }
        assertTrue(coordinator.state.value is ImageImportState.Cancelled)
    }

    @Test fun anOldPickerResultCannotReplaceANewerRequest() = runTest {
        val importer = FakeImporter()
        val coordinator = ImageImportCoordinator(importer, StandardTestDispatcher(testScheduler))
        val first = coordinator.beginPick(anchor())
        val second = coordinator.beginPick(anchor().copy(sourceOffset = 4))
        coordinator.pickerResult(first.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { context() }
        assertEquals(0, importer.reads)
        assertEquals(second, (coordinator.state.value as ImageImportState.Picking).request)
    }

    @Test fun failureCanRetryTheSameControlledInputWithoutChangingSource() = runTest {
        val importer = FakeImporter().apply { fail = true }
        val coordinator = ImageImportCoordinator(importer, StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor())
        coordinator.pickerResult(request.requestId, { ByteArrayInputStream(byteArrayOf(1)) }) { context() }
        assertEquals(ImageImportFailureReason.ReadFailed, (coordinator.state.value as ImageImportState.Failed).reason)
        importer.fail = false
        coordinator.retry { context() }
        assertTrue(coordinator.state.value is ImageImportState.ReadyToInsert)
        assertEquals(2, importer.reads)
    }

    @Test fun existingScreenshotUsesTheSamePlacementGateAndKeepsItsAssetIdentity() = runTest {
        val importer = FakeImporter()
        val coordinator = ImageImportCoordinator(importer, StandardTestDispatcher(testScheduler))
        val request = coordinator.beginPick(anchor())
        coordinator.selectExisting(request.requestId, importer.asset.assetId, listOf(importer.asset)) { context() }
        assertEquals(importer.asset, (coordinator.state.value as ImageImportState.ReadyToInsert).asset)
        coordinator.contextChanged(context().copy(documentVersion = 2L))
        assertTrue(coordinator.state.value is ImageImportState.AwaitingPlacement)
    }

    private fun anchor() = ImageImportAnchor("session", 1L, 2)
    private fun context() = ImageImportContext("session", 1L, 10)
    private class FakeImporter(private val gate: CompletableDeferred<Unit>? = null) : NoteImageImporter {
        val asset = PortableImageFixtures.asset()
        var reads = 0
        var fail = false
        override suspend fun importImage(openStream: () -> InputStream): CardAsset {
            reads++; gate?.await()
            if (fail) throw ImageImportException(ImageImportFailureReason.ReadFailed)
            openStream().use { it.read() }
            return asset
        }
        override suspend fun useExisting(assetId: String, manifest: List<CardAsset>): CardAsset = manifest.single { it.assetId == assetId }
    }
}
