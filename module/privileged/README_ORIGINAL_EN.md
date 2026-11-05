# Hail Enhanced .13 — original-package systemless module

Package `com.aistra.hail`; module ID `Hail-System-PrivApp`; system APK `system/priv-app/Hail/Hail.apk`. The signed APK is embedded unchanged. An existing module with the same ID is updated; other known active injectors for the same package are rejected.

Export a backup outside the app, install through the root manager and reboot. KernelSU/ReSukiSU needs a working system mount provider. Keep a single compatible metamodule; device-specific NoMount support is not verified here. Then run Action and check `Active APK matches module: YES`, system classification, PRIVILEGED and actual permission grants.

This does not bypass Android signature verification. A differently signed upstream installation or an APK under `/data/app` can prevent replacement or remain active. The installer does not uninstall apps, clear data or modify signature patches. Backup first; actual replacement, data migration, reboot and rollback on a device are NOT_RUN.

On Android 16, MANAGE_APP_OPS_MODES has no privileged protection flag; the allowlist alone does not grant it. FORCE_STOP_PACKAGES and CHANGE_COMPONENT_ENABLED_STATE are separately privileged permissions. Choose the system-app Disable or Force stop mode after verifying the relevant grants. Force stop is cleared by launching the app, not an unfreeze command.

See APP_AUDIT.md for fresh APK verification and known limitations: translation lint debt, synchronous manual operations, single immediate package-state verification. Read README_RU.md for installation and rollback details.

Build: `python3 tools/build.py --variant original --apk signed-original-package.apk --output module.zip` (Python 3 + cryptography). No boot scripts, SELinux changes or physical system writes. GPL-3.0; see LICENSE/NOTICE.
