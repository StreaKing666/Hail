import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get('TEST_ORIGINAL_APK'), 'Set TEST_ORIGINAL_APK')
class OriginalReplacement(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.module = Path(cls.tmp.name) / 'built'
        output = Path(cls.tmp.name) / 'original.zip'
        result = subprocess.run([sys.executable, str(ROOT / 'tools/build.py'),
            '--variant', 'original', '--apk', os.environ['TEST_ORIGINAL_APK'], '--output', str(output)],
            capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        with zipfile.ZipFile(output) as z:
            z.extractall(cls.module)

    def test_original_cli_builds_exact_apk_with_original_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'original.zip'
            apk = Path(os.environ['TEST_ORIGINAL_APK'])
            result = subprocess.run([sys.executable, str(ROOT / 'tools/build.py'),
                '--variant', 'original', '--apk', str(apk), '--output', str(target)],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            with zipfile.ZipFile(target) as z:
                self.assertEqual(z.read('system/priv-app/Hail/Hail.apk'), apk.read_bytes())
                self.assertTrue(z.read('module.prop').startswith(b'id=Hail-System-PrivApp\n'))
                xml = ET.fromstring(z.read('system/etc/permissions/privapp-permissions-com.aistra.hail.xml'))
                self.assertIsNotNone(xml.find("privapp-permissions[@package='com.aistra.hail']"))
                self.assertNotIn('system/priv-app/HailEnhanced/HailEnhanced.apk', z.namelist())
                self.assertNotIn(b'com.aistra.hail.enhanced', z.read('customize.sh'))
                self.assertNotIn(b'com.aistra.hail.enhanced', z.read('action.sh'))

    def test_cloned_apk_is_rejected_without_overwriting_output(self):
        if not os.environ.get('TEST_APK'):
            self.skipTest('TEST_APK missing')
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'out.zip'
            target.write_bytes(b'previous output')
            result = subprocess.run([sys.executable, str(ROOT / 'tools/build.py'),
                '--variant', 'original', '--apk', os.environ['TEST_APK'], '--output', str(target)],
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(target.read_bytes(), b'previous output')

    def test_original_audit_must_match_the_actual_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'project/module/privileged'
            shutil.copytree(ROOT, root)
            audit_doc = root.parent.parent / 'docs/APK_REAUDIT_ORIGINAL_2026-11-05.md'
            audit_doc.parent.mkdir()
            shutil.copy2(ROOT.parent.parent / 'docs/APK_REAUDIT_ORIGINAL_2026-11-05.md', audit_doc)
            meta = root / 'original-audit.json'
            data = {'apk_sha256': '00' * 32}
            meta.write_text(json.dumps(data))
            result = subprocess.run([sys.executable, str(root / 'tools/build.py'),
                '--variant', 'original', '--apk', os.environ['TEST_ORIGINAL_APK'],
                '--output', str(Path(tmp) / 'out.zip')], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Audited APK mismatch', result.stderr)

    def test_installer_checks_hashes_and_only_queries_existing_package(self):
        runner = '''
ui_print() { printf '%s\\n' "$*"; }
abort() { printf 'ABORT:%s\\n' "$*"; exit 27; }
set_perm_recursive() { :; }
set_perm() { :; }
pm() { printf '%s\\n' "$*" >> "$CALL_LOG"; printf 'package:/data/app/old/base.apk\\n'; }
. "$MODPATH/customize.sh"
'''
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / 'module'
            shutil.copytree(self.module, dest)
            log = Path(tmp) / 'pm-calls'
            env = dict(os.environ, MODPATH=str(dest), CALL_LOG=str(log), API='36', KSU='true')
            good = subprocess.run(['sh', '-c', runner], env=env, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
            self.assertEqual(log.read_text().splitlines(), ['path com.aistra.hail'])
            self.assertIn('does not bypass Android signature checks', good.stdout)
            apk = dest / 'system/priv-app/Hail/Hail.apk'
            apk.write_bytes(b'corrupted')
            bad = subprocess.run(['sh', '-c', runner], env=env, capture_output=True, text=True)
            self.assertEqual(bad.returncode, 27)
            self.assertIn('APK checksum mismatch', bad.stdout)

    def test_action_detects_another_active_apk_without_modifying_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            commands = directory / 'bin'
            commands.mkdir()
            apk = directory / 'active.apk'
            apk.write_bytes(Path(os.environ['TEST_ORIGINAL_APK']).read_bytes())
            (commands / 'pm').write_text('#!/bin/sh\nif [ "$1" = path ]; then printf "package:%s\\n" "$ACTIVE_APK"; else printf "package:com.aistra.hail\\n"; fi\n')
            for name in ('getprop', 'dumpsys'):
                (commands / name).write_text('#!/bin/sh\nexit 0\n')
            for p in commands.iterdir():
                p.chmod(0o755)
            env = dict(os.environ, PATH=str(commands) + os.pathsep + os.environ['PATH'], ACTIVE_APK=str(apk))
            action = ['sh', str(self.module / 'action.sh')]
            good = subprocess.run(action, env=env, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertIn('Active APK matches module: YES', good.stdout)
            apk.write_bytes(b'other installed APK')
            other = subprocess.run(action, env=env, capture_output=True, text=True)
            self.assertIn('Active APK matches module: NO', other.stdout)
            self.assertEqual(apk.read_bytes(), b'other installed APK')


class OriginalConflicts(unittest.TestCase):
    def run_check(self, module_id, apk_path, disabled=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / module_id / apk_path
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'fixture')
            if disabled:
                (root / module_id / 'disable').touch()
            return subprocess.run(['sh', '-c',
                '. "$1"; hail_check_module_tree "$2" com.aistra.hail Hail-System-PrivApp Hail',
                'test', str(ROOT / 'lib/install_checks.sh'), str(root)],
                capture_output=True, text=True)

    def test_other_original_injector_is_rejected(self):
        self.assertNotEqual(self.run_check('another-original', 'system/priv-app/Hail/Hail.apk').returncode, 0)

    def test_update_of_legacy_original_module_is_allowed(self):
        self.assertEqual(self.run_check('Hail-System-PrivApp', 'system/priv-app/Hail/Hail.apk').returncode, 0)

    def test_separate_enhanced_clone_can_coexist(self):
        self.assertEqual(self.run_check('Hail-Enhanced-PrivApp', 'system/priv-app/HailEnhanced/HailEnhanced.apk').returncode, 0)

    def test_disabled_original_injector_is_allowed(self):
        self.assertEqual(self.run_check('another-original', 'system/priv-app/Hail/Hail.apk', True).returncode, 0)

    def test_renamed_original_allowlist_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = root / 'renamed/system/system_ext/etc/permissions/other.xml'
            xml.parent.mkdir(parents=True)
            xml.write_text('<permissions><privapp-permissions package="com.aistra.hail"/></permissions>')
            result = subprocess.run(['sh', '-c',
                '. "$1"; hail_check_module_tree "$2" com.aistra.hail Hail-System-PrivApp Hail',
                'test', str(ROOT / 'lib/install_checks.sh'), str(root)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
