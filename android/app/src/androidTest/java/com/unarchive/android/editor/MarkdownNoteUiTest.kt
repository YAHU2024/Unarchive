package com.unarchive.android.editor

import android.content.Context
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentAtomicWriter
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteContentMigrationDecision
import com.unarchive.android.card.NoteDraftState
import com.unarchive.android.card.PendingNoteSourceEdit
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the new production Route, native host, session and real file repository. */
class MarkdownNoteUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testRoot = File(context.cacheDir, "note-redesign-ui-${System.nanoTime()}")

    @After fun cleanOnlyTheSyntheticRepository() {
        composeRule.runOnUiThread { composeRule.activity.viewModelStore.clear() }
        check(testRoot.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        testRoot.deleteRecursively()
    }

    @Test fun readEditBlueCheckReadCommitsExactSourceWithOneNativeBuffer() {
        val source = "# 正式标题\r\n\r\n第一段保留 **加粗**。\r\n\r\n正文末尾"
        val (repository, formal) = repository(source)
        val committed = mutableListOf<NoteContent>()
        mount(repository, formal, onFormalCommitted = committed::add)
        awaitReading()
        val native = native()
        val editable = onMain { native.text }
        onMain { assertFalse(native.isCursorVisible) }
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        commitAt(native, onMain { native.length() }, "，新增中文🙂")
        awaitSource(native, source + "，新增中文🙂")
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        val saved = checkNotNull(repository.find(formal.cardId, formal.cardVersion))
        assertEquals(source + "，新增中文🙂", saved.markdown)
        assertEquals(formal.markdownRevision + 1, saved.markdownRevision)
        assertEquals(source, repository.findRevision(formal.cardId, formal.cardVersion, formal.markdownRevision)?.markdown)
        assertSame(native, native())
        assertSame(editable, onMain { native.text })
        composeRule.runOnIdle { assertEquals(1, committed.size) }
        writeEvidence("r1-save-loop", JSONObject().put("sameNativeView", true).put("sameEditable", true)
            .put("formalRevision", saved.markdownRevision).put("exactUntouchedSource", true))
    }

    @Test fun unchangedBlueCheckReturnsToReadingWithoutCreatingARevision() {
        val (repository, formal) = repository("普通正文")
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertEquals(1, repository.listVersions(formal.cardId).size)
        assertEquals(formal.updatedAtEpochMs, repository.find(formal.cardId, formal.cardVersion)?.updatedAtEpochMs)
    }

    @Test fun firstFormalH1ControlsTheAppBarWithoutRewritingTheSource() {
        val source = "# 原文完整标题\n\n正文\n\n# 保留的另一个标题"
        val (repository, formal) = repository(source)
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-title").assertIsDisplayed()
        composeRule.onNodeWithText("原文完整标题").assertIsDisplayed()
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        val native = native()
        assertTrue(onMain { native.text.contains("保留的另一个标题") })
    }

    @Test fun clearingPlainBodyPublishesAnEmptyV3InsteadOfRestoringLegacyText() {
        val (repository, formal) = repository("可以清空的正文")
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        onMain {
            native.setSelection(0, native.length())
            val connection = checkNotNull(native.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.commitText("", 1))
        }
        awaitSource(native, "")
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        val saved = checkNotNull(repository.find(formal.cardId, formal.cardVersion))
        assertEquals("", saved.markdown)
        assertEquals(formal.markdownRevision + 1, saved.markdownRevision)
    }

    @Test fun formalReadDoesNotApplyARecoveryDraftUntilTheUserRestoresIt() {
        val (repository, formal) = repository("正式正文")
        repository.saveDraft(formal, NoteDraftState("待恢复正文", formal.markdownRevision,
            NoteContent.markdownFingerprint("待恢复正文"), formal.updatedAtEpochMs + 1))
        mount(repository, formal)
        awaitReading()
        val native = native()
        assertTrue(onMain { native.text.endsWith("正式正文") })
        assertEquals("正式正文", onMain { native.bridge!!.session.source })
        assertEquals("正式正文", repository.find(formal.cardId, formal.cardVersion)?.markdown)
        composeRule.onNodeWithTag("markdown-note-recovery").performClick()
        composeRule.onNodeWithTag("markdown-note-restore-0").performClick()
        awaitEditing()
        awaitSource(native, "待恢复正文")
        assertEquals("正式正文", repository.find(formal.cardId, formal.cardVersion)?.markdown)
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        assertEquals("待恢复正文", repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertTrue(repository.loadRecoveryState(formal.cardId, formal.cardVersion).isEmpty())
    }

    @Test fun saveFailureKeepsNativeInputAndRetryCommitsOnce() {
        val failing = AtomicBoolean(false)
        val writer = NoteContentAtomicWriter { target, bytes ->
            if (failing.get() && target.name == "note.md") throw IOException("synthetic failure")
            check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
            target.writeBytes(bytes)
        }
        val (repository, formal) = repository("保存前正文", writer)
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        val editable = onMain { native.text }
        commitAt(native, onMain { native.length() }, "，保留修改")
        awaitSource(native, "保存前正文，保留修改")
        failing.set(true)
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("markdown-note-error").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("markdown-note-done").assertIsDisplayed()
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertEquals("保存前正文，保留修改", onMain { native.text.toString() })
        assertSame(editable, onMain { native.text })
        failing.set(false)
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        assertEquals(formal.markdownRevision + 1, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
    }

    @Test fun leavingImmediatelyFlushesDraftAndContinueKeepsTheSameBuffer() {
        val (repository, formal) = repository("快速离开正文")
        var left = 0
        mount(repository, formal, onBack = { left++ })
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        commitAt(native, onMain { native.length() }, "新输入")
        // The same leave intent used by the app menu is exercised before the 500 ms debounce.
        composeRule.onNodeWithTag("markdown-note-edit-more").performClick()
        composeRule.onNodeWithText("返回笔记库").performScrollTo().performClick()
        composeRule.waitUntil(8_000) { composeRule.onAllNodesWithTag("markdown-note-save-leave").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("markdown-note-save-leave").assertIsDisplayed()
        composeRule.waitUntil(5_000) { repository.loadRecoveryState(formal.cardId, formal.cardVersion).isNotEmpty() }
        assertEquals("快速离开正文新输入", repository.loadRecoveryState(formal.cardId, formal.cardVersion).single().draft?.markdown)
        composeRule.onNodeWithText("继续编辑").performClick()
        assertSame(native, native())
        composeRule.runOnIdle { assertEquals(0, left) }
    }

    @Test fun savingThenEditingAndUndoKeepsOneBufferAndCreatesANewerFormalRevision() {
        val source = "正式原文"
        val (repository, formal) = repository(source)
        mount(repository, formal)
        awaitReading()
        val native = native()
        val editable = onMain { native.text }
        composeRule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        commitAt(native, source.length, "一次修改")
        awaitSource(native, source + "一次修改")
        composeRule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(1L, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        composeRule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        composeRule.onNodeWithTag("markdown-note-undo").performClick(); awaitSource(native, source)
        composeRule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(2L, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertSame(editable, onMain { native.text })
    }

    @Test fun sourceCancelIsIsolatedAndSourceApplyIsOneUndoableSessionTransaction() {
        val source = "原文不变"
        val (repository, formal) = repository(source)
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        openSourceFromMore()
        replaceSourcePanel("未应用 **暂存**")
        assertEquals(source, onMain { native.bridge!!.session.source })
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithText("放弃面板修改").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("markdown-note-source-panel").fetchSemanticsNodes().isEmpty() }
        assertEquals(source, onMain { native.bridge!!.session.source })
        openSourceFromMore()
        replaceSourcePanel("# 新标题\r\n\r\n完整 **正文**")
        composeRule.onNodeWithTag("markdown-note-apply-source").performClick()
        awaitSource(native, "# 新标题\r\n\r\n完整 **正文**")
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        composeRule.onNodeWithTag("markdown-note-undo").performClick(); awaitSource(native, source)
        composeRule.onNodeWithTag("markdown-note-redo").performClick()
        awaitSource(native, "# 新标题\r\n\r\n完整 **正文**")
    }

    @Test fun persistedUnappliedSourceScratchRemainsReviewableAfterFreshViewModelRestore() {
        val source = "正式正文"
        val text = "恢复的未应用源码 **尾部END"
        val (repository, formal) = repository(source)
        val scratch = PendingNoteSourceEdit(0, source.length, NoteContent.markdownFingerprint(source),
            NoteContent.markdownFingerprint(source), text)
        repository.saveDraft(formal, NoteDraftState(source, formal.markdownRevision, NoteContent.markdownFingerprint(source),
            formal.updatedAtEpochMs + 1, pendingSourceEdit = scratch))
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-recovery").performClick()
        composeRule.onNodeWithTag("markdown-note-restore-0").performClick(); awaitEditing()
        openSourceFromMore()
        sourcePanelView().check(androidx.test.espresso.assertion.ViewAssertions.matches(
            androidx.test.espresso.matcher.ViewMatchers.withText(text)))
        assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    @Test fun selectedBoldAndLinkAreRealSourceTransactionsAndUndoOneAtATime() {
        val source = "选择格式文字"
        val (repository, formal) = repository(source)
        mount(repository, formal); awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        onMain { native.setSelection(0, 2) }
        composeRule.onNodeWithTag("markdown-note-bold").performClick()
        awaitSource(native, "**选择**格式文字")
        composeRule.onNodeWithTag("markdown-note-undo").performClick(); awaitSource(native, source)
        onMain { native.setSelection(0, 2) }
        composeRule.onNodeWithTag("markdown-note-insert").performClick()
        composeRule.onNodeWithText("链接").performClick()
        composeRule.onNodeWithText("网址").performTextInput("https://example.com/notes")
        composeRule.onNodeWithText("应用").performClick()
        awaitSource(native, "[选择](https://example.com/notes)格式文字")
        composeRule.onNodeWithTag("markdown-note-undo").performClick(); awaitSource(native, source)
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
    }

    @Test fun chineseInputConnectionCompositionStaysTransientUntilCommit() {
        val (repository, formal) = repository("中文协议：")
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        val connection = onMain {
            native.setSelection(native.length())
            checkNotNull(native.onCreateInputConnection(EditorInfo()))
        }
        onMain { assertTrue(connection.setComposingText("zhong", 1)) }
        composeRule.waitForIdle()
        assertEquals(formal.markdown, onMain { native.bridge!!.session.captureSnapshot().source })
        onMain { assertTrue(connection.setComposingText("中文", 1)); assertTrue(connection.finishComposingText()) }
        awaitSource(native, "中文协议：中文")
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        assertEquals("中文协议：中文", repository.find(formal.cardId, formal.cardVersion)?.markdown)
        writeEvidence("r1-ime-protocol", JSONObject().put("compositionTransient", true)
            .put("scope", "production host InputConnection protocol; vendor candidate touches require a separate test"))
    }

    @Test fun longProductionRouteKeepsHeadMiddleTailEditableAndRecordsDebugBaseline() {
        val source = longFixture()
        assertTrue(source.codePointCount(0, source.length) >= 110_000)
        val (repository, formal) = repository(source)
        val openedAt = SystemClock.elapsedRealtime()
        mount(repository, formal)
        awaitReading(30_000)
        val readableMs = SystemClock.elapsedRealtime() - openedAt
        val native = native()
        val editable = onMain { native.text }
        assertTrue(onMain { native.text.endsWith("长文最后一行END") })
        val editStart = SystemClock.elapsedRealtime()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val switchMs = SystemClock.elapsedRealtime() - editStart
        val fullParsesBefore = onMain { native.bridge!!.session.diagnostics.fullParses }
        val samples = JSONArray()
        for (name in listOf("head", "middle", "tail")) {
            val offset = onMain { when (name) {
                "head" -> 0
                "middle" -> native.text.indexOf('\n', native.length() / 2) + 1
                else -> native.length()
            } }
            val drawnVersion = AtomicLong(-1)
            onMain { native.bridge!!.onDraw = { version, _ -> drawnVersion.set(version) } }
            val previousVersion = onMain { native.bridge!!.session.version }
            val at = SystemClock.elapsedRealtime()
            commitAt(native, offset, "中")
            composeRule.waitUntil(15_000) { onMain { native.bridge!!.session.version > previousVersion } }
            val acceptedMs = SystemClock.elapsedRealtime() - at
            onMain { native.postInvalidateOnAnimation() }
            composeRule.waitUntil(15_000) { drawnVersion.get() > previousVersion }
            samples.put(JSONObject().put("position", name).put("inputToAcceptedMs", acceptedMs)
                .put("inputToObservedDrawMs", SystemClock.elapsedRealtime() - at))
        }
        assertEquals(fullParsesBefore, onMain { native.bridge!!.session.diagnostics.fullParses })
        assertSame(editable, onMain { native.text })
        assertTrue(onMain { native.text.endsWith("长文最后一行END中") })
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading(30_000)
        val saved = checkNotNull(repository.find(formal.cardId, formal.cardVersion))
        assertTrue(saved.markdown.endsWith("长文最后一行END中"))
        assertSame(editable, onMain { native.text })
        writeEvidence("r1-long-debug-baseline", JSONObject().put("sourceCodePoints", source.codePointCount(0, source.length))
            .put("routeMountToReadableMs", readableMs).put("readingToEditingMs", switchMs).put("samples", samples)
            .put("sameEditable", true).put("normalInputFullParses", 0).put("pssKbAfterSave", Debug.getPss())
            .put("scope", "Debug production Route synthetic baseline; not Release/R8 or physical keyboard latency"))
    }

    @Test fun realLongPressAndHandleDragCrossParagraphsAndAutoScrollBeforeCutUndo() {
        val source = buildString {
            append("English selection begins here. 中文选区验证。\n\n")
            repeat(240) { append("段落 $it 连续选择不会拆开宿主，向下拖动可越过最初视口。\n\n") }
            append("真实拖柄末尾END")
        }
        val (repository, formal) = repository(source)
        mount(repository, formal)
        awaitReading()
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        val editable = onMain { native.text }
        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
        val previousClip = onMain { clipboard.primaryClip }
        try {
            showKeyboard(native, source.indexOf("English") + 2)
            val word = pointAt(native, source.indexOf("English") + 3)
            gesture(word, word, ViewConfiguration.getLongPressTimeout() + 250L)
            composeRule.waitUntil(5_000) { onMain { native.selectionEnd > native.selectionStart } }
            SystemClock.sleep(350)
            val initialStart = onMain { native.selectionStart }
            val initialEnd = onMain { native.selectionEnd }
            assertTrue(source.substring(initialStart, initialEnd).contains("English"))
            screenshot("r1-handles-01-longpress")
            gesture(endHandlePoint(native), pointAt(native, source.indexOf("段落 3") + 3), 750)
            composeRule.waitUntil(5_000) { onMain { native.selectionEnd > initialEnd } }
            assertEquals(initialStart, onMain { native.selectionStart })
            assertTrue(source.substring(initialStart, onMain { native.selectionEnd }).contains('\n'))
            screenshot("r1-handles-02-cross-paragraph")
            val oldScroll = onMain { native.scrollY }
            val viewportEnd = onMain {
                native.layout.getLineEnd(native.layout.getLineForVertical(native.scrollY + native.height - native.totalPaddingBottom - 1))
            }
            val bounds = onMain { Rect().also { native.getGlobalVisibleRect(it) } }
            val edge = PointF(bounds.centerX().toFloat(), bounds.bottom + onMain { native.lineHeight }.toFloat() * 2)
            gesture(endHandlePoint(native), edge, 650, holdAtEndMs = 1_600)
            composeRule.waitUntil(7_000) { onMain { native.scrollY > oldScroll && native.selectionEnd > viewportEnd } }
            val selectionStart = onMain { native.selectionStart }
            val selectionEnd = onMain { native.selectionEnd }
            assertEquals(initialStart, selectionStart)
            assertEquals(source, onMain { native.bridge!!.session.captureSnapshot().source })
            screenshot("r1-handles-03-auto-scroll")
            // No setSelection constructs this range. Only the long press and real handle touch events do.
            val selected = source.substring(selectionStart, selectionEnd)
            onMain { assertTrue(native.onTextContextMenuItem(android.R.id.cut)) }
            awaitSource(native, source.removeRange(selectionStart, selectionEnd))
            assertEquals(selected, onMain { clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString() })
            composeRule.onNodeWithTag("markdown-note-undo").performClick()
            awaitSource(native, source)
            assertEquals(selectionStart, onMain { native.selectionStart })
            assertEquals(selectionEnd, onMain { native.selectionEnd })
            assertSame(editable, onMain { native.text })
            writeEvidence("r1-real-handles", JSONObject().put("selectionSetProgrammatically", false)
                .put("crossedParagraphs", true).put("autoScrolled", true).put("selectedUtf16Units", selected.length)
                .put("exactCutUndoAndSelection", true).put("sameEditable", true))
        } finally {
            onMain { if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip() }
        }
    }

    @Test fun baiduRealTouchCandidatesAtLongDocumentHeadMiddleTailPreserveExactSource() {
        assertEquals("Bounded observed touch map requires PHQ110", "PHQ110", Build.MODEL)
        assertEquals("Bounded observed touch map requires Baidu full-pinyin QWERTY", "com.baidu.input_oppo/.ImeService",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        assertEquals(1080, context.resources.displayMetrics.widthPixels)
        assertEquals(2412, onMain { composeRule.activity.windowManager.currentWindowMetrics.bounds.height() })
        val source = longFixture()
        val (repository, formal) = repository(source)
        mount(repository, formal)
        awaitReading(30_000)
        composeRule.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        val native = native()
        val editable = onMain { native.text }
        showKeyboard(native, source.indexOf("第0段"))
        var expected = source
        var commits = 0
        val samples = JSONArray()
        fun typeCandidate(pinyin: String, chinese: String, location: String) {
            val caret = onMain { native.selectionEnd }
            assertEquals(caret, onMain { native.selectionStart })
            val sourceAt = onMain { native.bridge!!.sourceSelection().focus }
            pinyin.forEach(::tapBaiduKey)
            SystemClock.sleep(100)
            assertTrue("Vendor preedit must not reach source", expected == onMain { native.bridge!!.session.captureSnapshot().source })
            val before = SystemClock.elapsedRealtime()
            gesture(PointF(100f, 1580f), PointF(100f, 1580f), 45)
            expected = expected.replaceRange(sourceAt, sourceAt, chinese)
            awaitSource(native, expected)
            assertTrue("Native text must match the source after every candidate", expected == onMain { native.text.toString() })
            assertEquals(caret + chinese.length, onMain { native.selectionEnd })
            commits++
            samples.put(JSONObject().put("position", location).put("touchCandidateToAcceptedMs", SystemClock.elapsedRealtime() - before))
        }
        for (location in listOf("head", "middle", "tail")) {
            val marker = when (location) { "head" -> "第0段"; "middle" -> "第1430段"; else -> "长文最后一行END" }
            val at = onMain { native.text.indexOf(marker) }
            assertTrue(at >= 0)
            // Initial caret placement only. All pinyin, candidate, backspace and Enter input uses real touches.
            onMain { native.bridge!!.revealSourceOffset(at) }
            composeRule.waitUntil(5_000) { onMain { native.selectionEnd == at && offsetVisible(native, at) } }
            repeat(8) { index ->
                if (index % 2 == 0) typeCandidate("nihao", "你好", location)
                else typeCandidate("zhongwen", "中文", location)
            }
            screenshot("r1-vendor-$location-committed")
        }
        repeat(2) {
            val at = onMain { native.bridge!!.sourceSelection().focus }
            gesture(PointF(975f, 2040f), PointF(975f, 2040f), 45)
            expected = expected.removeRange(at - 1, at)
            awaitSource(native, expected)
        }
        val enterAt = onMain { native.bridge!!.sourceSelection().focus }
        gesture(PointF(975f, 2190f), PointF(975f, 2190f), 45)
        expected = expected.replaceRange(enterAt, enterAt, "\n")
        awaitSource(native, expected)
        typeCandidate("nihao", "你好", "tail-after-enter")
        assertSame(editable, onMain { native.text })
        composeRule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading(30_000)
        assertTrue("The exact vendor-written source must be formally committed", expected == repository.find(formal.cardId, formal.cardVersion)?.markdown)
        writeEvidence("r1-vendor-long", JSONObject().put("touchCandidateCommits", commits).put("backspaceEdits", 2)
            .put("enterEdits", 1).put("sourceExactAfterEveryCommit", true).put("sameEditable", true).put("samples", samples)
            .put("scope", "PHQ110 Baidu observed full-pinyin touch map; no text injection; not sustained human writing acceptance"))
    }

    private fun mount(repository: FileNoteContentRepository, formal: NoteContent, onBack: () -> Unit = {},
        onFormalCommitted: (NoteContent) -> Unit = {}) {
        composeRule.setContent {
            UnarchiveTheme { MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, onBack, onFormalCommitted) }
        }
    }

    private fun awaitReading(timeout: Long = 8_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openSourceFromMore() {
        composeRule.onNodeWithTag("markdown-note-edit-more").performClick()
        composeRule.onNodeWithText("全文源码").performScrollTo().performClick()
        composeRule.onNodeWithTag("markdown-note-source-panel").assertIsDisplayed()
    }
    private fun sourcePanelView() = androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(
        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java),
        org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(NativeDocumentEditText::class.java)),
    ))
    private fun replaceSourcePanel(text: String) {
        sourcePanelView().perform(androidx.test.espresso.action.ViewActions.replaceText(text))
        composeRule.waitForIdle()
    }
    private fun awaitEditing() {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun native(): NativeDocumentEditText = onMain {
        descendants(composeRule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun <T> onMain(action: () -> T): T = composeRule.runOnIdle(action)
    private fun commitAt(native: NativeDocumentEditText, offset: Int, value: String) = onMain {
        native.setSelection(offset)
        native.requestFocus()
        assertTrue(checkNotNull(native.onCreateInputConnection(EditorInfo())).commitText(value, 1))
    }
    private fun awaitSource(native: NativeDocumentEditText, source: String) {
        composeRule.waitUntil(10_000) { onMain { native.bridge!!.session.captureSnapshot().source == source } }
    }
    private fun longFixture(): String = buildString {
        append("生产长文验收\n\n")
        repeat(2_860) { index -> append("第${index}段。中文🙂连续阅读编辑的合成正文，用于首中尾输入与完整末尾验证。\n\n") }
        append("长文最后一行END")
    }
    private fun showKeyboard(native: NativeDocumentEditText, offset: Int) {
        val point = pointAt(native, offset)
        gesture(point, point, 45)
        onMain { context.getSystemService(InputMethodManager::class.java).showSoftInput(native, InputMethodManager.SHOW_IMPLICIT) }
        composeRule.waitUntil(5_000) { onMain { native.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true } }
        SystemClock.sleep(300)
    }
    private fun pointAt(native: NativeDocumentEditText, offset: Int): PointF = onMain {
        val xy = IntArray(2).also(native::getLocationOnScreen)
        val line = native.layout.getLineForOffset(offset)
        PointF(xy[0] + native.totalPaddingLeft + native.layout.getPrimaryHorizontal(offset) - native.scrollX,
            xy[1] + native.totalPaddingTop + (native.layout.getLineTop(line) + native.layout.getLineBottom(line)) / 2f - native.scrollY)
    }
    private fun endHandlePoint(native: NativeDocumentEditText): PointF = onMain {
        val xy = IntArray(2).also(native::getLocationOnScreen)
        val end = native.selectionEnd
        val line = native.layout.getLineForOffset(end)
        val thumb = 10f * context.resources.displayMetrics.density
        PointF(xy[0] + native.layout.getPrimaryHorizontal(end) + native.totalPaddingLeft - native.scrollX + thumb,
            xy[1] + native.layout.getLineBottom(line) + native.totalPaddingTop - native.scrollY + thumb)
    }
    private fun offsetVisible(native: NativeDocumentEditText, offset: Int): Boolean {
        val line = native.layout.getLineForOffset(offset)
        val top = native.layout.getLineTop(line)
        val bottom = native.layout.getLineBottom(line)
        return top >= native.scrollY && bottom <= native.scrollY + native.height - native.totalPaddingTop - native.totalPaddingBottom
    }
    private fun tapBaiduKey(letter: Char) {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val row = rows.indexOfFirst { letter in it }
        check(row >= 0)
        val column = rows[row].indexOf(letter)
        val point = PointF(listOf(80f, 132f, 234f)[row] + 102.5f * column, listOf(1730f, 1880f, 2040f)[row])
        gesture(point, point, 45)
        SystemClock.sleep(55)
    }
    private fun gesture(from: PointF, to: PointF, durationMs: Long, holdAtEndMs: Long = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(motion, true)) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from.x, from.y)
        val steps = maxOf(1, (durationMs / 25).toInt())
        repeat(steps) { step ->
            SystemClock.sleep(durationMs / steps)
            val fraction = (step + 1).toFloat() / steps
            event(MotionEvent.ACTION_MOVE, from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
        }
        repeat((holdAtEndMs / 30).toInt()) { SystemClock.sleep(30); event(MotionEvent.ACTION_MOVE, to.x, to.y) }
        event(MotionEvent.ACTION_UP, to.x, to.y)
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(evidenceDirectory(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun repository(source: String, writer: NoteContentAtomicWriter? = null): Pair<FileNoteContentRepository, NoteContent> {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_redesign_synthetic"), cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_redesign_synthetic", title = "合成测试标题",
            ownerName = "合成测试作者", videoDurationSeconds = 10, timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000, updatedAtEpochMs = 1_000, markdown = "# 合成测试标题",
        )
        val content = when (val migration = NoteContentMigration.fromKnowledgeCard(card)) {
            is NoteContentMigrationDecision.Ready -> migration.content
            is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(migration, migration.generatedMarkdown).content
        }.copy(markdown = source, lastKnownGoodMarkdown = source)
        val repository = if (writer == null) FileNoteContentRepository(testRoot) else FileNoteContentRepository(testRoot, writer)
        val saved = repository.save(content)
        assertTrue(saved.isComplete)
        assertNotNull(repository.find(content.cardId, content.cardVersion))
        return repository to saved.content
    }

    private fun writeEvidence(name: String, values: JSONObject) {
        File(evidenceDirectory(), "$name.json").writeText(values.put("passed", true).put("productionRoute", "MarkdownNoteRoute").toString(2))
    }
    private fun evidenceDirectory(): File {
        val arguments = InstrumentationRegistry.getArguments()
        val run = arguments.getString("noteEditorReportRun", "direct")!!.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val directory = File(context.getExternalFilesDir(null), "note-editor-redesign/$run")
        check(directory.mkdirs() || directory.isDirectory)
        return directory
    }
}
