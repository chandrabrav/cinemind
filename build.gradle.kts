buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        alias(libs.plugins.android.application)
        alias(libs.plugins.android.library)
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.compose)
        alias(libs.plugins.kotlin.serialization)
        alias(libs.plugins.ksp)
        alias(libs.plugins.hilt)
        alias(libs.plugins.protobuf)
        alias(libs.plugins.ktlint)
        alias(libs.plugins.detekt)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}