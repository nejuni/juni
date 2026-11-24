# Automix removal and playback boundary

Automix has been removed from Juni. This feature change is intentionally separate from standard crossfade.

## Removed

- Automix and performance controls from Settings and all locale strings.
- The analysis status/detail UI, transition window marker, mix pulse and mix popup.
- Track analysis scheduling and persistence, beat/downbeat tracking, vocal tracking, and analysis JNI.
- ONNX Runtime, the Beat This and vocal separation models, and model keep rules.
- LoopMix planning/session/renderer code, its native JNI/CMake target, and the Signalsmith-only vendor sources.
- Automix-only tests and persisted analysis/performance settings.

## Retained

- The regular crossfade duration setting and two-player handoff in `CrossfadeController`.
- Equalizer, spatial audio, loudness normalization, queue handling, local lyrics, and the rest of playback.
- The progress bar displays playback position and loading feedback. It has no transition marker or “Mixing” label.

## System media controls

`PlaybackService.SessionPlayer` publishes metadata attached to the current queue item rather than ExoPlayer's aggregate metadata, which can merge tags parsed from the audio container. It sanitizes current/queued `MediaItem`s and sends the clean queue-item metadata for `onMediaMetadataChanged`; this prevents OEM media panels from exposing embedded lyrics. Embedded lyrics remain available in the app's lyrics view, which reads them directly from the local audio URI. Keep lyric text out of notification/lock-screen metadata unless that behavior is explicitly reintroduced.

## Future maintenance

For feature work, update this page, `CODEBASE_GUIDE.md`, and the relevant guide under `docs/`. Search all source sets, generated resource mirrors, Gradle dependencies, CMake targets, model assets, and translated strings before declaring a feature fully removed.
