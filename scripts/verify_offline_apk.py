"""Fail CI if the final APK regains network access or loses its bundled OCR."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile


def verify(permissions, badging, entries, release=False):
    declared = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", permissions))
    forbidden = {
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.ACCESS_WIFI_STATE',
    }
    assert not declared & forbidden, f'Network permissions found: {declared & forbidden}'
    assert 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' in declared, 'Missing screen capture permission'
    assert "name='com.warung.autobag'" in badging, 'Unexpected package'
    assert "versionName='0.3.17-offline-fast-order'" in badging, 'Unexpected version'
    if release:
        assert 'application-debuggable' not in badging, 'Release is debuggable'
    assert any('mlkit' in name.lower() and name.startswith('assets/') for name in entries), 'Missing bundled ML Kit assets'
    return sorted(declared)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()
    def dump(kind):
        return subprocess.check_output([args.aapt, 'dump', kind, str(args.apk)], text=True)
    with zipfile.ZipFile(args.apk) as apk:
        assert apk.testzip() is None, 'Corrupt APK'
        declared = verify(dump('permissions'), dump('badging'), apk.namelist(), args.release)
    print('PASS: final APK has no network permissions and contains bundled ML Kit assets.')
    print('\n'.join(declared))
    print('This checks packaging, not device behavior or Play Protect approval.')


if __name__ == '__main__':
    main()
