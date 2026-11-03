plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

// Windows PC capabilities for Ciel (storage, cleanup, Gaming Mode, monitoring, health, downloads).
// Pure JVM: logic is tested on any OS with fakes; real behaviour is smoke-tested on Windows in CI.
dependencies {
    api(libs.coroutines.core)
    api(project(":skill-system"))
    api(project(":research-engine"))
}
