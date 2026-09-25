// Root build file: plugins declared once here, applied in the modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Optional: move every build directory off the repo (the repo sits on a slow 9p mount in WSL).
// wsl-build.sh passes -Pcontour.buildRoot; without it, builds land in <module>/build as usual.
providers.gradleProperty("contour.buildRoot").orNull?.let { layout.buildDirectory.set(file("$it/root")) }
