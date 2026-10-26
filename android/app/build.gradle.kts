import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

providers.gradleProperty("contour.buildRoot").orNull?.let { layout.buildDirectory.set(file("$it/app")) }

// Release signing, maintainer only: -Pcontour.signing=<file.properties> with storeFile (relative to that file),
// storePassword, keyAlias and keyPassword. Without it the release APK comes out unsigned - sign it with your own key.
val signingFile = providers.gradleProperty("contour.signing").orNull?.let { file(it) }
val signing = signingFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "io.github.chronosauros.contour"
    // Compose BOM 2026.09.00 (compose 1.12.1, lifecycle 2.11.0) requires compiling against API 37; targetSdk stays 36.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.chronosauros.contour"
        minSdk = 28
        targetSdk = 36
        // v0.2 (versionCode 2) is on the Pixel; `install -r` refuses a lower code, and keeps the data only on update.
        versionCode = 34
        versionName = "1.6.1" // literal: F-Droid reads it for update checks
    }

    signingConfigs {
        if (signingFile != null && signing != null) {
            create("release") {
                storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Canonical review build: the release code - R8, not debuggable - that the shell can still profile
        // (src/perf/AndroidManifest.xml), with its own package and launcher name:
        //   android/wsl-build.sh :app:assemblePerf  ->  io.github.chronosauros.contour.perf, "Contour Private"
        // Review hooks in MainActivity work only here and in debug builds. Signed with the build machine's debug key.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            manifestPlaceholders["perfLabel"] = "Contour Private"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // "Contour Beta": pre-release channel. The release build under its own package and name, installed next to
        // Contour and used to test the next stable version before release:
        //   android/wsl-build.sh :app:assembleBeta  ->  io.github.chronosauros.contour.beta, "Contour Beta"
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["betaLabel"] = "Contour Beta"
            matchingFallbacks += "release"
        }
    }

    // No AGP dependency-info block in the APK signature (it is encrypted for Google Play only; F-Droid rejects it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Copies the repo's licence files and PRIVACY.md into the APK (assets/licences/), for the in-app about, privacy and licences screen. */
abstract class CopyLicences : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("licences")
        out.deleteRecursively()
        out.mkdirs()
        sources.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

val copyLicences = tasks.register<CopyLicences>("copyLicences") {
    val repo = rootProject.projectDir.parentFile
    sources.from(repo.resolve("NOTICE"), repo.resolve("THIRD_PARTY_NOTICES.md"), repo.resolve("LICENSE"), repo.resolve("PRIVACY.md"))
}

/**
 * Writes the Android USB attach filter (res/xml/device_filter.xml, decimal ids as the platform requires) from the device records in <repo>/devices:
 * one `<usb-device>` per record, in `usb.listOrder`. The records are the only place a VID:PID is typed (core's GeneratedDevices.USB_IDS comes from
 * the same files), so the filter and the app's recognition cannot drift apart. Offline: groovy.json.JsonSlurper ships with Gradle.
 */
abstract class GenerateUsbDeviceFilter : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val records: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        class Row(val order: Int, val vid: Int, val pid: Int, val name: String)
        fun usbId(v: Any?, at: String): Int = when (v) {
            is Int -> v
            is String -> runCatching { Integer.decode(v) }.getOrNull() ?: throw GradleException("devices/$at \"$v\" is not a number")
            else -> throw GradleException("devices/$at must be a hex string or an integer")
        }
        val rows = records.files.sortedBy { it.name }.map { f ->
            val rec = groovy.json.JsonSlurper().parse(f) as? Map<*, *> ?: throw GradleException("${f.name}: not a JSON object")
            val usb = rec["usb"] as? Map<*, *> ?: throw GradleException("${f.name}: no usb section")
            Row((usb["listOrder"] as? Int) ?: throw GradleException("${f.name}: usb.listOrder must be an integer"),
                usbId(usb["vid"], "${f.name}: usb.vid"), usbId(usb["pid"], "${f.name}: usb.pid"), rec["name"] as? String ?: throw GradleException("${f.name}: no name"))
        }.sortedBy { it.order }
        if (rows.isEmpty()) throw GradleException("no device records in <repo>/devices")
        if (rows.map { it.order }.toSet().size != rows.size) throw GradleException("usb.listOrder is not unique across the device records")
        if (rows.map { it.vid to it.pid }.toSet().size != rows.size) throw GradleException("two device records share one VID:PID")
        val out = outputDir.get().asFile.resolve("xml")
        out.deleteRecursively()
        out.mkdirs()
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
            append("    <!-- GENERATED by :app:generateUsbDeviceFilter from the device records (devices folder) - do not edit; change the record. -->\n")
            for (r in rows) {
                append("    <!-- ${r.name}: USB 0x%04X:0x%04X -->\n".format(r.vid, r.pid))
                append("    <usb-device\n        vendor-id=\"${r.vid}\"\n        product-id=\"${r.pid}\" />\n")
            }
            append("</resources>\n")
        }
        out.resolve("device_filter.xml").writeText(xml)
    }
}

val generateUsbDeviceFilter = tasks.register<GenerateUsbDeviceFilter>("generateUsbDeviceFilter") {
    // Same root rule as repoRoot in core/build.gradle.kts: the repo root is android/.. .
    records.from(fileTree(rootProject.projectDir.parentFile.resolve("devices")) { include("*.json") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicences, CopyLicences::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(generateUsbDeviceFilter, GenerateUsbDeviceFilter::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
}
