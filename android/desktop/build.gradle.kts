plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    application
}

providers.gradleProperty("contour.buildRoot").orNull?.let { layout.buildDirectory.set(file("$it/desktop")) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.chronosauros.contour.desktop.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Dapple.awt.application.name=Contour"
    )
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.ui:ui-desktop:1.7.3")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.7.3")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.8.18")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.8.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
    implementation("org.hid4java:hid4java:0.8.0")
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(libs.kotlin.test)
    testImplementation(project(":core"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val repoRoot = rootProject.projectDir.parentFile

tasks.register<Exec>("packageMacApp") {
    dependsOn("installDist")
    workingDir = repoRoot
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "Contour",
        "--app-version", "1.6.4",
        "--input", "${layout.buildDirectory.get()}/install/desktop/lib",
        "--main-jar", "desktop.jar",
        "--main-class", "io.github.chronosauros.contour.desktop.MainKt",
        "--icon", "docs/images/Contour.icns",
        "--dest", "dist/mac",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dapple.awt.application.name=Contour"
    )
}

tasks.register<Exec>("packageMacDmg") {
    workingDir = repoRoot
    commandLine(
        "jpackage",
        "--type", "dmg",
        "--name", "Contour",
        "--app-version", "1.6.4",
        "--app-image", "dist/mac/Contour.app",
        "--dest", "dist/mac"
    )
}
