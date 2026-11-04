plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.cinemind.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    
    defaultConfig {
        applicationId = "com.cinemind.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xopt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
        jniLibs {
            pickFirsts += listOf(
                "libc++_shared.so",
                "libonnxruntime.so"
            )
        }
    }
    
    buildFeatures {
        compose = true
        viewBinding = true
        dataBinding = false
    }
    
    namespace = "com.cinemind.app"
}dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.animation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.core)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx)
    implementation(libs.ktor.client.logging)
    implementation(libs.coil.compose)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mediapipe.tasks.audio)
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.onnxruntime.android)

    implementation(project( :core:common))
    implementation(project(:core:data))
    implementation(project(:core:domain))
    implementation(project(:core:ui))
    implementation(project(:feature:media))
    implementation(project(:feature:editor:timeline))
    implementation(project(:feature:editor:preview))
    implementation(project(:feature:editor:effects))
    implementation(project(:feature:editor:color))
    implementation(project(:feature:editor:audio))
    implementation(project(:feature:ai:analysis))
    implementation(project(:feature:ai:llm))
    implementation(project(:feature:ai:generation))
    implementation(project(:feature:ai:cloud))
    implementation(project(:feature:project))
    implementation(project(:feature:export))
    implementation(project(:feature:settings))
    implementation(project(:media3))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.rules)
    testImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.contrib)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.testing)
    androidTestImplementation(libs.compose.testing.manifest)
    androidTestImplementation(libs.junit.jupiter)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.robolectric)
    debugImplementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("hilt.disableAggregatingTask", "true")
}

ktlint {
    version = libs.versions.ktlint.get()
    reporters {
        reporter("plain")
        reporter("checkstyle", file("$buildDir/reports/ktlint/checkstyle.xml"))
    }
}

detekt {
    version = libs.versions.detekt.get()
    config = files("$projectDir/../../config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = true
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xopt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt")
}
