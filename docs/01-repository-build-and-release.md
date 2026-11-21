# 01 — Repository map, build system and release workflow

This is the first chapter of the Juni codebase documentation. It explains how the
repository is put together, what every root-level file does, how Gradle turns the
three modules into an installable Android app, how the native libraries are built,
and how GitHub Actions produces signed APKs. Later chapters zoom into the app,
the shared modules, the native code, resources, and tests.

Read this chapter first. It gives you the vocabulary and the build commands that
the other chapters assume.

---

## 1. What Juni is

Juni is an Android music player focused on the device's own music library. It can
scan local audio, show albums/artists/folders, manage device playlists, play
lyrics (including word-synced lyrics), remember listening statistics, show Replay
cards, control playback from widgets and Android Auto, and drive USB DACs and
bit-perfect output. Optional online pieces remain for lyrics lookup, translation,
artist artwork/canvas experiments, and Last.fm scrobbling.

The project is a community fork of BitChord. The app ID is `com.nejuni.juni`; the
source path still uses `app/src/main/java/com/mus/io`, while the Kotlin package
declarations use `com.nejuni.juni`. That mismatch is intentional and appears
throughout the repository: always check the `package` line in a file rather than
inferring the package from its directory path.

Important reality check: `CODEBASE_GUIDE.md` and several source comments describe
features that have since been removed (YouTube Music/InnerTube streaming, add-on
sources, downloads, Cast, Listen Together, Wear OS, Discord, an in-app updater,
and others). The actual files in this checkout are the source of truth. Where a
comment says "the watch app" or "YouTube login", treat it as historical context,
not as a description of current behavior. Each chapter notes significant cases.

---

## 2. The top-level tree

```
juni/
├── .github/workflows/android.yml   CI: build + release APKs
├── app/                            Android application module
├── build-support/                  plain-file mirror of Compose resources
├── gradle/wrapper/                 Gradle wrapper JAR + pinned distribution
├── native/                         shared C/C++ sources (analysis, loop mix, FFmpeg)
├── shared/                         Kotlin Multiplatform domain/data module
├── sharedUi/                       Compose Multiplatform UI module
├── build.gradle.kts                root build, plugin + R8 versions
├── settings.gradle.kts             module inclusion (app only if SDK exists)
├── gradle.properties               JVM/AndroidX/Kotlin flags
├── gradlew / gradlew.bat           Gradle launchers
├── CODEBASE_GUIDE.md               historical review notes (198 KB)
├── README.md                       user/developer overview
├── CODE_OF_CONDUCT.md              community rules
├── TRADEMARKS.md                   name/icon usage policy
├── LICENSE                          GPL-3.0-only
└── keystore.properties.example     release-signing template
```

The repository has about 570 text files. Roughly 380 are Kotlin, 120 are XML
(including 19 Android locale catalogs and 19 Compose locale catalogs), 40 are
C/C++ headers and sources, plus Gradle, Java and Markdown files. Binary assets
(ONNX models, fonts, launcher PNG/WebP files, prebuilt `libffmpegJNI.so`) are
inventoried in chapter 13.

### Source-set shape

The two library modules use a non-standard source set named `jvmSharedMain`:

- `commonMain` — pure Kotlin, no Android or Compose dependencies.
- `jvmSharedMain` — JVM code shared by Android and desktop; depends on `commonMain`.
- `androidMain` / `jvmMain` — thin platform adapters that depend on `jvmSharedMain`.

That means most "shared" code is written against the JVM, not against Android,
and is shared between the phone and a possible desktop target. The app module
(`app`) is the only Android application in this checkout; there is no desktop
application module in `settings.gradle.kts`.

---

## 3. Root files, one by one

### `build.gradle.kts`

The root Gradle file does three jobs:

1. **Pins R8 to 8.13.23.** AGP 8.10.1 bundles an older R8. The comment explains a
   real Dex register-allocation bug in 8.10.x that miscompiled the very large
   `NowPlayingScreen`, producing an `ART VerifyError` in dev builds. 8.13.23
   also understands Kotlin 2.3 metadata. Remove this override only after AGP
   bundles an R8 newer than 8.11.32.
2. **Defines the app version in one place.** `juniVersionCode` defaults to
   `999999` and `juniVersionName` to `1.0.0-dev`. CI overrides both with `-P`
   properties. `juniBetaSuffix` is applied as a `versionNameSuffix` in both debug
   and release builds so a sideloaded beta is still recognized as a pre-release.
