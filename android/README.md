# Unarchive Android

This directory contains the independent Android MVP. Phase 0 is an ASR benchmark
harness used to select the on-device transcription engine before Bilibili and
analysis workflows are integrated.

## Local setup

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 34
- A local `local.properties` file pointing to the Android SDK

The SDK, model files, benchmark recordings, results, and local planning documents
must remain untracked.

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Without the ignored local sherpa AAR, the UI uses `PreviewAsrEngine` to validate
audio selection, progress, cancellation, and result rendering. After running
`scripts/prepare_sherpa.ps1`, SenseVoice uses the real CPU recognizer when its
`model.int8.onnx` and `tokens.txt` are present in the app's private
`files/models/sensevoice-2024-07-17-int8/` directory.

The first native path accepts 16 kHz mono PCM WAV input. Cancellation is checked
before and after sherpa's blocking native decode; it cannot interrupt a decode
already in progress. Media decoding, VAD segmentation, and finer progress are
later benchmark milestones.
