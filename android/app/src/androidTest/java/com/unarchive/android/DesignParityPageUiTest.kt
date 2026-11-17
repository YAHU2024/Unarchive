package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.ui.state.*
import com.unarchive.android.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertTrue

/** Public synthetic titles matching the approved prototype; never opens a user repository. */
@RunWith(Parameterized::class)
class DesignParityPageUiTest(private val dark: Boolean, private val scale: Float) {
    @get:Rule val rule = createComposeRule()

    @Test fun filledPagesMatchPrototypeAndSettingsAreIndependentDestinations() {
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val titles = listOf("如何建立自己的知识体系", "注意力，是一种可以练习的能力", "读书笔记：从记录到表达")
        val documents = titles.mapIndexed { index, title ->
            NoteDocument(cardId = KnowledgeCardId("bilibili", "BV_DESIGN_$index"), title = title,
                source = NoteSource("https://www.bilibili.com/video/BV_DESIGN_$index", "合成示例", 125000L, TranscriptTimingAccuracy.EXACT),
                sourceTranscript = emptyList(), blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = listOf("把零散的信息，变成能够持续使用的知识。", "用更少的切换，留出连续思考的时间。", "用自己的语言重述观点，再连接已有经验。")[index])),
                generation = NoteGeneration("fixture", state = NoteGenerationState.COMPLETE, baseState = CardStageState.SUCCEEDED, analysisState = CardStageState.SUCCEEDED, screenshotsState = CardStageState.SUCCEEDED),
                editing = NoteEditingState(lastSavedAtEpochMs = today), publishing = NotePublishingState(updatedAtEpochMs = today),
                createdAtEpochMs = today - if (index == 2) 86400000L else 0L, updatedAtEpochMs = today - if (index == 2) 86400000L else 0L)
        }
        val items = buildNotesLibraryItems(documents, emptyList(), emptyList()).mapIndexed { index, item -> item.copy(destinationSummary = if (index == 1) "ima · 同步失败" else "ima · 未同步") }
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                UnarchiveTheme(darkTheme = dark, motionPolicy = MotionPolicy.Reduced) {
                    UnarchiveNavigationHost(state = UnarchiveUiState(create = CreateUiState("BV1Q541167Qg", false, "", false, 0, latestNoteDocument = documents.first()), notes = NotesUiState(3, 0, titles, noteDocuments = documents, libraryItems = items), graph = GraphUiState(3), me = MeUiState()), onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {}, legacyTestContent = { _, _ -> }, legacyLogContent = {}, settingsDetailContent = { section, back ->
                        com.unarchive.android.ui.components.SecondaryPage(section.label, back) { androidx.compose.material3.Text(section.name, androidx.compose.ui.Modifier.testTag("fixture-settings-detail")) }
                    }, securityOverviewContent = { back, open ->
                        com.unarchive.android.ui.components.SecondaryPage("安全设置", back) { com.unarchive.android.ui.components.UnarchiveTextButton({ open(com.unarchive.android.security.SecurityProfile.IMA) }, androidx.compose.ui.Modifier.testTag("fixture-open-ima")) { androidx.compose.material3.Text("ima") } }
                    }, securityDetailContent = { profile, back ->
                        com.unarchive.android.ui.components.SecondaryPage(profile.name, back) { androidx.compose.material3.Text(profile.name, androidx.compose.ui.Modifier.testTag("fixture-security-detail")) }
                    }, appearanceContent = { back -> com.unarchive.android.ui.settings.AppearanceScreen(back) })
                }
            }
        }
        capture("filled-create")
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithText("今天").assertExists()
        capture("filled-notes")
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-workspace").assertExists()
        capture("filled-me")
        rule.onNodeWithTag("me-appearance").performScrollTo().performClick()
        rule.onNodeWithTag("appearance-screen").assertExists()
        capture("filled-appearance")
        rule.onNodeWithTag("appearance-back").performClick()
        rule.onNodeWithTag("me-settings").performScrollTo().performClick()
        rule.onNodeWithTag("settings-overview").assertExists()
        val accountTitle = rule.onNodeWithText("平台账号").fetchSemanticsNode().boundsInRoot
        val accountValue = rule.onNodeWithText("Bilibili").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "platform account value should remain on the same row",
            abs(accountTitle.center.y - accountValue.center.y) < 32f * scale,
        )
        capture("filled-settings")
        rule.onNodeWithTag("settings-open-Engine").performScrollTo().performClick()
        rule.onNodeWithTag("fixture-settings-detail").assertTextEquals("Engine")
        rule.onNodeWithTag("page-back").performClick()
        rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("me-security").performScrollTo().performClick()
        rule.onNodeWithTag("fixture-open-ima").performClick()
        rule.onNodeWithTag("fixture-security-detail").assertTextEquals("IMA")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "cupertino-fixtures").apply { mkdirs() }
        rule.onNode(isRoot()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name-${if (dark) "dark" else "light"}-font-$scale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, font={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f), arrayOf(true, 2f))
    }
}
