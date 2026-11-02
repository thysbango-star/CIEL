plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-ai"))
}

application {
    // Runs every core test: ./gradlew :testing:run
    mainClass.set("dev.ciel.testing.RunAllKt")
}
