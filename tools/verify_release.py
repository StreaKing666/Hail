#!/usr/bin/env python3
"""Verify both signed APKs and the exact clone APK embedded in the module; emit JSONL."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'module/privileged/tools'))
from apk_verify import verify_apk_v2
from build import read_manifest


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def verify(clone, original, module, aapt2):
    results = []
    for path, package in [(clone, 'com.aistra.hail.enhanced'), (original, 'com.aistra.hail')]:
        with zipfile.ZipFile(path) as archive:
            assert archive.testzip() is None, 'APK ZIP corruption'
            manifest = read_manifest(archive)
        assert manifest['package'] == package, 'Wrong package identity'
        assert manifest['versionCode'] == 111012, 'Wrong version code'
        assert str(manifest['versionName']).startswith('1.11.0-enhanced.12'), 'Wrong version name'
        assert manifest['minSdkVersion'] == 23, 'Android 6 compatibility floor changed'
        assert ('-original-package' in manifest['versionName']) == (package == 'com.aistra.hail')
        signer = verify_apk_v2(path)  # Includes full APK content authentication and pinned certificate.
        xml = subprocess.check_output([str(aapt2), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(path)], text=True)
        for suffix in ['.shizuku', '.action.FREEZE', '.action.APPLY_PROFILE']:
            assert package + suffix in xml, 'Manifest identity mismatch: ' + suffix
        other = 'com.aistra.hail.enhanced' if package == 'com.aistra.hail' else 'com.aistra.hail'
        if package == 'com.aistra.hail':
            assert other + '.action.' not in xml and other + '.shizuku' not in xml
        results.append(dict(file=path.name, status='PASS', sha256=digest(path), size_bytes=path.stat().st_size,
                            signer_sha256=signer, manifest=manifest))
    with zipfile.ZipFile(module) as archive:
        assert archive.testzip() is None
        embedded = archive.read('system/priv-app/HailEnhanced/HailEnhanced.apk')
        assert hashlib.sha256(embedded).hexdigest() == digest(clone), 'Module contains a different APK'
        xml = ET.fromstring(archive.read('system/etc/permissions/privapp-permissions-com.aistra.hail.enhanced.xml'))
        assert xml.find(".//privapp-permissions[@package='com.aistra.hail.enhanced']") is not None
        assert b'id=Hail-Enhanced-PrivApp' in archive.read('module.prop')
    results.append(dict(file=module.name, status='PASS', sha256=digest(module), size_bytes=module.stat().st_size))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['clone', 'original', 'module', 'aapt2', 'log']:
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    event = dict(run_id='hail-reliability-20261105', timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 stage='artifact_validation', commit_sha=subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip())
    try:
        event.update(status='PASS', artifacts=verify(args.clone, args.original, args.module, args.aapt2))
    except Exception as error:
        event.update(status='FAIL', error_type=type(error).__name__, reason=str(error))
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open('a') as stream:
        stream.write(json.dumps(event, ensure_ascii=False) + '\n')
    print(json.dumps(event, ensure_ascii=False, indent=2))
    sys.exit(0 if event['status'] == 'PASS' else 1)
