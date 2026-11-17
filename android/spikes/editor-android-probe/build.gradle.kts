plugins {
    id("com.android.application") version "9.3.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "com.unarchive.editorprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.unarchive.editorprobe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-e0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("benchmark") {
            initWith(getByName("release"))
            // The in-process instrumentation references shared Kotlin/Compose APIs
            // removed by target R8. Keep this non-debuggable profileable lane
            // unshrunk; it is not evidence for a production R8 artifact.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = providers.gradleProperty("probeTestBuildType").orNull ?: "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").kotlin.directories.add("../editor-kernel-probe/src/main/kotlin/com/unarchive/editorkernelprobe/source")
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.2.0")
    implementation("org.jetbrains:markdown:0.7.9")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
