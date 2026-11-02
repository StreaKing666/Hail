# Enhanced.10 development verification

This is a development test build. The debug application ID is `com.aistra.hail.enhanced.debug`; its launcher label is **Hail Enhanced Test**. It installs separately from `com.aistra.hail.enhanced` and the original Hail. It uses a debug certificate, not the permanent release certificate.

## Repairs

- Restore missing backup-model imports so the app compiles.
- Persist selected backup categories and preference-key scope. REPLACE leaves excluded categories untouched.
- Treat the unknown preference scope of older v1/v2 archives conservatively: overwrite known imported values without deleting unrelated preferences.
- Normalize protected preference keys consistently during export and reject malformed scope metadata during import.
- MERGE preserves existing tags and applications, remaps colliding tag IDs, matches tag names without case sensitivity, and retains existing app flags and memberships. Imported flags are used for newly added apps.
- Separate backup choice lists from the final preview, because an AlertDialog message suppresses its choice list.
- Applying a freeze profile changes only its explicitly assigned packages; packages absent from the target are not implicitly unfrozen.

## Verification

The full JVM suite contains 68 tests. Four additional Python tests cover the legacy migration exporter. Run:

```sh
python3 -m unittest tools/test_legacy_export_hail_backup.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Release compilation without configured permanent signing is only a compilation check. Distribute the separate debug APK for this test, not a release APK signed with a temporary key.

## Remaining modernization gaps

- Freeze backends, permission handling, hidden-app discovery and recovery after process termination need real-device validation. Discovery currently covers the current Android user only.
- The controller facade now serializes operations globally and reconciles pending journal entries with their original backend without replaying privileged commands.
- Profiles snapshot the current frozen/unfrozen state of tracked apps. Editing individual package assignments and profile names is not yet exposed in the UI.
- Rules use all tracked apps and a fixed 30-second cooldown. SCREEN_OFF depends on AutoFreezeService. Per-package/cooldown editing is absent; a bounded operation audit viewer is available in Settings. Repeated events are coalesced, and partially completed operations are retried idempotently.
- Deleting a profile does not yet clean up its existing launcher shortcuts.
- Navigation, rotation, fonts, multiple columns and launcher shortcuts still require visual/device checks.

These limitations are present in the modernization branch and are not completion claims for the seven-area roadmap.

## Device check

1. Install **Hail Enhanced Test** alongside the existing app.
2. Start with a small set of nonessential apps and verify the selected freeze backend, thawing and error reporting.
3. Export/import a backup in the test app. Check MERGE, REPLACE and exclusion of preferences/additional files.
4. Check profile application, shortcuts, search, disabled/hidden filters, navigation and rotation.
5. Test rules and interrupted restore separately before relying on them for daily automation.

The separate app starts with its own data and permissions. Copy data through an external backup if needed; its package ID does not replace the existing installation.
