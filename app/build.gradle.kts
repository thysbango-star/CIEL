import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is read from keystore.properties (never committed) or CI environment variables.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "dev.ciel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ciel.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // A fixed debug key committed to the repo (debug keys are not secret). Without it every CI
        // run signs with a new random key, and Android refuses to install the update over the
        // previous build ("App not installed" / "package conflicts").
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val storeFilePath = signingValue("storeFile", "CIEL_KEYSTORE_PATH")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "CIEL_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "CIEL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "CIEL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint {
        // Lint runs in CI and its report is uploaded; it does not block the build.
        abortOnError = false
        checkReleaseBuilds = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-ai"))

    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // Offline wake-word ("Ciel") detection. The speech model is downloaded at runtime by the user.
    // "@aar" without transitive deps, as documented by Vosk, so JNA comes only as its Android AAR.
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // On-device text recognition for games (offline, bundled Latin model; nothing is uploaded).
    implementation(libs.mlkit.text)
}
