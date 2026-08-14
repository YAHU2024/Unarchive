# Unarchive Android

The Android source in this repository is licensed under GPL-3.0; see the
repository [LICENSE](../LICENSE). AndroidX, Kotlin, and other dependencies keep
their own licenses. The project-level inventory is in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

This directory contains the independent Android MVP. The accepted local-first
flow processes one Bilibili link, downloads its audio, transcribes it on device,
and persists a timestamped result that can be restored, copied, shared, or rerun.
The local-audio ASR benchmark remains available for engine and device checks.

**v0.1.0 released 2026-08-14** (see
[GitHub Releases](https://github.com/YAHU2024/Unarchive/releases)): signed APK,
corresponding-source archive, CycloneDX SBOM, and checksums. Requires Android
8.0+; first launch installs the bundled ASR models without network.

## Local setup

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 34
- A local `local.properties` file pointing to the Android SDK

The SDK, model files, benchmark recordings, results, and local planning documents
must remain untracked.

The sherpa-onnx AAR, SenseVoice model, and Silero VAD model are local inputs and
are not committed to this repository. The AAR is built by
`scripts/prepare_sherpa.ps1`. The model weights are bundled into the APK under
`assets/models/` and copied to the app's private `files/models/` directory on
first install (verified by SHA-256). Before shipping an APK, record exact
versions/checksums, upstream licenses and notices, model terms, and the
corresponding source package.

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Release builds enable R8 shrinking and produce **per-ABI APKs**
(`app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`, `app-x86_64-release.apk`,
`app-x86-release.apk`) plus a universal APK, so a phone only carries its own
native library payload (~184 MB arm64 vs ~274 MB universal with the bundled
model). `scripts/device_acceptance.ps1` picks the debug APK matching the
connected device's ABI automatically.

The APK bundles third-party license texts under
`app/src/main/assets/licenses/` and exposes them in-app via the "查看开源许可"
button in the Models section. Release signing reads
`android/keystore.properties` (git-ignored; see the private keystore custody
notes); absent that file, release builds are unsigned for local validation.
Release-time SBOM generation:

```powershell
.\android\scripts\generate_sbom.ps1
```

This writes a CycloneDX SBOM of the Android dependencies (Gradle
`org.cyclonedx.bom` plugin) plus a reference `pip freeze` under the
git-ignored `release-artifacts/` directory.

Without the ignored local sherpa AAR, the UI uses `PreviewAsrEngine` to validate
audio selection, progress, cancellation, and result rendering. With the AAR and
bundled models present, SenseVoice uses the real CPU recognizer from the private
`files/models/sensevoice-2024-07-17-int8/` directory. A network download of the
models remains available as a fallback for builds without bundled assets and
future model updates; the fallback archive is a zip (native zlib extraction),
built with `scripts/repack_model_archive.ps1` and hosted on this project's
GitHub Release. Legacy tar.bz2 archives are still extracted but are an order of
magnitude slower (pure-Java bzip2), so prefer the zip artifact.

The native path reads WAV files with PCM 8/16/24/32-bit integer, IEEE float
32/64-bit, and WAVE_FORMAT_EXTENSIBLE containers, and uses Android's platform
codecs for other audio containers. Both paths normalize output to 16 kHz mono
before ASR. Decoding accepts up to four hours of input. Optional Silero VAD
detects speech, adds up to 500 ms context, and bounds SenseVoice inputs to 30
seconds. VAD segments longer than 8 seconds are re-split at internal pauses so
each timestamp block stays close to one sentence even when the model emits no
mid-segment punctuation. Inference threads default to the device's exclusive
(big) cores on Android 13+ — sizing the ORT pool to the slow efficiency cores
is the main cause of near-little-core throughput — and can be overridden with
the "推理线程数" selector in the app (or `AsrConfig.numThreads`).
Cancellation is checked between decoding, VAD windows, and ASR segments, and
before and after sherpa's blocking native decode; it cannot interrupt a decode
already in progress. Saved video results use private atomic JSON files keyed by
platform and canonical video ID. Bilibili audio cache entries include private
metadata without signed CDN URLs, remain reusable for seven days, and can be
explicitly redownloaded from a saved result. Failed refreshes preserve the prior
audio and saved transcript. Legacy audio files without metadata refresh once;
subsequent normal reruns report that the audio cache was reused. Pipeline progress
is monotonic and visually animated across cache, download, and transcription stages.
