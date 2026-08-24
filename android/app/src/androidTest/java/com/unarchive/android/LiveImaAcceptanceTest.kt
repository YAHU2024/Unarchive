package com.unarchive.android

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.card.FileKnowledgeCardRepository
import com.unarchive.android.card.FileKnowledgeSyncRepository
import com.unarchive.android.card.FileNoteDocumentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.NoteMarkdownProjection
import com.unarchive.android.card.NoteDocumentSavePhase
import com.unarchive.android.sync.ImaClient
import com.unarchive.android.sync.ImaCredentialException
import com.unarchive.android.sync.ImaCredentialStore
import com.unarchive.android.sync.ImaSyncService
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Opt-in PHQ110 acceptance against the configured real ima account. */
@RunWith(AndroidJUnit4::class)
class LiveImaAcceptanceTest {
    private val arguments
        get() = InstrumentationRegistry.getArguments()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireExplicitLiveAcceptanceOptIn() {
        assumeTrue(arguments.getString(ARG_LIVE_IMA_ACCEPTANCE) == "true")
    }

    @Test
    fun importConfiguredImaProfile() {
        val encoded = requireArgument(ARG_IMA_CONFIG_BASE64)
        val config = JSONObject(
            Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8),
        )
        val clientId = config.getString("client_id").trim()
        val apiKey = config.getString("api_key").trim()
        val knowledgeBaseId = config.getString("knowledge_base_id").trim()
        val folderId = config.optString("folder_id").trim()
        require(clientId.isNotBlank() && apiKey.isNotBlank() && knowledgeBaseId.isNotBlank()) {
            "Imported ima configuration is incomplete"
        }

        ImaCredentialStore(context).apply {
            saveClientId(clientId)
            saveApiKey(apiKey)
        }
        context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
            .edit()
            .putString("ima_kb_id", knowledgeBaseId)
            .putString("ima_folder_id", folderId)
            .commit()

