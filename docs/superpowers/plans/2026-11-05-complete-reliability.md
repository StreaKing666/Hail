# Hail Enhanced .14 Reliability Implementation Plan

Goal: fix confirmed application defects left by the .13 audit, preserve user data and all existing actions; deliver both APK identities and both module targets.

Architecture: keep existing data-operation serialization and controller journals. Move legacy privileged UI operations to a retained ViewModel on IO, returning values/Intents for UI delivery. Observe a single nullable package-state snapshot and retry observation only, never replay a privileged command.

Constraints: minSdk23, target36, existing signer; no telemetry/new services, no signature bypass, no automatic uninstall/data clearing. Existing English/Russian numeric freeze notifications remain. No mass lint suppressions, baselines or fake translations. Work in existing isolated checkout; keep GitHub backup, no Actions/releases.

## Tasks

- [x] Freeze reliability (FreezeController, HPackages, FreezeModePolicy, ModeFreezeBackend): regression tests first for delayed observation, unavailable state, interrupt cancellation and no command replay; bounded polling about1s, snapshot reuse, guarded hidden field reflection, Android6 discovery flags. Existing modes/current-user identity preserved.
- [x] UI execution (ManualOperationsViewModel, PagerFragment, ApiActivity, AppsFragment, AppManager): IO worker plus retained one-shot result, main-thread intents/toasts, immutable package requests, no repeated operation after rotation; privileged fallback to uninstall UI occurs on main. Test pure-operation contracts where feasible; Android lifecycle explicitly device-only.
- [x] Resources/lint: fix actual EN/RU copy/plural/XML issues, produce locale gap inventory without removing translations. Review warnings by cause: durable commit is intentional off main; optional dependency upgrades/SDK37 do not constitute functional defects and require separate compatibility work. Missing translations requiring human review remain explicit blockers.
- [ ] Final validation/review: full JVM/migration/module suites and full lint after all edits stable; fresh whole-change reviewer before release, one fixes pass. Build .14 clone/original, verify certificates/packages/version and exact APK in both modules. Update original audit binding only after auditing the actual newly built APK. Save sources/history, report/statuses, checksums and installation guidance.

Review focus: rotation while command is pending (no replay, result once); interrupted verification (interrupt retained, durable pending entry reconciled without replay); inaccessible hidden state (unknown does not become thawed); partial batch success (numeric toast but journal failures retained); data restore while UI action queued (serialize and use fresh state).

Rulings: external signature mismatch and unavailable physical device are platform/test prerequisites, not fixes achievable inside Hail. 5873 currently missing locale/key pairs cannot be represented as reviewed translations this pass. Record them, do not claim all problems eliminated.

## Verification checkpoint before release

- PASS: final Gradle JVM suite 163 tests, zero failures/errors/skipped; migration4tests PASS.
- Final independent reviewer: no Critical, 3 Important. One fix pass completed: exact private full recovery snapshot; user-scoped tag editing with foreign-profile preservation; visible tri-state UNKNOWN/filter/selection bits. Targeted regressions are included in final163tests.
- Full lint additionally reproduced RestrictedApi in two shortcut copy-builders. Fixed with public context/id partial-update builders preserving rank/labels/activity/extras; final full lint now281MissingTranslation only,61warnings. No suppression/baseline added.
- Remaining locale inventory5873missingpairs/35directories requires translation review; full lint remains FAIL and translationwork BLOCKED. ADB no devices: Android runtime NOT_RUN.
- Next: commit exact app source, build/sign both identities, bind audited original APK, package/test both modules, save source/history/report/checksums.
