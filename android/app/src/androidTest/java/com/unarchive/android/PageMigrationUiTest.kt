package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteContentMigrationDecision
import com.unarchive.android.editor.MarkdownAiProposalBuilder
import com.unarchive.android.editor.MarkdownEditorEvent
import com.unarchive.android.editor.MarkdownEditorScreen
import com.unarchive.android.editor.MarkdownEditorUiState
import com.unarchive.android.ui.components.AdaptiveActions
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.components.UnarchiveSecondaryButton
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.diagnostics.DiagnosticsScreen
import com.unarchive.android.ui.me.MeScreen
import com.unarchive.android.ui.me.MoreToolsScreen
import com.unarchive.android.ui.notes.NotesScreen
import com.unarchive.android.ui.settings.SettingsScreen
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.SettingsEvent
import com.unarchive.android.ui.state.SettingsUiState
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Synthetic fixtures only: native device density is retained when testing 2x text. */
@RunWith(Parameterized::class)
class PageMigrationUiTest(private val dark: Boolean, private val glass: Boolean, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()

    @Composable
    private fun Theme(content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            UnarchiveTheme(darkTheme = dark, liquidGlassEnabled = glass, motionPolicy = MotionPolicy.Reduced) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    @Test
    fun adaptiveActionsKeepLabelsAndDisabledSemanticsAtNarrowWidth() {
        var calls = 0
        rule.setContent {
            Theme {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AdaptiveActions(Modifier.width(240.dp)) {
                        UnarchiveButton({ calls++ }, actionModifier.testTag("primary")) { Text("继续编辑笔记") }
                        UnarchiveSecondaryButton({ calls++ }, actionModifier.testTag("secondary"), enabled = false) { Text("分享与去向") }
                    }
                }
            }
        }
        rule.onNodeWithTag("primary").assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        rule.onNodeWithTag("secondary").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        val primary = rule.onNodeWithTag("primary").fetchSemanticsNode().boundsInRoot
        val secondary = rule.onNodeWithTag("secondary").fetchSemanticsNode().boundsInRoot
        assertTrue("Narrow actions must stack", secondary.top >= primary.bottom)
        rule.runOnIdle { assertEquals(1, calls) }
        screenshot("actions")
    }

    @Test
    fun createGroupedNavigationEmitsOnlyExplicitCallbacks() {
        val events = mutableListOf<CreateEvent>()
        val opened = mutableListOf<String>()
        rule.setContent {
            Theme {
                CreateScreen(
                    CreateUiState("BV_fixture", false, "", false, 0), events::add,
                    { opened += "notes" }, {}, { opened += "batch" }, { opened += "developer" },
                )
            }
        }
        screenshot("create")
        rule.onNodeWithTag("create-generate-note").performScrollTo().performClick()
        rule.onNodeWithTag("create-screen").performScrollToNode(hasTestTag("create-batch-create"))
        rule.onNodeWithTag("create-batch-create").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("开发者测试").performScrollTo().performClick()
        rule.onNodeWithText("查看笔记").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(CreateEvent.GenerateNoteDraft), events)
            assertEquals(listOf("batch", "developer", "notes"), opened)
        }
        screenshot("create-navigation")
    }

    @Test
    fun notesSegmentsRestoreSelectionAndKeepEmptyStateReachable() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            Theme { NotesScreen(NotesUiState(0, 0, emptyList()), {}, {}, {}, {}) }
        }
        rule.onNodeWithTag("notes-filter-recent").assertIsSelected()
        rule.onNodeWithTag("notes-filter-saved").performScrollTo().performClick().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("notes-filter-saved").assertIsSelected()
        rule.onNodeWithText("还没有已保存笔记").performScrollTo().assertIsDisplayed()
        screenshot("notes-empty")
    }

    @Test
    fun savedNoteKeepsEditorAndDestinationActionsReachable() {
        val card = fixtureCard()
        val document = fixtureContent(card).structuredMetadata
        val opened = mutableListOf<String>()
        rule.setContent {
            Theme {
                NotesScreen(
                    NotesUiState(1, 0, listOf(card.title), noteDocuments = listOf(document), noteCards = listOf(card)),
                    {}, {}, { opened += "editor" }, { opened += "destination" },
                )
            }
        }
        rule.runOnIdle { assertTrue(opened.isEmpty()) }
        rule.onNodeWithText("编辑", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("分享与去向", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("editor", "destination"), opened) }
        screenshot("notes-saved")
    }

    @Test
    fun markdownCandidateKeepsBodyAndExplicitReviewActionsAtNativeDensity() {
        val content = fixtureContent(fixtureCard())
        val proposal = MarkdownAiProposalBuilder.create(content, "# AI 候选\n\n待确认的测试摘要。", "dc6-fixture", "fixture", 2_000L)
        val events = mutableListOf<MarkdownEditorEvent>()
        rule.setContent {
            Theme { MarkdownEditorScreen(MarkdownEditorUiState(content, aiProposal = proposal), events::add, {}) }
        }
        rule.onNodeWithTag("markdown-reading-surface").assertIsDisplayed()
        rule.onNodeWithTag("markdown-editor-save").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("markdown-ai-proposal-apply").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("markdown-ai-proposal-reject").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf(MarkdownEditorEvent.ApplyAiProposal, MarkdownEditorEvent.RejectAiProposal), events) }
        screenshot("markdown-candidate")
    }

    @Test
    fun groupedSettingsKeepWholeRowActionsAndBusyState() {
        val events = mutableListOf<SettingsEvent>()
        var state by mutableStateOf(SettingsUiState(availableEngines = listOf(AsrEngineKind.SILICONFLOW_CLOUD)))
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent {
            Theme { SettingsScreen(state, File(context.cacheDir, "d-c6-missing-model-fixture"), {}, events::add, {}) }
        }
        screenshot("settings")
        rule.onNodeWithText("启用 VAD 静音检测（实验性，可能崩溃）").performScrollTo().performClick()
        rule.onNodeWithText("思考模式（更慢但更深入）").performScrollTo().performClick()
        rule.onNodeWithTag("liquid-glass-switch").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(SettingsEvent.SetEnableVad(true), SettingsEvent.SetThinkingEnabled(true), SettingsEvent.SetLiquidGlassEnabled(false)), events)
            state = state.copy(isBusy = true)
        }
        rule.onNodeWithTag("liquid-glass-switch").assertIsNotEnabled()
        rule.onNodeWithText("思考模式（更慢但更深入）").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("刷新存储信息").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("系统存储设置").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf(SettingsEvent.RefreshStorage, SettingsEvent.OpenSystemStorageSettings), events.takeLast(2))
        }
        screenshot("settings-storage")
    }

    @Test
    fun meGroupedNavigationRespectsAvailability() {
        val events = mutableListOf<MeEvent>()
        var state by mutableStateOf(MeUiState())
        var opened = 0
        rule.setContent {
            Theme { MeScreen(state, events::add, {}, { opened++ }) }
        }
        screenshot("me")
        rule.onNodeWithTag("me-more-tools").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(MeEvent.OpenDeveloperOptions), events)
            assertEquals(1, opened)
            state = state.copy(developerToolsAvailable = false)
        }
        rule.onNodeWithTag("me-more-tools").assertIsNotEnabled()
    }

    @Test
    fun compatibilityHubKeepsEveryExistingDestinationReachable() {
        val opened = mutableListOf<String>()
        rule.setContent {
            Theme { MoreToolsScreen({}, { opened += "test" }, { opened += "results" }, { opened += "logs" }, { opened += "settings" }) }
        }
        for (tag in listOf("test", "results", "logs", "settings")) {
            rule.onNodeWithTag("more-tools-$tag").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        rule.runOnIdle { assertEquals(listOf("test", "results", "logs", "settings"), opened) }
        screenshot("more-tools")
    }

    @Test
    fun diagnosticsActionsRemainReachableWithoutMutatingOnEntry() {
        val actions = mutableListOf<String>()
        rule.setContent {
            Theme { DiagnosticsScreen(emptyList(), null, 0, { actions += "back" }, { actions += "clear" }, { actions += "copy" }, { actions += "export" }) }
        }
        rule.runOnIdle { assertTrue(actions.isEmpty()) }
        rule.onNodeWithText("复制").performScrollTo().performClick()
        rule.onNodeWithText("导出").performScrollTo().performClick()
        rule.onNodeWithText("清空").performScrollTo().performClick()
        rule.onNodeWithText("返回").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("copy", "export", "clear", "back"), actions) }
        screenshot("diagnostics")
    }

    private fun fixtureCard() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_dc6_fixture"), cardVersion = "dc6-v1",
        canonicalUrl = "https://www.bilibili.com/video/BV_dc6_fixture", title = "阅读与整理测试笔记",
        ownerName = "测试作者", videoDurationSeconds = 10, timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L, markdown = "# 阅读与整理测试笔记\n\n测试正文。",
    )

    private fun fixtureContent(card: KnowledgeCard) = when (val result = NoteContentMigration.fromKnowledgeCard(card)) {
        is NoteContentMigrationDecision.Ready -> result.content
        is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(result, result.generatedMarkdown).content
    }

    private fun screenshot(page: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "d-c6-fixtures").apply { mkdirs() }
        val name = page + "-" + (if (dark) "dark" else "light") + "-glass-" + glass + "-font-" + fontScale + ".png"
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, glass={1}, font={2}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, false, 1f), arrayOf(true, true, 1f),
            arrayOf(false, true, 2f), arrayOf(true, false, 2f),
        )
    }
}
