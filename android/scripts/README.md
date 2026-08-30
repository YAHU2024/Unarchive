# Local ASR dependencies

The Android app uses sherpa-onnx's Apache-2.0 Kotlin API and JNI package. The
large AAR and SenseVoice model stay outside Git and are copied into ignored
paths. Run the preparation script from PowerShell after installing JDK 17 and
accepting the Android SDK licenses:

```powershell
.\android\scripts\prepare_sherpa.ps1 -SdkRoot "D:\YaHu\Documents\Unarchive\android\.android-sdk"
```

The script pins sherpa-onnx `v1.13.4`, validates the official source/archive
locations through the download itself, repairs Windows-expanded Kotlin API
symlinks, builds the AAR, verifies JNI libraries for all four upstream Android
ABIs, and copies it to `android/app/libs/`.

SenseVoice model files are not fetched implicitly by the Android build. They
must be obtained from the upstream sherpa-onnx model release, reviewed for
their own model license, and placed under the ignored `android/models/` path.
The current multilingual package is about 1.05 GB compressed, so model download
is an explicit benchmark setup step rather than a normal Gradle dependency.

Silero VAD uses the upstream `silero_vad.onnx` model from the sherpa-onnx
`asr-models` release. Store it at the ignored
`android/models/silero-vad/silero_vad.onnx` path. The currently verified file is
643854 bytes with SHA-256
`9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6`.

After preparing the AAR, building the APK, and extracting the model, connect one
authorized device and run:

```powershell
.\android\scripts\device_acceptance.ps1 `
    -ModelDirectory "D:\path\to\sensevoice-model" `
    -VadModel "D:\path\to\silero_vad.onnx" `
    -AudioFile "D:\path\to\sample.m4a"
```

The script installs the debug APK and imports the ignored model files into the
app's private storage with `run-as`. It does not
claim acceptance: RTF, memory, thermal behavior, battery delta, transcript
accuracy, and cancellation must still be recorded from the physical device.

## D4.3 focused gate

For the repeatable portion of the Android redesign acceptance, run:

```powershell
.\android\scripts\d43_focus_acceptance.ps1
```

The runner executes JVM tests, builds the Debug and instrumentation APKs,
installs both with `adb install -r`, launches `MainActivity`, and runs the
focused D1, editor, graph, destination, and security/accessibility Compose
tests. The current PHQ110 gate contains 20 cases; eight cover the Create and
compatibility-entry flow, and four verify 2x-font scroll
reachability, heading/presence/status/live-region/password semantics, default
visual order, and clear-dialog cancellation. Reports are written under the
ignored `android/build/reports/d43-focus/` directory.

It never uninstalls the package or runs `pm clear`. Passing this gate does not
prove that a real receiving application imports Markdown/images or that a
configured ima API succeeds and recovers. WPS opening the shared Markdown and
rendering its embedded Base64 image passed separate manual PHQ110 acceptance
on 2026-08-24. Configured ima success/revision/offline/process recovery also
passes through the separate opt-in real ima gate below.
The accessibility cases are repeatable structural proxies. They do not prove
the words TalkBack actually speaks or the exact on-screen appearance of masked
characters; those two checks remain manual.

## A5-2 device security gate

To verify the Android Keystore migration/clear boundary and private log
retention on one authorized device, run:

```powershell
.\android\scripts\d43_focus_acceptance.ps1 `
    -DeviceSerial 5b14556a `
    -SkipBuild `
    -ReportGroup a52-security `
    -TestClass com.unarchive.android.SecurityDeviceAcceptanceTest
