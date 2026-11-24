# Bug hunting and verification

1. Reproduce with the narrowest realistic case and capture device/API level, file format/provider, granted folder URI, playback state, and relevant logs.
2. Search symbols with `rg`; trace callers from UI through ViewModel/repository/service to persistence/platform/native code.
3. For local media issues, distinguish the media URI, persisted SAF permission, cached database row, MediaStore entry, and embedded metadata. Check whether reinstall/uninstall revoked the grant.
4. For queue/playback issues, inspect queue mutation serialization, playback generation/cancellation, player callbacks, and whether expensive preparation touches the main/audio thread.
5. Make a focused change. Add a regression test when behavior can be isolated; do not use a successful compile as proof of device behavior.
6. Build the appropriate variants and inspect merged manifest when permissions/dependencies change.
7. Re-run relevant checks and document known limits.

## Crash trace from a device

Settings > Miscellaneous > Share startup trace exports launch and queue-start milestones. If the process exits with an uncaught Java/Kotlin exception, `StartupTrace` synchronously saves the exception and full stack trace separately before Android's default crash handler runs, then includes that retained report in the next export after restart. It does not capture native crashes or forced process kills.

Useful Windows commands:

```powershell
rg -n "SymbolOrEndpoint" app shared sharedUi .github
.\gradlew.bat assembleDebug
.\gradlew.bat assembleProdRelease
.\gradlew.bat :app:processProdDebugMainManifest
```

For an offline claim, inspect `app/build/intermediates/merged_manifest/prodDebug/processProdDebugMainManifest/AndroidManifest.xml` (path may vary by AGP) and verify INTERNET/ACCESS_NETWORK_STATE are absent. Update this file and `CODEBASE_GUIDE.md` when verification commands or build tasks change.
