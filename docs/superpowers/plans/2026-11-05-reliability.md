# Hail Enhanced Reliability Implementation Plan

> For agentic workers: use superpowers:executing-plans task-by-task.

**Goal:** implement the user's audited reliability, architecture and editor fixes.
**Architecture:** bounded process runner, bounded backup reader, shared data coordinator,
independent rule-service lifecycle, saved-state editor and precise verification.
**Tech Stack:** Kotlin, AndroidX, WorkManager, JUnit, Gradle, Python.
**Spec:** ../specs/2026-11-05-reliability.md

## Global Constraints
- minSdk 23, existing JSON/ZIP schemas and signing identity preserved.
- Default com.aistra.hail.enhanced; opt-in com.aistra.hail.
- Local builds only, no paid dependencies or new external services.
- No destructive device commands, no automatic release/merge.
- Real device validation is NOT_RUN without a connected device.

## Review Focus
- Interrupted restore and damaged recovery journal: retain evidence and block mutations.
- Subprocess stdout flood, never-ending child and cancellation: bounded time/memory/cleanup.
- Concurrent rule edit/import/worker: no lost updates or commands during restore.
- Mode changes and user identity: never target the foreground user's apps accidentally.
- Editor rotation and failed save: keep drafts; do not recreate deleted records.

## Task 1: Bounded subprocess execution and platform contracts
Files: utils/BoundedProcessRunner.kt, HShell.kt, HPolicy.kt, HPackages.kt;
tests: utils/BoundedProcessRunnerTest.kt.
Produces: execute(command, root) compatibility; timeout exit 124, cancellation 130.
- [ ] Write tests for large output, timeout, interrupt and normal/nonzero exit; observe RED.
- [ ] Implement concurrent drain, bounded capture, deadline and deterministic cleanup.
- [ ] Use process user's ID; guard clearProfileOwner on API 24; correct flag contracts.
- [ ] Run targeted tests and commit.

## Task 2: Bounded export and coordinated recovery/storage
Files: app/BackupFileReader.kt, FullBackupArchive.kt, FullBackupStorage.kt,
AppDataAccess.kt, HailData.kt, FreezeRuleStore.kt, FreezeProfileStore.kt, HailApp.kt,
ui/main/MainActivity.kt, ui/home/HomeFragment.kt; tests: BackupFileReaderTest,
AppDataCoordinatorTest and archive tests.
Produces: atomic read/modify/write APIs and shared operation gate.
- [ ] Test excluded oversized files, size/count limits and transaction/recovery failure RED.
- [ ] Implement selection before reads, bounded streams, shared locking and atomic mutations.
- [ ] Gate failed recovery; show persistent actionable message and retry; retain journal.
- [ ] Move restore IO off main; retain responsive operation progress.
- [ ] Verify valid/corrupt archives and concurrent updates, commit.

## Task 3: Rule lifecycle, state and user correctness
Files: HailApp.kt, AutoFreezeService.kt, ScreenOffReceiver.kt, RuleScheduler.kt,
RuleEngineWorker.kt, AppManager.kt, FreezeControllerFacade.kt, ModeFreezeBackend.kt;
tests: RuleServicePolicyTest, FreezeModeStateTest plus existing controller tests.
- [ ] Test independent service policy, disabled rules, restore gate and mode/user invariants RED.
- [ ] Drive service from legacy OR enabled screen-off rules and refresh after mutations.
- [ ] Serialize evaluation/execution/state commit, cancellation and bounded retries.
- [ ] Ensure reversible freeze state survives a mode switch; reject unsupported thaw clearly.
- [ ] Verify current-user addressing, commit.

## Task 4: Saved-state editors and background operations
Files: ui/settings/EnhancedEditorDialogFragment.kt, EnhancedOperationsViewModel.kt,
SettingsFragment.kt, utils/HShortcuts.kt, EnhancedEditors.kt, resource strings;
tests: EnhancedEditorsTest and draft serialization tests.
- [ ] Test trigger/action editing, protected app filtering and draft round trip RED.
- [ ] Implement retained asynchronous operations and saved editor draft, one form, app
  labels/icons/search, full rule edits, validation and conflict-aware atomic saves.
- [ ] Refresh existing shortcuts; localize statuses and readable journal headings.
- [ ] Fix touch accessibility; run tests/compile and commit.

## Task 5: Build, wrapper, acceptance and persistence
Files: tools/verification runner, module wrapper, docs, 4PDA draft.
- [ ] Run complete JVM/Python suites and full lint; list all outstanding findings.
- [ ] Build/sign both variants and verify package/version/certificate/hash.
- [ ] Build module using exact clone APK; test APK/XML and wrapper checks.
- [ ] Fresh whole-branch review; resolve important findings with tests.
- [ ] Record acceptance matrix, limitations, source manifest and JSONL run log.
- [ ] Save all deliverables; push isolated branch with skip-ci commit; no release.

## Pre-flight
Tasks 2/3/4 share data gate: lock order must always be data gate -> store/controller,
never store -> data gate. No suspension while holding a thread-bound lock.
Task 4 consumes atomic update APIs from Task 2; it must reject deleted/stale edits.
Task 5 consumes exact final signed binaries; never re-sign nested APKs independently.
