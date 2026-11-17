# Android editor kernel probe

Standalone APK with no production note access and a separate application ID:
`com.unarchive.editorprobe`. It mounts the same source-range adapter validated by
the JVM probe, Compose Rich Editor 1.2.0 directly, or a plain-source control.
The default `adapted` mode includes source-writeback costs in its timings.

Build from `android/`:

```powershell
.\gradlew.bat -p spikes/editor-android-probe assembleDebug assembleDebugAndroidTest
.\gradlew.bat -p spikes/editor-android-probe -PprobeTestBuildType=benchmark assembleBenchmark assembleBenchmarkAndroidTest
```

`benchmark` is signed with the local debug key, non-debuggable, unshrunk,
and profileable by shell. The in-process runner cannot use shared classes removed
by target R8; this lane does not establish production R8 artifact performance.
Functional debug results must be reported separately
from benchmark frame samples. The test package and target package are isolated
from the existing application. No signing credentials are read from the repo.

The IME case enables a **test-only InputMethodService**, sends `setComposingText`
and `commitText` over the actually served Android `InputConnection`, observes
composition range and cursor, and restores the previous selected IME in `finally`.
It does not establish compatibility with a physical vendor pinyin keyboard,
candidate bar, gesture input, or TalkBack. Do not leave the test IME selected.

The long-document case reports source Unicode code points and UTF-8 bytes,
load-to-draw, actual Window FrameMetrics, eight shell-injected swipes paced over
real wall time, and twelve model-append to draw samples. It verifies a tail
sentinel. Model-append timing is distinct from
IME keystroke latency. Use `-e editorMode plain` to run the plain-source comparison.
Reports contain names, counts, status and timing; no full note text is logged.
`passed=true` means the measurement/functional assertions completed; it does not
mean a latency or frame budget passed. The direct `candidate` mode has its own
20-entry rich-text history and can fail long-document memory stress separately.

Builds do not install. The runner `android/scripts/editor_probe_validation.ps1`
checks the host adapter report, uses an explicit device serial, installs only the
probe packages, restores the original IME in an outer `finally`, and pulls reports
from `Android/data/com.unarchive.editorprobe/files/probe-reports/`.

The `native` mode mounts one continuous Android `EditText/Editable` through the
same incremental source session and bounded transaction history. Platform undo
is disabled through XML so the source session remains the only history owner.
Ordinary edits retain the same buffer; explicit history replay currently reloads
the document and restores its projected selection. Display spans are a limited
heading/bold prototype; protected Markdown and rich clipboard formatting are not
a complete product editor.

After running the host source/incremental/history gates, use these scenarios:

```powershell
.\scripts\editor_probe_validation.ps1 -DeviceSerial <serial> -BuildType benchmark -Scenario E2Selection -EditorMode native
.\scripts\editor_probe_validation.ps1 -DeviceSerial <serial> -BuildType benchmark -Scenario E2Long -EditorMode native -SkipBuild -SkipInstall
.\scripts\editor_probe_validation.ps1 -DeviceSerial <serial> -BuildType benchmark -Scenario E2Ime -EditorMode native -SkipBuild -SkipInstall
```

`NativeLayout`, `NativeSelection`, and `NativeIme` run the isolated layout
controls. `E2Selection` uses actual native clipboard actions on a programmatically
set multi-viewport selection and verifies source/history/selection restoration;
finger drag handles are not tested. `E2Long` times twelve visible head/middle/tail
Editable insertions through source acceptance to drawing; these are distinct
from IME keystroke latency. `-SkipBuild` still installs APKs; use `-SkipInstall`
only when the same built versions are already installed. Clipboard contents and
the selected IME are restored by the tests.

`RealHandles` adds touchscreen long-press and actual end-handle dragging across
paragraphs and a viewport edge, then checks source-exact cut/undo. It does not
construct the dragged range with programmatic selection. `VendorLong` taps the
observed PHQ110 Baidu full-pinyin QWERTY keyboard and candidate bar at document
head/middle/tail; it checks every commit, backspace and Enter against source and
caret expectations. This coordinate map is specific to the observed 1080x2412
device/layout and fails rather than claiming generic keyboard compatibility.

```powershell
.\scripts\editor_probe_validation.ps1 -DeviceSerial <serial> -BuildType benchmark -Scenario RealHandles -EditorMode native
.\scripts\editor_probe_validation.ps1 -DeviceSerial <serial> -BuildType benchmark -Scenario VendorLong -EditorMode native -SkipBuild -SkipInstall
```

These are bounded real-interaction smoke tests. They are distinct from a human
typing/feel assessment and from the served test-IME protocol cases. Reports and
screenshots use only synthetic content, and reusable remote scenario outputs are
cleared before a run so failed runs cannot borrow stale success evidence.
