pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:8.5.0")
            }
            if (requested.id.namespace == "org.jetbrains.kotlin") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "CineMind"

include(":app")
include(":core:common")
include(":core:data")
include(":core:domain")
include(":core:ui")
include(":feature:media")
include(":feature:editor:timeline")
include(":feature:editor:preview")
include(":feature:editor:effects")
include(":feature:editor:color")
include(":feature:editor:audio")
include(":feature:ai:analysis")
include(":feature:ai:llm")
include(":feature:ai:generation")
include(":feature:ai:cloud")
include(":feature:project")
include(":feature:export")
include(":feature:settings")
include(":media3")
include(":benchmark")