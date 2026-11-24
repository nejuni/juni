# Repository build and release

## Local prerequisites

- JDK 17.
- Android SDK Platform 37. Gradle/Android Gradle Plugin resolves the configured NDK and CMake versions.
- Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or create an untracked `local.properties` with `sdk.dir=...`.
- Windows uses `gradlew.bat`; macOS/Linux uses `./gradlew`.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleProdRelease
```

`assembleDebug` builds the dev and prod debug variants. Release APKs are under `app/build/outputs/apk/prod/release/`. Default APK ABIs are arm64-v8a and armeabi-v7a; set `-PapkAbis=arm64-v8a` for one ABI or `-PapkAbis=arm64-v8a,armeabi-v7a` for split APKs plus universal.

## Source ZIPs

When preparing a source ZIP for sharing, always exclude `.cxx` directories, along with `.git`, `.gradle`, `.idea`, and generated `build` directories. `.cxx` contains local native-build state and must never be included in delivered ZIPs.

## GitHub Actions

`.github/workflows/android.yml` runs on `main` pushes, pull requests, `v*` tags, and manual dispatch. It materializes Compose resources from `build-support/composeResources`, installs Temurin JDK 17, derives app version metadata, restores the release key when configured, builds `assembleProdRelease`, uploads APK artifacts, and creates a GitHub Release only for a supplied/pushed tag.

Use strict `vMAJOR.MINOR.PATCH` tags. Version name removes the leading `v`. Version code is `1,000,000 + MAJOR*1,000,000 + MINOR*1,000 + PATCH`, subject to Android's range checks. Untagged builds use the root Gradle defaults and the `-dev` suffix/default development code.

Publish after pushing the desired commit:

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI release signing uses repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. Keep the keystore and credentials private. A tagged release fails if the key is unavailable; branch/PR builds can use the debug key. The workflow does not use Last.fm credentials.

When editing CI, update this guide and `CODEBASE_GUIDE.md`; verify that tag builds set the Settings version and that untagged builds remain build-only.
