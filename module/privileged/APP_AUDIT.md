# Результат проверки — 3 ноября 2026

Статус: **APK собран и подписан прежним ключом; аудит НЕ закрыт из-за ошибок lint. Это тестовая сборка, а не полностью проверенный финальный релиз.**

- Точный SHA исходников APK: `18ff92c0fdbb1c462984e43aa005884ecb040419`.
- Пакет: `com.aistra.hail.enhanced`; версия `1.11.0-enhanced.11-g18ff92c`, код `111011`; minSdk 23, targetSdk 36.
- Сертификат SHA256: `ae3ca1228d75917a561aa98efa4b5959af77095e1d089d26673b5567afa648cb`; подпись V1/V2/V3 проверена библиотекой apksig.
- APK SHA256: `8cd81ec51f1cfbd6388d3d737a98ff78f5219a82b07725cd4d94dacf5a6cea3b`.
- CI: https://github.com/StreaKing666/Hail/actions/runs/41577228715 — тесты и debug/release-компиляция успешны; lint завершился ошибкой. **Ровно один новый запуск Actions.**
- 77 JVM-тестов в CI: 0 ошибок; четыре теста Python прошли. Дополнительно четыре локальных проверки реальных классов хранилищ с минимальными Android-заглушками прошли. Это не проверка Android framework на устройстве.
- Патч проверен: применение к исходному SHA точно даёт итоговое Git tree `5d4f499b6612a1efa6b2305cfe1bbd582985f2b7`.
- Подпись и пакет подходят для обновления прежней release-установки с этим же сертификатом. Отдельный `.debug` этим APK не обновляется. Установка и перенос данных на устройстве не выполнялись.

## Очередь незавершённых задач

| Приоритет | Остаток | Что подтвердилось |
| --- | --- | --- |
| Высокий для Android 6 | HPolicy.kt:61: `clearProfileOwner`, API 24 без проверки при minSdk 23 | Одна ошибка NewApi; исходный файл не менялся относительно базы. Нужна защита совместимости и регрессия. |
| Проверить контракты API | HPackages.kt:25,33,42: три WrongConstant | Lint не принимает контракты передаваемых флагов. Это статические замечания, не доказательство трёх отдельных сбоев на устройстве. Файл исходный. |
| Локализация | 257 MissingTranslation | 253 существующих ключа и четыре новых ключа редакторов не переведены во все имеющиеся локали. Русский и английский тексты новых редакторов добавлены. Нужны переводы или явная согласованная политика частичной локализации, без маскировки ошибок. |
| Устройство/интеграция | Root, Shizuku, Dhizuku, ярлыки, аварийный импорт, работа правил при выключении экрана | Не проверялись аппаратно. |
| Дополнительная проверка исходного кода | HS​hell.execute ждёт процесс до чтения stdout; экспорт FullBackupStorage читает файл до проверки лимита | Кандидаты на блокировку при большом выводе и повышенный расход памяти. Требуют отдельного воспроизведения и тестов; не объявлены исправленными. |

Новые попытки CI не запускались согласно ограничению. Итоговый APK содержит проверенные до CI изменения; перечисленные остатки не выданы за исправленные. Эта итоговая запись добавлена локально после сборки и не меняет исходный SHA APK; архив исходников и патч сохранены для точного построенного коммита. Ветка main и исходная enhanced/full-modernization-v1 не изменялись.

---

# Hail Enhanced audit — 3 November 2026

## Provenance

Base SHA `765a6adfb6457eefaeb80394943d76e537534743`, tree `e8aa235376fbd12910c7dc8dbd735f6951779d5c`. Both reconstructed and verified exactly after scratch cleanup removed the earlier unpushed checkout. Remote audit branch and planned audit documents were absent. No active local writer/build observed. Main, original Hail and source branch untouched.

## Changes

- Staged profile name, application membership and frozen/unfrozen state editing. Cancel performs no save. Existing unavailable applications retained unless removed; self package excluded; IDs and creation times retained.
- Staged rule name, application selection and cooldown (0–86400 seconds) editing. Existing trigger/action/enabled state retained. New rules use the editor before saving. Cooldown is a minimum interval, not delayed execution.
- Deleting profiles removes dynamic/long-lived shortcuts and disables pinned shortcuts. Launcher-owned pinned icons may remain disabled. Existing shortcut namespaces preserved.
- UUID journal IDs prevent distinct controller instances overwriting operations at the same timestamp. Old behavior reproduced: expected two journal entries, observed one.
- Shared atomic file writer uses same-directory rename without unlinking existing data first. Applied to profiles, rules, restore snapshots/journals/data. File contents synchronized before rename; sudden power-loss durability of directory entries is not claimed.
- Profile/rule stores share locks across instances and reject corrupt JSON instead of silently overwriting it with empty data. Settings management reads show errors; worker evaluation failure does not require a successful audit write.
- Version 1.11.0-enhanced.11, code 111011. Release ID remains `com.aistra.hail.enhanced`; existing debug ID remains `com.aistra.hail.enhanced.debug`. Release builds without the permanent key are unsigned, never silently debug-signed.

## Verification

- Baseline: 69 JVM tests and four Python migration tests passed locally.
- Editors: five new tests, 74 full-suite tests passed. Journal regression failed before fix, then 75 passed. Atomic file regression failed against prior delete-before-rename algorithm, then all 77 passed.
- Offline Kotlin compiler uses cached dependencies. Pure JVM tests cover domain, backup/archive/restore planning and tag editing, not Android UI/stores.
- Local Android Gradle attempt failed before configuration: wrapper distribution absent, network unreachable; Android SDK absent. No repeated download attempts.
- One isolated-branch CI run prepared for Android tests, debug/unsigned-release compilation and lint. Results not yet confirmed at this source commit.
- Independent review found and corrected a generated store expression syntax error and missing error handling introduced by fail-closed reads. Full Android compilation remains a required gate.

## Backup/import

Existing tests cover bounded ZIP extraction, traversal rejection, manifest hashes, selective MERGE/REPLACE, preference scope, tag/app remapping and resetting device-bound settings. Archive format limits remain 8 MiB/file, 40 MiB total files and 48 MiB/archive. Arbitrary 500 MiB archives are unsupported by this Hail format; limits were not raised blindly. Storage replacement gap fixed; archive and JSON schemas unchanged.

## Remaining checks / limitations

APK/package/version/signature/SHA256 and lint await Android CI and artifact retrieval. Root/Shizuku/Dhizuku, real freeze/thaw, pinned launcher behavior, interrupted restore, concurrent import/automation, screen-off delivery, rotation/fonts and visual layout are NOT device-verified. No complete bug-free claim is made. Dialog drafts close without saving on configuration changes. Read-modify-write across separate store calls and restore/automation cross-component transactions need integration coverage. A profile rename does not refresh already pinned shortcut labels automatically.

## Decisions

Use an isolated CI branch only because SDK/wrapper recovery is blocked; one run, no force-push and no secrets committed. Preserve debug isolation to protect existing release data. Continue from the exact upstream snapshot rather than treating old pruned-workspace logs as current verification.
