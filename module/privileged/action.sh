#!/system/bin/sh
# Read-only diagnostics. Never grant permissions or modify device state.
MODDIR="${0%/*}"
PKG=com.aistra.hail.enhanced
APP=/system/priv-app/HailEnhanced/HailEnhanced.apk
XML=/system/etc/permissions/privapp-permissions-com.aistra.hail.enhanced.xml

echo '=== Hail Enhanced: READ-ONLY diagnostic ==='
echo "Android: $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
echo "Module ID: ${MODDIR##*/}"
for MARKER in disable remove skip_mount; do
  if [ -f "$MODDIR/$MARKER" ]; then echo "Module flag: $MARKER"; fi
done
if [ -r "$APP" ]; then echo 'System APK: VISIBLE'; else echo 'System APK: NOT VISIBLE'; fi
if [ -r "$XML" ]; then echo 'Permissions XML: VISIBLE'; else echo 'Permissions XML: NOT VISIBLE'; fi

if command -v pm >/dev/null 2>&1; then
  echo 'Package path(s):'
  pm path "$PKG" 2>&1 || true
  if pm list packages -s "$PKG" 2>/dev/null | grep -Fx "package:$PKG" >/dev/null 2>&1; then
    echo 'PackageManager system classification: YES'
  else
    echo 'PackageManager system classification: NO'
  fi
else
  echo 'PackageManager command pm: UNAVAILABLE'
fi

if command -v dumpsys >/dev/null 2>&1; then
  echo 'PackageManager flags and permission lines (inspect granted=true):'
  # Output varies by Android ROM; do not claim grants merely because a
  # requested permission appears in package metadata.
  dumpsys package "$PKG" 2>/dev/null | grep -E \
    '(^[[:space:]]*(flags=|privateFlags=|pkgFlags=|privatePkgFlags=)|android\.permission\.(PACKAGE_USAGE_STATS|FORCE_STOP_PACKAGES|CHANGE_COMPONENT_ENABLED_STATE|MANAGE_APP_OPS_MODES):)' | head -n 32
fi

echo 'Notes:'
echo '- A /data/app update may replace the APK code path of a system app.'
echo '- Check system classification, PRIVILEGED flag and grant states.'
echo '- If an expected permission is missing: check logcat -b all for privapp warnings.'
echo '- This report does not change settings, permissions, app data or the system.'
