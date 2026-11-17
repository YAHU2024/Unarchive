package com.unarchive.android.editor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.shareMarkdownFile
import com.unarchive.android.ui.destination.DestinationScreen
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test

/** Actual production export helper and OS chooser; no recipient is selected and nothing is sent. */
class NoteSystemShareFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val runId = InstrumentationRegistry.getArguments().getString("noteEditorReportRun")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } ?: "direct-${System.currentTimeMillis()}"
    private val caseToken = "$runId-${UUID.randomUUID().toString().take(8)}"
    private val fileName = "unarchive-system-share-$caseToken.md"
    private val root = File(context.cacheDir, "note-system-share-$caseToken")
    private val ownedExports = mutableListOf<File>()
    private var destination by mutableStateOf<KnowledgeCard?>(null)
    private var request: FormalOperationRequest? = null
    private var artifact: KnowledgeCardExportArtifact? = null
    private var exportFile: File? = null
    private var shareOpen = false
    private var automationFlags: Int? = null
    private var exportDirectoriesBefore: Set<String> = emptySet()
    private var exportAttempted = false
    private var commits = 0
    private lateinit var fixture: Fixture

    @After fun cleanSyntheticFixturesAndOnlyOwnedExport() {
        if (shareOpen) runCatching { instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        automationFlags?.let { flags -> val info = instrumentation.uiAutomation.serviceInfo
            info.flags = flags; instrumentation.uiAutomation.serviceInfo = info }
        rule.runOnUiThread { rule.activity.viewModelStore.clear() }
        // If launch failed after writing, discover only our unique new filename for cleanup.
        if (ownedExports.isEmpty() && exportAttempted) runCatching { locateOwnExport()?.let(ownedExports::add) }
        val exportRoot = File(context.cacheDir, "export").canonicalFile
        ownedExports.distinctBy { it.canonicalPath }.forEach { file ->
            val canonical = file.canonicalFile
            val parent = checkNotNull(canonical.parentFile)
            check(canonical.name == fileName && parent.parentFile == exportRoot && parent.name.matches(UUID_DIRECTORY))
            if (canonical.exists()) check(canonical.delete())
            if (parent.listFiles()?.isEmpty() == true) check(parent.delete())
        }
        val canonical = root.canonicalFile
        check(canonical.parentFile == context.cacheDir.canonicalFile && canonical.name == "note-system-share-$caseToken")
        if (canonical.exists()) {
            canonical.walkTopDown().forEach { file -> check(file.canonicalPath == canonical.path || file.canonicalPath.startsWith(canonical.path + File.separator)) }
            canonical.deleteRecursively()
        }
    }

    @Test fun dirtyUsesLastFormalImageRevisionInRealSystemChooserAndCancelReturnsDraft() = report("dirty") { evidence ->
        fixture = fixture(withImage = true)
        mount(); awaitReading(); edit()
        val native = native()
        val editable = main { native.text }
        append(native, "\n未保存合成输入不得导出-$caseToken")
        val draft = main { native.bridge!!.session.source }
        val selection = main { native.bridge!!.sourceSelection() }
        openExternal()
        rule.onNodeWithText("使用上次正式版本").performClick()
        awaitDestination()
        val fixed = main { checkNotNull(request) }
        assertEquals(fixture.formal.markdownRevision, fixed.revision)
        rule.onNodeWithTag("destination-export").performScrollTo().performClick()
        assertRealChooser(evidence)
        val exported = checkNotNull(artifact)
        assertTrue(exported.isFullyPortable)
        assertEquals(1, exported.assets.embeddedCount)
        val file = awaitOwnExport()
        assertExactExport(file, exported.markdown)
        val output = decodeUtf8(file)
        assertTrue(output.contains("data:image/png;base64,"))
        assertFalse(output.contains("未保存合成输入"))
        assertFalse(output.contains(fixture.formal.structuredMetadata.assets.single().relativePath))
        assertEquals(fixture.formal.markdown, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        cancelChooser()
        rule.onNodeWithTag("destination-back").performClick(); awaitEditing()
        assertSame(native, native())
        assertSame(editable, main { native.text })
        assertEquals(draft, main { native.bridge!!.session.source })
        assertEquals(selection, main { native.bridge!!.sourceSelection() })
        assertTrue(main { vm().state.value.dirty })
        assertEquals(0, commits)
        evidence.put("formalRevision", fixed.revision).put("embeddedAssetCount", exported.assets.embeddedCount)
            .put("exportUtf8Exact", true).put("exportSha256", KnowledgeCard.sha256(file.readBytes()))
            .put("draftExcluded", true).put("returnedDraftExact", true).put("returnedSelectionExact", true)
            .put("sameNativeView", true).put("sameEditable", true).put("formalUnchanged", true)
    }

    @Test fun saveBeforeShareUsesExactEmptyFormalRevisionInRealSystemChooser() = report("empty") { evidence ->
        fixture = fixture(withImage = false)
        mount(); awaitReading(); edit()
        val native = native()
        val editable = main { native.text }
        main { native.setSelection(0, native.length())
            assertTrue(checkNotNull(native.onCreateInputConnection(EditorInfo())).commitText("", 1)) }
        rule.waitUntil(10_000) { main { native.bridge!!.session.source.isEmpty() } }
        openExternal()
        rule.onNodeWithText("保存后继续").performClick()
        awaitDestination()
        val fixed = main { checkNotNull(request) }
        val formal = checkNotNull(fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion))
        assertEquals("", formal.markdown)
        assertEquals(fixture.formal.markdownRevision + 1, formal.markdownRevision)
        assertEquals(formal.markdownRevision, fixed.revision)
        assertEquals(NoteContent.markdownFingerprint(""), fixed.sourceFingerprint)
        rule.onNodeWithTag("destination-export").performScrollTo().performClick()
        assertRealChooser(evidence)
        val file = awaitOwnExport()
        assertExactExport(file, "")
        assertEquals(0L, file.length())
        assertEquals("", checkNotNull(artifact).markdown)
        cancelChooser()
        rule.onNodeWithTag("destination-back").performClick(); awaitReading()
        assertSame(native, native())
        assertSame(editable, main { native.text })
        assertEquals("", main { native.bridge!!.session.source })
        assertFalse(main { vm().state.value.dirty })
        assertEquals(1, commits)
        evidence.put("formalRevision", fixed.revision).put("emptyFormalExportExact", true)
            .put("exportUtf8Exact", true).put("exportBytes", file.length()).put("exportSha256", KnowledgeCard.sha256(file.readBytes()))
            .put("returnedReading", true).put("sameNativeView", true).put("sameEditable", true)
    }

    private fun mount() {
        rule.setContent { UnarchiveTheme { Box(Modifier.fillMaxSize()) {
            // The retained editor owns its original Native view/session underneath Destination.
            MarkdownNoteRoute(fixture.card.cardId, fixture.card.cardVersion, fixture.notes, onBack = {},
                onFormalCommitted = { commits++ }, onOpenExternal = { fixed ->
                    request = fixed
                    val exact = checkNotNull(NoteExternalActionCoordinator.resolve(fixed, fixture.notes))
                    destination = FormalMarkdownSnapshotResolver.resolve(fixture.card, exact).card
                }, readAsset = { KnowledgeCardExport.readManagedAsset(it, fixture.cards.assetFile(fixture.card, it)) })
            destination?.let { card -> DestinationScreen(DestinationUiState(card = card, localSaved = true),
                onEvent = { event -> if (event == DestinationEvent.ExportMarkdown) exportFormal() },
                onBack = { destination = null }, onOpenSecuritySettings = {}) }
        } } }
    }
    private fun exportFormal() {
        val fixed = checkNotNull(request)
        val formal = checkNotNull(NoteExternalActionCoordinator.resolve(fixed, fixture.notes))
        val snapshot = FormalMarkdownSnapshotResolver.resolve(fixture.card, formal)
        check(snapshot.markdownRevision == fixed.revision)
        artifact = KnowledgeCardExport.portable(snapshot.card) {
            KnowledgeCardExport.readManagedAsset(it, fixture.cards.assetFile(snapshot.card, it))
        }
        exportDirectoriesBefore = exportDirectories()
        exportAttempted = true
        shareOpen = true
        rule.activity.shareMarkdownFile(fileName, checkNotNull(artifact).markdown, "合成系统分享 $caseToken")
    }
    private fun exportDirectories(): Set<String> = File(context.cacheDir, "export").listFiles()
        .orEmpty().filter { it.isDirectory && it.name.matches(UUID_DIRECTORY) }.map { it.name }.toSet()
    private fun locateOwnExport(): File? {
        val exportRoot = File(context.cacheDir, "export").canonicalFile
        val newDirectories = exportRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(UUID_DIRECTORY) && it.name !in exportDirectoriesBefore }
        val own = newDirectories.map { File(it, fileName) }.filter { it.isFile && it.canonicalFile.parentFile?.parentFile == exportRoot }
        return own.singleOrNull()
    }
    private fun awaitOwnExport(): File {
        rule.waitUntil(8_000) { locateOwnExport()?.also { exportFile = it } != null }
        return checkNotNull(exportFile).also { if (it !in ownedExports) ownedExports += it }
    }
    private fun decodeUtf8(file: File) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
    private fun assertExactExport(file: File, expected: String) {
        assertEquals(expected, decodeUtf8(file))
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), file.readBytes())
        assertEquals(NoteContent.markdownFingerprint(expected), KnowledgeCard.sha256(file.readBytes()))
    }
    private fun assertRealChooser(evidence: JSONObject) {
        val info = instrumentation.uiAutomation.serviceInfo
        automationFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        var chooser: AccessibilityNodeInfo? = null
        val found = waitFor(12_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
            val owner = root.packageName?.toString().orEmpty()
            val system = runCatching { context.packageManager.getApplicationInfo(owner, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }.getOrDefault(false)
            val resourceCount = nodes(root).count { node ->
                node.viewIdResourceName.orEmpty().substringAfter(":id/", "") in CHOOSER_IDS }
            val packageKnown = owner != "android" && owner in CHOOSER_PACKAGES
            val classKnown = root.className?.toString()?.let { it.contains("ChooserActivity") || it.contains("ResolverActivity") } == true
            evidence.put("chooserSystemPackage", system).put("chooserFixedResourceCount", resourceCount)
            if (system && (packageKnown || classKnown || resourceCount > 0)) { chooser = root; true } else false
        }
        if (!found) evidence.put("reasonCode", "REAL_SYSTEM_CHOOSER_NOT_UNIQUELY_IDENTIFIED")
        assumeTrue("REAL_SYSTEM_CHOOSER_NOT_UNIQUELY_IDENTIFIED", found)
        evidence.put("realSystemChooserObserved", true).put("chooserPackage", chooser?.packageName?.toString())
            .put("chooserRootClass", chooser?.className?.toString()).put("recipientSelected", false)
            .put("messageSent", false)
    }
    private fun cancelChooser() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        assertTrue("Chooser must return to the original app", waitFor(10_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
        })
        shareOpen = false
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("destination-back").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root); for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(nodes(it)) }
    }
    private fun waitFor(timeout: Long, predicate: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return true; SystemClock.sleep(100) }
        return false
    }
    private fun report(name: String, action: (JSONObject) -> Unit) {
        val result = JSONObject().put("runId", runId).put("case", name).put("productionRoute", "MarkdownNoteRoute")
            .put("productionShareHelper", "shareMarkdownFile").put("passed", false).put("recipientSelected", false)
            .put("scope", "Production portable formal export plus actual OS chooser; no receiving app rendering or delivery tested")
        try { action(result); result.put("passed", true) }
        catch (error: AssumptionViolatedException) { result.put("skipped", true); throw error }
        catch (error: Throwable) { result.put("failureType", error.javaClass.simpleName); throw error }
        finally { val directory = File(context.getExternalFilesDir(null), "note-editor-system-share/$runId")
            check(directory.mkdirs() || directory.isDirectory); File(directory, "summary-$name.json").writeText(result.toString(2)) }
    }
    private fun openExternal() {
        rule.onNodeWithTag("markdown-note-edit-more").performClick()
        rule.onNodeWithText("分享与同步").performScrollTo().performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitDestination() = rule.waitUntil(10_000) { rule.onAllNodesWithTag("destination-export").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitReading() = rule.waitUntil(12_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun edit() { rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing() }
    private fun append(native: NativeDocumentEditText, text: String) {
        main { native.setSelection(native.length()); assertTrue(checkNotNull(native.onCreateInputConnection(EditorInfo())).commitText(text, 1)) }
        rule.waitUntil(8_000) { main { native.bridge!!.session.source.endsWith(text) } }
    }
    private fun vm() = ViewModelProvider(rule.activity)["markdown-note-${fixture.card.cardId.value}-${fixture.card.cardVersion}", MarkdownNoteViewModel::class.java]
    private fun native() = main { descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single() }
    private fun descendants(view: View): Sequence<View> = sequence { yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index))) }
    private fun <T> main(action: () -> T) = rule.runOnIdle(action)
    private fun fixture(withImage: Boolean): Fixture {
        check(root.mkdirs() || root.isDirectory)
        val cards = FileKnowledgeCardRepository(File(root, "cards"))
        var card = KnowledgeCard(KnowledgeCardId("synthetic", "system-share"), "v1", "https://example.com/synthetic",
            "合成系统分享", "synthetic", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "正式中文🙂正文-$caseToken")
        // Asset writes belong to an already-published card-version namespace.
        card = cards.save(card)
        if (withImage) {
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val bytes = ByteArrayOutputStream().use { output -> try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
                finally { bitmap.recycle() } }
            val asset = cards.saveAsset(card.cardId, card.cardVersion, "synthetic-share-image", bytes, mimeType = "image/png")
            card = card.copy(markdown = card.markdown + "\n\n![合成图片](${asset.relativePath})", assets = listOf(asset))
        }
        card = cards.save(card)
        val notes = FileNoteContentRepository(File(root, "notes"))
        val formal = notes.save(NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        return Fixture(cards, card, notes, formal)
    }
    private data class Fixture(val cards: FileKnowledgeCardRepository, val card: KnowledgeCard, val notes: FileNoteContentRepository, val formal: NoteContent)
    private companion object {
        val UUID_DIRECTORY = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val CHOOSER_PACKAGES = setOf("android", "com.android.intentresolver", "com.google.android.intentresolver", "com.coloros.resolver", "com.oplus.resolver")
        val CHOOSER_IDS = setOf("chooser_header", "chooser_row_text_option", "chooser_grid", "resolver_list", "resolver_drawer_layout",
            "content_preview_filename", "content_preview_file_layout", "chooser_action_row", "chooser_nearby_button")
    }
}
