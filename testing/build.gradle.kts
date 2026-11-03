plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-ai"))
    implementation(project(":windows-integration"))
}

application {
    // Runs every core test: ./gradlew :testing:run
    mainClass.set("dev.ciel.testing.RunAllKt")
}

// Exercises Ciel's Windows features on a real Windows machine: ./gradlew :testing:windowsSmoke
tasks.register<JavaExec>("windowsSmoke") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.ciel.testing.WindowsSmokeKt")
}
