import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.cyclonedx.bom")
}

val sherpaAar = file("libs/sherpa_onnx-release.aar")
val sherpaEnabled = sherpaAar.isFile

// Release signing: loaded from the git-ignored android/keystore.properties
// (see android/README.md). Absent properties -> release builds are unsigned,
// which is fine for local validation; sign before publishing.
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) propsFile.inputStream().use(::load)
}

android {
    namespace = "com.unarchive.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unarchive.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("keystoreFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("keystoreFile"))
                storePassword = keystoreProps.getProperty("keystorePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Per-ABI APKs keep the onnxruntime + sherpa .so payload out of every
    // download: a phone only needs its own ABI. The universal APK is kept for
    // sideloading and the release workflow.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Extract .so on install instead of mapping them inside the APK:
            // APK-internal RELRO fails for the large FFmpeg libs
            // ("can't enable GNU RELRO protection ... Out of memory").
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    defaultConfig {
        buildConfigField("boolean", "SHERPA_ENABLED", sherpaEnabled.toString())
    }

    sourceSets {
        if (sherpaEnabled) {
            getByName("main").java.srcDir("src/sherpa/java")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // tar.bz2 extraction for on-device model downloads (SenseVoice archive).
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Fast container decode: the software c2.android.aac decoder is only
    // ~7-10x realtime on the PHQ110, while FFmpeg 8.1.7 decoded the same
    // normalized 742 s sample in 0.59-0.63 s with an identical sample count.
    // Community-maintained fork (dev.ffmpegkit-maintained, original retired
    // 2025-01); min build contains FFmpeg's built-in AAC decoder.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7")
    // ffmpeg-kit runtime dependency (its AAR does not pull it transitively).
    implementation("com.arthenica:smart-exception-java:0.2.1")
    if (sherpaEnabled) {
        implementation(files(sherpaAar))
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
