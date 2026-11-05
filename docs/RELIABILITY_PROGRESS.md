# Execution ledger — docs/superpowers/plans/2026-11-05-reliability.md

2026-11-05: recovered inputs, source identity matched, isolated worktree created.
Imported only the six documented original-package variant files (4170aeb).
Baseline local Gradle test + full lint running; SDK36 and JDK21 restored locally.
Ruling: preserve minSdk23 and existing JSON schemas; multiuser audit addresses current
process identity, not new cross-profile UI — expanding scope could target wrong users.
Ruling: use existing audited app plus variant import, not older main; source comparison
shows exact app logic correspondence to delivered APK archive.

Task 1: complete (9f42c76), shell flood/timeout/interruption/nonzero tests PASS;
actual baseline Gradle suite: 77 tests, zero failures. Baseline full lint:
261 errors (257 translations, 3 WrongConstant, 1 NewApi), 65 warnings.
Task 2: bounded reader, data gate, fail-closed startup and durable restore journal implemented.
RED logs: new-tests-red.log, recovery-red.log. GREEN: 101 pure tests and Gradle integration.
Task 3: independent receiver policy, atomic rule mutation/worker transaction, current-user
identity and reversible freeze layers implemented; mode-policy RED -> GREEN (93 tests).
Task 4: saved JSON draft, asynchronous retained operations, complete rule editing,
labeled/searchable apps, lazy cached icons, shortcut rename and progress dialogs implemented.
Editor RED -> GREEN; integration builds/tests PASS. Device UI checks NOT_RUN.
Ruling: keep existing extra locales with Android fallback; do not invent translations or
suppress missing-translation checks. English/Russian new copy is implemented; other locales
remain explicit translation debt. Cost: non-English/Russian users see fallback text.
Ruling: no automatic signature bypass or cross-authority thaw. A mode lacking a required
thaw permission fails visibly; cost: user must select an appropriate authorized mode.

Final review (fresh reviewer, 533c632): no Critical issues; three Important findings.
Fixed bounded Shizuku path using shared process runner with separate stderr drain;
fixed Shizuku (and root ordinary uninstall) to explicit process user; fixed main-thread
contention through complete guarded UI mutations and asynchronous latest-wins service policy.
Review fixes RED: review-fixes-red.log. GREEN: 105 pure tests; Android suite passed.
Also publish immutable HomeAdapter tag snapshots on main and use safe snapshot iterators
for background restore. No privileged commands are replayed automatically on recovery.
Ruling: descendant survival was not reproduced by reviewer probes; no process-tree kill
feature is introduced. Cost: device-specific descendants need real-device validation.
Ruling: all device-specific lifecycle/permissions/installation checks are NOT_RUN; unit
and compile results are not a substitute. Artifact checks will run after signing.
Ruling: preserve other Android users when uninstalling; root/Shizuku now always use the
current Hail process user. Cost: uninstall no longer implicitly removes all users' copies.
Final review: minor (deferred): none from the reviewer.

Additional lifecycle finding in the same fix pass: a temporary content view during
startup recovery could leave FragmentManager without restored NavHost containers.
Always create the real containers, keep content invisible until recovery/authentication,
and recreate once after reconciliation. RecoveryUiPolicy RED -> GREEN covers normal,
pending-startup and retry transitions. Real Android lifecycle acceptance remains NOT_RUN.

Final: 108 Gradle JVM + 4 migration + 14 wrapper tests PASS. Both signed APKs and exact module verified. Full lint FAIL solely 278 MissingTranslation (69 warnings); no suppression. Device acceptance NOT_RUN. Module test version assertion updated to input manifest instead of old .11 constant.
