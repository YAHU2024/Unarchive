package com.unarchive.android.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.assets.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.session.EditOrigin
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Production Route + real Android codec, card/file stores and ActivityResult
 * contract return. The registry deliberately does not launch system Picker UI;
 * these tests cannot certify the device's actual media-picker interaction.
 */
class NoteImageFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "note-image-flow-${System.nanoTime()}")
    private var owner by mutableStateOf(TestOwner())
    private var mounted by mutableStateOf(true)
    private val picker = PickerRegistry()
    private lateinit var activeFixture: Fixture
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }

    @After fun removeOnlySyntheticData() {
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        rule.runOnIdle { owner.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun imageContractReturnUsesRealImporterAndReopenedDraftThenFormalExportKeepsManifestAndBytes() {
        val fixture = fixture()
        mount(fixture)
        awaitReading(); edit(); placeAtEnd()
        val originalHost = native()
        choosePhoto()
        rule.runOnIdle { picker.returnResult(fixture.inputUri) }
        awaitImageInserted()
        val vm = vm(fixture)
        val first = rule.runOnIdle { vm.coordinator.currentAssets.single() }
        val source = rule.runOnIdle { native().bridge!!.session.captureSnapshot().source }
        assertTrue(source.contains(first.relativePath))
        assertEquals(1, rule.runOnIdle { native().bridge!!.session.projection.nodes.count { it.node.atomic?.kind == "image" } })
        assertSame(originalHost, native())
        assertTrue(fixture.cardRepository.assetFile(fixture.card, first)!!.isFile)
        rule.runOnIdle { vm.flushBoundary() }
        rule.waitUntil(8_000) { fixture.noteRepository.loadRecoveryState(fixture.card.cardId, fixture.card.cardVersion)
            .any { it.draft?.assets?.contains(first) == true && it.draft.markdown == source } }
        rule.runOnIdle { mounted = false }; rule.waitForIdle()
        rule.runOnIdle { owner.viewModelStore.clear(); owner = TestOwner(); mounted = true }
        awaitReading()
        assertEquals(fixture.formal.markdown, fixture.noteRepository.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        rule.onNodeWithTag("markdown-note-recovery").performClick()
        rule.onNodeWithTag("markdown-note-restore-0").performClick(); awaitEditing()
        rule.waitUntil(8_000) { rule.runOnIdle { vm(fixture).coordinator.currentAssets == listOf(first) &&
            native().bridge!!.session.source == source } }
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        val formal = fixture.noteRepository.find(fixture.card.cardId, fixture.card.cardVersion)!!
        assertEquals(source, formal.markdown)
        assertEquals(listOf(first), formal.structuredMetadata.assets)
        val snapshot = FormalMarkdownSnapshotResolver.resolve(fixture.card, formal)
        val exported = KnowledgeCardExport.portable(snapshot.card) {
            KnowledgeCardExport.readManagedAsset(it, fixture.cardRepository.assetFile(snapshot.card, it))
        }
        assertTrue(exported.isFullyPortable)
        assertEquals(1, exported.assets.embeddedCount)
        assertTrue(exported.markdown.contains("data:image/png;base64,"))
        assertFalse(exported.markdown.contains(first.relativePath))
        assertEquals(source, fixture.noteRepository.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        assertEquals(1, picker.launched.get())
        assertEquals("PickVisualMedia", picker.lastContract)
    }

    @Test fun cancellingThePickerKeepsTheExactCaretSourceAndFormalRevision() {
        val fixture = fixture()
        mount(fixture); awaitReading(); edit(); placeAtEnd()
        val source = rule.runOnIdle { native().bridge!!.session.source }
        val selection = rule.runOnIdle { native().bridge!!.sourceSelection() }
        choosePhoto()
        rule.runOnIdle { picker.returnResult(null) }
        rule.waitUntil(5_000) { rule.runOnIdle { vm(fixture).images?.state?.value is ImageImportState.Cancelled } }
        assertEquals(source, rule.runOnIdle { native().bridge!!.session.source })
        assertEquals(selection, rule.runOnIdle { native().bridge!!.sourceSelection() })
        assertTrue(rule.runOnIdle { vm(fixture).coordinator.currentAssets.isEmpty() })
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(fixture.formal.markdownRevision, fixture.noteRepository.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
    }

    @Test fun inFlightDocumentChangeRequiresPlacementAndSaveWaitsForItsResolution() {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        val importer = object : NoteImageImporter {
            override suspend fun importImage(openStream: () -> java.io.InputStream): CardAsset {
                gate.await(); return fixture.importer.importImage(openStream)
            }
            override suspend fun useExisting(assetId: String, manifest: List<CardAsset>) = fixture.importer.useExisting(assetId, manifest)
        }
        mount(fixture, importer); awaitReading(); edit(); placeAtEnd()
        choosePhoto(); rule.runOnIdle { picker.returnResult(fixture.inputUri) }
        rule.waitUntil(5_000) { rule.runOnIdle { vm(fixture).images?.state?.value is ImageImportState.Copying } }
        val native = native()
        // A synthetic concurrent document transaction, not injected picker content.
        rule.runOnIdle { native.bridge!!.session.replaceSource(native.bridge!!.session.source.length,
            native.bridge!!.session.source.length, "late text\n\n", origin = EditOrigin.SOURCE); native.bridge!!.refresh() }
        val changed = rule.runOnIdle { native.bridge!!.session.source }
        gate.complete(Unit)
        rule.waitUntil(8_000) { rule.runOnIdle { vm(fixture).images?.state?.value is ImageImportState.AwaitingPlacement } }
        assertEquals(changed, rule.runOnIdle { native.bridge!!.session.source })
        rule.onNodeWithText("返回正文").performClick()
        rule.onNodeWithTag("markdown-note-done").performClick()
        rule.onNodeWithText("图片导入").assertIsDisplayed()
        assertEquals(fixture.formal.markdownRevision, fixture.noteRepository.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
        rule.onNodeWithText("插入到当前光标").performClick()
        awaitImageInserted()
        assertTrue(rule.runOnIdle { native.bridge!!.session.source.contains("late text") })
        assertTrue(rule.runOnIdle { native.bridge!!.session.source.contains("![图片](assets/") })
    }

    @Test fun undoAndRedoImageRestoreTheSourceAndAssetManifestAsOneTransaction() {
        val fixture = fixture()
        mount(fixture); awaitReading(); edit(); placeAtEnd()
        val before = rule.runOnIdle { native().bridge!!.session.source }
        choosePhoto(); rule.runOnIdle { picker.returnResult(fixture.inputUri) }; awaitImageInserted()
        val imported = rule.runOnIdle { vm(fixture).coordinator.currentAssets.single() }
        val inserted = rule.runOnIdle { native().bridge!!.session.source }
        rule.onNodeWithTag("markdown-note-undo").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { native().bridge!!.session.source == before } }
        assertTrue("Undo must restore the prior manifest while keeping the file for redo",
            rule.runOnIdle { vm(fixture).coordinator.currentAssets.isEmpty() })
        assertTrue(fixture.cardRepository.assetFile(fixture.card, imported)!!.isFile)
        rule.onNodeWithTag("markdown-note-redo").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { native().bridge!!.session.source == inserted } }
        assertEquals(listOf(imported), rule.runOnIdle { vm(fixture).coordinator.currentAssets })
    }

    @Test fun anExistingScreenshotInsertsWithoutAnotherPhysicalCopy() {
        val fixture = fixture(withExisting = true)
        mount(fixture); awaitReading(); edit(); placeAtEnd()
        val existing = fixture.formal.structuredMetadata.assets.single()
        val filesBefore = fixture.cardRepository.assetFile(fixture.card, existing)!!.parentFile!!.listFiles()!!.toSet()
        rule.onNodeWithTag("markdown-note-insert").performClick()
        rule.onNodeWithText("插入已有图片 1").performClick(); awaitImageInserted()
        assertEquals(listOf(existing), rule.runOnIdle { vm(fixture).coordinator.currentAssets })
        assertTrue(rule.runOnIdle { native().bridge!!.session.source.contains(existing.relativePath) })
        assertEquals(filesBefore, fixture.cardRepository.assetFile(fixture.card, existing)!!.parentFile!!.listFiles()!!.toSet())
        assertEquals(0, picker.launched.get())
    }

    @Test fun insertionWithinAnOrdinaryParagraphCreatesARealAtomicImageInsteadOfRawMarkdownText() {
        val fixture = fixture()
        mount(fixture); awaitReading(); edit()
        rule.runOnIdle { native().bridge!!.revealSourceOffset(fixture.formal.markdown.indexOf("正文") + 1) }
        choosePhoto(); rule.runOnIdle { picker.returnResult(fixture.inputUri) }; awaitImageInserted()
        assertEquals("An image inserted at a normal writing caret must be an atomic image node", 1,
            rule.runOnIdle { native().bridge!!.session.projection.nodes.count { it.node.atomic?.kind == "image" } })
        assertFalse(rule.runOnIdle { native().text.contains("![图片]") })
    }

    private fun mount(fixture: Fixture, importer: NoteImageImporter = fixture.importer) {
        activeFixture = fixture
        rule.setContent {
            UnarchiveTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner,
                    LocalViewModelStoreOwner provides owner) {
                    if (mounted) MarkdownNoteRoute(fixture.card.cardId, fixture.card.cardVersion, fixture.noteRepository,
                        onBack = {}, imageImporter = importer)
                }
            }
        }
    }
    private fun vm(fixture: Fixture): MarkdownNoteViewModel = ViewModelProvider(owner)[
        "markdown-note-${fixture.card.cardId.value}-${fixture.card.cardVersion}", MarkdownNoteViewModel::class.java]
    private fun awaitReading() = rule.waitUntil(10_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun edit() { rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing() }
    private fun choosePhoto() {
        rule.onNodeWithTag("markdown-note-insert").performClick()
        rule.onNodeWithTag("markdown-note-pick-photo").performClick()
        rule.waitUntil(5_000) { picker.requestCode != null }
    }
    private fun placeAtEnd() = rule.runOnIdle { val bridge = native().bridge!!; bridge.revealSourceOffset(bridge.session.source.length) }
    private fun awaitImageInserted() = rule.waitUntil(10_000) {
        rule.runOnIdle { vm(activeFixture).images?.state?.value is ImageImportState.Inserted }
    }
    private fun native(): NativeDocumentEditText = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) nativeView()
        else rule.runOnIdle { nativeView() }
    private fun nativeView() = descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    private fun descendants(view: View): Sequence<View> = sequence { yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index))) }
    private fun fixture(withExisting: Boolean = false): Fixture {
        check(root.mkdirs() || root.isDirectory)
        val input = File(root, "synthetic.png")
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val cardRepository = FileKnowledgeCardRepository(File(root, "cards"))
        val card = cardRepository.save(KnowledgeCard(KnowledgeCardId("bilibili", "BV_IMAGE_FLOW"), "v1",
            "https://www.bilibili.com/video/BV_IMAGE_FLOW", "合成图片笔记", "synthetic", 1L,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            markdown = "# 合成图片笔记\n\n正文原样保留\n\n"))
        val assets = if (withExisting) listOf(cardRepository.saveAsset(card.cardId, card.cardVersion, "synthetic-existing", input.readBytes(), mimeType = "image/png")) else emptyList()
        val noteRepository = FileNoteContentRepository(File(root, "notes"))
        val content = NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument().copy(assets = assets),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
        val formal = noteRepository.save(content).content
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", input)
        return Fixture(cardRepository, card, noteRepository, formal, uri, NoteAssetStore(cardRepository, card, root))
    }
    private data class Fixture(val cardRepository: FileKnowledgeCardRepository, val card: KnowledgeCard,
        val noteRepository: FileNoteContentRepository, val formal: NoteContent, val inputUri: Uri, val importer: NoteAssetStore)
    private class TestOwner : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private class PickerRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null; private set
        val launched = AtomicInteger()
        var lastContract: String? = null; private set
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.PickVisualMedia)
            this.requestCode = requestCode; lastContract = contract.javaClass.simpleName; launched.incrementAndGet()
        }
        fun returnResult(uri: Uri?) {
            val code = checkNotNull(requestCode); requestCode = null
            dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) })
        }
    }
}
