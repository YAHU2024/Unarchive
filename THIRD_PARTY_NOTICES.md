# Third-Party Notices

Unarchive is distributed under the GNU General Public License, version 3. The
license text is in [LICENSE](LICENSE). This file records the third-party
components used by the tracked source tree and the release conditions for
optional Android artifacts.

## Python runtime and development dependencies

Versions are intentionally specified by `requirements.txt` ranges. The exact
installed version must be recorded in a release bill of materials.

| Component | Use | License / notice action |
| --- | --- | --- |
| Gradio | Web UI | Apache-2.0; include its upstream notice in a release bundle. |
| Playwright for Python | Browser automation | Apache-2.0; browser binaries are a separate redistribution review. |
| yt-dlp | Media retrieval | Unlicense; preserve upstream attribution and review any separately distributed codecs. |
| faster-whisper | Python ASR wrapper | MIT; preserve its copyright and license notice. |
| CTranslate2 | faster-whisper backend | MIT; preserve its copyright and license notice. |
| httpx | HTTP client | BSD-3-Clause; preserve notice and disclaimer. |
| python-dotenv | Environment loading | BSD-3-Clause; preserve notice and disclaimer. |
| Pydantic | Data validation | MIT; preserve its copyright and license notice. |
| pytest / pytest-asyncio | Tests only | MIT / Apache-2.0 respectively; test-only dependencies are not shipped by the app. |

The table is a project-level inventory, not a substitute for the installed
environment's transitive dependency report. Before a binary release, generate
an exact dependency/SBOM report and include applicable notices for transitive
packages.

## Android tracked dependencies

| Component | Use | License / notice action |
| --- | --- | --- |
| AndroidX Activity, Compose, Material3, Lifecycle | Android UI/runtime | Apache-2.0; include AndroidX notices in release materials. |
| multiplatform-markdown-renderer-m3 | 0.28.0; isolated Android Markdown reading Spike | Apache-2.0; its bundled MarkdownComposer portions retain the upstream MIT attribution. Include the renderer notice and Apache-2.0 text in release materials if the dependency is retained. |
| JetBrains Markdown parser | 0.7.3; transitive renderer dependency | Apache-2.0; preserve upstream notice if the renderer is retained. |
| Kotlin and kotlinx.coroutines | Language/runtime and async work | Apache-2.0; include Kotlin and coroutine notices. |
| FFmpegKit Maintained / FFmpeg 8.1.7 | Fast Android container/audio decode | LGPL-3.0; the AAR bundles the full license and source notice under `res/raw/`. Source: https://github.com/ffmpegkit-maintained/ffmpeg. |
| smart-exception Java/common 0.2.1 | FFmpegKit runtime support | BSD-3-Clause (Copyright 2019-2020 Taner Sener); ship the full license text. |
| JUnit | Android unit tests | Eclipse Public License 1.0; test-only and not shipped in the release APK. |
| org.json | Android unit-test JSON support | Public domain / JSON license terms; retain upstream notice when redistributed. |
| Gradle wrapper | Build tooling | Apache-2.0; the wrapper is tracked for building, not an application runtime dependency. |

## Bundled ASR models and runtime

The ASR runtime and model weights are packaged into the APK. The model weights
live under `android/app/src/main/assets/models/` (kept out of Git via
`.gitignore`) and are bundled into the APK for redistribution. Their exact
versions, source URLs, checksums, and license terms are recorded in the private
`docs/internal/GPL3_ARTIFACT_EVIDENCE.md`.

| Component | Version | License | Attribution / notice action |
| --- | --- | --- | --- |
| sherpa-onnx AAR + JNI (`android/app/libs/sherpa_onnx-release.aar`) | v1.13.4 | Apache-2.0 (bundled `libonnxruntime.so` is MIT) | ship Apache-2.0 text + ONNX Runtime MIT notice in the release APK. |
| SenseVoice model (`model.int8.onnx` + `tokens.txt`) | 2024-07-17 | FunASR Model Open Source License Agreement v1.1 (Alibaba 2023-2028) | attribute FunASR/Alibaba/SenseVoice and retain the "SenseVoice" model name in the app. No non-commercial clause, but no warranty. |
| Silero VAD (`silero_vad.onnx`) | sherpa-onnx `asr-models` | MIT (Silero Team) | ship MIT text and copyright notice in the release APK. |

A release must additionally freeze dependency versions, generate an SBOM with
transitive notices, build a corresponding-source package, and retain the model
attribution above in the app's about screen. Do not commit credentials or
downloaded models.

As of 2026-08-16 the Android APK bundles the full license texts under
`android/app/src/main/assets/licenses/` (Apache-2.0, ONNX Runtime MIT, FunASR
Model License v1.1, Silero VAD MIT, smart-exception BSD-3-Clause) plus an
in-APK `THIRD_PARTY_NOTICES.md` summary. FFmpegKit also bundles FFmpeg's
LGPL-3.0 and source notice under `res/raw/`; all are exposed in-app via the
"查看开源许可" button in the Models section. Release-time SBOM generation is scripted at
`android/scripts/generate_sbom.ps1` (Gradle CycloneDX plugin). Signing
keystore custody is documented privately; release builds without
`android/keystore.properties` are unsigned for local validation.

## Upstream application provenance

SubtitleEditforAndroid is GPL-3.0 reference material
(https://github.com/nihaina/SubtitleEditforAndroid). The model-management module
adapts the download -> verify -> atomic-install -> backup-recovery flow from its
`ModelDownloader` (reviewed commit
`ce255c6a37188b594d08e3e74a3ef984baefd3f9`, GNU GPL v3):

- `android/app/src/main/java/com/unarchive/android/model/ModelDownloader.kt` —
  adapts the download/verify/atomic-install/backup-recovery flow; extraction and
  transport are reimplemented (Apache Commons Compress + the project's own HTTPS
  transport) rather than the upstream 7-Zip/OkHttp stack.
- `android/app/src/main/java/com/unarchive/android/model/TarBz2Extractor.kt` —
  follows the upstream `StreamingTarExtractor` path-traversal and duplicate-entry
  defenses, implemented with Apache Commons Compress instead of the upstream
  7-Zip native bundle.

Both files retain the upstream provenance notice (source commit + GNU GPL v3) in
their header comments. No upstream application code has been copied verbatim.
