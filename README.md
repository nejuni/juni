# Juni

Juni is an open source Android music player for your local library, with streaming sources and optional online services alongside offline playback. It is a community fork of BitChord. The upstream project and contributors retain credit for their original code; Juni changes and additions are maintained by Sharavan Kumar.

## Features

- Browse and play local music, playlists, queues, lyrics and album art.
- Use optional online sources and add-ons, with playback controls and downloads.
- Replay listening statistics and share lyric or Replay cards.
- Android Auto, widgets, Wear support, USB audio, bit-perfect output, DSD, and audio effects where supported by the device.

Network-dependent features are optional, but the app does request network access for enabled sources, artwork and service integrations. Review Android permissions and optional integrations in Settings before use.

## App identity

- App name: **Juni**
- Android application ID: `com.nejuni.juni`
- Minimum Android version: API 26 (Android 8.0)
- Maintainer: [Sharavan Kumar](https://github.com/nejuni) · <ume@aster.cx>


## Build

### Requirements

- JDK 17
- Android SDK with Android SDK Platform 37 and the Android 36 platform/build tools required by this project
- Android NDK and CMake versions selected by the Android Gradle Plugin/CMake configuration
- Windows: use `gradlew.bat`; macOS/Linux: use `./gradlew`

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK directory, or create `local.properties` with `sdk.dir=<absolute SDK path>`. Android Studio can also open this project and sync the Gradle wrapper.

### Build a release APK

```powershell
# Windows PowerShell
.\gradlew.bat assembleProdRelease
```

```sh
# macOS / Linux
./gradlew assembleProdRelease
```

The APKs are written under `app/build/outputs/apk/prod/release/`. ABI splits are enabled for `arm64-v8a` and `armeabi-v7a`; install the one matching the device. You can build a single architecture with `-PapkAbis=arm64-v8a` or `-PapkAbis=armeabi-v7a`.

A fresh checkout can build a release signed with Gradle’s debug key for local installation. For a distributable release, create your own private release keystore and configure `keystore.properties` using [keystore.properties.example](keystore.properties.example). Never commit the keystore, passwords, or signing secrets. GitHub Actions release signing uses repository secrets described in `.github/workflows/android.yml`.

### Publish a GitHub release

Push your commit to `main` and wait for the build workflow to succeed. Create and push a semantic version tag to publish a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Use a new `vMAJOR.MINOR.PATCH` tag for each release. The tag becomes the app version shown in Settings (for example, `v1.0.0` displays as `1.0.0`); GitHub Actions also assigns an increasing Android version code so the release can update an earlier build. Untagged builds are marked `1.0.0-dev`. Create a tag with `git tag`, then push it with `git push origin <tag>`.

Last.fm API credentials are optional. Supply `LASTFM_API_KEY` and `LASTFM_SECRET` through local properties or environment variables; do not commit credentials.

## Branding and copyright

The Juni wave mark and app icon were created by **Sharavan Kumar**. The editable SVG source and Android icon assets are in `app/src/main/assets/branding/` and `app/src/main/res/`. The icon is reserved artwork and is excluded from the source-code license; see [TRADEMARKS.md](TRADEMARKS.md) for the project’s name and logo policy.

The application source is licensed under GPL-3.0-only as shown in [LICENSE](LICENSE), subject to the notices and licenses of upstream and bundled dependencies. The logo and name are not relicensed by that code license.

## Credits

- [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh.
- The maintainers of the open-source libraries and projects listed in their respective license notices.

## Contributing

Bug reports and pull requests are welcome. Please read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and the licensing and attribution terms before contributing. For questions about the Juni name or artwork, contact <ume@aster.cx>.
---
> Code is GPLv3. Icons and branding are not; see TRADEMARK.md
