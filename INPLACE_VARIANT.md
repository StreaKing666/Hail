# Original-package Enhanced .11 variant

Requested use: existing original Hail installed through Hail-System-PrivApp, with a user-installed update in /data/app. User reports Lucky Patcher signature patches.

Build flag: -PhailOriginalPackage=true. Package becomes com.aistra.hail; manifest actions, provider authorities and BuildConfig IDs follow it. Default clone remains com.aistra.hail.enhanced. VersionCode remains 111011; versionName adds -original-package. Application logic and legacy data formats are unchanged.

Deliverable: signed APK update for the existing package; keep the original system module. Signature is the user's Enhanced key, not upstream's key. Standard Android will reject it as an update of upstream without a working relevant framework patch. Patch operation, actual data preservation, privileges and reboot behavior cannot be verified here.

Before installation: make an external backup of original Hail configuration/data and retain the original APK. Do not uninstall the original app, remove its system module, clear data or remove installed updates as a migration step. Android installations must be tested on the user's device; no universal compatibility claim.
