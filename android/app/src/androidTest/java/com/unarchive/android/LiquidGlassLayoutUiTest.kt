package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteContentMigrationDecision
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.me.MeScreen
import com.unarchive.android.ui.notes.NotesScreen
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateProcessingUiState
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.RecoveryUiItem
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Fixed local fixtures only. These captures do not read user notes or call services. */
@RunWith(Parameterized::class)
class LiquidGlassLayoutUiTest(private val dark: Boolean, private val glass: Boolean, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()

    @Composable private fun Theme(enabled: Boolean = glass, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            UnarchiveTheme(darkTheme = dark, liquidGlassEnabled = enabled, motionPolicy = MotionPolicy.Reduced) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Test fun materialSwitchPreservesAllThreePageStructuresWithoutEmittingEvents() {
        var page by mutableStateOf(0)
        var enabled by mutableStateOf(glass)
        val events = mutableListOf<Any>()
        val card = fixtureCard()
        val document = when (val migration = NoteContentMigration.fromKnowledgeCard(card)) {
            is NoteContentMigrationDecision.Ready -> migration.content.structuredMetadata
            is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(migration, migration.generatedMarkdown).content.structuredMetadata
        }
        rule.setContent {
            Theme(enabled) {
                when (page) {
                    0 -> CreateScreen(CreateUiState("BV_lgb_fixture", false, "", false, 0), events::add, {}, {}, onOpenDeveloperTest = {})
                    1 -> NotesScreen(NotesUiState(1, 0, listOf(card.title), noteCards = listOf(card), noteDocuments = listOf(document)), events::add, {}, {}, {})
                    else -> MeScreen(MeUiState(), events::add, {}, {}, {})
                }
            }
        }
        listOf("创作", "笔记", "我的").forEachIndexed { index, title ->
            rule.runOnIdle { page = index; enabled = glass }
            val titleNode = rule.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            titleNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)).assertIsDisplayed()
            val before = titleNode.fetchSemanticsNode().boundsInRoot
            screenshot("page-$index", enabled)
            rule.runOnIdle { enabled = !glass }
            titleNode.assertIsDisplayed()
            assertEquals(before, titleNode.fetchSemanticsNode().boundsInRoot)
            screenshot("page-$index", enabled)
        }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @Test fun processingAndFailureKeepTaskActionsAndRetainedMaterialsReachable() {
        var state by mutableStateOf(CreateUiState(
            "BV_lgb_fixture", true, "正在识别", false, 1,
            processing = CreateProcessingUiState(CreateProcessingStage.TRANSCRIBING, 0.4f, 3, true),
        ))
        val events = mutableListOf<CreateEvent>()
        var opened = 0
        rule.setContent { Theme { CreateScreen(state, events::add, { opened++ }, {}, onOpenDeveloperTest = {}) } }
        rule.onNodeWithTag("create-processing-stage").assertIsDisplayed()
        rule.onNodeWithTag("create-generate-note").assertDoesNotExist()
        rule.onNodeWithTag("create-cancel").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("create-cancel-confirm").assertIsDisplayed()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("create-cancel-confirm").performClick()
        screenshot("create-processing")
        rule.runOnIdle {
            assertEquals(listOf(CreateEvent.CancelProcessing), events)
            state = state.copy(isProcessing = false, statusMessage = "处理失败，转写已保留", processing = state.processing.copy(stage = CreateProcessingStage.FAILED))
        }
        rule.onNodeWithTag("create-cancel").assertDoesNotExist()
        rule.onNodeWithTag("create-screen").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 2000f) }
        rule.onNodeWithTag("create-review-results").performClick()
        screenshot("create-failed")
        rule.runOnIdle { assertEquals(1, opened); assertEquals(1, events.size) }
    }

    @Test fun recoveryStillRequiresConfirmationAndNeverResumesOnEntry() {
        val events = mutableListOf<CreateEvent>()
        val recovery = RecoveryUiItem("lgb-recovery", "fixture", "v1", "已中断的测试笔记", "AI 整理", SingleCardRecoveryState.RECOVERABLE, true)
        rule.setContent {
            Theme { CreateScreen(CreateUiState("", false, "", false, 0, recoveries = listOf(recovery)), events::add, {}, {}, onOpenDeveloperTest = {}) }
        }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("create-recovery-resume-lgb-recovery").performScrollTo().performClick()
        screenshot("create-recovery-sheet")
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("create-recovery-dismiss").performScrollTo().performClick()
        rule.onNodeWithTag("create-recovery-resume-lgb-recovery").performScrollTo().performClick()
        rule.onNodeWithTag("create-recovery-confirm").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(CreateEvent.ResumeSingleCard("lgb-recovery")), events) }
    }

    @Test fun opaqueEmptyStateOffersExplicitNextStepAndRetainsFilterSemantics() {
        val events = mutableListOf<NotesEvent>()
        var opened = 0
        rule.setContent { Theme { NotesScreen(NotesUiState(0, 0, emptyList()), events::add, {}, {}, {}, onOpenCreate = { opened++ }) } }
        rule.onNodeWithTag("notes-filter-saved").performScrollTo().performClick().assertIsSelected()
        screenshot("notes-empty-saved")
        rule.onNodeWithTag("notes-empty-action").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("notes-filter-recent").assertIsSelected()
        rule.runOnIdle { assertTrue(events.isEmpty()); assertEquals(0, opened) }
        rule.onNodeWithTag("notes-empty-action").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(events.isEmpty()); assertEquals(1, opened) }
    }

    @Test fun meAppearanceUsesExplicitExistingRouteWithoutDeveloperSideEffects() {
        val events = mutableListOf<MeEvent>()
        val opened = mutableListOf<String>()
        rule.setContent {
            Theme { MeScreen(MeUiState(developerToolsAvailable = false), events::add, { opened += "security" }, { opened += "tools" }, { opened += "appearance" }) }
        }
        rule.onNodeWithTag("me-more-tools").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("me-appearance").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("安全设置").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("appearance", "security"), opened); assertTrue(events.isEmpty()) }
    }

    private fun fixtureCard() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_lgb_fixture"), cardVersion = "lgb-v1",
        canonicalUrl = "https://www.bilibili.com/video/BV_lgb_fixture", title = "把阅读变成自己的知识",
        ownerName = "测试作者", videoDurationSeconds = 120, timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L, markdown = "# 把阅读变成自己的知识\n\n固定样板正文。",
    )

    private fun screenshot(page: String, enabled: Boolean = glass) {
        rule.waitForIdle()
        val roots = rule.onAllNodes(isRoot())
        val bitmap = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "lg-b-fixtures").apply { mkdirs() }
        val name = "$page-${if (dark) "dark" else "light"}-glass-$enabled-font-$fontScale.png"
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
