package com.unarchive.android

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotesLibraryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun filtersNotesAndMaterialsAndExposesCompleteUserFacingMetadata() {
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
        composeRule.onNodeWithText("#跑步 #训练 · 1 个关联").assertIsDisplayed()
        composeRule.onNodeWithText("本地已保存 · ima · 学习资料 · 根目录 · 已同步").assertIsDisplayed()
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
        try {
            check(v2Repository.save(document).isComplete)
            composeRule.setContent {
                UnarchiveTheme {
                    UnarchiveNavigationHost(
                        state = state(document, material()),
                        onCreateEvent = {},
                        onNotesEvent = {},
                        onMeEvent = {},
                        noteDocumentRepository = v2Repository,
                        noteContentRepository = v3Repository,
                        legacyTestContent = { _, _ -> Text("legacy test") },
                        legacyResultsContent = { _ -> Text("legacy results") },
                        legacyLogContent = { _ -> Text("legacy log") },
                        legacySettingsContent = { _ -> Text("legacy settings") },
                    )
                }
            }

            composeRule.onNodeWithTag("bottom-nav-notes").performClick()
            composeRule.onNodeWithTag("notes-filter-saved").performClick()
            composeRule.onNodeWithText("编辑").performClick()
            composeRule.waitUntil(timeoutMillis = 2_500L) {
                composeRule.onAllNodesWithTag("markdown-editor-source").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("markdown-editor-source").assertIsDisplayed()
            check(v3Repository.find(document.cardId, document.generation.cardVersion) != null)
            check(v3Repository.migrationBackup(document.cardId, document.generation.cardVersion) != null)
        } finally {
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
