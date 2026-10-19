plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // Release-time SBOM generation (org.cyclonedx.bom). Adds the
    // `cyclonedxBom` task; used only by the release packaging scripts.
    id("org.cyclonedx.bom") version "3.2.0" apply false
}
