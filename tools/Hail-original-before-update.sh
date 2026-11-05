#!/system/bin/sh
set -eu

[ "$(id -u)" = 0 ] || {
  echo 'Run this script through su.'
  exit 1
}

data=/data/user/0/com.aistra.hail
[ -d "$data" ] || {
  echo 'Original Hail user-0 data directory not found. Nothing changed.'
  exit 1
}
command -v tar >/dev/null 2>&1 || exit 1
command -v sha256sum >/dev/null 2>&1 || exit 1

destination="/sdcard/Download/Hail-before-update-$(date +%Y%m%d-%H%M%S)"
mkdir "$destination" || exit 1
am force-stop com.aistra.hail >/dev/null 2>&1
tar -czf "$destination/original-hail-data.tar.gz" -C "$data" .
tar -tzf "$destination/original-hail-data.tar.gz" > "$destination/contents.txt"
sha256sum "$destination/original-hail-data.tar.gz" > "$destination/SHA256SUMS.txt"
mkdir "$destination/apk"
pm path com.aistra.hail > "$destination/apk-paths.txt"
index=0
while IFS= read -r entry; do
  case "$entry" in
    package:/*.apk)
      source=${entry#package:}
      [ -f "$source" ] || exit 1
      index=$((index + 1))
      cp "$source" "$destination/apk/$index-$(basename "$source")"
      ;;
    *) echo 'Unexpected APK path. Backup needs inspection.'; exit 1 ;;
  esac
done < "$destination/apk-paths.txt"
[ "$index" -gt 0 ] || exit 1
sha256sum "$destination"/apk/*.apk >> "$destination/SHA256SUMS.txt"
echo "Backup created: $destination"
echo 'Contains private application data. Keep this folder private.'
echo 'Includes the installed APK(s) and user-0 credential-encrypted private data.'
echo 'Other users and device-encrypted storage are not included.'
echo 'Restoring this raw backup requires the correct package UID and SELinux labels.'
