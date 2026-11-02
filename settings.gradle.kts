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

rootProject.name = "Ciel"

// Pure-Kotlin (JVM) modules: platform-independent and unit-tested without a device.
include(
    ":core",
    ":ai-providers",
    ":task-engine",
    ":skill-system",
    ":memory-engine",
    ":research-engine",
    ":communication",
    ":game-engine",
    ":core-ai",
    ":testing",
)
// Android application: UI, voice, accessibility, overlay, storage, platform bridges.
include(":app")
