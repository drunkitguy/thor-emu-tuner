import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP and KGP come from the root buildscript classpath / plugins block, so no versions here.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version name/code come from CI (-PversionName=0.1.0 -PversionCode=42); local builds get dev values.
val appVersionName: String = providers.gradleProperty("versionName").orNull ?: "0.0.0-dev"
val appVersionCode: Int = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1

// Signing: CI decodes SIGNING_KEYSTORE_B64 into app/release.jks when that secret exists; otherwise the
// committed PUBLIC test keystore is used (documented in README as NOT secret).
val privateKeystore = file("release.jks")
val publicKeystore = rootProject.file("keystore/thor-emu-tuner-public.jks")
val usePrivateKey = privateKeystore.exists() && System.getenv("SIGNING_STORE_PASSWORD") != null

android {
    namespace = "dev.thoremutuner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.thoremutuner"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (usePrivateKey) {
                storeFile = privateKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                storeFile = publicKeystore
                storePassword = "thoremutuner-public"
                keyAlias = "thoremutuner"
                keyPassword = "thoremutuner-public"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint runs as a separate concern; it must not block release packaging in CI.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
