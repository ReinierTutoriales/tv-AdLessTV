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
            gradle.write_text('#!/bin/sh\nexit 0\n')
            gradle.chmod(0o700)
            client = root / 'python-client'
            client.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['TEST_CAPTURE'], 'a') as stream: stream.write(json.dumps(sys.argv[1:]) + '\\n')
if 'pm install -r /data/local/tmp/adlesstv-preview.apk' in sys.argv:
 print('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' if os.environ.get('FAIL_INSTALL') else 'Success')
elif 'am start -W -n com.adlesstv.launcher/.MainActivity' in sys.argv: print('Status: ok')
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
            failure = subprocess.run(command, cwd=root, env={**environment, 'FAIL_INSTALL': '1'}, capture_output=True)
            self.assertNotEqual(failure.returncode, 0)
            failed_calls = [json.loads(line) for line in capture.read_text().splitlines()]
            self.assertIn('rm -f /data/local/tmp/adlesstv-preview.apk',
                          [arg for call in failed_calls for arg in call])
            self.assertNotIn('--activate', capture.read_text())


if __name__ == '__main__': unittest.main()
