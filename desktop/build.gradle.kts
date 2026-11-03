import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-ai"))
    implementation(project(":windows-integration"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "dev.ciel.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Ciel"
            packageVersion = "1.0.0"
            description = "Ciel — personal AI assistant for Windows"
            vendor = "Project Ciel"
            includeAllModules = true
            windows {
                menuGroup = "Ciel"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "6f1c7c43-5d2e-4b8a-9a51-0c1e7d9b3a21"
            }
        }
    }
}
