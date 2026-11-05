"""Verify compiled original-package identity. Requires aapt2 via AAPT2."""
import os
import subprocess
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1]).resolve()
aapt = os.environ.get('AAPT2', 'aapt2')


def dump(*args):
    return subprocess.check_output([aapt, 'dump', *args, str(apk)], text=True)


badging = dump('badging')
assert "package: name='com.aistra.hail'" in badging, 'Wrong application ID'
assert "versionCode='111013'" in badging, 'Unexpected version code'
assert '1.11.0-enhanced.13-original-package' in badging, 'Missing variant identifier'
xml = dump('xmltree', '--file', 'AndroidManifest.xml')
for required in ('com.aistra.hail.shizuku', 'com.aistra.hail.action.FREEZE',
                 'com.aistra.hail.action.APPLY_PROFILE'):
    assert required in xml, 'Missing original namespace: ' + required
for forbidden in ('com.aistra.hail.enhanced.shizuku', 'com.aistra.hail.enhanced.action.'):
    assert forbidden not in xml, 'Clone namespace still present'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    for name in archive.namelist():
        assert not name.startswith('/') and '..' not in Path(name).parts
print('PASS: original package, API actions, provider authority, version and ZIP integrity')
