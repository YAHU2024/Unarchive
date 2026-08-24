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
tests. The current PHQ110 gate contains 11 cases; four verify 2x-font scroll
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
the real Bilibili-to-knowledge-card flow, edits and explicitly saves the note,
force-stops the app, and verifies the saved title after process restart. The
test is skipped unless the runner supplies the explicit live-acceptance flag,
so it is not part of ordinary automated tests. Reports are written under the
ignored `android/build/reports/d43-live/` directory.

The runner never uninstalls or clears app data. It intentionally updates the
accepted note title with one replaceable `D4-<timestamp>-` prefix as persistence
evidence. System-share receiving and Base64 image rendering were accepted
manually with WPS on PHQ110; ima API recovery remains an independent live gate.

## D4.3 real ima gate

After the live creation gate has saved a fresh structured revision and the app
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
latest revision through the real ima API, force-stops the app, verifies the
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
