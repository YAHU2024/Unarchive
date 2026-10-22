package com.unarchive.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unarchive.android.ui.notes.NotesLibraryScreen
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotesLibraryScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun emptyLibraryShowsIndependentPageAndBackAction() {
        var backs = 0
        rule.setContent {
            UnarchiveTheme {
                NotesLibraryScreen(
                    state = NotesUiState(0, 0, emptyList()),
                    onBack = { backs++ },
                    onOpenEditor = {},
                    onOpenDestinations = {},
                )
            }
        }
        rule.onNodeWithTag("notes-library-screen").assertIsDisplayed()
        rule.onNodeWithText("完整笔记库").assertIsDisplayed()
        rule.onNodeWithTag("notes-library-empty").assertIsDisplayed()
        rule.onNodeWithTag("notes-library-back").performClick()
        rule.runOnIdle { assertEquals(1, backs) }
    }
}
