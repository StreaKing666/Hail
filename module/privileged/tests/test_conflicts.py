import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ConflictChecks(unittest.TestCase):
    def check(self, make_fixture):
        library = ROOT / 'lib/install_checks.sh'
        self.assertTrue(library.is_file(), 'Enhanced-specific conflict checker missing')
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            make_fixture(directory)
            return subprocess.run(['sh', '-c', '. "$1"; hail_check_module_tree "$2"',
                                   'test', str(library), str(directory)],
                                  capture_output=True, text=True)

    def test_original_hail_module_can_coexist(self):
        def fixture(root):
            apk = root / 'Hail-System-PrivApp/system/priv-app/Hail/Hail.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'original')
        self.assertEqual(self.check(fixture).returncode, 0)

    def test_active_duplicate_enhanced_apk_is_rejected(self):
        def fixture(root):
            apk = root / 'another/system/priv-app/HailEnhanced/HailEnhanced.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'duplicate')
        self.assertNotEqual(self.check(fixture).returncode, 0)

    def test_renamed_module_with_enhanced_allowlist_is_rejected(self):
        def fixture(root):
            xml = root / 'another/system/product/etc/permissions/renamed.xml'
            xml.parent.mkdir(parents=True)
            xml.write_text('<permissions><privapp-permissions package="com.aistra.hail.enhanced" /></permissions>')
        self.assertNotEqual(self.check(fixture).returncode, 0)

    def test_disabled_duplicate_does_not_block_installation(self):
        def fixture(root):
            apk = root / 'another/system/priv-app/HailEnhanced/HailEnhanced.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'duplicate')
            (root / 'another/disable').touch()
        self.assertEqual(self.check(fixture).returncode, 0)

    def test_updating_same_module_is_allowed(self):
        def fixture(root):
            apk = root / 'Hail-Enhanced-PrivApp/system/priv-app/HailEnhanced/HailEnhanced.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'previous')
        self.assertEqual(self.check(fixture).returncode, 0)
