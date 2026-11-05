#!/usr/bin/env python3
"""Build a Magisk / KernelSU Hail priv-app module without modifying or resigning Hail.apk.

Dependencies: Python 3 + cryptography (APK v2 signature verifier). Usage: python tools/build.py --apk Hail.apk --output Hail.zip
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from apk_verify import verify_apk_v2

EXPECTED_PACKAGE = 'com.aistra.hail.enhanced'
ROOT = Path(__file__).resolve().parent.parent


def read_manifest(apk: zipfile.ZipFile) -> dict[str, str | int]:
    """Get package/version/minSdk from compiled Android Binary XML (stdlib only)."""
    b = apk.read('AndroidManifest.xml')
    if len(b) < 8 or struct.unpack_from('<H', b, 0)[0] != 0x0003:
        raise ValueError('Invalid binary AndroidManifest.xml')

    def u16(at): return struct.unpack_from('<H', b, at)[0]
    def u32(at): return struct.unpack_from('<I', b, at)[0]
    def var8(at):
        first = b[at]; at += 1
        if first & 0x80:
            return ((first & 0x7f) << 8) | b[at], at + 1
        return first, at
    def var16(at):
        first = u16(at); at += 2
        if first & 0x8000:
            return ((first & 0x7fff) << 16) | u16(at), at + 2
        return first, at
    def read_string(at, utf8):
        if utf8:
            _, at = var8(at)
            length, at = var8(at)
            return b[at:at + length].decode('utf8')
        length, at = var16(at)
        return b[at:at + length * 2].decode('utf-16le')

    strings = []
    result = {}
    pos = 8
    while pos < len(b):
        typ, hsize, size = struct.unpack_from('<HHI', b, pos)
        if size < 8 or pos + size > len(b):
            raise ValueError('Corrupt manifest XML chunks')
        if typ == 0x0001:
            count = u32(pos + 8)
            flags = u32(pos + 16)
            start = pos + u32(pos + 20)
            strings = [read_string(start + u32(pos + hsize + j * 4), bool(flags & 0x100))
                       for j in range(count)]
        elif typ == 0x0102:
            name_idx = u32(pos + 20)
            name = strings[name_idx]
            if name not in ('manifest', 'uses-sdk'):
                pos += size
                continue
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', b, pos + 24)
            for j in range(attr_count):
                a = pos + 16 + attr_start + attr_size * j
                _, idx, raw, _, _, dtype, data = struct.unpack_from('<IIIHBBI', b, a)
                key = strings[idx]
                if raw != 0xffffffff:
                    value = strings[raw]
                elif dtype == 0x03:
                    value = strings[data]
                elif dtype in (0x10, 0x11):
                    value = data
                else:
                    continue
                if key in ('package', 'versionCode', 'versionName', 'minSdkVersion', 'targetSdkVersion'):
                    result[key] = value
        pos += size
    for req in ('package', 'versionCode', 'versionName', 'minSdkVersion'):
        if req not in result:
            raise ValueError(f'AndroidManifest missing {req}')
    return result


def add_zip_bytes(z: zipfile.ZipFile, name: str, data: bytes, executable=False):
    zi = zipfile.ZipInfo(name, date_time=(2026, 10, 30, 0, 0, 0))
    zi.create_system = 3
    zi.external_attr = ((0o100755 if executable else 0o100644) << 16)
    zi.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(zi, data, compresslevel=9)


def build(apk_path: Path, output: Path, variant: str = 'clone'):
    if variant not in ('clone', 'original'):
        raise ValueError('Unknown module variant')
    original = variant == 'original'
    package = 'com.aistra.hail' if original else EXPECTED_PACKAGE
    module_id = 'Hail-System-PrivApp' if original else 'Hail-Enhanced-PrivApp'
    app_dir = 'Hail' if original else 'HailEnhanced'
    apk_bytes = apk_path.read_bytes()
    apk_hash = hashlib.sha256(apk_bytes).hexdigest()
    with zipfile.ZipFile(apk_path) as apk:
        if apk.testzip() is not None:
            raise ValueError('APK ZIP CRC check failed')
        manifest = read_manifest(apk)
        print('Manifest:', manifest)
        if manifest['package'] != package:
            raise ValueError(f'Expected {package} APK for {variant} module')
        if int(manifest['minSdkVersion']) < 23:
            raise ValueError('Unexpected minimum Android version: inspect manually')
    # Verify cryptographic APK v2 signature AND the signed entire content.
    # This fails closed on tampering, even if a stale META-INF cert remains.
    cert = verify_apk_v2(apk_path)
    if original:
        audit_bytes = (ROOT / 'original-audit.json').read_bytes()
        audit = json.loads(audit_bytes)
        if audit.get('apk_sha256') != apk_hash:
            raise ValueError('Audited APK mismatch: run a new audit before packaging another APK')
        if (audit.get('package') != package or audit.get('version_code') != manifest['versionCode']
                or audit.get('version_name') != manifest['versionName'] or audit.get('signer_sha256') != cert):
            raise ValueError('Audited APK identity mismatch')

    ver = str(manifest['versionName'])
    code = int(manifest['versionCode'])
    if code <= 0:
        raise ValueError('Invalid APK versionCode')
    module_version = f'1.0.0-hail{ver}'
    module_code = code
    permissions = (ROOT / 'privapp-permissions-com.aistra.hail.enhanced.xml').read_bytes().replace(
        EXPECTED_PACKAGE.encode(), package.encode())

    def template(name: str) -> bytes:
        s = (ROOT / name).read_text(encoding='utf-8')
        s = (s.replace('__HAIL_VERSION__', ver)
              .replace('__MODULE_VERSION__', module_version)
              .replace('__MODULE_CODE__', str(module_code))
              .replace('__APK_SHA256__', apk_hash)
              .replace('__XML_SHA256__', hashlib.sha256(permissions).hexdigest())
              .replace('__PACKAGE__', package)
              .replace('__APP_DIR__', app_dir)
              .replace('__MODULE_ID__', module_id)
              .replace('__TARGET_LABEL__', 'original replacement' if original else 'clone')
              .replace('__MIN_SDK__', str(manifest['minSdkVersion'])))
        if any(token in s for token in ('__MODULE_', '__HAIL_', '__APK_', '__XML_', '__MIN_SDK__', '__PACKAGE__', '__APP_DIR__', '__TARGET_LABEL__')):
            raise ValueError('Unsubstituted template tokens')
        return s.encode('utf-8')

    root = ET.fromstring(permissions)
    app = root.find(f".//privapp-permissions[@package='{package}']")
    expected = {
        'android.permission.PACKAGE_USAGE_STATS',
        'android.permission.FORCE_STOP_PACKAGES',
        'android.permission.CHANGE_COMPONENT_ENABLED_STATE',
        'android.permission.MANAGE_APP_OPS_MODES',
    }
    if app is None or {n.get('name') for n in app.findall('permission')} != expected:
        raise ValueError('Invalid Hail privileged permissions XML')

    files = {
        'module.prop': (template('module.prop.in'), False),
        'customize.sh': (template('customize.sh.in'), True),
        'action.sh': (template('action.sh.in'), True),
        'lib/install_checks.sh': ((ROOT / 'lib/install_checks.sh').read_bytes(), False),
        f'system/priv-app/{app_dir}/{app_dir}.apk': (apk_bytes, False),
        f'system/etc/permissions/privapp-permissions-{package}.xml': (permissions, False),
        'README_RU.md': ((ROOT / ('README_ORIGINAL_RU.md' if original else 'README_RU.md')).read_bytes(), False),
        'README_EN.md': ((ROOT / ('README_ORIGINAL_EN.md' if original else 'README_EN.md')).read_bytes(), False),
        'CHANGELOG.md': ((ROOT / 'CHANGELOG.md').read_bytes(), False),
        'LICENSE': ((ROOT / 'LICENSE').read_bytes(), False),
        'NOTICE': (template('NOTICE.in'), False),
        'APP_AUDIT.md': ((ROOT / 'APP_AUDIT.md').read_bytes(), False),
        'TESTED_DEVICES.md': ((ROOT / 'TESTED_DEVICES.md').read_bytes(), False),
        'RELEASE_NOTES_RU.md': ((ROOT / 'RELEASE_NOTES_RU.md').read_bytes(), False),
        'RELEASE_NOTES_EN.md': ((ROOT / 'RELEASE_NOTES_EN.md').read_bytes(), False),
        'APK_SHA256.txt': (f'{apk_hash}  Hail.apk\nSigner SHA256: {cert}\n'.encode(), False),
    }
    if original:
        for name in ('CHANGELOG.md', 'APP_AUDIT.md', 'TESTED_DEVICES.md', 'RELEASE_NOTES_RU.md', 'RELEASE_NOTES_EN.md'):
            files.pop(name)
        files['APP_AUDIT.md'] = ((ROOT.parent.parent / 'docs/APK_REAUDIT_ORIGINAL_2026-11-05.md').read_bytes(), False)
        files['AUDIT_METADATA.json'] = (audit_bytes, False)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w') as z:
        for name, (data, exe) in files.items():
            add_zip_bytes(z, name, data, exe)

    with zipfile.ZipFile(output) as z:
        assert z.testzip() is None
        assert hashlib.sha256(z.read(f'system/priv-app/{app_dir}/{app_dir}.apk')).hexdigest() == apk_hash
        assert z.read('module.prop').splitlines()[0] == f'id={module_id}'.encode()
        assert z.read('customize.sh').count(apk_hash.encode()) == 1
        assert z.read(f'system/priv-app/{app_dir}/{app_dir}.apk') == apk_bytes
    print('APK SHA256:', apk_hash)
    print('Signer SHA256:', cert)
    print('Created:', output, f'({output.stat().st_size:,} bytes)')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--variant', choices=('clone', 'original'), default='clone')
    args = p.parse_args()
    try:
        build(args.apk, args.output, args.variant)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as e:
        sys.exit(f'ERROR: {e}')