        val stored = ImaCredentialStore(context)
        assertTrue(stored.clientId()?.isNotBlank() == true)
        assertTrue(stored.apiKey()?.isNotBlank() == true)
    }

    @Test
    fun saveNewLocalRevisionForAppendAcceptance() {
        val fixture = loadFixture()
        val repository = FileNoteDocumentRepository(File(context.filesDir, "knowledge-notes"))
        val current = requireNotNull(repository.find(fixture.card.cardId, fixture.card.cardVersion))
        val baseTitle = current.title.replace(ACCEPTANCE_PREFIX_PATTERN, "")
        val updatedTitle = "D4-IMA-${requireArgument(ARG_RUN_ID)}-$baseTitle"
        val saved = repository.save(current.copy(title = updatedTitle))

        assertEquals(NoteDocumentSavePhase.COMPLETE, saved.phase)
        assertTrue(saved.isComplete)
        assertTrue(saved.document.editing.contentRevision > current.editing.contentRevision)
        assertTrue(saved.document.title.startsWith("D4-IMA-${requireArgument(ARG_RUN_ID)}-"))
    }

    @Test
    fun syncCurrentRevisionToConfiguredTarget() = runBlocking {
        val fixture = loadFixture()
        val client = ImaClient(fixture.clientId, fixture.apiKey)
        client.connect()
        val bases = client.listKnowledgeBases()
        val selectedBase = requireNotNull(bases.firstOrNull { it.id == fixture.knowledgeBaseId }) {
            "The configured ima target is no longer available"
        }
        val folders = client.listKnowledgeBaseFolders(selectedBase.id)
        val selectedFolderName = if (fixture.folderId.isBlank()) {
            "根目录"
        } else {
            requireNotNull(folders.firstOrNull { it.id == fixture.folderId }) {
                "The configured ima folder is no longer available"
            }.name
        }

        val result = ImaSyncService(
            client = client,
            states = fixture.syncRepository,
            readAsset = { asset -> fixture.cardRepository.assetFile(fixture.card, asset)?.readBytes() },
        ).sync(
            card = fixture.card,
            kbId = selectedBase.id,
            folderId = fixture.folderId,
            targetName = selectedBase.name,
            folderName = selectedFolderName,
            contentRevision = fixture.contentRevision,
            operationId = "d43-ima-${requireArgument(ARG_RUN_ID)}",
        )

        assertEquals(KnowledgeSyncState.SYNCED, result.state)
        assertNotNull("ima did not return or recover a note ID", result.noteId)
        val persisted = fixture.syncRepository.find(fixture.key)
        assertNotNull("Successful ima state was not persisted", persisted)
        assertEquals(KnowledgeSyncState.SYNCED, persisted?.state)
        assertTrue("Successful ima state did not retain association", persisted?.kbAdded == true)
        assertEquals(fixture.contentRevision, persisted?.key?.contentRevision)
        if (arguments.getString(ARG_EXPECT_REVISION_APPEND) == "true") {
            assertTrue(
                "Expected the existing ima note to receive an appended revision; actual: ${result.message}",
                result.message.contains("修订已追加"),
            )
        }
        println(
            "D4 ima sync passed; contentRevision=${fixture.contentRevision}; " +
                "availableTargets=${bases.size}; availableFolders=${folders.size}; " +
                "imageMode=${result.imageDelivery.mode}",
        )
    }

    @Test
    fun offlineSyncPersistsRetryableFailure() = runBlocking {
        val fixture = loadFixture()
        val result = ImaSyncService(
            client = ImaClient(fixture.clientId, fixture.apiKey),
            states = fixture.syncRepository,
            readAsset = { asset -> fixture.cardRepository.assetFile(fixture.card, asset)?.readBytes() },
        ).sync(
            card = fixture.card,
            kbId = fixture.knowledgeBaseId,
            folderId = fixture.folderId,
            contentRevision = fixture.contentRevision,
            operationId = "d43-ima-offline-${requireArgument(ARG_RUN_ID)}",
        )

        assertEquals(KnowledgeSyncState.RETRYABLE_FAILURE, result.state)
        val persisted = fixture.syncRepository.find(fixture.key)
        assertNotNull("Offline ima failure was not persisted", persisted)
        assertEquals(KnowledgeSyncState.RETRYABLE_FAILURE, persisted?.state)
        assertEquals("同步请求失败", persisted?.lastError)
        assertTrue(persisted?.kbAdded == false)
    }

    @Test
    fun verifyConfiguredTargetAfterProcessRestart() {
        val fixture = loadFixture()
        val persisted = fixture.syncRepository.find(fixture.key)
        assertNotNull("No ima state was restored after process restart", persisted)
        assertEquals(KnowledgeSyncState.SYNCED, persisted?.state)
        assertTrue("Restored ima state lost its association", persisted?.kbAdded == true)
        assertNotNull("Restored ima state lost its remote note reference", persisted?.remoteNoteId)
        assertEquals(fixture.contentRevision, persisted?.key?.contentRevision)
    }

    @Test
    fun invalidThenValidCredentialConnectionRecoversWithoutChangingStoredSecrets() = runBlocking {
        val fixture = loadFixture()
        val invalidError = runCatching {
            ImaClient(fixture.clientId, "d4-invalid-${requireArgument(ARG_RUN_ID)}").connect()
        }.exceptionOrNull()

        assertNotNull("Invalid ima credentials unexpectedly connected", invalidError)
        assertTrue(
            "Invalid ima credentials were not classified by the provider: ${invalidError?.javaClass?.simpleName}",
            invalidError is ImaCredentialException,
        )
        ImaClient(fixture.clientId, fixture.apiKey).connect()

        val unchanged = ImaCredentialStore(context)
        assertTrue(unchanged.clientId()?.isNotBlank() == true)
        assertTrue(unchanged.apiKey()?.isNotBlank() == true)
    }

    private fun loadFixture(): Fixture {
        val videoReference = requireArgument(ARG_VIDEO_REFERENCE)
        val videoId = BV_PATTERN.find(videoReference)?.value
            ?: error("Live ima acceptance currently requires a BV video reference")
        val credentials = ImaCredentialStore(context)
        val clientId = requireNotNull(credentials.clientId()?.takeIf(String::isNotBlank)) {
            "ima client ID is not configured"
        }
        val apiKey = requireNotNull(credentials.apiKey()?.takeIf(String::isNotBlank)) {
            "ima API key is not configured"
        }
        val preferences = context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
        val knowledgeBaseId = requireNotNull(
            preferences.getString("ima_kb_id", "")?.trim()?.takeIf(String::isNotBlank),
        ) { "No ima knowledge base is selected in the app" }
        val folderId = preferences.getString("ima_folder_id", "").orEmpty().trim()

        val cardRepository = FileKnowledgeCardRepository(File(context.filesDir, "knowledge-cards"))
        val cardId = KnowledgeCardId("bilibili", videoId)
        val storedCard = requireNotNull(cardRepository.find(cardId)) {
            "No local knowledge card exists for the requested video"
        }
        val document = requireNotNull(
            FileNoteDocumentRepository(File(context.filesDir, "knowledge-notes"))
                .find(cardId, storedCard.cardVersion),
        ) { "No structured note exists for the current card version" }
        val card = storedCard.copy(
            title = document.title,
            timingAccuracy = document.source.timingAccuracy,
            transcript = document.sourceTranscript,
            tags = document.tags,
            relations = document.relations,
            assets = document.assets,
            updatedAtEpochMs = document.updatedAtEpochMs,
            markdown = NoteMarkdownProjection.render(document),
        )
        val contentRevision = document.editing.contentRevision
        val syncRepository = FileKnowledgeSyncRepository(
            File(context.filesDir, "knowledge-cards/ima-sync.json"),
        )
        return Fixture(
            clientId = clientId,
            apiKey = apiKey,
            knowledgeBaseId = knowledgeBaseId,
            folderId = folderId,
            card = card,
            contentRevision = contentRevision,
            cardRepository = cardRepository,
            syncRepository = syncRepository,
            key = KnowledgeSyncKey(
                card.cardId,
                card.cardVersion,
                "ima",
                knowledgeBaseId,
                folderId,
                contentRevision,
            ),
        )
    }

    private fun requireArgument(name: String): String =
        requireNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $name"
        }

    private data class Fixture(
        val clientId: String,
        val apiKey: String,
        val knowledgeBaseId: String,
        val folderId: String,
        val card: KnowledgeCard,
        val contentRevision: Long,
        val cardRepository: FileKnowledgeCardRepository,
        val syncRepository: FileKnowledgeSyncRepository,
        val key: KnowledgeSyncKey,
    )

    private companion object {
        const val ARG_LIVE_IMA_ACCEPTANCE = "live_ima_acceptance"
        const val ARG_VIDEO_REFERENCE = "video_reference"
        const val ARG_RUN_ID = "run_id"
        const val ARG_IMA_CONFIG_BASE64 = "ima_config_base64"
        const val ARG_EXPECT_REVISION_APPEND = "expect_revision_append"
        val BV_PATTERN = Regex("BV[0-9A-Za-z]{10}")
        val ACCEPTANCE_PREFIX_PATTERN = Regex("^D4-(?:IMA-)?(?:\\d{8}-\\d{6}-)?")
    }
}
