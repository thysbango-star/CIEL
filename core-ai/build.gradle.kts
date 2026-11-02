plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.coroutines.core)
    api(project(":memory-engine"))
    api(project(":research-engine"))
    api(project(":communication"))
    api(project(":game-engine"))
}
