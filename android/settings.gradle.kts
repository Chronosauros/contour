pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "contour"
include(":core")
include(":app")
// Bench tool for the measuring rig (maintainer only): android/tools is local-only, the public export drops it.
if (file("tools/bench").isDirectory) {
    include(":bench")
    project(":bench").projectDir = file("tools/bench")
}