3. **Declares plugin versions** for Android application/library, Kotlin
   Android/Multiplatform/JVM/Compose/serialization, and Compose Multiplatform
   (`1.10.3`).

### `settings.gradle.kts`

- `rootProject.name = "Juni"`.
- Always includes `:shared` and `:sharedUi`.
- Includes `:app` only if it can find an Android SDK: `local.properties`
  `sdk.dir` (parsed as a Java `Properties` file so Windows escaping works), then
  `ANDROID_HOME`, then `ANDROID_SDK_ROOT`, and only if the path is a directory.
  Without an SDK the build logs "building the desktop targets only" and skips the
  app module. This keeps library-only machines usable.
- Repositories: Google, Maven Central, and JitPack.

### `gradle.properties`

- 4 GB Gradle heap, UTF-8 source encoding, IPv4 preference for downloads.
- AndroidX and non-transitive R classes.
- Kotlin official style.
- Suppresses the AGP warning for compile SDK 36/37.

### `gradlew`, `gradlew.bat`, `gradle/wrapper/`

Standard Gradle 8.14.6 launchers and wrapper. The wrapper JAR is binary and not
line-readable; the properties file pins `gradle-8.14.6-bin.zip`. `.gitattributes`
forces `native/ffmpeg/build.sh` to LF so it stays executable on Linux CI.

### `.gitignore`

Ignores Gradle/IDE output, `local.properties`, Kotlin caches, native build
directories, logs, `.jks`/`.keystore`, `keystore.properties`, APK/AAB files,
scratch folders, and `AGENTS.md`. `gradle-wrapper.jar` and
`gradle-wrapper.properties` are explicitly re-included.

### `keystore.properties.example`

A documented template for release signing. It explains how to create a key with
`keytool`, how to base64-encode it for GitHub Actions, and which four repository
secrets to add (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). The real `keystore.properties` and
keystore file must never be committed.

### `README.md`

The human-facing overview: feature list, app identity, build requirements
(JDK 17, SDK platform 37 / build tools 36, NDK/CMake), release build commands,
APK output paths, tag-based release instructions, Last.fm credential notes,
branding, licensing, and credits. Some sentences still mention streaming
sources; the actual app module is local-library-first. Chapter 15
(`summary.md`) lists the documentation set.

### `CODEBASE_GUIDE.md`

A 570-line, ~199 KB repository review notebook. It contains a review-progress
table and dense per-directory notes. It is useful as a second opinion and as a
record of why certain removals happened, but it is **not** a reliable inventory:
it describes files that no longer exist and features that have been removed.
Use the chapters in `docs/` for the current tree, and use the guide for historical
context.

### `CODE_OF_CONDUCT.md`, `TRADEMARKS.md`, `LICENSE`

- Code of conduct: Contributor Covenant 2.1 adaptation, contact
  `juni@proton.me`.
- Trademarks: the Juni name and wave icon are identifying artwork; the GPL code
  license does not grant branding rights. Forks should use their own name/icon.
- License: GPL-3.0-only. Bundled third-party code has its own licenses (for
  example Signalsmith MIT, DST LGPL-2.1-or-later, Mornye LGPL-3.0 adaptations,
  Oboe Apache-2.0, libusb LGPL-2.1).

### `.github/workflows/android.yml`

The only CI workflow. It is covered in section 7.

---

## 4. The three Gradle modules

### `app` — the Android application

`app/build.gradle.kts` is the most important build file for feature work.

**Identity and SDK levels**

| Setting | Value | Meaning |
| --- | --- | --- |
| `namespace` | `com.nejuni.juni` | Kotlin/R class namespace |
| `applicationId` | `com.nejuni.juni` | installed package (prod) |
| `compileSdk` | 37 | compile against newest APIs |
| `targetSdk` | 36 | runtime behavior target |
| `minSdk` | 26 | Android 8.0 minimum |
| Java/Kotlin | 17 | toolchain level |

**Versions**

`versionCode` and `versionName` come from the root extras. The debug build gets
`-<betaSuffix>` when the suffix is non-empty; release gets the same suffix.

**Flavors**

The `env` dimension creates:

- `dev` — application ID `com.nejuni.juni.dev`; the flavor sets the `app_name`
  resource to "Juni Dev", but `app/src/dev/AndroidManifest.xml` overrides the
  application label to "bit Dev".
- `prod` — the real `com.nejuni.juni` package.

Both can be installed side by side. The dev manifest also allows cleartext
traffic; the prod manifest does not.

