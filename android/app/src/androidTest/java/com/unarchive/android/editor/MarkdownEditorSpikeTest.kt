package com.unarchive.android.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Rule
import org.junit.Test

class MarkdownEditorSpikeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchesBetweenSourceAndPreviewAndKeepsImageReferenceVisible() {
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = "# 截图笔记\n\n![](assets/chapter-000.jpg)",
                    onMarkdownChange = {},
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-source").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-editor-preview-tab").performClick()
        composeRule.onNodeWithTag("markdown-editor-preview").assertIsDisplayed()
        composeRule.onNodeWithText("截图笔记").assertIsDisplayed()
        composeRule.onNodeWithText("图片不可用：图片").assertIsDisplayed()
    }

    @Test
    fun sourceChangeIsAcceptedWithoutReplacingTheCallerOwnedText() {
        var source = "# 原标题"
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorSpike(
                    markdown = source,
                    onMarkdownChange = { source = it },
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 新标题")
        assert(source == "# 新标题")
    }
}
