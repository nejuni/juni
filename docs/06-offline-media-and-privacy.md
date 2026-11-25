# Offline media and privacy

The Android manifest explicitly removes `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` from the merged manifest. The supported app flow is local playback, local library scanning, embedded metadata/lyrics/artwork, local listening statistics/Replay, and local M3U export. Remote lyric providers, translation/romanization providers, Last.fm scrobbling, and remote Replay enrichment have been removed with their client code and dependencies. The settings migration no longer carries cleanup code for those removed services.

## Storage access

Folder scanning uses Android's Storage Access Framework (SAF), which gives the app access only to selected document trees. Persisted grants are device/provider-specific and can be revoked by the user, provider, or uninstall. Files may be visible in a file manager but inaccessible to Juni until the relevant folder tree is granted to the app. For distributed libraries, grant each storage root or a common parent available from the document provider.

Scans should use document IDs/content URIs and stream metadata/artwork rather than assuming filesystem paths. A MediaStore query may be used as a last-resort fallback where the provider or user flow calls for it; SAF is the direct source of truth for an explicitly granted tree.

## Optional full-resolution cover art

Settings > Miscellaneous > High-res cover art is off by default. When enabled, the next library scan reads embedded artwork from local audio metadata and copies the original encoded image (up to 64 MiB) into the app-private `files/high-res-art/` directory. It does not upload the image or make it visible to other apps. The Now Playing screen uses the cached original while the setting is on; otherwise it uses the normal artwork. Turning the setting off stops additional extraction but leaves previously cached images in the private app folder.

## Export and stored data

M3U is a playlist reference format, not a copy of audio files. Entries may be absolute paths or content URIs and can be device-specific; another app may not have permission to resolve them. Local listening history, settings, scanner metadata, and thumbnail cache remain on-device unless the user explicitly exports data through an app feature.

If a future change adds online access, update the manifest, privacy explanation, README, feature docs, and review all network-capable dependencies and user-visible controls together. Update this document and `CODEBASE_GUIDE.md` when permissions or storage behavior changes.
