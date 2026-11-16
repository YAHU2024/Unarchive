package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.ui.state.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.abs

/** Synthetic production-page fixtures; no real repositories, credentials or network calls. */
@RunWith(Parameterized::class)
class RootHeaderAlignmentUiTest(private val dark: Boolean, private val scale: Float) {
    @get:Rule val rule = createComposeRule()
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseline = arguments.getString("headerBaseline") == "true"
    private val phase = if (baseline) "before" else "after"
    private val configuration = "${if (dark) "dark" else "light"}-font-$scale"
    private val metrics = JSONArray()
    private val events = mutableListOf<NotesEvent>()

    @Test
    fun filledPagesAlignAndActionsSurviveCollapseAndTabRestoration() {
        mount(filled = true)
        inspectPages("filled")
        if (baseline) return

        rule.onNodeWithTag("bottom-nav-create").performClick()
        rule.onNodeWithTag("create-batch-create").assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        rule.onNodeWithTag("batch-create-screen").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()

        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("notes-refresh").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf(NotesEvent.Refresh), events) }
        rule.onNodeWithTag("notes-more").performTouchInput { click(center) }
        rule.onNodeWithTag("notes-more-open-library").assertIsDisplayed().performClick()
        rule.onNodeWithTag("notes-library-screen").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()

        rule.onNodeWithTag("notes-scroll").performScrollToIndex(10)
        rule.onNodeWithTag("root-compact-title").assertIsDisplayed()
        rule.onAllNodesWithTag("notes-refresh").assertCountEquals(1)
        rule.onAllNodesWithTag("notes-more").assertCountEquals(1)
        capture("filled-notes-collapsed")
        val pinned = bounds("notes-more")
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("root-compact-title").assertIsDisplayed()
        assertEquals(pinned, bounds("notes-more"))
        rule.onNodeWithTag("notes-refresh").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf(NotesEvent.Refresh, NotesEvent.Refresh), events) }
        rule.onNodeWithTag("notes-more").performTouchInput { click(center) }
        rule.onNodeWithTag("notes-more-open-library").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("notes-scroll").performScrollToIndex(0)
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
        assertCentered("notes-refresh", bounds("root-large-title"))
        capture("filled-notes-restored")
    }

    @Test
    fun emptyPagesAndNotesFiltersFollowTheSameHeader() {
        mount(filled = false)
        inspectPages("empty")
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("notes-filter-materials").performClick().assertIsSelected()
        rule.onNodeWithText("没有待整理素材").assertIsDisplayed()
        capture("empty-notes-materials")
        rule.onNodeWithTag("notes-filter-saved").performClick().assertIsSelected()
        rule.onNodeWithText("还没有已保存笔记").assertIsDisplayed()
        rule.onNodeWithTag("bottom-nav-graph").performClick()
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("notes-filter-saved").assertIsSelected()
        if (!baseline) assertCentered("notes-more", bounds("root-large-title"))
    }

    private fun mount(filled: Boolean) {
        val documents = if (filled) (0 until 12).map(::document) else emptyList()
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                UnarchiveTheme(darkTheme = dark, motionPolicy = MotionPolicy.Reduced) {
                    UnarchiveNavigationHost(
                        state = UnarchiveUiState(
                            create = CreateUiState("", false, "", false, documents.size,
                                latestNoteDocument = documents.firstOrNull()),
                            notes = NotesUiState(documents.size, 0, documents.map { it.title }, noteDocuments = documents),
                            graph = GraphUiState(documents.size, noteDocuments = documents,
                                selectedCardId = documents.firstOrNull()?.cardId?.value,
                                selectedTitle = documents.firstOrNull()?.title),
                            me = MeUiState(),
                        ),
                        onCreateEvent = {}, onNotesEvent = events::add, onMeEvent = {},
                        legacyTestContent = { _, _ -> }, legacyLogContent = {},
                    )
                }
            }
        }
    }

    private fun inspectPages(state: String) {
        var titleTop: Float? = null
        var dock: Rect? = null
        for (page in listOf("create", "notes", "graph", "me")) {
            rule.onNodeWithTag("bottom-nav-$page").performClick()
            rule.waitForIdle()
            val title = bounds("root-large-title")
            val currentDock = bounds("app-dock")
            val firstContent = when (page) {
                "create" -> "create-input-surface"
                "notes" -> "notes-filter-row"
                "me" -> "me-workspace"
                else -> if (state == "empty") "graph-empty-state" else "graph-note-bilibili:BV_HEADER_0"
            }
            val entry = JSONObject().put("page", page).put("state", state)
                .put("density", rule.density.density).put("title", rectangle(title))
                .put("dock", rectangle(currentDock))
            val contentNodes = rule.onAllNodesWithTag(firstContent).fetchSemanticsNodes()
            if (contentNodes.isNotEmpty()) entry.put("content", rectangle(contentNodes.first().boundsInRoot))
            for (action in when (page) {
                "create" -> listOf("create-batch-create")
                "notes" -> listOf("notes-refresh", "notes-more")
                else -> emptyList()
            }) {
                entry.put(action, rectangle(bounds(action)))
                if (!baseline) {
                    rule.onNodeWithTag(action).assertHeightIsAtLeast(48.dp)
                    assertCentered(action, title)
                    assertTrue("Title and $action must not overlap", title.right <= bounds(action).left)
                }
            }
            if (!baseline) {
                titleTop?.let { assertTrue("Four titles must share one height", abs(title.top - it) <= px(1f)) }
                dock?.let { assertEquals("Dock must not move between root pages", it, currentDock) }
            }
            titleTop = title.top
            dock = currentDock
            metrics.put(entry)
            capture("$state-$page")
        }
        File(directory(), "$state-$configuration.json").writeText(metrics.toString(2))
    }

    private fun assertCentered(action: String, title: Rect) {
        assertTrue("$action must align with the large title", abs(bounds(action).center.y - title.center.y) <= px(1f))
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }
    private fun rectangle(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun directory(): File = File(ApplicationProvider.getApplicationContext<Context>()
        .getExternalFilesDir(null), "root-header-$phase").apply { mkdirs() }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory(), "$name-$configuration.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun document(index: Int) = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BV_HEADER_$index"), title = "知识整理示例 ${index + 1}",
        source = NoteSource("https://www.bilibili.com/video/BV_HEADER_$index", "合成示例", 125000L, TranscriptTimingAccuracy.EXACT),
        sourceTranscript = emptyList(),
        blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "把零散的信息整理成可以持续使用的知识。")),
        generation = NoteGeneration("fixture", state = NoteGenerationState.COMPLETE,
            baseState = CardStageState.SUCCEEDED, analysisState = CardStageState.SUCCEEDED, screenshotsState = CardStageState.SKIPPED),
        editing = NoteEditingState(lastSavedAtEpochMs = 1794758400000L), publishing = NotePublishingState(updatedAtEpochMs = 1794758400000L),
        createdAtEpochMs = 1794758400000L, updatedAtEpochMs = 1794758400000L,
    )

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, font={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f), arrayOf(true, 2f))
    }
}
