import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from verify_offline_apk import verify

class OfflinePackagingTest(unittest.TestCase):
    permissions = "uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION'"
    badging = "package: name='com.warung.autobag' versionCode='17' versionName='0.3.17-offline-fast-order'"
    assets = ['assets/mlkit-google-ocr-models/model.binarypb']

    def test_offline_release(self):
        verify(self.permissions, self.badging, self.assets, True)

    def test_each_network_permission_rejected(self):
        for permission in ['INTERNET', 'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE']:
            for tag in ['uses-permission', 'uses-permission-sdk-23']:
                with self.subTest(permission=permission, tag=tag), self.assertRaises(AssertionError):
                    verify(self.permissions + f"\n{tag}: name='android.permission.{permission}'", self.badging, self.assets)

    def test_debug_release_rejected(self):
        with self.assertRaises(AssertionError):
            verify(self.permissions, self.badging + '\napplication-debuggable', self.assets, True)

    def test_debug_allowed_for_debug_workflow(self):
        verify(self.permissions, self.badging + '\napplication-debuggable', self.assets)

    def test_missing_model_rejected(self):
        with self.assertRaises(AssertionError):
            verify(self.permissions, self.badging, [])

    def test_wrong_version_rejected(self):
        with self.assertRaises(AssertionError):
            verify(self.permissions, self.badging.replace('0.3.17-offline-fast-order', '0.3.16-offline'), self.assets)

    def test_capture_permission_required(self):
        with self.assertRaises(AssertionError):
            verify('', self.badging, self.assets)

if __name__ == '__main__':
    unittest.main()
