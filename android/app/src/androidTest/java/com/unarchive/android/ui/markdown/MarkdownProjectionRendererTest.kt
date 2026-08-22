package com.unarchive.android.ui.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Rule
import org.junit.Test

class MarkdownProjectionRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersChineseLongFormAndMissingImageFallback() {
        val markdown = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("markdown_spike.md")
            .use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }

        composeRule.setContent {
            UnarchiveTheme {
                MarkdownProjectionRenderer(markdown)
            }
        }

        composeRule.onNodeWithTag("markdown-projection").assertIsDisplayed()
        composeRule.onNodeWithText("中文视频笔记").assertIsDisplayed()
        // The fallback is after the long-form content and may be below the
        // initial device viewport; existence verifies the accessible text
        // without coupling the test to a particular scroll position.
        composeRule.onNodeWithText("图片不可用：本地截图").fetchSemanticsNode()
    }
}
