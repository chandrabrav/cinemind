plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is configured only when CI provides the secrets (never committed).
val releaseKeystore: String? = System.getenv("CINEMIND_KEYSTORE_PATH")

// Debug builds are signed with ONE permanent CineMind debug key (a CI secret, never committed), so a
// newer debug APK installs over an older one and keeps projects, settings and downloaded models.
// Without it every CI machine invents a throwaway key and Android rejects the "update"
// (root cause of the uninstall-to-update problem, 2026-11-11).
val debugKeystore: String? = System.getenv("CINEMIND_DEBUG_KEYSTORE_PATH")

android {
    namespace = "com.cinemind.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cinemind.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones are 64-bit ARM; x86_64 is for the CI emulator. Drops unused native code (LiteRT-LM).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (debugKeystore != null) {
            create("stableDebug") {
                storeFile = file(debugKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("CINEMIND_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = "cinemind-debug"
                keyPassword = System.getenv("CINEMIND_DEBUG_KEYSTORE_PASSWORD")
            }
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("CINEMIND_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CINEMIND_KEY_ALIAS")
                keyPassword = System.getenv("CINEMIND_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (debugKeystore != null) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(project(":feature:media"))
    implementation(project(":feature:editor:timeline"))
    implementation(project(":feature:project"))
    implementation(project(":feature:ai:generation"))
    implementation(project(":feature:ai:llm"))
    implementation(project(":feature:export"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:captions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
