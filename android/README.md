# Unarchive Android

The Android source in this repository is licensed under GPL-3.0; see the
repository [LICENSE](../LICENSE). AndroidX, Kotlin, and other dependencies keep
their own licenses. The project-level inventory is in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

This directory contains the independent Android MVP. The accepted local-first
flow processes one Bilibili link, downloads its audio, transcribes it on device,
and persists a timestamped result that can be restored, copied, shared, or rerun.
The local-audio ASR benchmark remains available for engine and device checks.

## Local setup

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 34
- A local `local.properties` file pointing to the Android SDK

The SDK, model files, benchmark recordings, results, and local planning documents
must remain untracked.

The optional sherpa-onnx AAR, SenseVoice model, and Silero VAD model are local
inputs and are not cleared for redistribution by this repository. Before
shipping an APK that contains them, record exact versions/checksums, upstream
licenses and notices, model terms, and the corresponding source package.

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
normalize output to 16 kHz mono before ASR. Decoding is limited to five minutes
until bounded-memory streaming decode and durable resume are available. Optional Silero VAD detects
speech, adds up to 500 ms context, and bounds SenseVoice inputs to 30 seconds.
Cancellation is checked between decoding, VAD windows, and ASR segments, and
before and after sherpa's blocking native decode; it cannot interrupt a decode
already in progress. Saved video results use private atomic JSON files keyed by
platform and canonical video ID. Bilibili audio cache entries include private
metadata without signed CDN URLs, remain reusable for seven days, and can be
explicitly redownloaded from a saved result. Failed refreshes preserve the prior
audio and saved transcript. Legacy audio files without metadata refresh once;
subsequent normal reruns report that the audio cache was reused. Pipeline progress
is monotonic and visually animated across cache, download, and transcription stages.
