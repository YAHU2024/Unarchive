package com.unarchive.android.editor

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.assets.ImageImportState
import com.unarchive.android.editor.assets.NoteImageImporter
import com.unarchive.android.editor.native.NativeDocumentHost
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** SavedState identity restoration with the production VM/native host, not an OS Picker test. */
class NotePendingPickerIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "pending-picker-identity-${System.nanoTime()}")

    @After fun cleanOnlySyntheticFixture() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun identityOnlyRestoreReusesThePreBridgeImageCoordinatorAndBlocksDoneUntilExplicitCancel() {
        val formal = content()
        val repository = FileNoteContentRepository(root)
        assertTrue(repository.save(formal).isComplete)
        val anchor = formal.markdown.indexOf("正文") + 1
        val saved = SavedStateHandle(mapOf("note-picker" to arrayOf(
            "synthetic-pending-request", NoteContent.markdownFingerprint(formal.markdown), anchor.toString(), "-1", "合成图片",
            formal.cardId.value, formal.cardVersion,
        )))
        assertFalse(saved.contains("note-picker-uri"))
        val importerCalls = AtomicInteger()
        val importer = object : NoteImageImporter {
            override suspend fun importImage(openStream: () -> InputStream): CardAsset {
                importerCalls.incrementAndGet(); error("Identity-only restoration must not read media")
            }
            override suspend fun useExisting(assetId: String, manifest: List<CardAsset>): CardAsset {
                importerCalls.incrementAndGet(); error("Identity-only restoration must not read an asset")
            }
        }
        lateinit var vm: MarkdownNoteViewModel
        lateinit var beforeBridgeController: com.unarchive.android.editor.assets.ImageImportCoordinator
        compose.runOnUiThread {
            vm = MarkdownNoteViewModel(formal.cardId, formal.cardVersion, repository, saved)
            compose.activity.viewModelStore.put("pending-picker-identity", vm)
            // Constructor starts asynchronous file load. This call occurs in the same main
            // turn before its IO continuation can publish a bridge.
            assertNull(vm.bridge)
            vm.configureImages(importer)
            beforeBridgeController = checkNotNull(vm.images)
            assertTrue(beforeBridgeController.state.value is ImageImportState.Idle)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            val bridge = vm.bridge
            UnarchiveTheme {
                if (bridge != null) NativeDocumentHost(bridge, state.mode == NoteEditorMode.Editing,
                    Modifier.fillMaxSize(), textColor = MaterialTheme.colorScheme.onSurface,
                    backgroundColor = MaterialTheme.colorScheme.background)
            }
        }
        compose.waitUntil(12_000) { vm.bridge != null && vm.state.value.mode == NoteEditorMode.Reading }
        compose.runOnIdle {
            vm.configureImages(importer)
            assertSame(beforeBridgeController, vm.images)
            val picking = beforeBridgeController.state.value as ImageImportState.Picking
            assertEquals("synthetic-pending-request", picking.request.requestId)
            assertEquals(vm.bridge!!.session.sessionId, picking.request.anchor.sessionId)
            assertEquals(vm.bridge!!.session.version, picking.request.anchor.documentVersion)
            assertEquals(anchor, picking.request.anchor.sourceOffset)
            assertNull(picking.request.anchor.replaceEnd)
            assertTrue(beforeBridgeController.needsResolution)
            assertNull(vm.savedPickerUri)
            vm.onIntent(NoteEditorIntent.Done)
        }
        compose.waitUntil(5_000) { vm.state.value.panel == NoteEditorPanel.Import }
        assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(0L, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertEquals(formal.markdown, compose.runOnIdle { vm.bridge!!.session.source })
        assertEquals(0, importerCalls.get())
        compose.runOnIdle { vm.cancelImport() }
        compose.waitUntil(5_000) { !beforeBridgeController.needsResolution && vm.state.value.panel == NoteEditorPanel.None }
        assertFalse(saved.contains("note-picker")); assertFalse(saved.contains("note-picker-uri"))
        compose.runOnIdle { vm.onIntent(NoteEditorIntent.Edit) }
        compose.waitUntil(5_000) { vm.state.value.mode == NoteEditorMode.Editing && vm.bridge!!.editing }
        val bridge = compose.runOnIdle { vm.bridge!! }
        val native = compose.runOnIdle { checkNotNull(bridge.view) }
        val buffer = compose.runOnIdle { native.text }
        compose.runOnIdle {
            native.setSelection(native.length())
            assertTrue(native.onCreateInputConnection(EditorInfo())!!.commitText("，取消后正式编辑", 1))
        }
        val updated = formal.markdown + "，取消后正式编辑"
        compose.waitUntil(8_000) { bridge.session.source == updated }
        compose.runOnIdle { vm.onIntent(NoteEditorIntent.Done) }
        compose.waitUntil(10_000) { vm.state.value.mode == NoteEditorMode.Reading &&
            repository.find(formal.cardId, formal.cardVersion)?.markdown == updated }
        assertEquals(1L, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        compose.runOnIdle { assertSame(beforeBridgeController, vm.images); assertSame(buffer, native.text); assertSame(bridge, vm.bridge) }
        assertEquals(0, importerCalls.get())
    }

    private fun content(): NoteContent {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_picker_identity_only"), "v1",
            "https://www.bilibili.com/video/BV_picker_identity_only", "合成身份恢复", "合成作者", 1,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000, markdown = "# 合成身份恢复\n\n正式正文")
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0, card.toNoteDocument(), createdAtEpochMs = 1000, updatedAtEpochMs = 1000)
    }
}
