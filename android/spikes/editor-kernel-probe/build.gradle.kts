plugins {
    kotlin("jvm") version "2.4.10"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // Pinned to an upstream source tag; this standalone probe is not an app dependency.
    implementation("com.mohamedrejeb.richeditor:richeditor-compose-desktop:1.2.0")
    implementation("org.jetbrains.compose.ui:ui-text-desktop:1.12.0")
    implementation("org.jetbrains:markdown-jvm:0.7.9")
}

application {
    mainClass.set("com.unarchive.editorkernelprobe.EditorKernelProbeKt")
}

tasks.named<JavaExec>("run") {
    maxHeapSize = "1g"
    args(layout.buildDirectory.dir("reports/kernel-probe").get().asFile.absolutePath)
}

tasks.register<JavaExec>("sourceAdapterProbe") {
    group = "verification"
    description = "Validate candidate edits against source-preserving Markdown transactions."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.unarchive.editorkernelprobe.source.SourceAdapterProbeKt")
    maxHeapSize = "1g"
    args(layout.buildDirectory.dir("reports/source-adapter").get().asFile.absolutePath)
}

tasks.register<JavaExec>("additionalAdapterProbe") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.unarchive.editorkernelprobe.review.AdditionalAdapterProbeKt")
    args(layout.buildDirectory.dir("reports/source-adapter").get().asFile.absolutePath)
}

listOf(
    "incrementalAdapterProbe" to "IncrementalAdapterProbeKt",
    "boundedHistoryProbe" to "BoundedHistoryProbeKt",
).forEach { (taskName, entry) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("com.unarchive.editorkernelprobe.source.$entry")
        maxHeapSize = "1g"
        args(layout.buildDirectory.dir("reports/e2").get().asFile.absolutePath)
    }
}
