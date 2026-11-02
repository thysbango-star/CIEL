plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.coroutines.core)
    api(project(":core"))
    api(project(":ai-providers"))
    api(project(":task-engine"))
}
