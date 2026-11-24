# Performance and queue

Juni targets large local libraries and queues. Keep the amount of prepared work proportional to what playback is about to need.

- Queue insertion is batched (currently 10 items at a time) and yields between batches; it should not synchronously compose or resolve every row for a 1,000+ item queue.
- Playback preparation maintains a bounded window ahead of the current item (currently 10) and replenishes near a low-water point (currently 5). Verify exact constants in `BoundedContextRequests` and related playback files before changing this guide.
- Repeated rapid skips must cancel or invalidate stale asynchronous work by generation/identity, and callbacks from old items must not overwrite the current player state.
- Keep disk/SAF scanning, tag extraction, thumbnail decoding, analysis, and persistence off the UI and audio render threads. Use bounded concurrency and avoid retaining decoded artwork for the whole library.
- Prefer measuring startup, queue insertion, rapid skip, and memory on a device/release-like build. A successful compilation does not measure responsiveness.

## Player interactions

- Queue rows support a left swipe to remove and a right swipe to move the song to the next playback position. Keep these actions routed through the existing queue mutation path so queue metadata and ordering stay consistent.
- The expanded player animates play/pause and skip controls. Respect the reduced-animation setting when adjusting these effects.
- The Android Quick Settings playback tile uses `MediaWidgetSnapshot` for its state and sends toggle commands through `MediaWidgetActions`; avoid introducing a second playback-control path.
- Artwork palette loading is tied to the current artwork URL. Clear the previous palette when the current track has no usable artwork or decoding fails, so colors from an old track do not remain behind the new one.

When tuning limits, test small and very large queues, rapid next/previous, interrupted batch edits, playback restoration, and process recreation. Update `CODEBASE_GUIDE.md` and this page whenever batching/window values or queue architecture changes.
