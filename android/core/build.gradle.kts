// :core - pure Kotlin/JVM, zero Android dependencies (model, DSP, text codecs, device protocol).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

providers.gradleProperty("contour.buildRoot").orNull?.let { layout.buildDirectory.set(file("$it/core")) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

// The parity test reads the device captures in research/protocol/ and, when present, the maintainer's profiles
// in a sibling eq-library folder (read-only). Paths are resolved from the repo root, which is android/.. .
val repoRoot: String = rootProject.projectDir.parentFile.absolutePath

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("contour.repoRoot", repoRoot)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
