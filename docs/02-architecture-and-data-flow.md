# Architecture and data flow

## Modules

- `app` owns Android lifecycle, permissions, SAF folder grants, MediaStore fallback, playback service, notification/session, persistence, widgets, Android Auto, and JNI integration.
- `shared` contains cross-platform models and reusable Kotlin/JVM code. Its Android and JVM source sets share selected implementations.
- `sharedUi` contains the Compose player and common UI surfaces. `PlayerHost` and `PlayerPlatform` bridge shared UI to platform operations.
- `native` contains any shared native audio code. `app/src/main/cpp` contains DSD and bit-perfect Android output implementations; there is no track-analysis or loop-mix native pipeline.

## Local library path

The app obtains audio using SAF document-tree grants and scans granted trees recursively; MediaStore is a fallback/index source. The scanner persists media metadata and artwork/thumbnail data in the app's local library cache/database. A grant is scoped to a storage tree and can be revoked by Android or disappear after uninstall. Scanner changes should preserve `content://` access and handle providers that paginate or return delayed metadata.

## Playback and queue

`MainActivity`/`MainViewModel` coordinate visible state; `PlayerConnection` binds to `PlaybackService`; queue editing/planning and playback state are in `app/src/main/java/com/mus/io/playback/`. Shared player UI is in `sharedUi/.../ui/player/`. Avoid moving file IO, metadata reads, artwork decoding, or queue-wide preparation onto the UI/audio thread.

The queue uses a small preparation window and incremental batched additions. Follow `05-performance-and-queue.md` before changing window sizes or queue persistence.

## Lyrics, stats, and export

Embedded tags are read from local files by the app's lyrics adapter; the shared player renders lines and supports local lyric sharing/offset controls. There is no online provider lookup in the offline UI path. Listening stats are stored locally and feed Replay. M3U export is implemented in `app/src/main/java/com/mus/io/data/M3uExporter.kt` and surfaced from settings.

## Settings and migrations

`AppSettings` is the central Android settings registry and SharedPreferences migration boundary. When removing a setting, remove its UI/state/writer/reader and migrate or clear stale persisted keys so old installs do not resurrect removed behavior. Avoid deleting user data that is unrelated to the removed setting.

For exact build/permission details, see `01-repository-build-and-release.md` and `06-offline-media-and-privacy.md`.

## Crossfade and removed Automix

Playback keeps the ordinary user-selected crossfade in `app/src/main/java/com/mus/io/playback/CrossfadeController.kt`. Automix was removed: there is no beat/vocal analysis, analysis database, ONNX runtime/model asset, loop-mix renderer, Automix settings UI, or analysis status UI. Removing Automix does not disable regular crossfade.

The Android media session publishes the track's normal metadata. Do not publish lyric lines through `MediaMetadata.subtitle`; lyric display remains an in-app surface.
