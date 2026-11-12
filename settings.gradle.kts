pluginManagement {
    repositories {
        google()
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

rootProject.name = "CineMind"

include(":app")
include(":core:common")
include(":core:data")
include(":core:domain")
include(":core:ui")
include(":feature:media")
include(":feature:editor:timeline")
include(":feature:project")
include(":feature:ai:analysis")
include(":feature:ai:llm")
include(":feature:ai:generation")
include(":feature:ai:cloud")
include(":feature:export")
include(":feature:settings")
include(":feature:captions")
include(":feature:color")

// Scaffolded but empty (build files only, no sources). Re-include each one when
// it gets real code so CI stays green and fast:
//   :feature:editor:preview, :feature:editor:effects, :feature:editor:color,
//   :feature:editor:audio, :media3, :benchmark
