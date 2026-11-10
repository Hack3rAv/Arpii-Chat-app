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
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
        }
    }
}

rootProject.name = "SystemUtility"

include(":app")
include(":core")
include(":core-ui")
include(":core-database")
include(":core-network")
include(":core-security")
include(":feature-auth")
include(":feature-chat")
include(":feature-media")
include(":feature-voice")
include(":feature-calls")
include(":feature-settings")
include(":feature-backup")
include(":feature-profile")
