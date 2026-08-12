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

After preparing the AAR, building the APK, and extracting the model, connect one
authorized device and run:

```powershell
.\android\scripts\device_acceptance.ps1 `
    -ModelDirectory "D:\path\to\sensevoice-model" `
    -AudioFile "D:\path\to\sample.m4a"
```

The script installs the debug APK and imports the ignored model files into the
app's private storage with `run-as`. It does not
claim acceptance: RTF, memory, thermal behavior, battery delta, transcript
accuracy, and cancellation must still be recorded from the physical device.