**ABI splits**

Only `arm64-v8a` and `armeabi-v7a` are supported. The `apkAbis` Gradle property
(comma-separated) selects a subset; if more than one ABI is selected, Gradle also
builds a universal APK. The default is both ABIs.

**Signing**

- Reads `keystore.properties` from the repo root if present.
- Creates a `release` signing config only if both the properties and the actual
  keystore file exist.
- Release builds fall back to the debug key when no release key is configured,
  so a fresh checkout still produces an installable APK.
- A `benchmark` build type copies `release`, uses the debug key, and adds the
  `.benchmark` application-id suffix. It exists for measuring R8/startup behavior
  without replacing dev or prod installs.

**Native build**

`externalNativeBuild.cmake` points at `app/src/main/cpp/CMakeLists.txt` with
CMake 3.22.1. The CMake file reaches up to the root `native/` directory for the
analyzer and loop-mix sources, and compiles the app-local JNI/bit-perfect/DSD
sources. See section 5.

**Build features and tests**

Compose and BuildConfig are enabled. Unit tests use `isReturnDefaultValues = true`
so stubbed `android.util.Log` calls do not fail JVM tests.

**Dependencies (actual current list)**

- Compose BOM `2024.12.01`, plus `foundation 1.10.0` (needed for
  `OverscrollFactory`), Material 3, activity-compose, lifecycle, core-ktx,
  appcompat, tooling.
- Media3 `1.11.0`: exoplayer, session, common.
- `kotlinx-coroutines-guava`.
- Coil 3 (`coil-compose`, `coil-network-okhttp`, `coil-svg`).
- Jetpack SF Symbols (`sfsymbols-android:1.1.1`, non-transitive) for iOS-style
  icons.
- AndroidX Palette.
- Haze `1.3.1` + `haze-materials` for frosted glass.
- Ktor `3.5.2` (core, OkHttp engine, content negotiation, JSON) and
  kotlinx-serialization JSON for Last.fm.
- jsoup for HTML parsing (lyrics).
- ONNX Runtime Android `1.28.0` for Automix beat/vocal models.
- Test: JUnit 4.13.2, coroutines-test, AndroidX test/ext/JUnit, Espresso.

Notably absent despite comments: YouTube/NewPipe/InnerTube/Rhino, Cast,
WorkManager, SMB/WebDAV, downloads. Comments referring to them are stale.

**Custom task**

`verifyDevInstall` runs after `installDevDebug`. It calls `adb shell cmd package
compile -m verify -f com.nejuni.juni.dev` on every connected device so a debug APK is
ahead-of-time verified once instead of re-verified on every launch.

### `shared` — Kotlin Multiplatform domain layer

`shared/build.gradle.kts`:

- Targets: `androidTarget()` and `jvm()`, both JVM 17.
- Creates `jvmSharedMain`, depends on `commonMain`, adds:
  - `compileOnly` ONNX Runtime 1.20.0 (the app supplies the runtime),
  - `api` Ktor 3.5.2 (core, OkHttp, content negotiation, JSON),
  - `api` kotlinx-serialization-json 1.11.0, coroutines-core 1.10.2, jsoup 1.22.2.
- `androidMain` and `jvmMain` depend on `jvmSharedMain`.
- `commonMain` uses serialization JSON 1.7.3 and has no Android/Compose deps.
- Android namespace `com.nejuni.juni.shared`, compileSdk 36.

This module owns the domain models, queue math, equalizer/limiter math,
transition planning, track analysis models, lyrics providers, HTTP helpers,
Last.fm? (no — Last.fm is in app), and smart analysis. Chapter 10 covers every
file.

### `sharedUi` — Compose Multiplatform UI layer

`sharedUi/build.gradle.kts`:

- Targets Android + JVM, JVM 17, `-Xexpect-actual-classes`.
- `jvmSharedMain` shared by both; Android/JVM adapters depend on it.
- Compose Multiplatform `1.10.3` for runtime/foundation/animation/ui;
  material3 `1.7.3`; components-resources; lifecycle-runtime-compose 2.8.4;
  Coil 3.0.4; Haze 1.3.1.
- On desktop, `jvmMain` resolves `ui-desktop` at `1.10.3` on Windows and `1.12.0`
  elsewhere. The comment explains a Skiko `Matrix33` ABI break that forces this
  split. Do not "simplify" it without testing both.
- Android adapter deps: activity-compose, core-ktx, appcompat, palette.
- Compose resources: `publicResClass = true`, package
  `com.nejuni.juni.sharedui.resources`, always generate the resource class.
