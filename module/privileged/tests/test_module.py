import hashlib
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from apk_verify import verify_apk_v2, EXPECTED_CERT_SHA256, _sig_block
from build import build, read_manifest


class TestStatic(unittest.TestCase):
    def test_allowlist_has_exactly_upstream_permissions(self):
        import xml.etree.ElementTree as ET
        root = ET.parse(ROOT / 'privapp-permissions-com.aistra.hail.enhanced.xml').getroot()
        app = root.find(".//privapp-permissions[@package='com.aistra.hail.enhanced']")
        self.assertIsNotNone(app)
        self.assertEqual({i.attrib['name'] for i in app.findall('permission')}, {
            'android.permission.PACKAGE_USAGE_STATS',
            'android.permission.FORCE_STOP_PACKAGES',
            'android.permission.CHANGE_COMPONENT_ENABLED_STATE',
            'android.permission.MANAGE_APP_OPS_MODES',
        })

    def test_no_unsafe_boot_scripts(self):
        for name in ('service.sh', 'post-fs-data.sh', 'sepolicy.rule', 'system.prop', 'uninstall.sh'):
            self.assertFalse((ROOT / name).exists(), name)

    def test_unsigned_blob_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'unsigned.apk'
            with zipfile.ZipFile(p, 'w') as z:
                z.writestr('AndroidManifest.xml', b'not valid')
            with self.assertRaises(ValueError):
                verify_apk_v2(p)


@unittest.skipUnless(os.environ.get('TEST_APK'), 'Set TEST_APK to verify full release APK + negatives')
class TestBundledUpstreamAPK(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.apk = Path(os.environ['TEST_APK']).resolve()
        if not cls.apk.is_file():
            raise RuntimeError('TEST_APK does not point to a file')

    def test_original_signature_verified(self):
        self.assertEqual(verify_apk_v2(self.apk), EXPECTED_CERT_SHA256)

    def test_changed_signer_pinning_rejected(self):
        with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
            verify_apk_v2(self.apk, expected_cert='00' * 32)

    def test_tampered_signed_zip_content_rejected(self):
        apk = bytearray(self.apk.read_bytes())
        _blk, start, _cd, _eocd = _sig_block(apk)
        self.assertGreater(start, 4096)
        apk[100] ^= 0x01  # tamper with a byte outside the signing block
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'tampered.apk'
            p.write_bytes(apk)
            with self.assertRaisesRegex(ValueError, 'content digest mismatch'):
                verify_apk_v2(p)

    def test_tampered_signature_rejected(self):
        apk = bytearray(self.apk.read_bytes())
        block, _start, _cd, _eocd = _sig_block(apk)
        # Alter one byte in the v2 block's cryptographic signature, not ZIP data.
        import struct
        _b, st, cd, _eocd = _sig_block(apk)
        size = struct.unpack_from('<Q', apk, cd - 24)[0]
        start = cd - size - 8
        # Signature is after 8byte length + 4byte block ID + 4byte signers length,
        # 4byte signer length + 4byte signed-data length + signed-data bytes.
        off = start + 8 + 8 + 4
        l_signers = struct.unpack_from('<I', apk, off)[0]
        self.assertGreater(l_signers, 100)
        off += 4 + 4
        l_signed = struct.unpack_from('<I', apk, off)[0]
        off += 4 + l_signed
        l_sigs = struct.unpack_from('<I', apk, off)[0]
        self.assertGreater(l_sigs, 100)
        off += 4 + 4 + 4 + 4
        apk[off] ^= 0x01
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'tampered_sig.apk'
            p.write_bytes(apk)
            with self.assertRaisesRegex(ValueError, 'signature verification failed'):
                verify_apk_v2(p)

    def test_build_reproducible_and_apk_unmodified(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / 'a.zip', Path(tmp) / 'b.zip'
            build(self.apk, a)
            build(self.apk, b)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            with zipfile.ZipFile(a) as z:
                self.assertIsNone(z.testzip())
                self.assertEqual(z.read('system/priv-app/HailEnhanced/HailEnhanced.apk'), self.apk.read_bytes())
                self.assertTrue(z.read('module.prop').startswith(b'id=Hail-Enhanced-PrivApp\n'))
                self.assertIn('APK_SHA256.txt', z.namelist())
                self.assertIn('LICENSE', z.namelist())
                self.assertIn('TESTED_DEVICES.md', z.namelist())
                module_prop = dict(line.split('=', 1) for line in z.read('module.prop').decode('utf8').splitlines() if '=' in line)
                self.assertEqual(module_prop['version'], '1.0.0-hail1.11.0-enhanced.11-g18ff92c')
                self.assertEqual(int(module_prop['versionCode']), 111011)
                self.assertIn(b'Hail Enhanced Privileged 1.0.0', z.read('customize.sh'))


if __name__ == '__main__':
    unittest.main()


class TestInstaller(unittest.TestCase):
    """Execute sourced customize.sh in POSIX sh with mocked Magisk/KernelSU hooks."""
    @unittest.skipUnless(os.environ.get('TEST_APK'), 'Release APK needed')
    def test_install_mock_good_and_bad_api_and_corrupt_apk(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'module.zip'
            build(Path(os.environ['TEST_APK']), output)
            dest = Path(tmp) / 'module'
            dest.mkdir()
            with zipfile.ZipFile(output) as z:
                z.extractall(dest)
            installer = dest / 'customize.sh'
            runner = '''
ui_print() { printf '%s\\n' "$*"; }
abort() { printf 'ABORT:%s\\n' "$*"; exit 27; }
set_perm_recursive() { :; }
set_perm() { :; }
. "$MODPATH/customize.sh"
'''
            def run(api):
                env = dict(os.environ, MODPATH=str(dest), API=api, KSU='true')
                return subprocess.run(['sh', '-c', runner], capture_output=True, text=True, env=env)
            good = run('36')
            self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
            self.assertIn('SHA-256: OK', good.stdout)
            old = run('22')
            self.assertEqual(old.returncode, 27)
            self.assertIn('Requires Android API 23', old.stdout)
            malformed = run('abc')
            self.assertEqual(malformed.returncode, 27)
            self.assertIn('Cannot determine Android API', malformed.stdout)
            target = dest / 'system/priv-app/HailEnhanced/HailEnhanced.apk'
            apk = bytearray(target.read_bytes())
            apk[100] ^= 1
            target.write_bytes(apk)
            corrupted = run('36')
            self.assertEqual(corrupted.returncode, 27)
            self.assertIn('APK checksum mismatch', corrupted.stdout)
            target.write_bytes(Path(os.environ['TEST_APK']).read_bytes())
            xml = dest / 'system/etc/permissions/privapp-permissions-com.aistra.hail.enhanced.xml'
            xml.write_text('<permissions/>')
            corrupted_xml = run('36')
            self.assertEqual(corrupted_xml.returncode, 27)
            self.assertIn('Permissions XML checksum mismatch', corrupted_xml.stdout)
