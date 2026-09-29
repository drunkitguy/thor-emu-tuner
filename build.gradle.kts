// Root build. Kotlin plugins are declared here (not applied) so that :core and :app share one
// Kotlin Gradle Plugin. AGP goes on the root buildscript classpath only when :app is part of the
// build, which keeps AGP and KGP in the same classloader while letting :core build without the
// Android SDK or google() access.

buildscript {
    val appIncluded = rootProject.findProject(":app") != null
    repositories {
        if (appIncluded) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        mavenCentral()
    }
    dependencies {
        if (appIncluded) {
            // Literal coordinates: version-catalog accessors are not reliably available inside
            // buildscript {}. Keep in sync with `agp` in gradle/libs.versions.toml (a :core test checks).
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
