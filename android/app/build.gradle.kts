import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

providers.gradleProperty("contour.buildRoot").orNull?.let { layout.buildDirectory.set(file("$it/app")) }

// Shown in the perf build's launcher name ("Contour 1.0"), so the icon itself says which build is on the phone.
val appVersion = "1.0.0"

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
        versionCode = 3
        versionName = appVersion
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
        //   android/wsl-build.sh :app:assemblePerf  ->  io.github.chronosauros.contour.perf, "Contour 1.0"
        // Review hooks in MainActivity work only here and in debug builds. Signed with the build machine's debug key.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            manifestPlaceholders["perfLabel"] = "Contour ${appVersion.substringBeforeLast('.')}"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Copies the repo's licence files into the APK (assets/licences/), for the in-app licences screen. */
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
    sources.from(repo.resolve("NOTICE"), repo.resolve("THIRD_PARTY_NOTICES.md"), repo.resolve("LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicences, CopyLicences::outputDir)
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
