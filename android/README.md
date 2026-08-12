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

The current UI uses `PreviewAsrEngine` to validate audio selection, engine
selection, progress, cancellation, and result rendering. It does not perform
speech recognition. Native SenseVoice and Whisper adapters will be added as
separate milestones after their dependencies and model licenses are recorded.
