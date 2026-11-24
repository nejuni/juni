# Feature change checklist

Before coding, identify the feature's entry point, state owner, persistence, platform bridge, runtime service, dependencies, manifest contributions, workflow secrets, and tests. Search names and call sites across `app`, `shared`, `sharedUi`, `native`, `.github`, README, and docs.

## Adding a feature

- Keep Android framework access in `app`; shared UI should use a narrow host/interface boundary.
- Put blocking scans, database work, and image decoding off the main/audio thread.
- Define settings defaults, serialization, migration, reset behavior, and backup behavior.
- Add UI text/resources for supported locales and check accessibility labels/empty states.
- Add or update focused tests, and build affected variants.
- Document permission, network, storage, battery, and data retention effects.

## Removing a feature

Remove its visible routes/actions, state flows, host methods, jobs/services, persistence writers/readers, obsolete preference keys, dependencies, manifest permissions, CI secrets, tests that only cover removed behavior, and docs. Search both feature names and endpoints afterward. Retain shared parsers/models only when another supported local feature still calls them. Automix-specific analysis, ONNX runtime/models, native loop rendering, settings, markers, and translations are removed; ordinary crossfade remains.

## Offline boundary

The Android manifest removes `INTERNET` and `ACCESS_NETWORK_STATE`; do not re-add a network feature without revisiting product scope and permission documentation. A dependency alone does not prove runtime use, but dead network clients and URL-hosting libraries should be removed when no supported local feature needs them.

For each substantive change, update `CODEBASE_GUIDE.md` and the relevant page in `docs/`. Keep this checklist current if the architecture or release process changes.
