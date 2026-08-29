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

// Lets Gradle fetch a toolchain it can't find locally. The relay
// pins jvmToolchain(17) to match CI and its Dockerfile; a dev box
// with only a newer JDK would otherwise be unable to build or test
// it at all.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "talon"
include(":core")
include(":composeApp")
// Notification push relay — JVM-only Ktor server. Lives in the
// repo so the design doc, client, and server stay in lockstep.
// Build with `./gradlew :relay:installDist` or `docker build relay/`.
include(":relay")
// Headless party-line participant — joins a line with its own ship
// and moves PCM between it and a file (or, later, a stream). Reuses
// :core verbatim. Build with `./gradlew :bridge:installDist`.
include(":bridge")
