package com.unarchive.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
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
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesThumbnailCandidate
import com.unarchive.android.ui.state.NotesThumbnailKind
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteCoverUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fixtureFiles = mutableListOf<File>()

    @After
    fun deleteFixtureFiles() {
        fixtureFiles.forEach(File::delete)
    }

    @Test
    fun validCoverIsPreferredAndDoesNotOfferRetry() {
        val document = document("COVER")
        val coverPath = imageFixture("cover", Color.rgb(24, 96, 180))
        val chapterPath = imageFixture("chapter", Color.rgb(40, 160, 90))
        setNotesContent(
            item(
                document = document,
                candidates = listOf(
                    NotesThumbnailCandidate(coverPath, NotesThumbnailKind.COVER),
                    NotesThumbnailCandidate(chapterPath, NotesThumbnailKind.CHAPTER_SCREENSHOT),
                ),
            ),
        )

        openNotes()

        composeRule.onNodeWithContentDescription("${document.title} 的视频封面").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("${document.title} 的章节截图")
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("重试封面").assertDoesNotExist()
    }

    @Test
    fun brokenCoverFallsBackToChapterAndEmitsRetryEvent() {
        val events = mutableListOf<NotesEvent>()
        val document = document("FALLBACK")
        val chapterPath = imageFixture("fallback-chapter", Color.rgb(40, 160, 90))
        setNotesContent(
            item(
                document = document,
                candidates = listOf(
                    NotesThumbnailCandidate(missingFixturePath("missing-cover"), NotesThumbnailKind.COVER),
                    NotesThumbnailCandidate(chapterPath, NotesThumbnailKind.CHAPTER_SCREENSHOT),
                ),
                coverStatusLabel = "封面文件缺失",
                canRetryCover = true,
            ),
            events::add,
        )

        openNotes()

        composeRule.onNodeWithContentDescription("${document.title} 的章节截图").assertIsDisplayed()
        composeRule.onNodeWithText("封面文件缺失").assertIsDisplayed()
        composeRule.onNodeWithTag("notes-cover-retry-${stableKey(document)}").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(NotesEvent.RetryCover("bilibili", document.cardId.videoId, CARD_VERSION)),
                events,
            )
        }
    }

    @Test
    fun unavailableCoverUsesPlaceholderWithoutBlockingEditing() {
        val document = document("PLACEHOLDER")
        setNotesContent(
            item(
                document = document,
                candidates = emptyList(),
                coverStatusLabel = "封面暂不可用",
                canRetryCover = true,
            ),
        )

        openNotes()

        composeRule.onNodeWithTag(
            "notes-thumbnail-placeholder-${stableKey(document)}",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            "notes-cover-status-${stableKey(document)}",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("编辑").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("note-editor-title").assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsCoverStatusRetryAndEditReachable() {
        val events = mutableListOf<NotesEvent>()
        val document = document("LARGE_FONT")
        setNotesContent(
            item(
                document = document,
                candidates = emptyList(),
                coverStatusLabel = "封面格式不支持",
                canRetryCover = true,
            ),
            events::add,
            fontScale = 2f,
        )

        openNotes()

        composeRule.onNodeWithTag(
            "notes-cover-status-${stableKey(document)}",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            "notes-cover-retry-${stableKey(document)}",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("编辑").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, events.filterIsInstance<NotesEvent.RetryCover>().size)
        }
    }

    private fun setNotesContent(
        item: NotesLibraryItem,
        onNotesEvent: (NotesEvent) -> Unit = {},
        fontScale: Float = 1f,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val noteRepository = FileNoteDocumentRepository(
            File(context.cacheDir, "note-cover-ui-repository"),
        )
        val state = UnarchiveUiState(
            create = CreateUiState("", false, "", false, 0),
            notes = NotesUiState(
                noteCount = 1,
                materialCount = 0,
                noteTitles = listOf(item.title),
                noteDocuments = listOf(requireNotNull(item.document)),
                libraryItems = listOf(item),
            ),
            graph = GraphUiState(1),
            me = MeUiState(),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                UnarchiveTheme {
                    UnarchiveNavigationHost(
                        state = state,
                        onCreateEvent = {},
                        onNotesEvent = onNotesEvent,
                        onMeEvent = {},
                        legacyTestContent = { _, _ -> Text("legacy test") },
                        legacyResultsContent = { _ -> Text("legacy results") },
                        legacyLogContent = { _ -> Text("legacy log") },
                        legacySettingsContent = { _ -> Text("legacy settings") },
                        noteDocumentRepository = noteRepository,
                    )
                }
            }
        }
    }

    private fun openNotes() {
        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
    }

    private fun item(
        document: NoteDocument,
        candidates: List<NotesThumbnailCandidate>,
        coverStatusLabel: String? = null,
        canRetryCover: Boolean = false,
    ) = NotesLibraryItem(
        kind = NotesLibraryItemKind.SAVED_NOTE,
        title = document.title,
        summary = "封面验收摘要",
        ownerName = document.source.ownerName,
        durationSeconds = document.source.durationMs / 1_000L,
        tags = emptyList(),
        relationCount = 0,
        updatedAtEpochMs = document.updatedAtEpochMs,
        localStatusLabel = "本地已保存",
        thumbnailPath = candidates.firstOrNull()?.path,
        thumbnailCandidates = candidates,
        coverStatusLabel = coverStatusLabel,
        canRetryCover = canRetryCover,
        document = document,
    )

    private fun document(suffix: String): NoteDocument {
        val cardId = KnowledgeCardId("bilibili", "BV_$suffix")
        return NoteDocument(
            cardId = cardId,
            title = "封面笔记 $suffix",
            source = NoteSource(
                "https://www.bilibili.com/video/${cardId.videoId}",
                "封面测试作者",
                120_000L,
                TranscriptTimingAccuracy.EXACT,
            ),
            sourceTranscript = listOf(TranscriptSegment(0, 1_000, "测试转写")),
            blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "封面验收摘要")),
            generation = NoteGeneration(
                CARD_VERSION,
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

    private fun imageFixture(name: String, color: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "note-cover-ui-$name.png")
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        fixtureFiles += file
        return file.absolutePath
    }

    private fun missingFixturePath(name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "note-cover-ui-$name-does-not-exist.png").absolutePath
    }

    private fun stableKey(document: NoteDocument): String =
        "note:${document.cardId.value}:${document.generation.cardVersion}"

    private companion object {
        const val CARD_VERSION = "cover-ui-version"
    }
}
