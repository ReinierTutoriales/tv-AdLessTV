import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipIf(sys.platform == 'win32' or not shutil.which('bash'), 'POSIX script fixture')
class InstallTests(unittest.TestCase):
    def test_default_home_changes_only_after_confirmed_install_and_open(self):
        script = Path(__file__).resolve().parents[1] / 'launcher' / 'build-and-install.sh'
        with tempfile.TemporaryDirectory(prefix='home install ') as directory:
            root = Path(directory)
            launcher = root / 'launcher'
            launcher.mkdir()
            shutil.copy(script, launcher / script.name)
            gradle = launcher / 'gradlew'
            # The script refuses an empty or missing APK, so the stub build produces one.
            gradle.write_text('#!/bin/sh\nmkdir -p "$(dirname "$0")/app/build/outputs/apk/debug"\n'
                              'printf apk > "$(dirname "$0")/app/build/outputs/apk/debug/app-debug.apk"\n')
            gradle.chmod(0o700)
            client = root / 'python-client'
            client.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['TEST_CAPTURE'], 'a') as stream: stream.write(json.dumps(sys.argv[1:]) + '\\n')
if 'getprop ro.build.version.sdk' in sys.argv:
 print(os.environ.get('TV_SDK', '30'))
elif 'pm list features' in sys.argv:
 print('feature:android.software.leanback')
elif 'pm install -r /data/local/tmp/adlesstv-preview.apk' in sys.argv:
 print('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' if os.environ.get('FAIL_INSTALL') else 'Success')
 if os.environ.get('MIXED_RESULT'): print('Failure [INSTALL_FAILED_ABORTED]')
elif 'am start -W -n com.adlesstv.launcher/.MainActivity' in sys.argv:
 print('Status: ok\\r' if os.environ.get('CRLF') else 'Status: ok')
 if os.environ.get('FAIL_START'): print('  eRRoR: Activity not started')
''')
            client.chmod(0o700)
            capture = root / 'calls.jsonl'
            environment = {**os.environ, 'PYTHON': str(client), 'TV_HOST': '192.0.2.1', 'TEST_CAPTURE': str(capture)}
            command = ['bash', str(launcher / script.name), '--make-home', 'backups/home.json',
                       '--replace-stock', 'com.factory.home']
            success = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True)
            self.assertEqual(success.returncode, 0, success.stderr)
            calls = [json.loads(line) for line in capture.read_text().splitlines()]
            self.assertIn('rm -f /data/local/tmp/adlesstv-preview.apk', [arg for call in calls for arg in call])
            self.assertEqual(calls[-1][1:], ['--activate', 'backups/home.json', '--stock-package', 'com.factory.home'])
            capture.unlink()
            crlf = subprocess.run(command, cwd=root, env={**environment, 'CRLF': '1'},
                                  capture_output=True, text=True)
            self.assertEqual(crlf.returncode, 0, crlf.stderr)
            capture.unlink()
            mixed = subprocess.run(command, cwd=root, env={**environment, 'MIXED_RESULT': '1'},
                                   capture_output=True, text=True)
            self.assertNotEqual(mixed.returncode, 0)
            self.assertNotIn('--activate', capture.read_text())
            capture.unlink()
            failure = subprocess.run(command, cwd=root, env={**environment, 'FAIL_INSTALL': '1'}, capture_output=True)
            self.assertNotEqual(failure.returncode, 0)
            failed_calls = [json.loads(line) for line in capture.read_text().splitlines()]
            self.assertIn('rm -f /data/local/tmp/adlesstv-preview.apk',
                          [arg for call in failed_calls for arg in call])
            self.assertNotIn('--activate', capture.read_text())
            capture.unlink()
            old_tv = subprocess.run(command, cwd=root, env={**environment, 'TV_SDK': '21'},
                                    capture_output=True, text=True)
            self.assertNotEqual(old_tv.returncode, 0)
            self.assertIn('API 21', old_tv.stderr)
            self.assertNotIn('--push', capture.read_text())
            capture.unlink()
            mismatch = subprocess.run(command, cwd=root, env={**environment, 'FAIL_INSTALL': '1'},
                                      capture_output=True, text=True)
            self.assertIn('otra clave', mismatch.stderr)
            self.assertNotIn('uninstall', capture.read_text())
            capture.unlink()
            bad_start = subprocess.run(command, cwd=root, env={**environment, 'FAIL_START': '1'},
                                       capture_output=True, text=True)
            self.assertNotEqual(bad_start.returncode, 0)
            self.assertNotIn('--activate', capture.read_text())


if __name__ == '__main__': unittest.main()