- Android namespace `com.nejuni.juni.sharedui`, minSdk 26, compileSdk 36.

Chapter 11 covers every shared UI file.

---

## 5. Native build and libraries

`app/src/main/cpp/CMakeLists.txt` builds six shared libraries plus one static
library:

| Target | Sources | Purpose |
| --- | --- | --- |
| `bit_analysis` | `native/analyzer/*.cpp` + `jni/analysis_jni.cpp`, `mel_jni.cpp`, `vocal_jni.cpp` | whole-track DSP (tempo/key/energy/structure), mel front end for Beat This!, linear STFT front end for open-unmix vocals |
| `bit_loopmix` | `native/loopmix/loop_mix_renderer.cpp` + `jni/loopmix_jni.cpp` | two-deck Signalsmith Stretch loop renderer for Automix |
| `bit_dsd` | `dsd/dsd_decimator.cpp` | DSD (1-bit) to float PCM decimation |
| `bit_dst` | `dsd/dst/decoder.c` + `dsd/dst/jni.cpp` | DST decompression for SACD-style DFF |
| `bit_exclusive` | `bitperfect/exclusive_output.cpp` | AAudio exclusive output through Oboe |
| `bit_usb` | `bitperfect/usb_transport.cpp` + static `bit_libusb` | direct USB Audio Class output through libusb |
| `bit_libusb` (static) | pinned libusb core/descriptor/hotplug/io/sync/error + Linux USB FS back end | userspace USB stack linked into `bit_usb` |

Key build details:

- C++17, `-O3`, hidden visibility, and 16 KB LOAD-segment alignment for every
  Android library (required for Android 15+ 16 KB page devices).
- `bit_analysis` deliberately does **not** use `-ffast-math` because its DSP is
  tuned to IEEE behavior. `bit_loopmix` does use `-ffast-math`.
- Oboe 1.11.0 and libusb 1.0.27 are fetched by CMake with pinned SHA-256 hashes.
  Oboe is configured with `OBOE_DISABLE_CONVERSION=ON`: if a stream needs format
  conversion, the library rejects it rather than silently converting.
- `bit_exclusive` and `bit_usb` export only their JNI symbols through
  `bitperfect/exports.map` and use `--gc-sections`.
- The DST decoder is deliberately restricted: DSD64, mono/stereo, bounded input,
  and only unsegmented/simple compressed forms. Unsupported forms return explicit
  errors. See chapters 4 and 12.

`native/ffmpeg/build.sh` is a separate, manual build step (not part of Gradle).
It checks out librempeg at a pinned commit, configures a minimal static decoder
set (AC-4, ALAC, AC-3/E-AC-3, TrueHD, DTS, FLAC, MP3, AAC, Vorbis, Opus, PCM
A-law/mu-law), and copies `libffmpegJNI.so` into
`app/src/main/jniLibs/<abi>/`. `ffmpeg_jni.cc` is the Media3 JNI glue. The Java
half lives under `app/src/main/java/androidx/media3/decoder/ffmpeg/`.

---

## 6. Build commands you will actually use

From the repository root on Windows:

```powershell
.\gradlew.bat assembleProdRelease
.\gradlew.bat assembleProdDebug
.\gradlew.bat testProdReleaseUnitTest
.\gradlew.bat lintProdRelease
.\gradlew.bat installDevDebug
.\gradlew.bat verifyDevInstall
```

On macOS/Linux, use `./gradlew` instead.

Useful properties:

```bash
# one ABI only
./gradlew assembleProdRelease -PapkAbis=arm64-v8a

# a specific version (CI does this)
./gradlew assembleProdRelease -PjuniVersionName=1.2.3 -PjuniVersionCode=2002003
```

Output:

- `app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk`
- `app/build/outputs/apk/prod/release/app-prod-armeabi-v7a-release.apk`
- `app/build/outputs/apk/prod/release/app-prod-universal-release.apk`

Requirements: JDK 17, Android SDK platform 37 and build tools 36, NDK and CMake
matching the AGP configuration. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or create
`local.properties` with `sdk.dir=...`.

Unit tests live in `app/src/test`, `shared/src/commonTest`,
`shared/src/jvmTest`, and `sharedUi/src/jvmTest`. Instrumented tests live in
`app/src/androidTest`. Chapter 14 explains their intent and how to run them.

---

## 7. CI and release workflow (`android.yml`)

