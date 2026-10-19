plugins {
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val backdropCandidate = providers.gradleProperty("backdropCandidate").orNull == "true"
android {
    namespace = "com.unarchive.lgspike"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.unarchive.lgspike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "lg-a-1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").java.srcDir(if (backdropCandidate) "src/candidate/java" else "src/fallback/java")
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    if (backdropCandidate) implementation("io.github.kyant0:backdrop:2.0.1")
    testImplementation("junit:junit:4.13.2")
}
