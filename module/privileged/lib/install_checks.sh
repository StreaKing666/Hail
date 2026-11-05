#!/system/bin/sh
# Returns nonzero for a known active module exposing the Enhanced package.
# The original com.aistra.hail and its module are intentionally permitted.
hail_check_module_tree() {
  for hail_other in "$1"/*; do
    [ -d "$hail_other" ] || continue
    [ "${hail_other##*/}" = 'Hail-Enhanced-PrivApp' ] && continue
    [ -f "$hail_other/disable" ] && continue
    [ -f "$hail_other/remove" ] && continue
    [ -f "$hail_other/skip_mount" ] && continue
    for hail_base in "$hail_other/system" "$hail_other/system/product" "$hail_other/system/system_ext"; do
      if [ -e "$hail_base/priv-app/HailEnhanced/HailEnhanced.apk" ] ||
         [ -e "$hail_base/app/HailEnhanced/HailEnhanced.apk" ]; then
        printf '%s\n' "! Conflicting Enhanced APK in module: ${hail_other##*/}"
        return 1
      fi
      for hail_xml in "$hail_base"/etc/permissions/*.xml; do
        [ -r "$hail_xml" ] || continue
        if grep -Eq "package[[:space:]]*=[[:space:]]*['\"]com[.]aistra[.]hail[.]enhanced['\"]" "$hail_xml"; then
          printf '%s\n' "! Conflicting Enhanced permissions in module: ${hail_other##*/}"
          return 1
        fi
      done
    done
  done
  return 0
}