Triggers: pushes to `main`, pull requests, tags matching `v*`, and manual runs
with an optional tag input. Permissions: `contents: write`.

Steps:

1. **Checkout** full history (`fetch-depth: 0`) so generated release notes can
   see commits since the previous tag.
2. **Materialize Compose resources.** Gradle rejects symlinks in the resource
   tree, so CI copies `build-support/composeResources` into
   `sharedUi/src/commonMain/composeResources` via a temp directory and fails if
   any symlink remains. The mirror is the canonical plain-file copy; keep it in
   sync when adding/removing Compose resources (chapter 13).
3. **JDK 17** (Temurin) with Gradle caching.
4. **Resolve the version.** A tag or manual `tag` input must match
   `vMAJOR.MINOR.PATCH`. `versionCode = 1,000,000 + major*1,000,000 +
   minor*1,000 + patch`; `v1.0.0` becomes 2,000,000. Untagged builds use the
   root defaults (`1.0.0-dev`, 999999).
5. **Restore signing.** Base64-decodes `RELEASE_KEYSTORE_BASE64`, writes
   `keystore.properties`, and fails a tag build if the secret is missing.
   Non-release runs fall back to the debug key.
6. **Build** both ABIs and the universal APK with `assembleProdRelease`,
   passing Last.fm credentials and the resolved version.
7. **Collect** the three APKs into `dist/Juni-v<version>-<abi>.apk`.
8. **Upload artifacts** as one archive.
9. **Publish a GitHub Release** only when a tag was pushed or supplied, with
   generated notes and all three APKs attached.

If you add a build variant or change ABI support, update both the Gradle splits
and this workflow's collect/release steps.

---

## 8. How the pieces fit at runtime (one-paragraph version)

`JuniApplication` creates the process-wide singletons (settings, caches, image
loader, platform hosts), then `MainActivity` composes the Compose tree from
`sharedUi`. `MainActivity` and `MainViewModel` own navigation and page state.
Playback lives in `PlaybackService`, a Media3 `MediaLibraryService` with two
ExoPlayers, queue/context managers, DSP, scrobbling, widgets, and Android Auto
browsing. `shared` supplies pure models, queue math, analysis models, transition
planning, lyrics provider clients, and HTTP helpers. `sharedUi` supplies the
player, queue, lyrics, search, and reusable visual components. Native libraries
provide DSP, model front ends, DSD decoding, and direct/exclusive audio output.
Chapters 2–12 walk through each layer.

---

## 9. Feature change checklist

When adding or removing any feature, touch the layers in this order and check
each one:

1. **Model / shared logic** — `shared/src/commonMain` or `jvmSharedMain`.
2. **Persistence** — `AppSettings`, SharedPreferences keys, JSON models,
   migrations, backup/export filters.
3. **Data access** — local library, playlists, stats, lyrics, Last.fm, canvas.
4. **Playback / service** — `PlaybackService`, `PlayerConnection`,
   `CrossfadeController`, queue files, `MediaSession` commands.
5. **UI state** — `MainViewModel`, `MainActivity`, `AndroidAppUiHost`, player
   host contracts.
6. **UI** — screens, components, sheets, menus, strings.
7. **Resources** — drawables, layouts, `res/xml`, locale catalogs, Compose
   resources and their `build-support` mirror.
8. **Manifest / permissions / providers / services**.
9. **Build** — dependencies, ProGuard keep rules, native CMake, ABI splits.
10. **Tests** — unit, instrumented, and any golden/fixture tests.
11. **Docs** — this `docs/` set, README, and the historical guide if relevant.

Deleting only the visible screen is the classic mistake: the service hooks,
settings flows, persistence, resources, and native/ProGuard assumptions often
survive and either keep code alive or crash later.

---

## 10. Files covered by this chapter

Root: `.gitattributes`, `.gitignore`, `build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.properties`, `keystore.properties.example`,
`README.md`, `CODE_OF_CONDUCT.md`, `TRADEMARKS.md`, `LICENSE`,
`CODEBASE_GUIDE.md`, `.github/workflows/android.yml`.

Build definitions: `app/build.gradle.kts`, `app/proguard-rules.pro`,
`app/src/main/cpp/CMakeLists.txt`, `shared/build.gradle.kts`,
`sharedUi/build.gradle.kts`, `app/src/dev/AndroidManifest.xml`,
`app/src/main/AndroidManifest.xml`.

Next: [02 — App startup and navigation](02-app-startup-and-navigation.md).
