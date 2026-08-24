package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class D1NavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startsOnCreateAndNavigatesToGraph() {
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = sampleState(),
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

        composeRule.onNodeWithText("添加 B 站视频").assertIsDisplayed()
        composeRule.onNodeWithText("图谱").performClick()
        composeRule.onNodeWithText("局部关系").assertIsDisplayed()
    }

    @Test
    fun meKeepsDeveloperEntryVisible() {
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = sampleState(),
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

        composeRule.onNodeWithTag("bottom-nav-me").performClick()
        composeRule.onNodeWithText("开发者选项").assertIsDisplayed()
        composeRule.onNodeWithText("查看运行日志").assertIsDisplayed()
    }

    @Test
    fun createPrimaryActionRequestsNoteDraftGeneration() {
        val events = mutableListOf<CreateEvent>()
        val state = sampleState().let { current ->
            current.copy(create = current.create.copy(videoReference = "BV1PS42197aM"))
        }
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = events::add,
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithText("开始生成笔记草稿").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CreateEvent.GenerateNoteDraft), events)
        }
    }

    private fun sampleState() = UnarchiveUiState(
        create = CreateUiState(
            videoReference = "",
            isProcessing = false,
            statusMessage = "请输入 B站链接或选择本地音频。",
            hasRecovery = false,
            storedResultCount = 0,
        ),
        notes = NotesUiState(noteCount = 0, materialCount = 0, noteTitles = emptyList()),
        graph = GraphUiState(noteCount = 0),
        me = MeUiState(),
    )
}
