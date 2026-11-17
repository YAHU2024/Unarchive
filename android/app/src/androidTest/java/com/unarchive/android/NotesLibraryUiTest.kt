package com.unarchive.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.Density
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardRelation
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.FileNoteDocumentRepository
import com.unarchive.android.card.FileKnowledgeCardRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NotePublishingState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.notes.NotesScreen
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotesLibraryUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun notesPresentationCanMountWithoutNavigationHostAndForwardsRefresh() {
        val events = mutableListOf<NotesEvent>()
        composeRule.setContent {
            UnarchiveTheme {
                NotesScreen(
                    state = state(document(), material()).notes,
                    onEvent = events::add,
                    onOpenLegacyNotes = {},
                    onOpenEditor = {},
                    onOpenDestinations = {},
                )
            }
        }

        composeRule.onNodeWithTag("notes-refresh").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(NotesEvent.Refresh), events)
        }
    }

    @Test
    fun notesMoreMenuOpensAnchoredLibraryActionWithoutDuplicateRefresh() {
        var openedLibrary = 0
        composeRule.setContent {
            UnarchiveTheme {
                NotesScreen(
                    state = state(document(), material()).notes,
                    onEvent = {},
                    onOpenLegacyNotes = { openedLibrary++ },
                    onOpenEditor = {},
                    onOpenDestinations = {},
                )
            }
        }

        composeRule.onNodeWithTag("notes-more").performClick()
        composeRule.onNodeWithTag("notes-more-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("notes-more-open-library-icon", useUnmergedTree = true).assertIsDisplayed()
        val anchorBounds = composeRule.onNodeWithTag("notes-more").fetchSemanticsNode().boundsInWindow
        val menuBounds = composeRule.onNodeWithTag("notes-more-menu").fetchSemanticsNode().boundsInWindow
        val menuWidthDp = menuBounds.width / composeRule.density.density
        org.junit.Assert.assertTrue(
            "the menu should match the compact 176-224dp design width: $menuWidthDp dp",
            menuWidthDp > 175f && menuWidthDp <= 224f && menuBounds.height > 0f,
        )
        composeRule.onNodeWithTag("notes-more-open-library").performClick()
        composeRule.runOnIdle { assertEquals(1, openedLibrary) }
    }

    @Test
    fun emptyRecentLibraryOpensCreateWithoutRefreshingOrOpeningLegacyLibrary() {
        val events = mutableListOf<NotesEvent>()
        var openedCreate = 0
        var openedLegacy = 0
        composeRule.setContent {
            UnarchiveTheme {
                NotesScreen(
                    state = NotesUiState(0, 0, emptyList()),
                    onEvent = events::add,
                    onOpenLegacyNotes = { openedLegacy++ },
                    onOpenEditor = {},
                    onOpenDestinations = {},
                    onOpenCreate = { openedCreate++ },
                )
            }
        }
        composeRule.onNodeWithTag("notes-empty-action").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, openedCreate)
            assertEquals(0, openedLegacy)
            assertEquals(emptyList<NotesEvent>(), events)
        }
    }

    @Test
    fun filtersNotesAndMaterialsAndShowsCompactMetadataWithoutLosingDraftAction() {
        val events = mutableListOf<NotesEvent>()
        val document = document()
        val material = material()
        val state = state(document, material)
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = {},
                    onNotesEvent = events::add,
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
        composeRule.onNodeWithText("跑步训练笔记").assertIsDisplayed()
        composeRule.onNodeWithText("待整理的视频素材").assertIsDisplayed()
        composeRule.onNodeWithText("跑步训练摘要").assertIsDisplayed()
        composeRule.onNodeWithText("#跑步 #训练 · 1 个关联").assertDoesNotExist()
        composeRule.onNodeWithTag(
            "notes-note-footer-note:${document.cardId.value}:${document.generation.cardVersion}",
            useUnmergedTree = true,
        ).assertIsDisplayed().assertTextContains("已保存", substring = true)
        composeRule.onNodeWithContentDescription("ima · 学习资料 · 根目录 · 已同步").assertDoesNotExist()
        composeRule.onNodeWithText("kb-secret").assertDoesNotExist()

        composeRule.onNodeWithTag("notes-filter-saved").performClick()
        composeRule.onNodeWithText("跑步训练笔记").assertIsDisplayed()
        composeRule.onNodeWithText("待整理的视频素材").assertDoesNotExist()

        composeRule.onNodeWithTag("notes-filter-materials").performClick()
        composeRule.onNodeWithText("跑步训练笔记").assertDoesNotExist()
        composeRule.onNodeWithText("待整理的视频素材").assertIsDisplayed()
        composeRule.onNodeWithText("生成笔记草稿").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(NotesEvent.GenerateDraft("bilibili", "BV_MATERIAL")), events)
        }
    }

    @Test
    fun largeFontKeepsMaterialFilterAndDraftActionReachable() {
        val document = document()
        val material = material()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                UnarchiveTheme {
                    UnarchiveNavigationHost(
                        state = state(document, material),
                        onCreateEvent = {},
                        onNotesEvent = {},
                        onMeEvent = {},
                        legacyTestContent = { _, _ -> Text("legacy test") },
                        legacyResultsContent = { _ -> Text("legacy results") },
                        legacyLogContent = { _ -> Text("legacy log") },
                        legacySettingsContent = { _ -> Text("legacy settings") },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
        composeRule.onNodeWithTag("notes-filter-materials").performClick()
        composeRule.onNodeWithText("生成笔记草稿").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun openingSavedNoteFromLibraryUsesMarkdownMigrationRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "notes-markdown-route-${System.nanoTime()}")
        val document = document()
        val v2Repository = FileNoteDocumentRepository(File(directory, "v2"))
        val v3Repository = FileNoteContentRepository(File(directory, "v3"))
        val cardRepository = FileKnowledgeCardRepository(File(directory, "cards"))
        val card = KnowledgeCard(document.cardId, document.generation.cardVersion,
            document.source.canonicalUrl, document.title, document.source.ownerName,
            document.source.durationMs / 1_000L, document.source.timingAccuracy, document.sourceTranscript,
            createdAtEpochMs = document.createdAtEpochMs, updatedAtEpochMs = document.updatedAtEpochMs,
            markdown = "# ${document.title}\n\n跑步训练摘要")
        try {
            check(v2Repository.save(document).isComplete)
            cardRepository.save(card)
            val initial = state(document, material())
            composeRule.setContent {
                UnarchiveTheme {
                    UnarchiveNavigationHost(
                        state = initial.copy(notes = initial.notes.copy(noteCards = listOf(card))),
                        onCreateEvent = {},
                        onNotesEvent = {},
                        onMeEvent = {},
                        noteDocumentRepository = v2Repository,
                        noteContentRepository = v3Repository,
                        knowledgeCardRepository = cardRepository,
                        legacyTestContent = { _, _ -> Text("legacy test") },
                        legacyResultsContent = { _ -> Text("legacy results") },
                        legacyLogContent = { _ -> Text("legacy log") },
                        legacySettingsContent = { _ -> Text("legacy settings") },
                    )
                }
            }

            composeRule.onNodeWithTag("bottom-nav-notes").performClick()
            composeRule.onNodeWithTag("notes-filter-saved").performClick()
            composeRule.onNodeWithTag("notes-item-note:${document.cardId.value}:${document.generation.cardVersion}").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 12_000L) {
                composeRule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("markdown-note-edit").assertIsDisplayed()
            composeRule.onNodeWithTag("markdown-editor-preview").assertDoesNotExist()
            composeRule.onNodeWithTag("markdown-editor-edit-tab").assertDoesNotExist()
            val migrated = checkNotNull(v3Repository.find(document.cardId, document.generation.cardVersion))
            check(v3Repository.migrationBackup(document.cardId, document.generation.cardVersion) != null)
            composeRule.onNodeWithTag("markdown-note-more").performClick()
            composeRule.onNodeWithText("全文源码").performScrollTo().performClick()
            composeRule.waitUntil(8_000L) {
                composeRule.onAllNodesWithTag("markdown-note-source-panel").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("markdown-note-source-panel").assertIsDisplayed()
            composeRule.onNodeWithText("取消").performClick()
            composeRule.waitUntil(8_000L) {
                composeRule.onAllNodesWithTag("markdown-note-source-panel").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("markdown-note-edit").assertIsDisplayed()
            val afterViewing = checkNotNull(v3Repository.find(document.cardId, document.generation.cardVersion))
            assertEquals(migrated.markdown, afterViewing.markdown)
            assertEquals(migrated.markdownRevision, afterViewing.markdownRevision)
            check(v3Repository.loadRecoveryState(document.cardId, document.generation.cardVersion).isEmpty())
        } finally {
            composeRule.runOnUiThread { composeRule.activity.viewModelStore.clear() }
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }

    private fun state(document: NoteDocument, material: StoredVideoResult): UnarchiveUiState {
        val items = listOf(
            NotesLibraryItem(
                kind = NotesLibraryItemKind.MATERIAL,
                title = material.title,
                summary = "尚未整理的完整转写",
                ownerName = material.ownerName,
                durationSeconds = material.videoDurationSeconds,
                tags = emptyList(),
                relationCount = 0,
                updatedAtEpochMs = material.updatedAtEpochMs,
                localStatusLabel = "待整理素材",
                material = material,
            ),
            NotesLibraryItem(
                kind = NotesLibraryItemKind.SAVED_NOTE,
                title = document.title,
                summary = "跑步训练摘要",
                ownerName = document.source.ownerName,
                durationSeconds = document.source.durationMs / 1_000L,
                tags = document.tags,
                relationCount = 1,
                updatedAtEpochMs = document.updatedAtEpochMs,
                localStatusLabel = "本地已保存",
                destinationSummary = "ima · 学习资料 · 根目录 · 已同步",
                document = document,
            ),
        )
        return UnarchiveUiState(
            create = CreateUiState("", false, "", false, 1),
            notes = NotesUiState(
                noteCount = 1,
                materialCount = 1,
                noteTitles = listOf(document.title),
                noteDocuments = listOf(document),
                libraryItems = items,
            ),
            graph = GraphUiState(1),
            me = MeUiState(),
        )
    }

    private fun document(): NoteDocument {
        val cardId = KnowledgeCardId("bilibili", "BV_NOTE")
        return NoteDocument(
            cardId = cardId,
            title = "跑步训练笔记",
            source = NoteSource("https://www.bilibili.com/video/BV_NOTE", "训练作者", 125_000L, TranscriptTimingAccuracy.EXACT),
            sourceTranscript = listOf(TranscriptSegment(0, 1_000, "转写")),
            blocks = listOf(
                NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "跑步训练摘要"),
                NoteBlock("tags", NoteBlockType.TAG_LIST, NoteBlockOrigin.USER, tags = listOf("跑步", "训练")),
            ),
            tags = listOf("跑步", "训练"),
            relations = listOf(
                CardRelation("source", CardRelationType.SOURCE_VIDEO, cardId.value, "来源", 1L, cardId.value),
                CardRelation("link", CardRelationType.USER_LINK, "bilibili:BV_RELATED", "延伸", 2L, cardId.value),
            ),
            generation = NoteGeneration(
                "version-note",
                state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SUCCEEDED,
            ),
            editing = NoteEditingState(lastSavedAtEpochMs = 2_000L),
            publishing = NotePublishingState(updatedAtEpochMs = 2_000L),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
        )
    }

    private fun material() = StoredVideoResult(
        key = VideoResultKey("bilibili", "BV_MATERIAL"),
        canonicalUrl = "https://www.bilibili.com/video/BV_MATERIAL",
        title = "待整理的视频素材",
        ownerName = "素材作者",
        videoDurationSeconds = 90L,
        engine = AsrEngineKind.SENSE_VOICE_SHERPA,
        processingDurationMs = 1_000L,
        audioDurationMs = 90_000L,
        segments = listOf(TranscriptSegment(0, 1_000, "完整转写")),
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 3_000L,
    )
}
