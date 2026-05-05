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

rootProject.name = "talon"
include(":composeApp")
// Notification push relay — JVM-only Ktor server. Lives in the
// repo so the design doc, client, and server stay in lockstep.
// Build with `./gradlew :relay:installDist` or `docker build relay/`.
include(":relay")
