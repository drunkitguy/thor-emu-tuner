// Thor Emu Tuner build settings.
//
// :core is pure Kotlin/JVM and always builds (the dev sandbox has no Android SDK and cannot reach
// dl.google.com). :app is included only when an Android SDK is detected (CI sets ANDROID_HOME).

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Non-Android artifacts must never be requested from google(): the sandbox cannot reach it.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "thor-emu-tuner"

val skipAndroid = providers.gradleProperty("thor.skipAndroid").orNull == "true"
val androidSdkDetected = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
val androidEnabled = androidSdkDetected && !skipAndroid

include(":core")
if (androidEnabled) {
    include(":app")
}
