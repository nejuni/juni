# Juni codebase guide

This is the current map for future maintenance. Update this file and the relevant `docs/` page whenever a change alters the architecture, build, feature behavior, or troubleshooting steps. Do not leave the guide describing removed features as current behavior.

## Current project shape

- `app/`: Android application, platform integration, local library scanning, settings, playback service, widgets, Android Auto, and native JNI bridge.
- `shared/`: Kotlin/JVM shared models and utilities. Review callers before removing a shared dependency because Android and JVM source sets both use this module.
- `sharedUi/`: Compose player and shared screens/resources.
- `native/`: C/C++ DSP, audio, DSD, analyzer, and loop-mix implementations and their build scripts.
- `build-support/composeResources/`: plain-file source mirror used by CI to materialize Compose resources before Gradle.
- `.github/workflows/android.yml`: version resolution, signing setup, release APK build, artifact upload, and tag-triggered GitHub release.
- `docs/`: practical guides for building, architecture, feature work, debugging, queue performance, and offline/privacy boundaries.

## Important current behavior

- App label: Juni. Production application ID: `com.nejuni.juni`; dev variant uses `com.nejuni.juni.dev`.
- The Android manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` from the merged manifest. Library access and playback are local; folder access uses Android's Storage Access Framework. Embedded lyrics are read from local media.
- Settings > Miscellaneous > High-res cover art optionally extracts original embedded audio artwork during a library scan. The encoded image is stored under the app-private `files/high-res-art/` directory and selected for the Now Playing screen; turn it on and scan the library to populate it. Turning it off stops future extraction and returns the player to regular artwork; saved images remain private on-device.
- The queue uses bounded preparation and batched edits to avoid eagerly creating work for every queued item.
- Settings can export the library as an M3U playlist. M3U entries may contain device-specific URIs/paths; portability depends on the player receiving the same storage access.
- Last.fm, online lyric lookup, translation/romanization, and remote Replay enrichment are not part of the offline app. The regular crossfade is retained; Automix, its settings, analysis pipeline, ONNX models, and loop-mix DSP were removed. The player progress bar shows only playback progress/loading; it no longer carries Automix markers or a “Mixing” caption. `PlaybackService.SessionPlayer` publishes queue-item metadata to Android system controls and ignores extractor-updated aggregate metadata so embedded lyric tags cannot become an OEM lyrics panel; in-app lyrics are still read directly from the audio URI.
- Settings > Miscellaneous > Share startup trace exports launch/playback milestones and the last uncaught exception stack trace. `StartupTrace` retains the crash report across process restarts and includes it in the next export, which helps diagnose playback-start failures even though a crashed process cannot open Settings.
- CI release tags use `vMAJOR.MINOR.PATCH`; the workflow derives the Settings version and Android version code. See `docs/01-repository-build-and-release.md` before changing this logic.

## Build and package

Use JDK 17, Android SDK Platform 37, and the NDK/CMake versions requested by Gradle. On Windows, Android Gradle Plugin 8.10.1 currently warns that SDK 37 is newer than its tested SDK 36 range. Confirm current build status in the change notes before relying on a previous build result. On Windows:

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk' # replace with the local SDK directory
.\gradlew.bat assembleDebug
.\gradlew.bat assembleProdRelease
```

Debug builds both dev and prod flavors. Release artifacts land under `app/build/outputs/apk/prod/release/`; ABI splits are controlled with `-PapkAbis=arm64-v8a,armeabi-v7a`. A clean checkout may use the debug signing key for local release builds. CI publishing requires the four `RELEASE_*` signing secrets. Source ZIPs shared with the user must always exclude `.cxx`, `.git`, `.gradle`, `.idea`, and generated `build` directories. See the workflow and build guide for the exact names and tag behavior.

## Change discipline

1. Trace the feature from UI entry point through state, persistence, service/repository, and platform/native boundary.
2. Search all call sites and tests before removing a class or dependency. Remove dead UI, state, migration code, dependencies, manifest permissions, CI secrets, and documentation as one change where relevant.
3. Preserve local media URI permissions and playback behavior when changing the scanner or database.
4. For large collections/queues, avoid main-thread scans, eager full-queue preparation, and unbounded retained artwork/decoder state.
5. Build the affected Android variants. Do not claim device behavior from compilation alone.
6. Update this map and the relevant `docs/*.md` page with each substantive feature or architecture change.

## Guide index

- [Repository build and release](docs/01-repository-build-and-release.md)
- [Architecture and data flow](docs/02-architecture-and-data-flow.md)
- [Feature change checklist](docs/03-feature-change-checklist.md)
- [Bug hunting and verification](docs/04-bug-hunting-and-verification.md)
- [Performance and queue](docs/05-performance-and-queue.md)
- [Offline media and privacy](docs/06-offline-media-and-privacy.md)
- [Automix removal and crossfade](docs/07-automix-removal.md)
