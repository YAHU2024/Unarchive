package com.unarchive.editorprobe

import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Measures actual native buffer edits through the same source session and bounded history. */
class E2IntegratedInstrumentedTest {
    @get:Rule val scenario = ActivityScenarioRule(ProbeActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: ProbeActivity

    @Test fun incrementalNativeLongHeadMiddleTailAndFrames() {
        val report = base("e2-integrated-long")
        val samples = JSONArray()
        try {
            mount("long")
            val controller = checkNotNull(app.nativeController)
            val initialEditable = onMain { controller.view!!.text }
            val initialSetCount = controller.documentSetCount
            report.put("loadToFirstDrawMs", (app.telemetry.firstMatchingDrawAtNs.get() - app.requestedAtNs) / 1_000_000.0)
                .put("sourceCodePoints", app.sourceCodePoints).put("sourceUtf8Bytes", app.sourceBytes)
            assertTrue(app.sourceCodePoints >= 100_000)
            var expectedSource = app.sourceMarkdown
            val tailAt = controller.text().indexOf(ProbeActivity.TAIL)
            onMain { controller.scrollToOffset(tailAt) }
            await { controller.isOffsetVisible(tailAt) }
            assertEquals(controller.text().length, onMain { controller.view!!.layout.text.length })
            val heapBefore = heap()
            onMain { controller.scrollToOffset(0); app.telemetry.start() }
            val bounds = onMain { controller.view!!.globalBounds() }
            repeat(8) {
                shell("input swipe ${bounds.centerX()} ${bounds.bottom - bounds.height()/6} ${bounds.centerX()} ${bounds.top + bounds.height()/6} 300")
                instrumentation.waitForIdleSync()
            }
            SystemClock.sleep(100)
            report.put("scrollFrames", onMain { app.telemetry.stop() })
            onMain { app.sourceSession!!.resetParseDiagnostics(); app.telemetry.start() }
            for (location in listOf("head", "middle", "tail")) repeat(4) { index ->
                val marker = when (location) { "head" -> "段落 0"; "middle" -> "段落 800"; else -> ProbeActivity.TAIL }
                val at = controller.text().indexOf(marker)
                assertTrue(at >= 0)
                onMain { controller.select(at); controller.scrollToOffset(at) }
                await { controller.isOffsetVisible(at) }
                val insertion = "测$index"
                val start = SystemClock.elapsedRealtimeNanos()
                var expectedLength = 0
                var version = 0L
                onMain {
                    expectedLength = controller.text().length + insertion.length
                    version = app.sourceSession!!.version + 1
                    app.telemetry.beginDrawMeasurement(expectedLength, version)
                    controller.replaceRange(at, at, insertion)
                }
                await(10_000) {
                    app.sourceSession!!.version >= version && app.telemetry.firstMatchingDrawAtNs.get() >= start &&
                        app.telemetry.drawnTextLength.get() == expectedLength.toLong()
                }
                expectedSource = expectedSource.replaceFirst(marker, insertion + marker)
                assertEquals(expectedSource, app.persistedSource())
                assertTrue(controller.isOffsetVisible(at))
                samples.put(JSONObject().put("location", location)
                    .put("editToAcceptedDrawMs", (app.telemetry.firstMatchingDrawAtNs.get() - start) / 1_000_000.0)
                    .put("sourceVersion", version).put("offsetActuallyVisible", true))
            }
            SystemClock.sleep(100)
            report.put("inputFrames", onMain { app.telemetry.stop() })
            assertEquals(0, app.adapterRejectedEdits)
            assertFalse(app.sourceSession!!.candidateMaterialized)
            assertTrue(initialEditable === onMain { controller.view!!.text })
            assertEquals(initialSetCount, controller.documentSetCount)
            assertEquals(0L, app.sourceSession!!.diagnostics.fullParses)
            report.put("passed", true).put("samples", samples).put("adapterAcceptSamplesMs", JSONArray(app.adapterSamplesMs))
                .put("parseDiagnostics", diagnostics()).put("history", history())
                .put("heapBefore", heapBefore).put("heapAfter", heap())
                .put("candidateMaterialized", false).put("sameEditableIdentity", true)
                .put("tailActuallyVisibleBeforeInput", true).put("documentSetCount", controller.documentSetCount)
        } finally { report.put("samples", samples); if (::app.isInitialized) write("e2-integrated-long", report) }
    }

    @Test fun continuousSelectionCutPasteAndBoundedHistoryStaySourceExact() {
        val report = base("e2-integrated-selection-history")
        var clipboard: ClipboardManager? = null
        var previousClip: android.content.ClipData? = null
        try {
            mount("long")
            val controller = app.nativeController!!
            val session = app.sourceSession!!
            val originalSource = session.source
            val originalText = controller.text()
            val editable = onMain { controller.view!!.text }
            clipboard = app.getSystemService(ClipboardManager::class.java)
            previousClip = onMain { clipboard!!.primaryClip }
            val first = originalText.indexOf("段落 0")
            val last = originalText.indexOf("段落 160")
            assertTrue(last - first > 8_000)
            val selected = originalText.substring(first, last)
            onMain { assertTrue(controller.view!!.requestFocus()); controller.select(first, last); controller.scrollToOffset(last) }
            await { controller.isOffsetVisible(last) }
            assertEquals(first, controller.snapshot().selectionStart)
            assertEquals(last, controller.snapshot().selectionEnd)
            assertTrue(onMain { assertTrue(controller.view!!.isFocused); controller.view!!.onTextContextMenuItem(android.R.id.copy) })
            assertEquals(selected, onMain { clipboard!!.primaryClip!!.getItemAt(0).coerceToText(app).toString() })
            // Native copy closes the action mode and may collapse selection.
            // Cutting is a separate user operation and must select its range again.
            val beforeCut = session.version
            assertTrue(onMain {
                assertTrue(controller.view!!.requestFocus())
                controller.select(first, last)
                assertTrue(controller.view!!.isFocused)
                assertEquals(first, controller.view!!.selectionStart)
                assertEquals(last, controller.view!!.selectionEnd)
                controller.view!!.onTextContextMenuItem(android.R.id.cut)
            })
            await { session.version > beforeCut }
            assertEquals(originalText.removeRange(first, last), session.projectedText())
            val beforePaste = session.version
            assertTrue(onMain {
                assertTrue(controller.view!!.requestFocus())
                controller.select(first)
                controller.view!!.onTextContextMenuItem(android.R.id.paste)
            })
            await { session.version > beforePaste }
            assertEquals(originalText, controller.text())
            assertEquals(originalSource, session.source)
            assertTrue(editable === onMain { controller.view!!.text })
            onMain { assertTrue(session.undo()); assertTrue(session.undo()); app.refreshNativeFromSource() }
            await { controller.text() == originalText }
            assertEquals(originalSource, session.source)
            assertEquals(first, controller.snapshot().selectionStart)
            assertEquals(last, controller.snapshot().selectionEnd)
            onMain { assertTrue(session.redo()); assertTrue(session.redo()); app.refreshNativeFromSource() }
            await { controller.text() == originalText }
            assertEquals(originalSource, session.source)
            assertEquals(last, controller.snapshot().selectionEnd)
            val gcBefore = heapAfterGc()
            repeat(256) {
                val oldVersion = session.version
                onMain { val end=controller.text().length; controller.replaceRange(end,end,"字") }
                await { session.version > oldVersion }
                assertTrue(session.historyStats.retainedCount <= 128)
                assertTrue(session.historyStats.retainedByteCharge <= 256L*1024L)
            }
            val final = session.source
            val stats = history()
            assertEquals(128, session.historyStats.retainedCount)
            val beforeNativeUndo = controller.text()
            val beforeNativeUndoVersion = session.version
            onMain { controller.view!!.onTextContextMenuItem(android.R.id.undo) }
            instrumentation.waitForIdleSync()
            assertEquals("The native control must not keep an independent undo chain", beforeNativeUndo, controller.text())
            assertEquals(beforeNativeUndoVersion, session.version)
            onMain { repeat(128) { assertTrue(session.undo()) }; app.refreshNativeFromSource() }
            await { controller.text() == session.projectedText() }
            assertFalse(session.canUndo)
            assertEquals(originalSource.trimEnd() + "字".repeat(128) + "\n", session.source)
            onMain { repeat(128) { assertTrue(session.redo()) }; app.refreshNativeFromSource() }
            await { controller.text() == session.projectedText() }
            assertEquals(final, session.source)
            assertFalse(session.canRedo)
            report.put("passed", true).put("selectedUtf16Units", selected.length).put("crossViewportSelection", true)
                .put("clipboardRoundTripSourceExact", true).put("stressEdits",256).put("historyAfterStress",stats)
                .put("nativeIndependentUndoDisabled", true)
                .put("cutUndoRestoresPreEditSelection", true).put("pasteRedoRestoresCaret", true)
                .put("historyAfterUndoRedo",history()).put("heapAfterGcBeforeStress",gcBefore)
                .put("heapAfterGcAfterStress",heapAfterGc()).put("adapterRejectedEdits",app.adapterRejectedEdits)
                .put("selectionEvidence", "Programmatic native multi-viewport range plus actual native copy/cut/paste actions; drag-handle gesture not measured")
            assertEquals(0, app.adapterRejectedEdits)
        } finally {
            if (clipboard != null) onMain { if(previousClip!=null) clipboard!!.setPrimaryClip(previousClip!!) else clipboard!!.clearPrimaryClip() }
            if (::app.isInitialized) {
                report.put("adapterRejectedEdits",app.adapterRejectedEdits).put("adapterResult",app.sourceSession?.lastResult.toString())
                    .put("adapterVersion",app.sourceSession?.version ?: -1).put("bufferLength",app.text().length)
                    .put("lastNativeRejection",app.lastNativeRejection).put("lastNativeRejectedDelta",app.lastNativeRejectedDelta)
                write("e2-integrated-selection-history", report)
            }
        }
    }

    @Test fun nativeIntegratedServedImeCommitsWithoutSourceComposition() {
        val report = base("e2-integrated-ime")
        mount("short")
        val controller=app.nativeController!!
        val initialText=controller.text()
        val initialSource=app.persistedSource()
        val previous=Settings.Secure.getString(app.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)
        val service=ComponentName(app,ProbeImeService::class.java).flattenToShortString()
        val enabled=shell("ime list -s").lineSequence().map(String::trim).toSet()
        try {
            shell("ime enable $service"); shell("ime set $service")
            onMain { controller.view!!.requestFocus() }
            val bounds=onMain { controller.view!!.globalBounds() }
            shell("input tap ${bounds.left+40} ${bounds.top+40}")
            onMain { controller.select(initialText.length) }
            await { ProbeImeService.active?.servedPackage==app.packageName && ProbeImeService.active?.connection!=null }
            for(value in listOf("ni","nihao")) {
                onMain { assertTrue(ProbeImeService.active!!.connection!!.setComposingText(value,1)) }
                await { controller.text()==initialText+value && controller.snapshot().isComposing }
                assertEquals(initialSource,app.persistedSource())
            }
            onMain { assertTrue(ProbeImeService.active!!.connection!!.commitText("你好",1)); ProbeImeService.active!!.connection!!.finishComposingText() }
            await { !controller.snapshot().isComposing && app.persistedSource()==initialSource.trimEnd()+"你好\n" }
            assertEquals(initialText+"你好",controller.text())
            assertEquals(controller.text().length,controller.snapshot().selectionEnd)
            assertEquals(0,app.adapterRejectedEdits)
            assertEquals(1,controller.documentSetCount)
            report.put("passed",true).put("compositionSourceUntouched",true).put("committedSourceExact",true)
                .put("selectionAtEnd",true).put("documentSetCount",controller.documentSetCount)
        } finally {
            if(!previous.isNullOrBlank())shell("ime set $previous")
            if(service !in enabled)shell("ime disable $service")
            report.put("previousImeRestored",Settings.Secure.getString(app.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)==previous)
            write("e2-integrated-ime",report)
        }
    }

    private fun mount(fixture:String) {
        scenario.scenario.onActivity { app=it; app.loadFixture(fixture,"native") }
        await(30_000) { app.nativeController?.view?.layout != null && app.telemetry.firstMatchingDrawAtNs.get() >= app.requestedAtNs }
    }
    private fun diagnostics():JSONObject = app.sourceSession!!.diagnostics.let {
        JSONObject().put("fullParses",it.fullParses).put("incrementalParses",it.incrementalParses)
            .put("parsedCharacters",it.parsedCharacters).put("lastParsedCharacters",it.lastParsedCharacters)
            .put("lastParsedNodes",it.lastParsedNodes).put("lastFallbackReason",it.lastFallbackReason)
    }
    private fun history():JSONObject = app.sourceSession!!.historyStats.let {
        JSONObject().put("undoCount",it.undoCount).put("redoCount",it.redoCount).put("retainedByteCharge",it.retainedByteCharge)
            .put("retainedPayloadCharacters",it.retainedPayloadCharacters).put("evictedTransactions",it.evictedTransactions)
            .put("oversizedBarriers",it.oversizedBarriers)
    }
    private fun heap():JSONObject {
        val runtime=Runtime.getRuntime()
        return JSONObject().put("javaUsedBytes",runtime.totalMemory()-runtime.freeMemory()).put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize())
    }
    private fun heapAfterGc():JSONObject { Runtime.getRuntime().gc(); SystemClock.sleep(100); return heap() }
    private fun base(name:String)=JSONObject().put("scenario",name).put("passed",false).put("device",Build.MODEL)
        .put("api",Build.VERSION.SDK_INT).put("buildType",BuildConfig.BUILD_TYPE).put("debuggable",BuildConfig.DEBUG)
        .put("targetMinified",false).put("scope","Integrated incremental source adapter, bounded source history and one native Editable")
    private fun write(name:String,report:JSONObject) {
        val dir=File(app.getExternalFilesDir(null),"probe-reports").apply{mkdirs()}
        File(dir,"$name.json").writeText(report.toString(2),Charsets.UTF_8)
    }
    private fun <T> onMain(action:()->T):T { var value:T?=null; instrumentation.runOnMainSync{value=action()}; @Suppress("UNCHECKED_CAST") return value as T }
    private fun await(timeout:Long=10_000,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){instrumentation.waitForIdleSync();if(onMain(condition))return;SystemClock.sleep(16)}
        assertTrue("Integrated source/native state did not converge",onMain(condition))
    }
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
}
