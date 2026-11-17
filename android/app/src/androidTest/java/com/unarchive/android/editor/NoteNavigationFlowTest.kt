package com.unarchive.android.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Every case mounts the production Route/ViewModel/Native host with its real file store. */
class NoteNavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "note-navigation-synthetic-${System.nanoTime()}")
    private var savedClipboard: ClipData? = null
    private var clipboardWasSaved = false
    private var expectedNativeTextSizePx = 0f
    private var baseNativeTextSizePx = 0f

    @After fun restoreClipboardAndRemoveOnlySyntheticFiles() {
        compose.runOnUiThread {
            if (clipboardWasSaved) {
                val manager = context.getSystemService(ClipboardManager::class.java)
                savedClipboard?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
            }
            compose.activity.viewModelStore.clear()
        }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun outlineFindCountPreviousNextAndNoResultKeepFormalSourceUntouched() {
        val source = buildString {
            append("# 总览\n\n## 第一章\n\nneedle 首处\n\n")
            repeat(35) { append("普通段落 $it 用于真实纵向滚动定位。\n\n") }
            append("### 第二章\n\nneedle 末处\n\n最后一行")
        }
        val (repository, formal) = repository(source)
        mount(repository, formal); awaitReading()
        val native = native()
        val buffer = main { native.text }
        compose.onNodeWithTag("markdown-note-outline").performClick()
        compose.onNodeWithText("第二章").performScrollTo().performClick()
        compose.waitUntil(5_000) { main { native.scrollY > 0 } }
        assertTrue(main { visible(native, native.text.indexOf("第二章")) })
        openMore("查找")
        compose.onNodeWithText("在正文中查找").performTextInput("needle")
        compose.waitUntil(8_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一个").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("上一个").performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("在正文中查找").performTextReplacement("不存在的词")
        compose.waitUntil(8_000) { compose.onAllNodesWithText("无结果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭").performClick(); awaitReading()
        assertEquals(source, main { native.bridge!!.session.source })
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertSame(buffer, main { native.text })
    }

    @Test fun verifiedTranscriptStartsFoldedAndFindingItsTextExpandsWithoutDirtying() {
        val metadata = card(withTranscript = true).toNoteDocument()
        val source = NoteMarkdownProjection.render(metadata)
        val (repository, formal) = repository(source, metadata)
        mount(repository, formal); awaitReading()
        val native = native()
        assertTrue(main { native.bridge!!.session.foldRanges.single().collapsed })
        assertFalse(main { native.text.contains("转录命中词") })
        openMore("查找")
        compose.onNodeWithText("在正文中查找").performTextInput("转录命中词")
        compose.waitUntil(8_000) { compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { main { native.text.contains("转录命中词") } }
        assertFalse(main { native.bridge!!.session.foldRanges.single().collapsed })
        assertEquals(0L, main { native.bridge!!.session.version })
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    @Test fun sameNamedUserChapterDoesNotFoldOrHideAnyText() {
        val metadata = card(withTranscript = true).toNoteDocument()
        val source = "# 用户笔记\n\n## 转录全文\n\n同名章节属于用户手写，必须完整显示。"
        val (repository, formal) = repository(source, metadata)
        mount(repository, formal); awaitReading()
        val native = native()
        assertTrue(main { native.bridge!!.session.foldRanges.isEmpty() })
        assertTrue(main { native.text.contains("同名章节属于用户手写") })
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    @Test fun wideTableHasAllCellsAndNodeSourceCancelReturnsToTheSameNativePosition() {
        val table = buildString {
            append("|A|B|C|D|E|F|G|H|\n|---|---|---|---|---|---|---|---|\n")
            repeat(14) { row -> append("|r${row}a|r${row}b|r${row}c|r${row}d|r${row}e|r${row}f|r${row}g|").append(if (row == 13) "LAST-CELL" else "r${row}h").append("|\n") }
        }.trimEnd()
        val source = "# 宽表\n\n$table\n\n末尾"
        val (repository, formal) = repository(source)
        mount(repository, formal); awaitReading()
        val native = native()
        val tableSourceStart = source.indexOf(table)
        main {
            val bridge = native.bridge!!
            val node = bridge.session.projection.nodes.single { it.node.atomic?.kind == "table" }
            assertEquals(tableSourceStart, node.node.start)
            val projected = bridge.session.projection.projectedOffset(tableSourceStart)
            assertTrue(projected in node.start until node.end)
            native.setSelection(projected)
            assertEquals("table", bridge.nodeAtSelection()?.atomic?.kind)
        }
        // Let TextView finish its own selection bring-into-view before recording
        // the viewport which the user actually has when opening a panel.
        compose.waitForIdle()
        android.os.SystemClock.sleep(150)
        val scrollBefore = main { native.scrollY }
        openMore("查看当前内容")
        compose.waitUntil(8_000) { compose.onAllNodesWithText("LAST-CELL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("LAST-CELL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看源码").performClick()
        compose.onNodeWithTag("markdown-note-source-panel").assertIsDisplayed()
        androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(
            androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java),
            org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(NativeDocumentEditText::class.java)),
        )).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.withText(table)))
        compose.onNodeWithText("取消").performClick(); awaitReading()
        assertEquals(source, main { native.bridge!!.session.source })
        try {
            compose.waitUntil(8_000) { main { kotlin.math.abs(native.scrollY - scrollBefore) <= native.lineHeight } }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Viewport expected=$scrollBefore actual=${main { native.scrollY }} lineHeight=${main { native.lineHeight }}", error)
        }
        assertTrue("Panel return viewport expected=$scrollBefore actual=${main { native.scrollY }}",
            main { kotlin.math.abs(native.scrollY - scrollBefore) <= native.lineHeight })
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
    }

    @Test fun nativeHtmlPasteAndPlainPasteEachUndoOnceAndRestoreSystemClipboard() {
        val source = "# 粘贴\n\n起点\n\n"
        val (repository, formal) = repository(source)
        mount(repository, formal); awaitReading()
        compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        val buffer = main { native.text }
        awaitNativeEditing(native)
        main { native.requestFocus(); native.bridge!!.hideIme() }
        compose.waitUntil(8_000) { main { native.hasWindowFocus() && native.isFocused } }
        main {
            val manager = context.getSystemService(ClipboardManager::class.java)
            savedClipboard = manager.primaryClip; clipboardWasSaved = true
            val html = "<h2>粘贴标题</h2><p><strong>粗体</strong> <a href='https://example.com'>链接</a></p><script>不能执行</script>"
            manager.setPrimaryClip(ClipData.newHtmlText("合成HTML", "粘贴标题\n粗体 链接", html))
            assertEquals(html, manager.primaryClip?.getItemAt(0)?.htmlText)
            native.setSelection(native.bridge!!.session.projection.projectedOffset(source.length))
            assertTrue(native.onTextContextMenuItem(android.R.id.paste))
        }
        compose.waitUntil(10_000) { main { native.bridge!!.session.source.contains("**粗体**") } }
        assertTrue(main { native.bridge!!.session.source.contains("[链接](https://example.com)") })
        assertFalse(main { native.bridge!!.session.source.contains("不能执行") })
        compose.onNodeWithTag("markdown-note-undo").performClick()
        compose.waitUntil(8_000) { main { native.bridge!!.session.source == source } }
        main {
            val manager = context.getSystemService(ClipboardManager::class.java)
            manager.setPrimaryClip(ClipData.newPlainText("合成纯文本", "# **literal** 😀"))
            assertEquals("# **literal** 😀", manager.primaryClip?.getItemAt(0)?.text?.toString())
            native.setSelection(native.bridge!!.session.projection.projectedOffset(source.length))
        }
        compose.onNodeWithTag("markdown-note-edit-more").performClick()
        compose.onNodeWithText("粘贴为纯文本").performScrollTo().performClick()
        compose.waitUntil(8_000) { main { native.text.endsWith("# **literal** 😀") } }
        assertEquals(source + "\\# \\*\\*literal\\*\\* 😀", main { native.bridge!!.session.source })
        compose.onNodeWithTag("markdown-note-undo").performClick()
        compose.waitUntil(8_000) { main { native.bridge!!.session.source == source } }
        assertSame(buffer, main { native.text })
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    @Test fun lightProductionRouteAtTwoTimesTextAndReducedMotionKeepsNamedEqualTargets() = verifyGeometry(dark = false)
    @Test fun darkProductionRouteAtTwoTimesTextAndReducedMotionKeepsNamedEqualTargets() = verifyGeometry(dark = true)

    private fun verifyGeometry(dark: Boolean) {
        val (repository, formal) = repository("# 这是用于整屏居中省略验证的很长标题，正文仍完整\n\n完整正文")
        mount(repository, formal, dark, fontScale = 2f, reduced = true); awaitReading()
        val screen = compose.onNodeWithTag("markdown-note-screen").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("markdown-note-title").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(screen.center.x - title.center.x) <= 1f)
        compose.onNodeWithContentDescription("更多").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        awaitNativeEditing(native)
        main { native.bridge!!.hideIme() }
        compose.onNodeWithContentDescription("完成并保存").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("撤销").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("重做").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("大纲").assertIsDisplayed().assertIsEnabled()
        val toolbar = compose.onNodeWithTag("markdown-note-toolbar").fetchSemanticsNode().boundsInRoot
        val tags = listOf("markdown-note-insert", "markdown-note-format", "markdown-note-bold", "markdown-note-list",
            "markdown-note-task", "markdown-note-edit-more", "markdown-note-keyboard")
            .filter { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        val widthDp = toolbar.width / compose.density.density
        val expectedTags = buildList {
            if (widthDp >= 192f) add("markdown-note-insert")
            add("markdown-note-format")
            if (widthDp >= 240f) add("markdown-note-bold")
            if (widthDp >= 288f) add("markdown-note-list")
            if (widthDp >= 336f) add("markdown-note-task")
            add("markdown-note-edit-more"); add("markdown-note-keyboard")
        }
        assertEquals(expectedTags, tags)
        compose.onNodeWithTag("markdown-note-italic").assertDoesNotExist()
        val targets = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val minimum = with(compose.density) { 48f * density }
        assertTrue(targets.size >= 3)
        targets.forEach { assertTrue(it.width + 1 >= minimum && it.height + 1 >= minimum); assertTrue(it.left >= toolbar.left - 1 && it.right <= toolbar.right + 1) }
        assertTrue(targets.maxOf { it.width } - targets.minOf { it.width } <= 1f)
        assertTrue(targets.zipWithNext().all { (left, right) -> left.right <= right.left + 1f })
        assertTrue(kotlin.math.abs(targets.last().right - toolbar.right) <= 1f)
        assertEquals(expectedNativeTextSizePx, main { native.textSize }, 0.5f)
        assertTrue(expectedNativeTextSizePx > baseNativeTextSizePx)
        compose.onNodeWithTag("markdown-note-edit-more").performClick()
        compose.onNodeWithText("斜体").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    private fun mount(repository: FileNoteContentRepository, formal: NoteContent, dark: Boolean = false, fontScale: Float = 1f, reduced: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val scaledSize = with(LocalDensity.current) { 17.sp.toPx() }
                val originalSize = with(Density(density.density, fontScale = 1f)) { 17.sp.toPx() }
                SideEffect { expectedNativeTextSizePx = scaledSize; baseNativeTextSizePx = originalSize }
                UnarchiveTheme(darkTheme = dark, motionPolicy = if (reduced) MotionPolicy.Reduced else MotionPolicy()) {
                    MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, onBack = {})
                }
            }
        }
    }
    private fun awaitReading() { compose.waitUntil(12_000) { compose.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitEditing() { compose.waitUntil(8_000) { compose.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitNativeEditing(native: NativeDocumentEditText) = compose.waitUntil(8_000) {
        main { native.bridge?.let { it.editing && native.text.toString() == it.session.projection.text && !it.state.value.pending && !it.state.value.dirtyBuffer } == true }
    }
    private fun openMore(item: String) { compose.onNodeWithTag("markdown-note-more").performClick(); compose.onNodeWithText(item).performScrollTo().performClick() }
    private fun native(): NativeDocumentEditText = main { descendants(compose.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single() }
    private fun descendants(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) } }
    private fun <T> main(action: () -> T): T = compose.runOnIdle(action)
    private fun visible(native: NativeDocumentEditText, offset: Int): Boolean {
        val line = native.layout.getLineForOffset(offset)
        return native.layout.getLineBottom(line) > native.scrollY && native.layout.getLineTop(line) < native.scrollY + native.height
    }
    private fun repository(source: String, metadata: NoteDocument = card().toNoteDocument()): Pair<FileNoteContentRepository, NoteContent> {
        val content = NoteContent(metadata.cardId, metadata.generation.cardVersion, source, 0, metadata,
            createdAtEpochMs = 1_000, updatedAtEpochMs = 1_000)
        val repository = FileNoteContentRepository(root)
        assertTrue(repository.save(content).isComplete)
        return repository to checkNotNull(repository.find(content.cardId, content.cardVersion))
    }
    private fun card(withTranscript: Boolean = false) = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_navigation_synthetic"), cardVersion = "v1",
        canonicalUrl = "https://www.bilibili.com/video/BV_navigation_synthetic", title = "合成导航夹具",
        ownerName = "合成作者", videoDurationSeconds = 10, timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = if (withTranscript) listOf(TranscriptSegment(0, 5_000, "第一段转录"), TranscriptSegment(5_000, 10_000, "转录命中词完整保留")) else emptyList(),
        analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000, updatedAtEpochMs = 1_000, markdown = "# 合成导航夹具",
    )
}
