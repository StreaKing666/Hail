#!/usr/bin/env python3
"""Report untranslated Android string/plural resources without altering locales.

Counts are resource keys, not words or plural items. A regional locale can inherit
an existing language translation; English/default fallback is counted as missing.
This is an inventory, not a replacement for Android lint or translation review.
"""

import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
LOCALE = re.compile(r"values-([a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)$")


def resources(directory):
    result = {}
    for path in sorted(directory.glob("*.xml")):
        for element in ET.parse(path).getroot():
            if element.tag not in {"string", "plurals"}:
                continue
            name = element.attrib["name"]
            if name in result:
                raise ValueError(f"Duplicate string/plural {name!r} in {directory}")
            result[name] = element
    return result


def inventory(res):
    default = resources(res / "values")
    required = {name for name, element in default.items()
                if element.attrib.get("translatable") != "false"}
    locales = {}
    for directory in sorted(res.glob("values-*")):
        match = LOCALE.fullmatch(directory.name)
        if match:
            # Include empty locale directories: they are present in lint's scope.
            locales[match[1]] = resources(directory)
    rows = []
    for locale, entries in locales.items():
        inherited = {}
        if "-r" in locale:
            inherited = locales.get(locale.split("-r", 1)[0], {})
        missing = sorted(required - entries.keys() - inherited.keys())
        rows.append({
            "locale": locale,
            "translated_keys_present": len(required & entries.keys()),
            "translated_keys_inherited": len((required - entries.keys()) & inherited.keys()),
            "missing_key_count": len(missing),
            "missing_keys": missing,
        })
    return {
        "translatable_default_keys": len(required),
        "locale_count": len(rows),
        "missing_locale_key_pairs": sum(row["missing_key_count"] for row in rows),
        "locales": rows,
    }


def markdown(report):
    lines = [
        "# Translation inventory",
        "",
        "Generated with `python tools/translation_inventory.py --format markdown`.",
        "",
        f"Default translatable string/plural keys: {report['translatable_default_keys']}. "
        f"Locale directories: {report['locale_count']}. "
        f"Missing locale/key pairs: {report['missing_locale_key_pairs']}.",
        "",
        "Counts include empty locale directories. Regional locales inherit existing "
        "language translations when available. Default English fallback does not count "
        "as a translation. Existing text is counted by presence; this report does not "
        "claim that existing translations have been linguistically reviewed. JSON output "
        "includes every missing key for translation work.",
        "",
        "| Locale | Present | Inherited | Missing |",
        "| --- | ---: | ---: | ---: |",
    ]
    for row in report["locales"]:
        lines.append(f"| {row['locale']} | {row['translated_keys_present']} | "
                     f"{row['translated_keys_inherited']} | {row['missing_key_count']} |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res", type=Path, default=ROOT / "app/src/main/res")
    parser.add_argument("--format", choices=("json", "markdown"), default="markdown")
    args = parser.parse_args()
    report = inventory(args.res)
    print(json.dumps(report, ensure_ascii=False, indent=2)
          if args.format == "json" else markdown(report))


if __name__ == "__main__":
    main()
