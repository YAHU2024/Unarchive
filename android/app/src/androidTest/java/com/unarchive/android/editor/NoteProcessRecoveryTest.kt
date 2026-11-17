package com.unarchive.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.assets.ImageImportState
import com.unarchive.android.editor.assets.NoteAssetStore
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Two separately instrumented phases; only the external runner performs force-stop. */
class NoteProcessRecoveryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val runId = InstrumentationRegistry.getArguments().getString("noteProcessRun").orEmpty()
    private val fixtureParent = File(context.filesDir, "note-editor-process-recovery")
    private val fixtureRoot = File(fixtureParent, runId)
    private val cardId = KnowledgeCardId("bilibili", "BV_PROCESS_SYNTHETIC")
    private val version = "process-v1"
    private val formalSource = "# 进程恢复合成标题\n\n正式正文保持不变，Unicode🙂与CRLF边界。\r\n\r\n"
    private var showRoute by mutableStateOf(true)
    private var disposed = false

    @Before fun requireTheDedicatedTwoPhaseRunner() {
        org.junit.Assume.assumeTrue("Requires the dedicated force-stop recovery runner", runId.isNotEmpty())
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "A safe unique noteProcessRun argument is required" }
    }

    @After fun releaseOnlyTheCurrentViewModel() {
        if (runId.isEmpty()) return
        if (!disposed) {
            rule.runOnIdle { showRoute = false }; rule.waitForIdle()
            rule.runOnIdle { rule.activity.viewModelStore.clear() }
        }
    }

    @Test fun seedNoteDraftAndSourceScratch() {
        check(!File(fixtureRoot, "seed-record.json").exists()) { "Refusing to replace an existing recovery run" }
        check(fixtureRoot.mkdirs() || fixtureRoot.isDirectory)
        val cardRepository = FileKnowledgeCardRepository(File(fixtureRoot, "cards"))
        val card = cardRepository.save(syntheticCard())
        val noteRepository = FileNoteContentRepository(File(fixtureRoot, "notes"))
        val content = noteRepository.save(NoteContent(cardId, version, formalSource, 0L, card.toNoteDocument(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        val input = File(fixtureRoot, "source-pixels.png")
        val pixels = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        input.outputStream().use { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }; pixels.recycle()
        mount(cardRepository, card, noteRepository)
        awaitReading()
        rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val vm = viewModel()
        val native = native()
        rule.runOnIdle { native.bridge!!.revealSourceOffset(native.bridge!!.session.source.length)
            vm.beginImagePick { /* A synthetic controlled input; no external media picker UI is claimed. */ } }
        rule.waitUntil(5_000) { rule.runOnIdle { vm.pendingPicker != null } }
        rule.runOnIdle { vm.pickerResult({ input.inputStream() }) }
        rule.waitUntil(10_000) { rule.runOnIdle { vm.images?.state?.value is ImageImportState.Inserted } }
        val asset = rule.runOnIdle { vm.coordinator.currentAssets.single() }
        val draftSource = rule.runOnIdle { native.bridge!!.session.captureSnapshot().source }
        assertTrue(draftSource.contains(asset.relativePath))
        assertEquals(1, rule.runOnIdle { native.bridge!!.session.projection.nodes.count { it.node.atomic?.kind == "image" } })
        rule.onNodeWithTag("markdown-note-edit-more").performClick()
        rule.onNodeWithText("全文源码").performScrollTo().performClick()
        rule.onNodeWithTag("markdown-note-source-panel").assertIsDisplayed()
        val pendingText = draftSource + "\n\n未应用源码面板内容🙂进程恢复END"
        sourcePanelView().perform(androidx.test.espresso.action.ViewActions.replaceText(pendingText))
        rule.waitForIdle()
        rule.runOnIdle { vm.flushBoundary() }
        rule.waitUntil(10_000) {
            noteRepository.loadRecoveryState(cardId, version).singleOrNull()?.draft?.let {
                it.markdown == draftSource && it.pendingSourceEdit?.text == pendingText && it.assets == listOf(asset)
            } == true
        }
        assertEquals(NoteContent.markdownFingerprint(formalSource),
            NoteContent.markdownFingerprint(noteRepository.find(cardId, version)!!.markdown))
        val storedFile = cardRepository.assetFile(card, asset)!!
        assertEquals(asset.sha256, java.security.MessageDigest.getInstance("SHA-256").digest(storedFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) })
        val seed = JSONObject().put("runId", runId).put("pid", Process.myPid()).put("timeEpochMs", System.currentTimeMillis())
            .put("formalRevision", content.markdownRevision).put("formalSourceFingerprint", NoteContent.markdownFingerprint(formalSource))
            .put("draftSourceFingerprint", NoteContent.markdownFingerprint(draftSource)).put("scratchFingerprint", NoteContent.markdownFingerprint(pendingText))
            .put("assetId", asset.assetId).put("assetPath", asset.relativePath).put("assetSha256", asset.sha256)
            .put("assetBytes", asset.byteCount).put("assetMime", asset.mimeType)
            .put("route", "MarkdownNoteRoute").put("scope", "persisted draft plus unapplied source panel and managed PNG")
        File(fixtureRoot, "seed-record.json").writeText(seed.toString(2))
        writeReport("seed", seed.put("passed", true))
        // Deliberately preserve this run's app-private fixture for force-stop + verify.
    }

    @Test fun verifyDraftAfterActualTargetProcessRestart() {
        val seedFile = File(fixtureRoot, "seed-record.json")
        check(seedFile.isFile) { "Seed phase must succeed before verify" }
        val seed = JSONObject(seedFile.readText())
        assertEquals(runId, seed.getString("runId"))
        assertNotEquals("Verify must execute in a new target-app process", seed.getInt("pid"), Process.myPid())
        val cardRepository = FileKnowledgeCardRepository(File(fixtureRoot, "cards"))
        val card = cardRepository.find(cardId, version)!!
        val noteRepository = FileNoteContentRepository(File(fixtureRoot, "notes"))
        val original = noteRepository.find(cardId, version)!!
        assertEquals(seed.getLong("formalRevision"), original.markdownRevision)
        assertEquals(seed.getString("formalSourceFingerprint"), NoteContent.markdownFingerprint(original.markdown))
        val recovery = noteRepository.loadRecoveryState(cardId, version).single()
        assertEquals(NoteRecoveryStatus.AVAILABLE, recovery.status)
        assertTrue(recovery.sourceScratchApplicable)
        val draft = recovery.draft!!
        assertEquals(seed.getString("draftSourceFingerprint"), NoteContent.markdownFingerprint(draft.markdown))
        assertEquals(seed.getString("scratchFingerprint"), NoteContent.markdownFingerprint(draft.pendingSourceEdit!!.text))
        val asset = draft.assets!!.single()
        assertEquals(seed.getString("assetId"), asset.assetId)
        assertEquals(seed.getString("assetPath"), asset.relativePath)
        assertEquals(seed.getString("assetSha256"), asset.sha256)
        assertEquals(seed.getLong("assetBytes"), asset.byteCount)
        mount(cardRepository, card, noteRepository)
        awaitReading()
        assertEquals(seed.getString("formalSourceFingerprint"), rule.runOnIdle { NoteContent.markdownFingerprint(native().bridge!!.session.source) })
        rule.onNodeWithTag("markdown-note-recovery").performClick()
        rule.onNodeWithTag("markdown-note-restore-0").performClick(); awaitEditing()
        rule.waitUntil(8_000) { rule.runOnIdle { viewModel().coordinator.currentAssets == listOf(asset) } }
        assertEquals(seed.getString("draftSourceFingerprint"), rule.runOnIdle { NoteContent.markdownFingerprint(native().bridge!!.session.source) })
        rule.onNodeWithTag("markdown-note-edit-more").performClick()
        rule.onNodeWithText("全文源码").performScrollTo().performClick()
        rule.onNodeWithTag("markdown-note-source-panel").assertIsDisplayed()
        sourcePanelView().check(androidx.test.espresso.assertion.ViewAssertions.matches(
            androidx.test.espresso.matcher.ViewMatchers.withText(draft.pendingSourceEdit!!.text)))
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val previousClip = rule.runOnIdle { clipboard.primaryClip }
        try {
            rule.onNodeWithText("复制源码").performClick()
            val copiedFingerprint = rule.runOnIdle { NoteContent.markdownFingerprint(clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString()) }
            assertEquals(seed.getString("scratchFingerprint"), copiedFingerprint)
        } finally {
            rule.runOnIdle { if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip() }
        }
        rule.onNodeWithTag("markdown-note-apply-source").performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { NoteContent.markdownFingerprint(native().bridge!!.session.source) == seed.getString("scratchFingerprint") } }
        assertEquals(seed.getLong("formalRevision"), noteRepository.find(cardId, version)?.markdownRevision)
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        val saved = noteRepository.find(cardId, version)!!
        assertEquals(seed.getLong("formalRevision") + 1L, saved.markdownRevision)
        assertEquals(seed.getString("scratchFingerprint"), NoteContent.markdownFingerprint(saved.markdown))
        assertEquals(listOf(asset), saved.structuredMetadata.assets)
        val formal = FormalMarkdownSnapshotResolver.resolve(card, saved)
        val artifact = KnowledgeCardExport.portable(formal.card) { KnowledgeCardExport.readManagedAsset(it, cardRepository.assetFile(formal.card, it)) }
        assertTrue(artifact.isFullyPortable)
        assertEquals(1, artifact.assets.embeddedCount)
        assertEquals(asset.byteCount, artifact.assets.embeddedBytes)
        assertTrue(artifact.markdown.contains("data:${asset.mimeType};base64,"))
        assertFalse(artifact.markdown.contains(asset.relativePath))
        assertTrue(noteRepository.loadRecoveryState(cardId, version).isEmpty())
        val result = JSONObject().put("runId", runId).put("seedPid", seed.getInt("pid")).put("verifyPid", Process.myPid())
            .put("passed", true).put("timeEpochMs", System.currentTimeMillis()).put("formalRevision", saved.markdownRevision)
            .put("draftAndScratchFingerprintsMatched", true).put("managedAssetBytesMatched", true)
            .put("portableImageCount", artifact.assets.embeddedCount).put("processIdsDiffer", true)
            .put("sourcePrinted", false).put("systemPickerUiAcceptance", "not covered")
        rule.runOnIdle { showRoute = false }; rule.waitForIdle()
        rule.runOnIdle { rule.activity.viewModelStore.clear() }; disposed = true
        deleteOnlyVerifiedRunFixture()
        writeReport("verify", result.put("syntheticFixtureRemoved", !fixtureRoot.exists()))
    }

    private fun mount(cards: FileKnowledgeCardRepository, card: KnowledgeCard, notes: FileNoteContentRepository) {
        val importer = NoteAssetStore(cards, card, fixtureRoot)
        rule.setContent { UnarchiveTheme {
            if (showRoute) MarkdownNoteRoute(cardId, version, notes, onBack = {}, imageImporter = importer,
                readAsset = { KnowledgeCardExport.readManagedAsset(it, cards.assetFile(card, it)) })
        } }
    }
    private fun awaitReading() = rule.waitUntil(10_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun viewModel() = ViewModelProvider(rule.activity)["markdown-note-${cardId.value}-$version", MarkdownNoteViewModel::class.java]
    private fun native(): NativeDocumentEditText = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) nativeView() else rule.runOnIdle { nativeView() }
    private fun nativeView() = descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    private fun descendants(view: View): Sequence<View> = sequence { yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index))) }
    private fun sourcePanelView() = androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(
        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java),
        org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(NativeDocumentEditText::class.java))))
    private fun writeReport(phase: String, values: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "note-editor-process-recovery/$runId")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$phase.json").writeText(values.toString(2))
    }
    private fun deleteOnlyVerifiedRunFixture() {
        val parent = fixtureParent.canonicalFile
        val target = fixtureRoot.canonicalFile
        check(target.parentFile == parent && target.name == runId && target != parent)
        target.walkTopDown().forEach { check(it.canonicalFile.toPath().startsWith(target.toPath())) }
        check(target.deleteRecursively() && !target.exists()) { "Synthetic recovery fixture cleanup failed" }
    }
    private fun syntheticCard() = KnowledgeCard(cardId, version, "https://www.bilibili.com/video/BV_PROCESS_SYNTHETIC",
        "进程恢复合成标题", "synthetic", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
        analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = formalSource)
}
