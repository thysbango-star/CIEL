# Building Ciel

## Requirements

- JDK 17
- Android SDK with platform 35 (Android Studio Ladybug or newer installs it automatically)
- Internet access to Google Maven, Maven Central and the Gradle distribution server

Pinned versions (see `gradle/libs.versions.toml`): Gradle 8.11.1 (wrapper), Android Gradle Plugin 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Compose BOM 2024.12.01, Room 2.6.1, Vosk Android 0.3.47. `compileSdk`/`targetSdk` 35, `minSdk` 26 (Android 8.0).

## Option A — GitHub Actions (no local setup)

1. Push this repository to GitHub.
2. Actions → **Build & test** runs automatically: core tests, then `assembleDebug` and lint.
3. Download the **ciel-debug-apk** artifact from the run.

## Option B — Android Studio

1. File → Open → select the repository folder. Let Gradle sync.
2. Run configuration **app** → choose your phone (USB debugging on) → Run.

## Option C — command line

```bash
./gradlew :testing:run          # core test suite
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as **dev.ciel.app.debug**, so it can sit beside a release build.

## Release APK (signed)

1. Create a keystore once (keep it safe — updates must be signed with the same key):
   ```bash
   keytool -genkeypair -v -keystore ciel-release.jks -alias ciel -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Local: copy `keystore.properties.example` to `keystore.properties` (git-ignored), fill it in, run `./gradlew :app:assembleRelease`.
3. CI: add repository secrets `CIEL_KEYSTORE_BASE64` (`base64 -w0 ciel-release.jks`), `CIEL_KEYSTORE_PASSWORD`, `CIEL_KEY_ALIAS`, `CIEL_KEY_PASSWORD`, then push a tag like `v0.1.0`. The **ciel-release-apk** artifact appears on that run.

Without signing configuration, release builds are not produced (the debug APK is fully functional).

## Installing on a phone

1. Copy the APK to the phone (or `adb install`).
2. Open it; allow your file manager/browser to install unknown apps when prompted.
3. Updates: install the newer APK over the old one (same signing key). Your data and keys are kept.

## Building without network access to Maven

The pure-Kotlin modules can be compiled and tested with only a Kotlin compiler (this is how milestone 1 was verified in an environment that blocks Maven):

```bash
# download kotlin-compiler-2.x.zip from https://github.com/JetBrains/kotlin/releases and unzip it
KOTLINC_HOME=/path/to/kotlinc ./scripts/verify-core.sh
```
