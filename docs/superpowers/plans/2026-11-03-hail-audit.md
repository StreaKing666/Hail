# Hail Enhanced audit — 3 November 2026

Base: 765a6adfb6457eefaeb80394943d76e537534743 (verified original Git tree and commit).
The earlier unpushed audit checkout was pruned. This plan explicitly resumes from recovered upstream; old logs are not current verification.

1. Restore exact sources, check branch/workflow/artifacts and active processes. DONE: no remote audit branch, no active local build, no APK artifacts.
2. Run baseline JVM/Python regression tests offline using cached Kotlin compiler; SDK and wrapper distribution are absent.
3. Implement staged profile name/package/state editing and rule name/package/cooldown editing with pure validation tests. No writes on cancel; preserve IDs and schema.
4. Remove dynamic and disable pinned profile shortcuts when deleting profiles. Test shared shortcut ID generation; Android launcher behavior remains a device check.
5. Review persistence/backup risks; fix confirmed data-loss risks with regression coverage where feasible; do not rewrite archive schema.
6. Run regression tests, review whole diff, request one independent review, fix important findings.
7. One isolated-branch CI run for Android compilation, unit tests, lint and signed release or explicit debug fallback. Do not change main/original/source branch.
8. Save source archive, patch, report and verified APK if build succeeds. Record blockers and remaining device checks honestly.

Constraints: no force push, no secrets in outputs, no hardware verification claims. The rule delay control edits the existing cooldown (minimum interval), not a new delayed execution semantic.
