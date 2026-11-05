# Hail Enhanced reliability specification

Authority: user's eight-section task in this session (2026-11-05). Execute locally,
preserve data/formats, both package variants and the existing signing identity.
No cloud, Telegram, WebUI, signature bypass, destructive device operations or releases.

Source: origin/enhanced/audit-2026-11-03 at 18ff92c0fdbb1c462984e43aa005884ecb040419.
origin/main: 67a43c8ea7d43eddf51d840f3615b97ce6079c9b. The delivered original-package
archive differs in six files only: opt-in build identity, two reports, three tools.
Imported as commit 4170aeb; app logic equals the audited .11. Work is isolated on
enhanced/reliability-2026-11-05. Last APK hash: 293fd41889d36d50512fe264077a3ad5b37ece92486cd8ea7dd44a227e5c19eb.

Required: bounded/cancellable process execution; background profile/storage work;
visible fail-closed recovery; bounded export before allocation; Android 6 guard;
independent screen-off rules; serialized restore/edit/worker transactions; consistent
mode/user handling; detailed failures; lifecycle-safe editors with searchable labeled
apps/icons, trigger/action edits, localized statuses and shortcut renames.

Deliver signed clone and original-package APKs, updated clone module, complete source,
checksums, JSONL execution log, Markdown acceptance report and factual 4PDA text.
Use PASS/FAIL/BLOCKED/NOT_RUN. Real-device checks stay NOT_RUN without ADB.
Never place signing secrets in git or deliverables. Push only this isolated branch;
do not merge main or trigger build workflows. Keep Android minSdk 23.

Implementation choices: reuse existing Kotlin/AndroidX/WorkManager and JSON schemas.
Use one shared data-operation gate with fail-closed recovery, atomic store updates,
and explicit current-process user identity. Do not expand multiuser support silently.
Use a saved-state DialogFragment for editors. Retain format compatibility and bounded
backup limits (8 MiB/file, 40 MiB data, 48 MiB archive, 254 app files).