```

The test uses synthetic credential values, verifies that legacy DeepSeek and
SiliconFlow values migrate out of plaintext preferences, clears all four
credential aliases, and restores the original encrypted values in `finally`.
It also verifies log redaction, explicit clearing, and the 1 MiB file reset
boundary. The runner only uses `adb install -r`; it never uninstalls or clears
package data. The source history has no pre-Keystore ima plaintext store, so
ima migration from an older released APK remains unverified. This is a device
implementation gate, not proof of a complete release-upgrade test.

## Notes P1 focused gate

To verify Notes library filtering, user-facing card metadata, and the
pending-material draft action on one authorized device, run:

```powershell
.\android\scripts\notes_p1_focus_acceptance.ps1
```

The runner executes the complete JVM suite, builds both Debug APKs, installs
them with `adb install -r`, and runs only `NotesLibraryUiTest`. Reports are
written under the ignored `android/build/reports/notes-p1-focus/` directory.
It never uninstalls the app or clears package data. This deterministic fixture
does not replace checking real saved notes, screenshots, and destination state
on the device.

## Note cover focused gate

To verify deterministic note-cover presentation and recovery semantics on one
authorized device, run:

```powershell
.\android\scripts\note_cover_focus_acceptance.ps1
```

The runner executes the complete JVM suite, builds both Debug APKs, installs
them with `adb install -r`, and runs only `NoteCoverUiTest`. Its four fixture
cases cover cover-first rendering, chapter-screenshot and placeholder fallback,
retry event dispatch, editor reachability, and 2x-font reachability. Reports are
written under the ignored `android/build/reports/note-cover-focus/` directory.
It never uninstalls the app or clears package data. This gate does not replace
real Bilibili download, force-stop/reopen, offline, or manual appearance checks.

## D4.3 real creation gate

After the deterministic gate passes and the PHQ110 has working Bilibili,
DeepSeek, and model configuration, run the opt-in live workflow:

```powershell
.\android\scripts\d43_live_creation_acceptance.ps1 `
    -DeviceSerial 5b14556a `
    -VideoReference BV1PS42197aM
```

This runner uses Compose semantics instead of screen coordinates or the active
input method. It builds and installs both APKs with `adb install -r`, performs
the real Bilibili-to-knowledge-card flow, opens the current Markdown migration
route, edits the Markdown source, formally saves it, force-stops the app, and
verifies the saved marker after process restart. If a legacy/v3 migration
conflict is shown, the test explicitly chooses the structured projection. The
test is skipped unless the runner supplies the explicit live-acceptance flag,
so it is not part of ordinary automated tests. Reports are written under the
ignored `android/build/reports/d43-live/` directory.

The runner never uninstalls or clears app data. It appends one synthetic,
replaceable `<!-- D4-<timestamp>- -->` Markdown marker as persistence evidence;
the marker contains no real card content. System-share receiving and Base64
image rendering were accepted manually with WPS on PHQ110; ima API recovery
remains an independent live gate.

The current default SiliconFlow model is
`XingChenAGI/XingChenASR-V3.2-Ultra`. Its real PHQ110 path uploads a 16 kHz
mono PCM WAV because the XingChen route rejects the legacy ADTS AAC form. The
client keeps the multipart request minimal (`model` and `file`) and retries
HTTP 503 within a bounded budget. The older
`FunAudioLLM/SenseVoiceSmall` model remains available for explicit existing
selections.

## D4.3 real ima gate

After the live creation gate has saved a fresh formal Markdown revision and the app
has valid ima credentials plus a selected target, run:

```powershell
.\android\scripts\d43_live_ima_acceptance.ps1 `
    -DeviceSerial 5b14556a `
    -VideoReference BV1PS42197aM
```

If the Android profile is intentionally being initialized from the repository's
ignored desktop `.env`, add `-ImportConfigurationFromEnv`. The runner transfers
the values in memory to the Android Keystore and selected-target preferences;
it does not print them, write a plaintext temporary file, or include them in
the report.

This opt-in runner uses the credentials and target already stored by the app
without printing secret values or internal target IDs. It synchronizes the
latest formal Markdown revision through the real ima API, force-stops the app, verifies the
target-scoped durable record in a new process, and checks invalid-to-valid
credential connection recovery without replacing the stored credentials. It
uses only `adb install -r`, never uninstalls, and never clears app data. A run
can create or associate a remote ima note; if the BV note already exists and
the local content revision is newer, ima's additive `append_doc` API is used.
Use `-ExpectRevisionAppend` after saving a new local edit when the previous
revision has already passed this gate; the test then fails unless the real API
path reports that the existing remote note received the appended revision.
For a repeatable acceptance-only revision, use `-CreateRevisionForAppend`; it
updates the local title with a replaceable `D4-IMA-<timestamp>-` prefix and
automatically enables the append assertion before synchronization.
Use `-TestNetworkRecovery` to add a real offline/retry cycle. The runner saves
a fresh local revision, records the device's Wi-Fi and mobile-data state,
temporarily disables both transports, asserts a durable retryable failure,
force-stops the app, restores the original network state in `finally`, and then
requires the real append retry to succeed.
