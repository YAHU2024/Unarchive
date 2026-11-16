package com.unarchive.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
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
import com.unarchive.android.ui.components.CompactNoteItem
import com.unarchive.android.ui.state.CompactNoteStatus
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesThumbnailCandidate
import com.unarchive.android.ui.state.NotesThumbnailKind
import com.unarchive.android.ui.state.compactNotePreview
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Bounded synthetic fixtures; never writes production notes or invokes a network request. */
@RunWith(Parameterized::class)
class CompactNoteItemUiTest(
    private val screenWidth: Int,
    private val fontScale: Float,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()
    private val fixtureFiles = mutableListOf<File>()

    @After fun cleanup() { fixtureFiles.forEach(File::delete) }

    @Test fun threeSingleLinesShareTheCoverCenterAndFooterKeepsStatus() {
        val item = item().copy(compactStatus = CompactNoteStatus.SAVE_FAILED)
        mount(item)
        val title = layout(tag("note-title", item))
        val summary = layout(tag("note-summary", item))
        val footer = layout(tag("note-footer", item))
        assertEquals(1, title.lineCount)
        assertEquals(1, summary.lineCount)
        assertEquals(1, footer.lineCount)
        assertTrue(title.isLineEllipsized(0))
        assertTrue(summary.isLineEllipsized(0))
        val coverBounds = rule.onNodeWithTag(tag("note-cover-bounds", item), true).fetchSemanticsNode().boundsInRoot
        val textBounds = rule.onNodeWithTag(tag("note-text-group", item), true).fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        assertEquals(72f, coverBounds.width, 1f)
        assertEquals(72f, coverBounds.height, 1f)
        assertTrue(abs(coverBounds.center.y - textBounds.center.y) <= 1f)
        assertEquals(16f, textBounds.top - rowBounds.top, 1f)
        assertEquals(16f, rowBounds.bottom - textBounds.bottom, 1f)
        val actualFooter = footer.layoutInput.text.text
        assertFalse(footer.isLineEllipsized(0))
        // Verify the selected string is the first fitting representation, rather than
        // merely accepting any short alternative that happens to remain one line.
        val input = footer.layoutInput
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        val expected = listOf("B 站 · 05:44 · 保存失败", "B 站 · 保存失败", "保存失败")
            .firstOrNull { measurer.measure(it, input.style, softWrap = false, maxLines = 1).size.width <= textBounds.width }
            ?: "保存失败"
        assertEquals(expected, actualFooter)
        capture("geometry")
    }

    @Test fun refreshIsIndependentAndBusyRegionDoesNotOpenTheNote() {
        val item = item()
        val busy = mutableStateOf(false)
        var opens = 0
        var retries = 0
        mount(item, busy, onOpen = { opens++ }, onRetry = { retries++ })
        rule.onNodeWithTag(tag("cover-retry", item)).assertIsDisplayed()
            .performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, opens)
            busy.value = true
        }
        rule.onNodeWithTag(tag("cover-retry", item)).assertIsNotEnabled()
            .performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, opens)
            busy.value = false
        }
        rule.onNodeWithTag(tag("cover-status", item), true).assertIsDisplayed()
        rule.onNodeWithTag(tag("cover-retry", item)).performTouchInput { click(center) }
        rule.onNodeWithTag(ROOT).performClick()
        rule.runOnIdle {
            assertEquals(2, retries)
            assertEquals(1, opens)
        }
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test fun samePathRepairReloadsTheCoverAndRemovesFailureControls() {
        val file = fixtureFile("repaired.png").apply { writeText("not an image") }
        val item = item().copy(thumbnailCandidates = listOf(
            NotesThumbnailCandidate(file.absolutePath, NotesThumbnailKind.COVER),
        ))
        val busy = mutableStateOf(false)
        mount(item, busy, onRetry = { busy.value = true })
        waitForTag(tag("cover-status", item))
        rule.onNodeWithTag(tag("cover-retry", item)).performClick()
        rule.onNodeWithTag(tag("cover-retry", item)).assertIsNotEnabled()
        writeImage(file, Color.rgb(20, 100, 180))
        rule.runOnIdle { busy.value = false }
        waitForTag("notes-thumbnail-cover-${item.stableKey}")
        rule.onNodeWithTag(tag("cover-status", item), true).assertDoesNotExist()
        rule.onNodeWithTag(tag("cover-retry", item), true).assertDoesNotExist()
        rule.onNodeWithTag("notes-thumbnail-cover-${item.stableKey}", true).assertIsDisplayed()
    }

    @Test fun chapterFallbackKeepsFailureOverlayAndSummarySpeechIsBounded() {
        val file = fixtureFile("chapter.png").also { writeImage(it, Color.rgb(30, 140, 80)) }
        val longSummary = "长转写与摘要内容 ".repeat(1000)
        val item = item().copy(
            summary = longSummary,
            thumbnailCandidates = listOf(NotesThumbnailCandidate(file.absolutePath, NotesThumbnailKind.CHAPTER_SCREENSHOT)),
        )
        mount(item)
        waitForTag("notes-thumbnail-chapter_screenshot-${item.stableKey}")
        rule.onNodeWithTag(tag("cover-status", item), true).assertIsDisplayed()
        rule.onNodeWithTag(tag("cover-retry", item)).assertIsDisplayed()
        val description = rule.onNodeWithTag(ROOT).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString()
        assertTrue(description.contains(item.title))
        assertTrue(description.contains(longSummary.compactNotePreview()))
        assertFalse(description.contains(longSummary))
        assertTrue(longSummary.compactNotePreview().codePointCount(0, longSummary.compactNotePreview().length) <= 160)
        assertEquals(2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        capture("fallback")
    }

    @Test fun completionRevisionReloadsARepairWhenBusyEdgesShareOneFrame() {
        val file = fixtureFile("coalesced.png").apply { writeText("broken image") }
        val item = item().copy(thumbnailCandidates = listOf(
            NotesThumbnailCandidate(file.absolutePath, NotesThumbnailKind.COVER),
        ))
        val shownItem = mutableStateOf(item)
        val busy = mutableStateOf(false)
        mount(item, busy, updatedItem = shownItem)
        waitForTag(tag("cover-status", item))
        writeImage(file, Color.rgb(20, 100, 180))
        rule.runOnIdle {
            busy.value = true
            shownItem.value = item.copy(coverReloadRevision = 1L)
            busy.value = false
        }
        waitForTag("notes-thumbnail-cover-${item.stableKey}")
        rule.onNodeWithTag(tag("cover-status", item), true).assertDoesNotExist()
    }

    @Test fun documentWithoutBackingCardKeepsOpenActionWithoutANoopRetry() {
        val item = item().copy(canRetryCover = false, coverRetrySupported = false, coverStatusLabel = null)
        var opens = 0
        mount(item, onOpen = { opens++ })
        rule.onNodeWithTag(tag("cover-retry", item), true).assertDoesNotExist()
        rule.onNodeWithTag(ROOT).performClick()
        rule.runOnIdle { assertEquals(1, opens) }
    }

    private fun mount(
        item: NotesLibraryItem,
        busy: MutableState<Boolean> = mutableStateOf(false),
        onOpen: () -> Unit = {},
        onRetry: () -> Unit = {},
        updatedItem: MutableState<NotesLibraryItem> = mutableStateOf(item),
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                UnarchiveTheme(darkTheme = dark, motionPolicy = MotionPolicy.Reduced) {
                    Box(Modifier.width(screenWidth.dp).padding(horizontal = 20.dp)) {
                        Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium) {
                            CompactNoteItem(updatedItem.value, busy.value, onOpen, onRetry, Modifier.testTag(ROOT))
                        }
                    }
                }
            }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(5_000L) {
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tag(kind: String, item: NotesLibraryItem) = "notes-$kind-${item.stableKey}"

    private fun fixtureFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "compact-note-$screenWidth-$fontScale-$dark-$name").also(fixtureFiles::add)
    }

    private fun capture(state: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "compact-note-item").apply { mkdirs() }
        val file = File(directory, "$state-$screenWidth-$fontScale-${if (dark) "dark" else "light"}.png")
        file.outputStream().use { output ->
            check(rule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun writeImage(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun item(): NotesLibraryItem {
        val doc = NoteDocument(
            cardId = KnowledgeCardId("bilibili", "BV_COMPACT"),
            title = "如何帮 DeepSeek Harness 做出有设计感、没 AI 味的内容？",
            source = NoteSource("https://www.bilibili.com/video/BV_COMPACT", "测试作者", 344_000L, TranscriptTimingAccuracy.EXACT),
            sourceTranscript = listOf(TranscriptSegment(0, 1000, "测试转写")),
            blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "测试摘要")),
            generation = NoteGeneration("compact-ui", state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED, analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SUCCEEDED),
            editing = NoteEditingState(lastSavedAtEpochMs = 2_000L),
            publishing = NotePublishingState(updatedAtEpochMs = 2_000L),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
        )
        return NotesLibraryItem(
            kind = NotesLibraryItemKind.SAVED_NOTE,
            title = doc.title,
            summary = "作者介绍了自己为 DeepSeek Harness 开发的设计插件，希望减少重复的模板感。",
            ownerName = "测试作者",
            durationSeconds = 344L,
            tags = emptyList(),
            relationCount = 0,
            updatedAtEpochMs = 2_000L,
            localStatusLabel = "本地已保存",
            coverStatusLabel = "封面暂不可用",
            canRetryCover = true,
            document = doc,
        )
    }

    companion object {
        private const val ROOT = "compact-note-test-item"
        @JvmStatic @Parameterized.Parameters(name = "width={0},font={1},dark={2}")
        fun configurations(): List<Array<Any>> = listOf(320, 360).flatMap { width ->
            listOf(1f, 2f).flatMap { scale -> listOf(false, true).map { dark -> arrayOf<Any>(width, scale, dark) } }
        }
    }
}
