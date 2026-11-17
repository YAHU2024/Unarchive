# LG-A isolated Backdrop compatibility spike

This standalone Gradle build is not included by `android/settings.gradle.kts`.
It uses the app's unchanged Kotlin 2.0.21 / AGP 8.6.1 / Compose BOM 2024.10.01 /
compileSdk 35 / minSdk 26 baseline. Its separate application ID is
`com.unarchive.lgspike`; it never opens app data, credentials, or network APIs.

Run from `android/`, with `JAVA_HOME` and `ANDROID_HOME` configured:

```powershell
# Runnable opaque/simulated control, rule tests, and Release/R8 baseline
.\gradlew.bat -p spikes/lg-a-backdrop testDebugUnitTest assembleDebug assembleRelease --no-daemon

# Real candidate API source, full transitive dependencies, Debug and Release
.\gradlew.bat -p spikes/lg-a-backdrop -PbackdropCandidate=true dependencies --configuration debugRuntimeClasspath assembleDebug assembleRelease --continue --no-daemon
```

The candidate is pinned to `io.github.kyant0:backdrop:2.0.1`, upstream tag
`2.0.1`, commit `65ab177e90e5c1d8c62e70cf7755841982da65f6` (Apache-2.0).
Only `src/candidate/` imports upstream APIs. `BackdropRenderer`, `SurfaceSpec`,
and `SampleMode` are project-owned; the four fixtures share identical content
and callbacks across renderers. Background capture belongs to the renderer.

`backdropCandidate` defaults to false and is an explicit compile experiment.
Resolving the dependency is not a successful candidate compilation. On the
current baseline, published AAR metadata requires compileSdk 37, and transitive
Compose 1.12.0 requires AGP >= 9.1.0. No suppression, forced dependency downgrade,
or toolchain upgrade is applied. The candidate cannot yet produce a runnable
APK, so its visual/performance, APK delta, startup, scrolling, and R8 acceptance
remain unmeasured. Do not present control screenshots as real Backdrop evidence.

API policy: 26–30 uses opaque simulation; 31–32 may use blur only after a
candidate passes compilation; 33+ lens additionally requires explicit opt-in.
Disabled glass, reduced transparency, high contrast, or constrained performance
keep the control renderer. None of these switches persist to the main app.
