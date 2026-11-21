# BlockVerse Android

BlockVerse **1.4.0** source and Android debug APK automation.

## Add the source archive

Upload this exact file to the repository root (not inside a folder):

`BlockVerse-v1.4.0-Full-Source.zip`

Use the corrected source archive from the ChatGPT conversation. Keep the filename unchanged. The GitHub Actions workflow in `.github/workflows/android-apk.yml` will then run automatically.

## Build pipeline

- Ubuntu GitHub Actions runner
- Eclipse Temurin Java 17
- Gradle 8.9 (compatible with Android Gradle Plugin 8.7.3)
- Runs `testDebugUnitTest`
- Builds `assembleDebug`
- Uploads `app-debug.apk` as the workflow artifact `BlockVerse-1.4.0-debug-apk`

## Download the APK

After the workflow completes successfully, open the repository's **Actions** tab, select the successful **Android APK** run, and download the `BlockVerse-1.4.0-debug-apk` artifact.

This workflow creates a **debug APK**, not a Play Store-signed release APK. Instrumentation tests require a connected emulator/device and are not part of this hosted unit-test job.
