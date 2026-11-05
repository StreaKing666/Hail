# Enhanced .11 privileged module plan

User specification: package this exact signed Enhanced .11 APK like the referenced Hail Universal module, and explain coexistence with the original.

Architecture: reuse the GPL wrapper, keeping the APK byte-identical; new module ID Hail-Enhanced-PrivApp, APK directory HailEnhanced, package-specific permission XML. Install through manager, not recovery. No app rebuilding, root scripts at boot, policy changes, data migration or deletion. Action is read-only. Original Hail remains separate.

1. Run adapted wrapper tests against the old wrapper to demonstrate missing Enhanced support.
2. Adapt pinned signer, manifest checks, metadata, isolated paths and integrity checks. Add tested duplicate-module detection that allows the original.
3. Run all wrapper tests, shell syntax checks, verify reproducibility and byte-identical embedded APK. Document actual limitations and installation/removal.
4. Deliver module ZIP plus source ZIP including wrapper and matching application source; no GitHub Actions or publishing.

Device acceptance remains pending: system/privileged flags, actual permission grants, freeze/thaw and reboot on the user's root/mount combination. Prior device tests of original module do not transfer to this build.

Completed: Tasks 1–3. Source archive and local deliverable prepared; external device acceptance remains pending.
