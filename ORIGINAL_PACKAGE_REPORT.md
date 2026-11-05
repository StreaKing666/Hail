# Original-package update variant — 2026-11-05

APK: Hail-Enhanced-original-package-enhanced11-signed.apk
Package: com.aistra.hail
Version: 1.11.0-enhanced.11-original-package-g3957f11
VersionCode: 111011
Base app: Enhanced .11, source originally recorded as 18ff92c0fdbb1c462984e43aa005884ecb040419. The g3957f11 suffix identifies the local restored-source snapshot used by Gradle, not a published upstream commit. The archive contains the complete variant source and diff.

Observed user device: Android 16 / SDK36, module Hail-System-PrivApp; original com.aistra.hail runs from /data/app as UPDATED_SYSTEM_APP, SYSTEM, PRIVILEGED. Three requested operational permissions are shown granted in the user-provided diagnostic. No live device connection was used.

Packaging change: opt-in -PhailOriginalPackage=true chooses original package; default clone remains com.aistra.hail.enhanced. Manifest placeholders, BuildConfig IDs and HailApi action constants follow the selected applicationId. No application logic/schema was changed. Retain the original system module; this deliverable is an APK update, not a module replacement.

Fresh validation: local Gradle testDebugUnitTest and assembleRelease succeeded. 77 JVM tests, zero failures/errors/skips. Four Python legacy-export tests passed. lintVitalRelease passed; full lint was not rerun and existing Enhanced .11 audit limitations remain. Compiled manifest identity/API actions/provider namespace, version, ZIP CRC and paths passed. apksigner verifies v1/v2; a separate v2 verifier validates the authenticated entire APK content against the pinned Enhanced certificate.

The signer is the user's Enhanced certificate, not upstream Hail's certificate. Ordinary Android will reject it as an update of upstream. User reports Lucky Patcher signature patches, but neither patch operation nor installation, actual original-data compatibility, permission retention and reboot behavior were verified on device. No data-preservation guarantee is made. Existing .11 experimental functionality and known limitations remain.

Backup helper: run as root before updating. It force-stops original Hail and archives user-0 credential-encrypted data plus copies the currently installed APK(s), with listings and SHA256. It does not cover other users or device-encrypted data. Raw-data restoration requires correct UID/SELinux labels and must not be done blindly. Keep backups private. Never uninstall, clear data or remove installed updates just to bypass an installation error.

Build from source in a Git checkout with JDK21/Android SDK36: tools/build_original_package.sh. HAIL_SIGNING_* environment variables may be supplied externally for signing; no keystore/passwords are included. An extracted source archive must be initialized/committed to a local Git repository before running the original Gradle metadata calls.

Independent source review found no concrete critical/important issues in package propagation or legacy-data readers. Real device acceptance remains pending.

APK SHA256: 293fd41889d36d50512fe264077a3ad5b37ece92486cd8ea7dd44a227e5c19eb
Signer SHA256: ae3ca1228d75917a561aa98efa4b5959af77095e1d089d26673b5567afa648cb
