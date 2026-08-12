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

The native path reads PCM16 WAV across common sample rates and channel counts,
and uses Android's platform codecs for other audio containers. Both paths
normalize output to 16 kHz mono before ASR. Decoding is temporarily limited to 5 minutes until
the long-audio streaming decoder is available. Optional Silero VAD detects
speech, adds up to 500 ms context, and bounds SenseVoice inputs to 30 seconds.
Cancellation is checked between decoding, VAD windows, and ASR segments, and
before and after sherpa's blocking native decode; it cannot interrupt a decode
already in progress. Streaming media decode and durable checkpoints remain later
milestones for long audio.
