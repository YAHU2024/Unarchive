# Third-Party Notices (in-APK)

Unarchive Android is distributed under the GNU General Public License, version
3. The license text is in the repository [LICENSE](../../../../LICENSE).

This APK bundles third-party software and model weights. The full license
texts for the components below are provided in this `licenses/` directory or
in the dependency resource named in the table:

| Component | Version | License | File |
| --- | --- | --- | --- |
| sherpa-onnx (Kotlin API + JNI) | v1.13.4 | Apache-2.0 | `apache-2.0.txt` |
| ONNX Runtime (`libonnxruntime.so`) | bundled with sherpa-onnx v1.13.4 | MIT | `onnxruntime-LICENSE.txt` |
| SenseVoice model (`model.int8.onnx`, `tokens.txt`) | 2024-07-17 | FunASR Model Open Source License v1.1 (Alibaba Group) | `funasr-MODEL_LICENSE.txt` |
| Silero VAD (`silero_vad.onnx`) | sherpa-onnx asr-models | MIT (Silero Team) | `silero-vad-LICENSE.txt` |
| FFmpeg / FFmpegKit Maintained | FFmpegKit 8.1.7 | LGPL-3.0 | `res/raw/license.txt` bundled by the AAR |
| smart-exception Java/common | 0.2.1 | BSD-3-Clause (Taner Sener) | `smart-exception-BSD-3-Clause.txt` |
| AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines | dependency versions in `android/app/build.gradle.kts` | Apache-2.0 | `apache-2.0.txt` |

## Model attribution (required by FunASR Model License v1.1 §2.2)

- SenseVoice model: **FunASR / Alibaba Group / SenseVoice**, 2023-2028.
  The model name "SenseVoice" is retained in this application.
- Silero VAD: **Silero Team**, MIT License, Copyright (c) 2020-present.

## Notice

This product includes software developed by The Apache Software Foundation
(Apache-2.0 components), Microsoft Corporation (ONNX Runtime), Alibaba Group
(SenseVoice / FunASR), the Silero Team (Silero VAD), and the FFmpeg and
FFmpegKit contributors.

FFmpegKit Maintained source: `https://github.com/ffmpegkit-maintained/ffmpeg`
(artifact group `dev.ffmpegkit-maintained`, version 8.1.7).

Source code and exact dependency versions for a specific release are recorded
in the release SBOM and corresponding-source archive distributed alongside
this APK.
