package com.unarchive.android.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.analyzer.ApiKeyStore
import com.unarchive.android.analyzer.LlmClient
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.ai.AiApplyResult
import com.unarchive.android.editor.ai.AiDocumentContext
import com.unarchive.android.editor.ai.AiProposalCoordinator
import com.unarchive.android.sync.ImaClient
import com.unarchive.android.sync.ImaCredentialStore
import com.unarchive.android.sync.ImaImageMode
import com.unarchive.android.sync.ImaSyncService
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live gates only. All outbound note/model content is test-generated. */
@RunWith(AndroidJUnit4::class)
class NoteLiveServiceAcceptanceTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId get() = arguments.getString("noteLiveRun").orEmpty()

    @Before fun requireDedicatedSyntheticRunIdentity() {
        assumeTrue("Requires explicit noteLiveRun for isolated synthetic artifacts", runId.isNotEmpty())
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid synthetic run identity" }
    }

    @Test fun reportConfiguredServiceAvailability() {
        val availability = availability()
        writeReport("availability", JSONObject().put("passed", true)
            .put("llmConfigured", availability.llm).put("imaClientConfigured", availability.imaClient)
            .put("imaKeyConfigured", availability.imaKey).put("imaTargetSelected", availability.imaTarget)
            .put("networkCallsPerformed", false).put("credentialsIncluded", false))
    }

    @Test fun liveLlmGeneratesReviewableCandidateFromSyntheticFormalMarkdown() = runBlocking {
        val status = availability()
        requireEnabled("llm", arguments.getString("noteLiveLlm") == "true", status.llm,
            "llm_not_configured")
        val fixture = fixture()
        val keyStore = ApiKeyStore(context)
        val key = keyStore.get()?.takeIf(String::isNotBlank)
        if (key == null) {
            writeSkipped("llm", "llm_not_configured")
            assumeTrue("LLM is not configured", false)
            return@runBlocking
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: AiProposalCoordinator? = null
        try {
            withTimeout(180_000L) {
                val proposals = FileMarkdownAiProposalRepository(File(fixture.root, "proposals"))
                val adapter = MarkdownAiProposalGenerator { formal ->
                    val response = LlmClient().chat(key, MarkdownAiSourcePrompt.SYSTEM, MarkdownAiSourcePrompt.user(formal),
                        thinkingEnabled = keyStore.getThinkingEnabled())
                    MarkdownAiSourcePrompt.result(response, "deepseek-v4-flash")
                }
                val ai = AiProposalCoordinator(scope, proposals, adapter,
                    { fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion) },
                    { fixture.notes.findRevision(fixture.card.cardId, fixture.card.cardVersion, it) },
                    { asset -> KnowledgeCardExport.readManagedAsset(asset, fixture.cards.assetFile(fixture.card, asset))
                        ?.let { bytes -> sha256(bytes) == asset.sha256 } == true })
                controller = ai
                val document = AiDocumentContext(fixture.formal, fixture.formal.markdown, 0L)
                withContext(Dispatchers.Main.immediate) { ai.restore(document) }.join()
                withContext(Dispatchers.Main.immediate) { ai.generate(document) }?.join()
                val proposal = ai.state.value.proposal
                if (proposal == null || ai.state.value.generationFailed) throw SafeLiveFailure("llm_generation_failed")
                withContext(Dispatchers.Main.immediate) { ai.openReview() }?.join()
                val review = ai.state.value.review ?: throw SafeLiveFailure("llm_review_unavailable")
                assertTrue("Complete candidate must remain reviewable", review.candidate == proposal.proposedMarkdown &&
                    review.candidateChunks.joinToString("") { it.text } == review.candidateLines.joinToString(""))
                assertTrue("Managed synthetic image reference must be preserved", proposal.proposedMarkdown.contains(fixture.asset.relativePath))
                assertTrue("Unknown synthetic content must be preserved", proposal.proposedMarkdown.contains("SYNTHETIC_KEEP_" + runId))
                assertTrue("The complete source tail must remain reviewable", proposal.proposedMarkdown.contains("SYNTHETIC_END"))
                val apply = withContext(Dispatchers.Main.immediate) { ai.prepareApply(document) }
                assertTrue("Real generated candidate must pass managed-asset and safety review", apply is AiApplyResult.Ready)
                val reloaded = fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)!!
                assertEquals(fixture.formal.markdownRevision, reloaded.markdownRevision)
                assertTrue("Generation/review must not change formal source", reloaded.markdown == fixture.formal.markdown)
                assertNotNull(proposals.find(fixture.card.cardId.value, fixture.card.cardVersion))
                writeReport("llm", JSONObject().put("passed", true).put("realNetworkCall", true)
                    .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    .put("formalRevisionUnchanged", true).put("candidatePersisted", true)
                    .put("candidateCodePoints", proposal.proposedMarkdown.codePointCount(0, proposal.proposedMarkdown.length))
                    .put("candidateFingerprint", NoteContent.markdownFingerprint(proposal.proposedMarkdown))
                    .put("reviewMode", review.mode.name).put("reviewChunkCount", review.candidateChunks.size)
                    .put("safetyErrorCount", ai.state.value.safetyErrors.size).put("managedImagePreserved", true)
                    .put("unknownSyntheticMarkerPreserved", true).put("humanQualityAcceptance", false))
            }
        } catch (error: Throwable) {
            writeReport("llm", JSONObject().put("passed", false).put("realNetworkCall", true)
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("reason", when (error) { is TimeoutCancellationException -> "timeout"; is SafeLiveFailure -> error.category; else -> "live_llm_acceptance_failed" }))
            throw AssertionError("Synthetic live LLM acceptance failed; see this run's sanitized llm.json")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { controller?.close(); scope.cancel() }
        }
    }

    @Test fun liveImaSyncsExactSyntheticFormalRevisionAndImages() = runBlocking {
        val status = availability()
        requireEnabled("ima", arguments.getString("noteLiveIma") == "true", status.imaClient && status.imaKey && status.imaTarget,
            "ima_credentials_or_target_not_configured")
        val fixture = fixture()
        val credentials = ImaCredentialStore(context)
        val clientId = credentials.clientId().orEmpty()
        val apiKey = credentials.apiKey().orEmpty()
        val preferences = context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
        val target = preferences.getString("ima_kb_id", "").orEmpty().trim()
        val folder = preferences.getString("ima_folder_id", "").orEmpty().trim()
        val started = android.os.SystemClock.elapsedRealtime()
        var remoteCreated = false
        try {
            withTimeout(240_000L) {
                val fixedRequest = NoteExternalActionCoordinator.request(fixture.formal)
                val fixed = NoteExternalActionCoordinator.resolve(fixedRequest, fixture.notes)
                    ?: throw SafeLiveFailure("formal_revision_unavailable")
                val snapshot = FormalMarkdownSnapshotResolver.resolve(fixture.card, fixed)
                val payload = KnowledgeCardExport.portable(snapshot.card) { asset ->
                    KnowledgeCardExport.readManagedAsset(asset, fixture.cards.assetFile(snapshot.card, asset)) }
                assertTrue(payload.isFullyPortable)
                assertEquals(1, payload.assets.embeddedCount)
                val statesFile = File(fixture.root, "live-ima-sync.json")
                val states = FileKnowledgeSyncRepository(statesFile)
                val client = ImaClient(clientId, apiKey)
                client.connect()
                val existedBefore = client.findNote(snapshot.card.cardId.videoId) != null
                val result = ImaSyncService(client, states) { asset ->
                    KnowledgeCardExport.readManagedAsset(asset, fixture.cards.assetFile(snapshot.card, asset))
                }.sync(snapshot.card, target, folder,
                    targetName = "自动验收目标", folderName = "自动验收目录", contentRevision = snapshot.markdownRevision,
                    operationId = "note-live-$runId")
                if (result.state != KnowledgeSyncState.SYNCED) throw SafeLiveFailure("ima_sync_not_successful")
                remoteCreated = !existedBefore && !result.noteId.isNullOrBlank()
                val persistedKey = KnowledgeSyncKey(snapshot.card.cardId, snapshot.card.cardVersion, "ima", target, folder, fixedRequest.revision)
                val stored = FileKnowledgeSyncRepository(statesFile).find(persistedKey)
                assertEquals(KnowledgeSyncState.SYNCED, stored?.state)
                assertTrue(stored?.kbAdded == true)
                assertEquals(fixedRequest.revision, stored?.key?.contentRevision)
                assertEquals(ImaImageMode.EMBEDDED, result.imageDelivery.mode)
                assertEquals(1, result.imageDelivery.embeddedCount)
                assertEquals(0, result.imageDelivery.missingCount)
                assertEquals(fixture.asset.byteCount, result.imageDelivery.embeddedBytes)
                val remoteVisible = client.findNote(snapshot.card.cardId.videoId)
                assertTrue("Remote title search must locate exactly the synthetic note", remoteVisible != null && remoteVisible == result.noteId)
                assertTrue(fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown == fixture.formal.markdown)
                writeReport("ima", JSONObject().put("passed", true).put("realNetworkCall", true)
                    .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started).put("formalRevision", fixedRequest.revision)
                    .put("formalSourceFingerprint", fixedRequest.sourceFingerprint).put("assetManifestFingerprint", fixedRequest.assetsFingerprint)
                    .put("syncState", result.state.name).put("stateRevisionMatched", true).put("durableSyncStateVerified", true)
                    .put("imageMode", result.imageDelivery.mode.name).put("imageEmbeddedCount", result.imageDelivery.embeddedCount)
                    .put("imageEmbeddedBytes", result.imageDelivery.embeddedBytes).put("imageMissingCount", result.imageDelivery.missingCount)
                    .put("remoteSyntheticNoteCreated", remoteCreated).put("remoteSyntheticNoteRetained", true)
                    .put("remoteTitleSearchVerified", true).put("remoteBodyReadbackVerified", false)
                    .put("remoteImageRenderingVerified", false).put("privateContentSent", false).put("remoteIdentifiersIncluded", false))
            }
        } catch (error: Throwable) {
            writeReport("ima", JSONObject().put("passed", false).put("realNetworkCall", true)
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("reason", when (error) { is TimeoutCancellationException -> "timeout"; is SafeLiveFailure -> error.category; else -> "live_ima_acceptance_failed" })
                .put("remoteSyntheticNoteCreated", remoteCreated).put("remoteSyntheticNoteMayExist", true)
                .put("remoteSyntheticNoteRetained", true).put("remoteIdentifiersIncluded", false))
            throw AssertionError("Synthetic live ima acceptance failed; see this run's sanitized ima.json; a synthetic remote note may remain")
        }
    }

    private data class Availability(val llm: Boolean, val imaClient: Boolean, val imaKey: Boolean, val imaTarget: Boolean)
    private fun availability(): Availability {
        val ima = ImaCredentialStore(context)
        val prefs = context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
        return Availability(ApiKeyStore(context).get()?.isNotBlank() == true, ima.clientId()?.isNotBlank() == true,
            ima.apiKey()?.isNotBlank() == true, !prefs.getString("ima_kb_id", "").isNullOrBlank())
    }
    private fun requireEnabled(service: String, optedIn: Boolean, configured: Boolean, reason: String) {
        if (!optedIn) { writeSkipped(service, "explicit_opt_in_disabled"); assumeTrue("Explicit live service opt-in is disabled", false) }
        if (!configured) { writeSkipped(service, reason); assumeTrue("Required live service configuration is absent", false) }
    }
    private fun writeSkipped(service: String, reason: String) = writeReport(service,
        JSONObject().put("passed", false).put("skipped", true).put("reason", reason).put("networkCallsPerformed", false))
    private fun writeReport(name: String, value: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "note-editor-live/$runId")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.json").writeText(value.put("runId", runId).put("syntheticOnly", true).toString(2))
    }
    private fun fixture(): Fixture {
        val root = File(context.filesDir, "note-editor-live/$runId")
        check(root.mkdirs() || root.isDirectory)
        val cards = FileKnowledgeCardRepository(File(root, "cards"))
        val id = KnowledgeCardId("editor_live", "UNARCHIVE_EDITOR_$runId")
        val title = "Unarchive 编辑器自动验收 $runId"
        val baseCard = KnowledgeCard(id, "synthetic-v1", "https://example.invalid/unarchive-editor/$runId", title, "synthetic", 1L,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# $title")
        cards.save(baseCard)
        val image = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val output = java.io.ByteArrayOutputStream()
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); image.recycle()
        val asset = cards.saveAsset(id, baseCard.cardVersion, "synthetic-live-png", output.toByteArray(), mimeType = "image/png")
        val markdown = "---\nsource: synthetic\ncustom: keep-original\n---\n# $title\n\n## 摘要\n这是一条纯合成中文学习笔记，包含🙂。\n\n" +
            "![合成像素](${asset.relativePath})\n\n:::custom\nSYNTHETIC_KEEP_$runId\n:::\n\n末尾SYNTHETIC_END"
        val card = baseCard.copy(markdown = markdown, assets = listOf(asset))
        cards.save(card)
        val notes = FileNoteContentRepository(File(root, "notes"))
        val content = notes.find(id, card.cardVersion) ?: notes.save(NoteContent(id, card.cardVersion, markdown, 3L,
            card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        return Fixture(root, cards, card, notes, content, asset)
    }
    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private data class Fixture(val root: File, val cards: FileKnowledgeCardRepository, val card: KnowledgeCard,
        val notes: FileNoteContentRepository, val formal: NoteContent, val asset: CardAsset)
    private class SafeLiveFailure(val category: String) : Exception(category)
}
