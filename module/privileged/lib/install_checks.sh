#!/system/bin/sh
# Reject known active injectors for the selected package, except this module's update.
hail_check_module_tree() {
  hail_target_package="${2:-com.aistra.hail.enhanced}"
  hail_target_id="${3:-Hail-Enhanced-PrivApp}"
  hail_target_dir="${4:-HailEnhanced}"
  case "$hail_target_package" in
    com.aistra.hail|com.aistra.hail.enhanced) ;;
    *) return 2 ;;
  esac
  hail_target_pattern="$(printf '%s' "$hail_target_package" | sed 's/[.]/[.]/g')"
  for hail_other in "$1"/*; do
    [ -d "$hail_other" ] || continue
    [ "${hail_other##*/}" = "$hail_target_id" ] && continue
    [ -f "$hail_other/disable" ] && continue
    [ -f "$hail_other/remove" ] && continue
    [ -f "$hail_other/skip_mount" ] && continue
    for hail_base in "$hail_other/system" "$hail_other/system/product" "$hail_other/system/system_ext"; do
      if [ -e "$hail_base/priv-app/$hail_target_dir/$hail_target_dir.apk" ] ||
         [ -e "$hail_base/app/$hail_target_dir/$hail_target_dir.apk" ]; then
        printf '%s\n' "! Conflicting $hail_target_package APK in module: ${hail_other##*/}"
        return 1
      fi
      for hail_xml in "$hail_base"/etc/permissions/*.xml; do
        [ -r "$hail_xml" ] || continue
        if grep -Eq "package[[:space:]]*=[[:space:]]*['\"]$hail_target_pattern['\"]" "$hail_xml"; then
          printf '%s\n' "! Conflicting $hail_target_package permissions in module: ${hail_other##*/}"
          return 1
        fi
      done
    done
  done
  return 0
}
