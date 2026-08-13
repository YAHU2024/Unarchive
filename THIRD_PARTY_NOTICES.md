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
| Kotlin and kotlinx.coroutines | Language/runtime and async work | Apache-2.0; include Kotlin and coroutine notices. |
| JUnit | Android unit tests | Eclipse Public License 1.0; test-only and not shipped in the release APK. |
| org.json | Android unit-test JSON support | Public domain / JSON license terms; retain upstream notice when redistributed. |
| Gradle wrapper | Build tooling | Apache-2.0; the wrapper is tracked for building, not an application runtime dependency. |

## Optional local Android artifacts

The following files are ignored and are not part of the public source checkout:

- `android/app/libs/sherpa_onnx-release.aar` (sherpa-onnx v1.13.4 and its
  native libraries); obtain and retain the exact upstream license and native
  notices before placing it in a release APK.
- SenseVoice model and `tokens.txt` files.
- Silero VAD ONNX model.

These artifacts are currently **release blockers**, not cleared third-party
notices. A release that bundles them must record the exact source URL, version,
checksum, license/model terms, attribution, and any source-distribution or
non-commercial restrictions. Do not commit credentials, downloaded models, or
local AARs merely to satisfy this document.

## Upstream application provenance

SubtitleEditforAndroid is GPL-3.0 reference material. No application code has
been copied into this repository. When a module is ported, add its source path,
upstream commit, copyright header, modifications, and test coverage to the
private provenance record before publishing it.
