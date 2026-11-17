package com.unarchive.android.editor.performance;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.FrameMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Platform-only entry point for the opt-in minified release measurement APK.
 * No AndroidX, JUnit, Kotlin or target business type is linked by this driver.
 * The only application entry is the two-method, kept platform Bundle facade.
 */
public final class NoteReleasePerformanceInstrumentation extends Instrumentation {
    private static final String TEST = "com.unarchive.android.editor.NoteReleasePerformanceTest";
    private static final String FACADE = "com.unarchive.android.editor.performance.NoteReleasePerformanceHarness";
    private static final String LONG = "longProductionRouteReleasePerformance";
    private static final String IMAGE = "imageProductionRouteReleasePerformance";
    private Bundle arguments;
    private String runId;
    private String mode;
    private int rounds;
    private int perPosition;
    private volatile String stage = "runner-start";
    private JSONObject syntheticSampleDiagnostic;
    private Activity measurementActivity;
    private EditText measurementEditor;
    private int originalAutomationFlags;
    private boolean automationFlagsChanged;
    private static final String IME_PACKAGE = "com.baidu.input_oppo";

    @Override public void onCreate(Bundle values) {
        super.onCreate(values);
        arguments = values == null ? new Bundle() : new Bundle(values);
        start();
    }

    @Override public void onStart() {
        int completed = 0;
        try {
            AccessibilityServiceInfo automation = getUiAutomation().getServiceInfo();
            originalAutomationFlags = automation.flags;
            automation.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(automation);
            automationFlagsChanged = true;
            runId = arguments.getString("noteReleaseRun", "direct-" + System.currentTimeMillis());
            require(runId.matches("[A-Za-z0-9_-]{1,120}"), "INVALID_RUN_ID");
            mode = arguments.getString("noteReleaseInputMode", "vendorTouch");
            require("vendorTouch".equals(mode) || "protocol".equals(mode), "INVALID_INPUT_MODE");
            rounds = Integer.parseInt(arguments.getString("noteReleaseRounds", "3"));
            perPosition = Integer.parseInt(arguments.getString("noteReleaseSamples", "3"));
            require(rounds >= 1 && rounds <= 10 && perPosition >= 1 && perPosition <= 10, "INVALID_SAMPLE_COUNT");
            require("release".equals(arguments.getString("noteReleaseBuildType")), "NOT_RELEASE_ARGUMENT");
            require((getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "TARGET_DEBUGGABLE");
            String selection = arguments.getString("class", TEST);
            List<String> tests = new ArrayList<>();
            if (TEST.equals(selection)) { tests.add(LONG); tests.add(IMAGE); }
            else if ((TEST + "#" + LONG).equals(selection)) tests.add(LONG);
            else if ((TEST + "#" + IMAGE).equals(selection)) tests.add(IMAGE);
            else throw new Check("UNKNOWN_TEST_SELECTION");
            for (String test : tests) {
                status(1, tests.size(), completed + 1, test, null);
                try { measure(LONG.equals(test) ? "long" : "image"); }
                catch (Throwable failure) {
                    status(-2, tests.size(), completed + 1, test, failureCode(failure));
                    Bundle result = new Bundle();
                    result.putString("stream", "\nFAILURES!!!\nTests run: " + (completed + 1) + ", Failures: 1\n");
                    result.putString("failureCode", failureCode(failure));
                    result.putString("failureStage", stage);
                    finish(Activity.RESULT_CANCELED, result);
                    return;
                }
                status(0, tests.size(), completed + 1, test, null);
                completed++;
            }
            Bundle result = new Bundle();
            result.putString("stream", "\nOK (" + completed + " tests)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Bundle result = new Bundle();
            result.putString("stream", "\nFAILURES!!!\nTests run: " + completed + ", Failures: 1\n");
            result.putString("failureCode", failureCode(failure));
            result.putString("failureStage", stage);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (automationFlagsChanged) {
                AccessibilityServiceInfo automation = getUiAutomation().getServiceInfo();
                automation.flags = originalAutomationFlags;
                getUiAutomation().setServiceInfo(automation);
            }
        }
    }

    private void status(int code, int total, int current, String test, String failure) {
        Bundle result = new Bundle();
        result.putString("id", getClass().getSimpleName());
        result.putInt("numtests", total); result.putInt("current", current);
        result.putString("class", TEST); result.putString("test", test);
        result.putString("stream", code == 1 ? "\n" + TEST + ":" : code == 0 ? "." : "\nFAILURE " + failure + " at " + stage + "\n");
        if (failure != null) { result.putString("failureCode", failure); result.putString("failureStage", stage); }
        sendStatus(code, result);
    }

    private void measure(String scenario) throws Exception {
        JSONObject report = new JSONObject().put("schemaVersion", 1).put("runId", runId).put("scenario", scenario)
            .put("productionRoute", "MarkdownNoteRoute").put("buildType", "release").put("inputMode", mode)
            .put("rounds", rounds).put("samplesPerPositionPerRound", perPosition).put("model", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("driver", "platform-java-instrumentation")
            .put("measurementScope", "vendorTouch".equals(mode)
                ? "PHQ110 Baidu candidate ACTION_DOWN to native accepted / observed draw; synthetic fixtures"
                : "Controlled InputConnection commit to native accepted / observed draw; not vendor touch latency")
            .put("startupScope", "Route mount after launcher Activity resumes; not process cold startup")
            .put("acceptedDrawScope", "Native callbacks; observed draw does not prove display presentation time")
            .put("quantiles", "median middle or middle-pair mean; p95 nearest rank ceil(.95*n)-1")
            .put("passed", false);
        JSONArray measurements = new JSONArray();
        report.put("roundMeasurements", measurements);
        try {
            for (int round = 0; round < rounds; round++) measurements.put(measureRound(scenario, round));
            report.put("summary", summarize(measurements)).put("passed", true);
        } catch (Exception | Error error) {
            report.put("failureType", error.getClass().getSimpleName()).put("failureCode", failureCode(error)).put("failureStage", stage);
            if (syntheticSampleDiagnostic != null) report.put("syntheticSampleDiagnostic", syntheticSampleDiagnostic);
            throw error;
        } finally { writeReport(scenario, report); }
    }

    private JSONObject measureRound(String scenario, int round) throws Exception {
        stage = scenario + "-" + round + "-launch";
        Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
        require(launch != null, "NO_LAUNCH_ACTIVITY");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final Activity activity = startActivitySync(launch);
        measurementActivity = activity;
        final Events events = new Events();
        Frames frames = null;
        Memory memory = null;
        JSONObject result = new JSONObject().put("round", round);
        JSONArray inputs = new JSONArray();
        result.put("inputs", inputs);
        String expected = fixture(scenario);
        require(expected.codePointCount(0, expected.length()) == 110447, "FIXTURE_LENGTH");
        try {
            if ("vendorTouch".equals(mode)) main(() -> { checkVendor(activity); return null; });
            frames = main(() -> new Frames(activity.getWindow()));
            memory = new Memory(); memory.start();
            frames.phase("mount");
            stage = scenario + "-" + round + "-mount";
            final String fixture = expected;
            long mountAt = SystemClock.elapsedRealtimeNanos();
            main(() -> {
                Bundle config = new Bundle(); config.putString("runId", runId); config.putString("scenario", scenario); config.putInt("round", round);
                config.putString("fixtureMarkdown", fixture); config.putString("fixtureFingerprint", fingerprint(fixture));
                config.putInt("fixtureCodePoints", fixture.codePointCount(0, fixture.length()));
                invoke("mount", activity, config, events); return null;
            });
            Bundle framework = events.await("framework", mountAt, null, 20000);
            stage = scenario + "-" + round + "-readable";
            Bundle readable = events.await("readable", mountAt, null, 40000); exact(readable, expected);
            result.put("frameworkMs", ms(time(framework) - mountAt)).put("readableMs", ms(time(readable) - mountAt))
                .put("fixtureCodePoints", 110447).put("fixtureFingerprint", fingerprint(expected));
            final EditText editor = main(() -> findEditor(activity.getWindow().getDecorView()));
            require(editor != null, "NO_NATIVE_EDITOR");
            measurementEditor = editor;
            final Object editable = main(editor::getText);
            JSONArray scrolls = new JSONArray();
            result.put("readingScrollMeasurements", scrolls);
            for (String gesture : new String[] { "drag", "fling" }) {
                for (boolean forward : new boolean[] { true, false }) {
                    scrolls.put(measureReadingScroll(activity, editor, editable, frames, events,
                        expected, scenario, round, gesture, forward));
                }
            }
            frames.phase("read-to-edit");
            long editAt = click("编辑"); stage = scenario + "-" + round + "-editable";
            Bundle editableEvent = events.await("editable", editAt, null, 15000);
            result.put("readToEditableMs", ms(time(editableEvent) - editAt));
            await(10000, () -> main(() -> editor.getKeyListener() != null && editor.onCreateInputConnection(new EditorInfo()) != null));
            String[] positions = { "head", "middle", "tail" };
            for (String position : positions) {
                for (int index = 0; index < perPosition; index++) {
                    String marker = "head".equals(position) ? "生产长文验收" : "middle".equals(position) ? "第1430段" : "长文最后一行END";
                    int sourceAt = expected.indexOf(marker); require(sourceAt >= 0, "SOURCE_MARKER_MISSING");
                    if ("tail".equals(position)) sourceAt += marker.length();
                    final boolean tail = "tail".equals(position);
                    main(() -> {
                        int at = editor.getText().toString().indexOf(marker); require(at >= 0, "NATIVE_MARKER_MISSING");
                        if (tail) at += marker.length();
                        editor.requestFocus(); editor.setSelection(at); editor.bringPointIntoView(at);
                        getTargetContext().getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                        return null;
                    });
                    await(10000, () -> main(() -> editor.getRootWindowInsets() != null && editor.getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
                    // IME visibility precedes its final touchable geometry. Observe stable
                    // platform window/inset bounds before restoring the measured caret.
                    if ("vendorTouch".equals(mode)) {
                        syntheticSampleDiagnostic = touchDiagnostic(editor, "await-ime-" + position + "-" + index, sourceAt, -1, "awaitImeGeometry");
                        awaitStableKeyboard(editor);
                    }
                    final int nativeAt = main(() -> {
                        int at = editor.getText().toString().indexOf(marker);
                        if (tail) at += marker.length();
                        editor.setSelection(at); editor.bringPointIntoView(at); return at;
                    });
                    awaitFrame(editor); awaitFrame(editor);
                    require(main(() -> editor.getSelectionStart() == nativeAt && editor.getSelectionEnd() == nativeAt), "CARET_NOT_SETTLED");
                    frames.phase("input-" + position);
                    final String sampleId = scenario + "-" + round + "-" + position + "-" + index;
                    stage = sampleId + "-begin";
                    final String before = expected;
                    main(() -> {
                        Bundle config = command("beginSample", sampleId);
                        config.putString("expectedSourceFingerprint", fingerprint(before));
                        config.putInt("expectedSourceCodePoints", before.codePointCount(0, before.length()));
                        invoke("command", activity, config, null); return null;
                    });
                    long inputAt;
                    long pinyinAt = 0;
                    String insertion;
                    if ("vendorTouch".equals(mode)) {
                        final int plannedSourceAt = sourceAt;
                        syntheticSampleDiagnostic = touchDiagnostic(editor, sampleId, plannedSourceAt, nativeAt, "beforePinyin");
                        JSONArray fixedTouches = new JSONArray();
                        syntheticSampleDiagnostic.put("fixedKeyboardTouches", fixedTouches);
                        pinyinAt = SystemClock.elapsedRealtimeNanos();
                        for (char letter : "nihao".toCharArray()) tapKey(letter, fixedTouches);
                        SystemClock.sleep(100);
                        long snapshotAt = SystemClock.elapsedRealtimeNanos();
                        main(() -> { invoke("command", activity, command("snapshot", sampleId), null); return null; });
                        syntheticSampleDiagnostic.put("beforeCandidate", touchDiagnostic(editor, sampleId, plannedSourceAt, nativeAt, "beforeCandidate"));
                        exact(events.await("snapshot", snapshotAt, sampleId, 10000), expected);
                        // Vendor preedit may stay inside its candidate window without
                        // EditText composing spans. The unchanged source and exact caret
                        // are still required before a real candidate touch.
                        boolean preeditAt = main(() -> {
                            int start = BaseInputConnection.getComposingSpanStart(editor.getText());
                            return start < 0 ? editor.getSelectionStart() == nativeAt && editor.getSelectionEnd() == nativeAt : start == nativeAt;
                        });
                        require(preeditAt, "VENDOR_PREEDIT_MOVED_FROM_SAMPLE_CARET");
                        require(main(() -> BaseInputConnection.getComposingSpanStart(editor.getText()) < 0 || plannedPinyin(editor)), "VENDOR_PREEDIT_NOT_NIHAO");
                        KeyboardSurface keyboard = keyboardSurface();
                        List<Rect> candidate = fixedImeTargets(keyboard, "你好");
                        JSONObject candidateInfo = new JSONObject().put("fixedLabel", "你好").put("exactMatchCount", candidate.size())
                            .put("imeWindowBounds", rect(keyboard.bounds));
                        syntheticSampleDiagnostic.put("candidateTouch", candidateInfo);
                        float[] point;
                        if (candidate.size() == 1) {
                            Rect bounds = candidate.get(0);
                            candidateInfo.put("method", "unique-accessible-label").put("bounds", rect(bounds));
                            point = new float[] { bounds.exactCenterX(), bounds.exactCenterY() };
                        } else {
                            require(candidate.isEmpty(), "VENDOR_CANDIDATE_LABEL_NOT_UNIQUE");
                            point = relativeObservedPoint(keyboard, 100f, 1580f);
                            candidateInfo.put("method", "observed-map-relative-to-live-ime-window");
                        }
                        candidateInfo.put("touchX", point[0]).put("touchY", point[1]);
                        inputAt = touch(point[0], point[1], 45); insertion = "你好";
                    } else {
                        inputAt = main(() -> {
                            InputConnection connection = editor.onCreateInputConnection(new EditorInfo()); require(connection != null, "NO_INPUT_CONNECTION");
                            long at = SystemClock.elapsedRealtimeNanos(); require(connection.commitText("中", 1), "IME_COMMIT_REJECTED"); return at;
                        });
                        insertion = "中";
                    }
                    expected = expected.substring(0, sourceAt) + insertion + expected.substring(sourceAt);
                    stage = sampleId + "-accepted";
                    Bundle accepted = events.await("inputAccepted", inputAt, sampleId, 20000);
                    final int diagnosticSourceAt = sourceAt;
                    JSONObject acceptedDiagnostic = main(() -> {
                        JSONObject diagnostic = new JSONObject().put("sampleId", sampleId).put("expectedSourceInsertionOffset", diagnosticSourceAt)
                            .put("observedSourceCodePoints", accepted.getInt("sourceCodePoints"))
                            .put("observedSourceFingerprint", accepted.getString("sourceFingerprint"))
                            .put("nativeSelectionStart", editor.getSelectionStart()).put("nativeSelectionEnd", editor.getSelectionEnd());
                        JSONArray codePoints = new JSONArray();
                        String visible = editor.getText().toString();
                        int at = Math.max(0, editor.getSelectionEnd() - 24);
                        String bounded = visible.substring(at, Math.min(visible.length(), at + 64));
                        bounded.codePoints().forEach(codePoints::put);
                        return diagnostic.put("syntheticNativeWindowStart", at).put("syntheticNativeWindowCodePoints", codePoints);
                    });
                    if (syntheticSampleDiagnostic == null) syntheticSampleDiagnostic = acceptedDiagnostic;
                    else syntheticSampleDiagnostic.put("acceptedSnapshot", acceptedDiagnostic);
                    exact(accepted, expected);
                    stage = sampleId + "-drawn";
                    Bundle drawn = events.await("inputDrawn", inputAt, sampleId, 20000); exact(drawn, expected);
                    require(drawn.getLong("acceptedVersion") >= accepted.getLong("acceptedVersion") && time(drawn) >= time(accepted), "DRAW_VERSION_ORDER");
                    require(main(editor::getText) == editable, "EDITABLE_REPLACED");
                    JSONObject input = new JSONObject().put("sampleId", sampleId).put("position", position)
                        .put("inputToAcceptedMs", ms(time(accepted) - inputAt)).put("inputToObservedDrawMs", ms(time(drawn) - inputAt))
                        .put("acceptedVersion", accepted.getLong("acceptedVersion")).put("sourceFingerprint", fingerprint(expected));
                    if (pinyinAt > 0) input.put("pinyinStartToCandidateDownMs", ms(inputAt - pinyinAt));
                    inputs.put(input);
                }
            }
            frames.phase("edit-to-read");
            long doneAt = click("完成并保存"); stage = scenario + "-" + round + "-formal-reading";
            Bundle reading = events.await("reading", doneAt, null, 40000); exact(reading, expected);
            result.put("editToReadingMs", ms(time(reading) - doneAt));
            require(main(() -> findEditor(activity.getWindow().getDecorView())) == editor && main(editor::getText) == editable, "NATIVE_BUFFER_REPLACED");
            stage = scenario + "-" + round + "-formal-snapshot";
            long snapshotAt = SystemClock.elapsedRealtimeNanos();
            main(() -> { invoke("command", activity, command("snapshot", "formal-final"), null); return null; });
            Bundle snapshot = events.await("snapshot", snapshotAt, "formal-final", 15000); exact(snapshot, expected);
            require(snapshot.getBoolean("formalCommitted"), "FORMAL_SNAPSHOT_NOT_COMMITTED");
            if ("image".equals(scenario)) {
                require(snapshot.getInt("decodedAssetCount", 0) >= 1, "NO_IMAGE_DECODE_EVIDENCE");
                require(snapshot.getInt("imageLoadFailures", -1) == 0, "IMAGE_DECODE_FAILURE");
            }
            SystemClock.sleep(100);
            JSONObject frameReport = frames.report(); require(frameReport.getInt("validDeadlineFrames") > 0, "NO_FRAME_DEADLINES");
            result.put("frames", frameReport).put("memory", memory.report()).put("sameNativeView", true).put("sameEditable", true)
                .put("finalSourceFingerprint", fingerprint(expected)).put("decodedAssetCount", snapshot.getInt("decodedAssetCount", 0))
                .put("imageLoadFailures", snapshot.getInt("imageLoadFailures", 0)).put("passed", true);
            return result;
        } finally {
            try { main(() -> { Bundle config = command("cleanup", null); config.putString("runId", runId); invoke("command", activity, config, null); return null; }); }
            catch (Throwable ignored) { /* Original measurement failure remains authoritative. */ }
            if (memory != null) memory.close();
            if (frames != null) frames.close();
            main(() -> { activity.finish(); return null; });
            waitForIdleSync();
        }
    }

    private void invoke(String method, Activity activity, Bundle config, ResultReceiver receiver) throws Exception {
        Class<?> type = Class.forName(FACADE, true, getTargetContext().getClassLoader());
        if (receiver == null) type.getMethod(method, Activity.class, Bundle.class).invoke(null, activity, config);
        else type.getMethod(method, Activity.class, Bundle.class, ResultReceiver.class).invoke(null, activity, config, receiver);
    }
    /** Real touch gestures on the production read surface; source authority is checked per phase. */
    private JSONObject measureReadingScroll(Activity activity, EditText editor, Object editable, Frames frames,
            Events events, String expected, String scenario, int round, String gesture, boolean forward) throws Exception {
        String direction = forward ? "forward" : "backward";
        String phase = "reading-" + gesture + "-" + direction;
        String sample = scenario + "-" + round + "-" + phase;
        stage = sample + "-before";
        long beforeAt = SystemClock.elapsedRealtimeNanos();
        main(() -> { invoke("command", activity, command("snapshot", sample + "-before"), null); return null; });
        Bundle before = events.await("snapshot", beforeAt, sample + "-before", 15000);
        exact(before, expected);
        require(main(() -> editor.getKeyListener() == null && !editor.isCursorVisible()), "SCROLL_NOT_READING_MODE");
        final Rect visible = main(() -> {
            Rect bounds = new Rect(); require(editor.getGlobalVisibleRect(bounds), "NO_VISIBLE_SCROLL_SURFACE");
            Rect window = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            WindowInsets insets = editor.getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                window = new Rect(window.left + bars.left, window.top + bars.top, window.right - bars.right, window.bottom - bars.bottom);
            }
            require(bounds.intersect(window), "SCROLL_SURFACE_OUTSIDE_WINDOW");
            require(bounds.width() > 120 && bounds.height() > 300, "SCROLL_SURFACE_TOO_SMALL");
            return bounds;
        });
        final int scrollBefore = main(editor::getScrollY);
        if (!forward) require(scrollBefore > 0, "BACKWARD_SCROLL_HAS_NO_CONTENT_BEFORE");
        float x = visible.left + visible.width() * .57f;
        float upper = visible.top + visible.height() * .24f;
        float lower = visible.top + visible.height() * .78f;
        float from = forward ? lower : upper;
        float to = forward ? upper : lower;
        boolean fling = "fling".equals(gesture);
        long duration = fling ? 110 : 760;
        frames.phase(phase);
        stage = sample + "-gesture";
        long gestureAt = SystemClock.elapsedRealtimeNanos();
        int scrollAtRelease = verticalGesture(editor, x, from, to, duration, fling);
        long upAt = SystemClock.elapsedRealtimeNanos();
        stage = sample + "-settle";
        int[] settled = awaitScrollSettled(editor, scrollAtRelease, upAt);
        int after = settled[0];
        long settledAt = SystemClock.elapsedRealtimeNanos();
        frames.phase("between-reading-scroll");
        frames.awaitCallbacks();
        require(forward ? after > scrollBefore : after < scrollBefore, "READING_TOUCH_SCROLL_DID_NOT_MOVE_IN_DIRECTION");
        if (fling) require(settled[1] > 0, "READING_FLING_HAS_NO_OBSERVED_POST_RELEASE_MOTION");
        stage = sample + "-source-check";
        long afterAt = SystemClock.elapsedRealtimeNanos();
        main(() -> { invoke("command", activity, command("snapshot", sample + "-after"), null); return null; });
        Bundle snapshot = events.await("snapshot", afterAt, sample + "-after", 15000);
        exact(snapshot, expected);
        require(snapshot.getLong("acceptedVersion") == before.getLong("acceptedVersion"), "READING_SCROLL_CHANGED_DOCUMENT_VERSION");
        require(main(() -> findEditor(activity.getWindow().getDecorView())) == editor && main(editor::getText) == editable,
            "READING_SCROLL_REPLACED_NATIVE_BUFFER");
        JSONObject frameReport = frames.report(phase);
        require(frameReport.getInt("validDeadlineFrames") > 0, "READING_SCROLL_NO_FRAME_DEADLINES");
        return new JSONObject().put("phase", phase).put("gesture", gesture).put("contentDirection", direction)
            .put("fingerDirection", forward ? "up" : "down").put("gestureBounds", rect(visible))
            .put("fromX", x).put("fromY", from).put("toX", x).put("toY", to).put("gestureDurationMs", duration)
            .put("gestureObservedMs", ms(upAt - gestureAt)).put("settleMs", ms(settledAt - upAt))
            .put("scrollYBefore", scrollBefore).put("scrollYAtRelease", scrollAtRelease).put("scrollYAfter", after)
            .put("scrollDelta", after - scrollBefore).put("postReleaseMotionSampleCount", settled[1])
            .put("postReleaseTravelPixels", settled[2]).put("frames", frameReport)
            .put("sourceFingerprint", snapshot.getString("sourceFingerprint")).put("sourceCodePoints", snapshot.getInt("sourceCodePoints"))
            .put("documentVersionUnchanged", true).put("sourceUnchanged", true).put("sameNativeView", true).put("sameEditable", true);
    }
    private int verticalGesture(EditText editor, float x, float fromY, float toY, long duration, boolean fling) throws Exception {
        long downAt = SystemClock.uptimeMillis();
        gestureEvent(downAt, MotionEvent.ACTION_DOWN, x, fromY);
        int steps = fling ? 8 : 30;
        long start = SystemClock.uptimeMillis();
        for (int step = 1; step <= steps; step++) {
            long target = start + duration * step / steps;
            long wait = target - SystemClock.uptimeMillis(); if (wait > 0) SystemClock.sleep(wait);
            gestureEvent(downAt, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / steps);
        }
        // The last drag movement remains held so this phase measures drag frames,
        // while the fast continuous fling releases with its observed touch velocity.
        if (!fling) SystemClock.sleep(150);
        int atRelease = main(editor::getScrollY);
        gestureEvent(downAt, MotionEvent.ACTION_UP, x, toY);
        return atRelease;
    }
    private void gestureEvent(long downAt, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "SCROLL_TOUCH_REJECTED"); }
        finally { event.recycle(); }
    }
    private int[] awaitScrollSettled(EditText editor, int releasedAt, long releasedTime) throws Exception {
        long until = SystemClock.elapsedRealtime() + 12000;
        int prior = releasedAt, stable = 0, motions = 0, travel = 0;
        while (SystemClock.elapsedRealtime() < until) {
            awaitFrame(editor);
            int current = main(editor::getScrollY);
            if (current == prior) stable++; else { motions++; travel += Math.abs(current - prior); stable = 0; }
            prior = current;
            if (stable >= 8 && SystemClock.elapsedRealtimeNanos() - releasedTime >= 400000000L) return new int[] { current, motions, travel };
        }
        throw new Check("READING_SCROLL_DID_NOT_SETTLE");
    }
    private static Bundle command(String action, String sample) { Bundle value = new Bundle(); value.putString("action", action); value.putString("sampleId", sample); return value; }
    private <T> T main(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) {
            if (failure.get() instanceof Error) throw (Error) failure.get();
            if (failure.get() instanceof Exception) throw (Exception) failure.get();
            throw new Check("MAIN_ACTION_FAILED");
        }
        return value.get();
    }
    private interface Condition { boolean check() throws Exception; }
    private static void await(long timeout, Condition condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) { if (condition.check()) return; SystemClock.sleep(20); }
        throw new Check("UI_STATE_TIMEOUT");
    }
    private static EditText findEditor(View root) {
        if (root instanceof EditText) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { EditText found = findEditor(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private long click(String label) throws Exception {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        await(15000, () -> {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            Rect found = root == null ? null : labelBounds(root, label); bounds.set(found); return found != null && !found.isEmpty();
        });
        Rect value = bounds.get(); return touch(value.exactCenterX(), value.exactCenterY(), 45);
    }
    private static Rect labelBounds(AccessibilityNodeInfo node, String label) {
        if (node.isVisibleToUser() && node.isEnabled() && (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
                label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); if (!bounds.isEmpty()) return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child != null) { Rect found = labelBounds(child, label); if (found != null) return found; }
        }
        return null;
    }
    private void checkVendor(Activity activity) {
        require("PHQ110".equals(android.os.Build.MODEL), "VENDOR_MODEL_MISMATCH");
        require("com.baidu.input_oppo/.ImeService".equals(Settings.Secure.getString(getTargetContext().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)), "VENDOR_IME_MISMATCH");
        require(getTargetContext().getResources().getDisplayMetrics().widthPixels == 1080 && activity.getWindowManager().getCurrentWindowMetrics().getBounds().height() == 2412, "VENDOR_GEOMETRY_MISMATCH");
    }
    private static final class KeyboardSurface {
        final AccessibilityNodeInfo root; final Rect bounds;
        KeyboardSurface(AccessibilityNodeInfo root, Rect bounds) { this.root = root; this.bounds = bounds; }
    }
    private KeyboardSurface keyboardSurface() throws Exception {
        List<KeyboardSurface> matches = new ArrayList<>();
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !IME_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) matches.add(new KeyboardSurface(root, bounds));
        }
        Rect screen = main(() -> measurementActivity.getWindowManager().getCurrentWindowMetrics().getBounds());
        int actualBottom = main(() -> measurementEditor.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom);
        // PHQ110 may expose an accessibility IME window translated below the display
        // during its animation. Only on-screen bounds are usable for physical touch.
        matches.removeIf(surface -> !screen.contains(surface.bounds) || Math.abs(surface.bounds.top - (screen.bottom - actualBottom)) > 4);
        if (matches.isEmpty()) return main(() -> {
            require(measurementActivity != null && measurementEditor != null, "NO_MEASUREMENT_EDITOR");
            WindowInsets insets = measurementEditor.getRootWindowInsets();
            require(insets != null && insets.isVisible(WindowInsets.Type.ime()), "VENDOR_IME_NOT_VISIBLE");
            Rect window = measurementActivity.getWindowManager().getCurrentWindowMetrics().getBounds();
            int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            Rect visible = new Rect(); measurementEditor.getWindowVisibleDisplayFrame(visible);
            require(bottom >= 600 && bottom <= 1200 && Math.abs(visible.bottom - (window.bottom - bottom)) <= 4,
                "VENDOR_IME_INSETS_GEOMETRY_UNSUPPORTED");
            return new KeyboardSurface(null, new Rect(window.left, window.bottom - bottom, window.right, window.bottom));
        });
        require(matches.size() == 1, "VENDOR_IME_WINDOW_NOT_UNIQUE");
        return matches.get(0);
    }
    private void awaitStableKeyboard(EditText editor) throws Exception {
        SystemClock.sleep(1000);
        String previous = null; int stable = 0; long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) {
            KeyboardSurface keyboard = keyboardSurface();
            String geometry = keyboard.bounds.toShortString() + main(() -> {
                Rect visible = new Rect(); editor.getWindowVisibleDisplayFrame(visible);
                WindowInsets insets = editor.getRootWindowInsets();
                return ":" + visible.toShortString() + ":" + (insets == null ? -1 : insets.getInsets(WindowInsets.Type.ime()).bottom);
            });
            stable = geometry.equals(previous) ? stable + 1 : 0; previous = geometry;
            if (stable >= 3) return;
            awaitFrame(editor);
        }
        throw new Check("VENDOR_IME_GEOMETRY_NOT_SETTLED");
    }
    private void awaitFrame(EditText editor) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        main(() -> { editor.postOnAnimation(frame::countDown); return null; });
        require(frame.await(2, TimeUnit.SECONDS), "NATIVE_ANIMATION_FRAME_TIMEOUT");
    }
    private List<Rect> fixedImeTargets(KeyboardSurface keyboard, String fixedLabel) {
        List<Rect> found = new ArrayList<>();
        if (keyboard.root != null) collectFixedImeTargets(keyboard.root, keyboard.bounds, fixedLabel, found);
        return found;
    }
    private void collectFixedImeTargets(AccessibilityNodeInfo node, Rect window, String fixedLabel, List<Rect> found) {
        CharSequence text = node.getText(); CharSequence description = node.getContentDescription();
        boolean exact = fixedLabel.equalsIgnoreCase(text == null ? "" : text.toString().trim()) ||
            fixedLabel.equalsIgnoreCase(description == null ? "" : description.toString().trim());
        if (node.isVisibleToUser() && node.isEnabled() && exact &&
            IME_PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && window.contains(bounds.centerX(), bounds.centerY()) &&
                bounds.width() < window.width() * .6 && bounds.height() < window.height() * .4) {
                boolean duplicate = false; for (Rect existing : found) if (existing.equals(bounds)) duplicate = true;
                if (!duplicate) found.add(bounds);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectFixedImeTargets(child, window, fixedLabel, found); }
    }
    /** Reference bounds are from the saved PHQ110/Baidu synthetic-fixture image.
     * Rebase both axes to the actual TYPE_INPUT_METHOD window; never reuse screen Y.
     * Reject floating/full-screen/unknown-size surfaces instead of guessing a key.
     */
    private float[] relativeObservedPoint(KeyboardSurface keyboard, float referenceX, float referenceY) {
        Rect bounds = keyboard.bounds;
        require(bounds.width() >= 900 && bounds.width() <= 1200 && bounds.height() >= 600 && bounds.height() <= 1200 &&
            bounds.top >= 900 && bounds.bottom <= 2412 && bounds.left >= 0 && bounds.right <= 1080,
            "VENDOR_IME_RELATIVE_MAP_GEOMETRY_UNSUPPORTED");
        float x = bounds.left + referenceX / 1080f * bounds.width();
        float y = bounds.top + (referenceY - 1506f) / 906f * bounds.height();
        require(bounds.contains((int) x, (int) y), "VENDOR_TOUCH_OUTSIDE_ACTUAL_IME_WINDOW");
        return new float[] { x, y };
    }
    private void tapKey(char letter, JSONArray diagnostics) throws Exception {
        KeyboardSurface keyboard = keyboardSurface();
        List<Rect> targets = fixedImeTargets(keyboard, Character.toString(letter));
        JSONObject diagnostic = new JSONObject().put("fixedLabel", Character.toString(letter))
            .put("exactMatchCount", targets.size()).put("imeWindowBounds", rect(keyboard.bounds));
        diagnostics.put(diagnostic);
        float[] point;
        if (targets.size() == 1) {
            Rect bounds = targets.get(0); point = new float[] { bounds.exactCenterX(), bounds.exactCenterY() };
            diagnostic.put("method", "unique-accessible-label").put("bounds", rect(bounds));
        } else {
            require(targets.isEmpty(), "VENDOR_FIXED_LETTER_NOT_UNIQUE");
            String[] rows = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
            float[] starts = { 80f, 132f, 234f }; float[] ys = { 1730f, 1880f, 2040f };
            point = null;
            for (int i = 0; i < rows.length; i++) { int column = rows[i].indexOf(letter);
                if (column >= 0) point = relativeObservedPoint(keyboard, starts[i] + 102.5f * column, ys[i]); }
            require(point != null, "INVALID_TOUCH_KEY");
            diagnostic.put("method", "observed-map-relative-to-live-ime-window");
        }
        diagnostic.put("touchX", point[0]).put("touchY", point[1]);
        touch(point[0], point[1], 45); SystemClock.sleep(55);
    }
    private static boolean plannedPinyin(EditText editor) {
        int start = BaseInputConnection.getComposingSpanStart(editor.getText());
        int end = BaseInputConnection.getComposingSpanEnd(editor.getText());
        if (start < 0 || end < start || end > editor.length()) return false;
        String pinyin = editor.getText().subSequence(start, end).toString().toLowerCase(java.util.Locale.ROOT)
            .replace("'", "").replace("’", "");
        return "nihao".equals(pinyin);
    }
    private JSONObject touchDiagnostic(EditText editor, String sample, int sourceAt, int nativeAt, String phase) throws Exception {
        JSONObject result = main(() -> {
            int composingStart = BaseInputConnection.getComposingSpanStart(editor.getText());
            int composingEnd = BaseInputConnection.getComposingSpanEnd(editor.getText());
            Rect visible = new Rect(); editor.getWindowVisibleDisplayFrame(visible);
            int[] origin = new int[2]; editor.getLocationOnScreen(origin);
            WindowInsets insets = editor.getRootWindowInsets();
            JSONObject value = new JSONObject().put("sampleId", sample).put("phase", phase)
                .put("plannedSourceOffset", sourceAt).put("plannedNativeOffset", nativeAt)
                .put("nativeSelectionStart", editor.getSelectionStart()).put("nativeSelectionEnd", editor.getSelectionEnd())
                .put("composingStart", composingStart).put("composingEnd", composingEnd)
                .put("windowVisibleDisplayFrame", rect(visible)).put("viewBounds", rect(new Rect(origin[0], origin[1], origin[0] + editor.getWidth(), origin[1] + editor.getHeight())))
                .put("scrollX", editor.getScrollX()).put("scrollY", editor.getScrollY());
            if (insets != null) value.put("imeInsetsTop", insets.getInsets(WindowInsets.Type.ime()).top)
                .put("imeInsetsBottom", insets.getInsets(WindowInsets.Type.ime()).bottom).put("imeVisible", insets.isVisible(WindowInsets.Type.ime()));
            String text = editor.getText().toString();
            int from = Math.max(0, editor.getSelectionEnd() - 24);
            value.put("syntheticNativeWindowStart", from).put("syntheticNativeWindowCodePoints", codePoints(text.substring(from, Math.min(text.length(), from + 64))));
            if (composingStart >= 0 && composingEnd >= composingStart && composingEnd <= text.length())
                value.put("syntheticComposingCodePoints", codePoints(text.substring(composingStart, composingEnd)));
            return value;
        });
        int imeWindows = 0; JSONArray bounds = new JSONArray();
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            imeWindows++; Rect value = new Rect(); window.getBoundsInScreen(value); bounds.put(rect(value));
        }
        return result.put("inputMethodWindowCount", imeWindows).put("inputMethodWindowBounds", bounds);
    }
    private static JSONArray codePoints(String text) {
        JSONArray points = new JSONArray(); int offset = 0;
        while (offset < text.length() && points.length() < 64) { int point = text.codePointAt(offset); points.put(point); offset += Character.charCount(point); }
        return points;
    }
    private static JSONObject rect(Rect value) throws Exception {
        return new JSONObject().put("left", value.left).put("top", value.top).put("right", value.right).put("bottom", value.bottom);
    }
    private long touch(float x, float y, long duration) {
        long uptime = SystemClock.uptimeMillis(); long at = SystemClock.elapsedRealtimeNanos();
        MotionEvent down = MotionEvent.obtain(uptime, uptime, MotionEvent.ACTION_DOWN, x, y, 0); down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(down, true), "TOUCH_DOWN_REJECTED"); } finally { down.recycle(); }
        SystemClock.sleep(duration);
        MotionEvent up = MotionEvent.obtain(uptime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(up, true), "TOUCH_UP_REJECTED"); } finally { up.recycle(); }
        return at;
    }

    private static final class Events extends ResultReceiver {
        private final CopyOnWriteArrayList<Bundle> values = new CopyOnWriteArrayList<>();
        Events() { super(new Handler(Looper.getMainLooper())); }
        @Override protected void onReceiveResult(int code, Bundle data) { if (data != null) values.add(new Bundle(data)); }
        Bundle await(String event, long after, String sample, long timeout) {
            long until = SystemClock.elapsedRealtime() + timeout;
            while (SystemClock.elapsedRealtime() < until) {
                for (Bundle value : values) {
                    if ("error".equals(value.getString("event")) && (time(value) < 0 || time(value) >= after)) throw new Check("TARGET_HARNESS_ERROR");
                    if (event.equals(value.getString("event")) && time(value) >= after && (sample == null || sample.equals(value.getString("sampleId")))) return value;
                }
                SystemClock.sleep(10);
            }
            throw new Check("MISSING_EVENT_" + event.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static final class Frames implements AutoCloseable {
        private final Window window;
        private final HandlerThread thread = new HandlerThread("note-release-platform-frames");
        private final CopyOnWriteArrayList<Phase> phases = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<long[]> samples = new CopyOnWriteArrayList<>();
        private final Window.OnFrameMetricsAvailableListener listener = (ignored, metric, dropped) -> {
            long vsync = metric.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP); int phase = -1;
            for (int i = 0; i < phases.size(); i++) { if (phases.get(i).at <= vsync) phase = i; }
            if (phase >= 0) samples.add(new long[] { phase, metric.getMetric(FrameMetrics.TOTAL_DURATION), metric.getMetric(FrameMetrics.DEADLINE),
                metric.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), metric.getMetric(FrameMetrics.DRAW_DURATION), dropped });
        };
        Frames(Window window) { this.window = window; thread.start(); window.addOnFrameMetricsAvailableListener(listener, new Handler(thread.getLooper())); }
        void phase(String name) { phases.add(new Phase(System.nanoTime(), name)); }
        JSONObject report() throws Exception {
            return report(null);
        }
        void awaitCallbacks() throws Exception {
            CountDownLatch drained = new CountDownLatch(1);
            new Handler(thread.getLooper()).post(drained::countDown);
            require(drained.await(2000, TimeUnit.MILLISECONDS), "FRAME_CALLBACK_DRAIN_TIMEOUT");
        }
        JSONObject report(String onlyPhase) throws Exception {
            List<Double> durations = new ArrayList<>(); List<Double> overruns = new ArrayList<>(); JSONArray data = new JSONArray(); int missed = 0; long dropped = 0;
            for (long[] item : samples) {
                String phase = phases.get((int) item[0]).name;
                if (onlyPhase != null && !onlyPhase.equals(phase)) continue;
                data.put(new JSONObject().put("phase", phase).put("totalNs", item[1]).put("deadlineNs", item[2])
                    .put("layoutNs", item[3]).put("drawNs", item[4]).put("droppedCallbacks", item[5])); dropped += item[5];
                if (item[1] >= 0 && item[2] > 0) { durations.add(ms(item[1])); overruns.add(ms(item[1] - item[2])); if (item[1] >= item[2]) missed++; }
            }
            return new JSONObject().put("count", data.length()).put("validDeadlineFrames", durations.size()).put("missedDeadlineCount", missed)
                .put("unknownDeadlineFrames", data.length() - durations.size()).put("droppedMetricsCallbacks", dropped)
                .put("totalDurationMs", statistics(durations)).put("overrunMs", statistics(overruns)).put("samples", data);
        }
        @Override public void close() { window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely(); }
    }
    private static final class Phase { final long at; final String name; Phase(long at, String name) { this.at = at; this.name = name; } }

    private static final class Memory implements AutoCloseable {
        private final AtomicBoolean active = new AtomicBoolean(); private final CopyOnWriteArrayList<long[]> values = new CopyOnWriteArrayList<>(); private Thread worker;
        void start() {
            active.set(true); worker = new Thread(() -> {
                while (active.get()) {
                    java.lang.Runtime runtime = java.lang.Runtime.getRuntime(); Map<String, String> stats = Debug.getRuntimeStats();
                    values.add(new long[] { SystemClock.elapsedRealtimeNanos(), runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(), Debug.getPss(),
                        number(stats.get("art.gc.gc-count")), number(stats.get("art.gc.blocking-gc-count")), number(stats.get("art.gc.gc-time")) });
                    SystemClock.sleep(250);
                }
            }, "note-release-platform-memory"); worker.setDaemon(true); worker.start();
        }
        JSONObject report() throws Exception {
            List<long[]> copy = new ArrayList<>(values); JSONArray data = new JSONArray(); long javaPeak = -1, nativePeak = -1, pssPeak = -1;
            for (long[] item : copy) { javaPeak = Math.max(javaPeak, item[1]); nativePeak = Math.max(nativePeak, item[2]); pssPeak = Math.max(pssPeak, item[3]);
                data.put(new JSONObject().put("atNanos", item[0]).put("javaUsedBytes", item[1]).put("nativeAllocatedBytes", item[2]).put("pssKb", item[3])
                    .put("gcCount", item[4]).put("blockingGcCount", item[5]).put("gcTimeMs", item[6])); }
            return new JSONObject().put("sampleIntervalMs", 250).put("sampleCount", copy.size())
                .put("peakScope", "Sampled peaks including instrumentation/fixture overhead; not an absolute process peak")
                .put("javaUsedBytesSampledPeak", javaPeak).put("nativeAllocatedBytesSampledPeak", nativePeak).put("pssKbSampledPeak", pssPeak)
                .put("gcCountDelta", delta(copy, 4)).put("blockingGcCountDelta", delta(copy, 5)).put("gcTimeMsDelta", delta(copy, 6)).put("samples", data);
        }
        @Override public void close() { active.set(false); if (worker != null) try { worker.join(1000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
        private static long number(String value) { try { return Long.parseLong(value); } catch (Exception ignored) { return -1; } }
        private static long delta(List<long[]> data, int key) { return data.isEmpty() || data.get(0)[key] < 0 || data.get(data.size() - 1)[key] < 0 ? -1 : data.get(data.size() - 1)[key] - data.get(0)[key]; }
    }

    private static JSONObject summarize(JSONArray rounds) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[] { "frameworkMs", "readableMs", "readToEditableMs", "editToReadingMs" }) {
            List<Double> values = new ArrayList<>(); for (int i = 0; i < rounds.length(); i++) values.add(rounds.getJSONObject(i).getDouble(key)); result.put(key, statistics(values));
        }
        for (String position : new String[] { "head", "middle", "tail" }) {
            List<Double> accepted = new ArrayList<>(); List<Double> drawn = new ArrayList<>();
            for (int i = 0; i < rounds.length(); i++) { JSONArray inputs = rounds.getJSONObject(i).getJSONArray("inputs");
                for (int j = 0; j < inputs.length(); j++) { JSONObject input = inputs.getJSONObject(j); if (position.equals(input.getString("position"))) {
                    accepted.add(input.getDouble("inputToAcceptedMs")); drawn.add(input.getDouble("inputToObservedDrawMs")); } } }
            result.put(position, new JSONObject().put("inputToAcceptedMs", statistics(accepted)).put("inputToObservedDrawMs", statistics(drawn)));
        }
        return result;
    }
    private static JSONObject statistics(List<Double> values) throws Exception {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted); JSONObject result = new JSONObject().put("n", sorted.size());
        if (sorted.isEmpty()) return result.put("median", JSONObject.NULL).put("p95", JSONObject.NULL);
        int middle = sorted.size() / 2; double median = sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        return result.put("median", median).put("p95", sorted.get((int) Math.ceil(.95 * sorted.size()) - 1)).put("min", sorted.get(0)).put("max", sorted.get(sorted.size() - 1));
    }
    private static String fixture(String scenario) {
        String tail = "长文最后一行END"; StringBuilder source = new StringBuilder("生产长文验收\n\n");
        for (int i = 0; i < 2860; i++) source.append("第").append(i).append("段。中文🙂连续阅读编辑的合成正文，用于首中尾输入与完整末尾验证。\n\n");
        if ("image".equals(scenario)) {
            StringBuilder images = new StringBuilder(); for (int i = 0; i < 24; i++) images.append("\n\n![合成图片](assets/perf-image.png)\n\n");
            int end = source.offsetByCodePoints(source.length(), -images.codePointCount(0, images.length())); source.setLength(end); source.append(images);
        }
        return source.append(tail).toString();
    }
    private void writeReport(String name, JSONObject report) throws Exception {
        File directory = new File(getTargetContext().getExternalFilesDir(null), "note-editor-release/" + runId);
        require(directory.mkdirs() || directory.isDirectory(), "REPORT_DIRECTORY_FAILED");
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".json"))) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }
    private static void exact(Bundle value, String source) throws Exception {
        require(fingerprint(source).equals(value.getString("sourceFingerprint")), "SOURCE_FINGERPRINT_MISMATCH");
        require(source.codePointCount(0, source.length()) == value.getInt("sourceCodePoints", -1), "SOURCE_CODEPOINT_MISMATCH");
    }
    private static String fingerprint(String source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16)); return result.toString();
    }
    private static long time(Bundle value) { return value.getLong("atNanos", -1L); }
    private static double ms(long nanos) { return nanos / 1000000.0; }
    private static void require(boolean value, String code) { if (!value) throw new Check(code); }
    private static String failureCode(Throwable error) { return error instanceof Check ? ((Check) error).code : error.getClass().getSimpleName(); }
    private static final class Check extends AssertionError { final String code; Check(String code) { super(code); this.code = code; } }
}
