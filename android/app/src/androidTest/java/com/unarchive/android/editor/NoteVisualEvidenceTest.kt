package com.unarchive.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Synthetic screenshots are visual evidence, not user visual/TalkBack acceptance. */
class NoteVisualEvidenceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "editor-visual-${System.nanoTime()}")
    @After fun close() { rule.runOnUiThread { rule.activity.viewModelStore.clear() }; root.deleteRecursively() }

    @Test fun readingLight() = capture(false, false, 1f)
    @Test fun editingLight() = capture(false, true, 1f)
    @Test fun readingDark() = capture(true, false, 1f)
    @Test fun editingDarkTwoTimesText() = capture(true, true, 2f)

    private fun capture(dark: Boolean, editing: Boolean, scale: Float) {
        rule.runOnUiThread {
            rule.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
        }
        val image = Bitmap.createBitmap(640, 300, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(android.graphics.Color.rgb(224, 235, 243))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(80, 130, 160) }
            drawCircle(510f, 65f, 150f, paint)
            paint.color = android.graphics.Color.rgb(98, 155, 126); drawCircle(120f, 310f, 215f, paint)
        }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val asset = CardAsset("visual-image", CardAssetKind.OTHER, "image/png", "assets/visual.png", bytes.size.toLong(), KnowledgeCard.sha256(bytes))
        val source = "# 把想法整理成自己的知识\n\n阅读是入口，写下自己的理解，才让知识成为可以再次使用的经验。\n\n## 为思考留一点空间\n\n从一个小问题开始，记录 **真正重要的发现**，也保留尚未确定的想法。\n\n> 好的笔记，不必一次写完。\n\n- 先理解，再组织\n- 用自己的语言连接观点\n- [ ] 留下下一步要验证的问题\n\n![合成配图](assets/visual.png)\n\n最后一行依然可以连续编辑。"
        val card = KnowledgeCard(KnowledgeCardId("synthetic", "visual"), "visual", "https://example.com/synthetic", "合成笔记", "合成作者",
            120, TranscriptTimingAccuracy.EXACT, emptyList(), assets = listOf(asset), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1, updatedAtEpochMs = 1, markdown = source)
        val repository = FileNoteContentRepository(root)
        check(repository.save(NoteContent(card.cardId, card.cardVersion, source, 0, card.toNoteDocument(), createdAtEpochMs = 1, updatedAtEpochMs = 1)).isComplete)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                UnarchiveTheme(darkTheme = dark) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().safeDrawingPadding()) {
                        MarkdownNoteRoute(card.cardId, card.cardVersion, repository, onBack = {}, readAsset = { bytes })
                    }
                }
            }
        }
        rule.waitUntil(12_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
        if (editing) { rule.onNodeWithTag("markdown-note-edit").performClick(); rule.waitUntil(8_000) {
            rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() } }
        if (!editing) rule.waitUntil(10_000) {
            rule.runOnIdle {
                val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[
                    "markdown-note-${card.cardId.value}-${card.cardVersion}", MarkdownNoteViewModel::class.java]
                val host = vm.bridge?.view
                host != null && host.text.getSpans(0, host.length(),
                    com.unarchive.android.editor.native.NativeDocumentSpans.OwnedAtomic::class.java)
                    .any { it.node.kind == "image" && it.bitmap != null }
            }
        }
        rule.waitForIdle()
        android.os.SystemClock.sleep(450)
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("noteEditorReportRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "direct"
        val folder = File(context.getExternalFilesDir(null), "note-editor-visual/$run").apply { mkdirs() }
        val name = "${if (editing) "editing" else "reading"}-${if (dark) "dark" else "light"}-${scale}x.png"
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().also { screenshot ->
            File(folder, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }
}
