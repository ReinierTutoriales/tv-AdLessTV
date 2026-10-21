import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which('pwsh'), 'PowerShell runtime required')
class PrebuiltInstallTests(unittest.TestCase):
    def test_prebuilt_flow_and_recovery_helpers_use_quoted_paths_and_stop_on_failures(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="onn owner's test ") as directory:
            root = Path(directory)
            shutil.copy(source / 'launcher/install-apk.ps1', root / 'install-apk.ps1')
            (root / 'tools').mkdir()
            (root / 'tools/home_setup.py').write_text('# fixture')
            (root / 'app-debug.apk').write_bytes(b'fixture')
            calls = root / 'calls.jsonl'
            adb = root / 'fake adb.ps1'
            adb.write_text('''$Arguments = @($args)
Add-Content -LiteralPath $env:FAKE_CALLS -Value (ConvertTo-Json -InputObject $Arguments -Compress)
if ($Arguments -contains 'connect') { Write-Output 'connected'; exit 0 }
if ($Arguments -contains 'get-state') { Write-Output 'device'; exit 0 }
if ($Arguments -contains 'install') {
 if ($env:FAIL_INSTALL) { Write-Output 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'; exit 1 }
 Write-Output 'Success'; exit 0
}
Write-Output 'Status: ok'
if ($env:FAIL_START) { Write-Output 'Error: Activity not started' }
exit 0
''', encoding='utf-8-sig')
            python = root / 'fake python.ps1'
            python.write_text('''$Arguments = @($args)
Add-Content -LiteralPath $env:FAKE_CALLS -Value (ConvertTo-Json -InputObject $Arguments -Compress)
if (($Arguments -contains 'apply') -and $env:FAIL_APPLY) { exit 1 }
exit 0
''', encoding='utf-8-sig')
            command = ['pwsh', '-NoProfile', '-File', str(root / 'install-apk.ps1'),
                       '-TvHost', '192.0.2.1', '-Adb', str(adb), '-Python', str(python), '-MakeHome']
            environment = {**os.environ, 'FAKE_CALLS': str(calls)}
            success = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True)
            self.assertEqual(success.returncode, 0, success.stderr)
            recorded = [json.loads(s) for s in calls.read_text(encoding='utf-8-sig').splitlines()]
            self.assertTrue(any('plan' in call for call in recorded))
            self.assertTrue(any('apply' in call for call in recorded))
            helper = next((root / 'backups').glob('Restaurar-HOME-*.ps1'))
            restore = subprocess.run(['pwsh', '-NoProfile', '-File', str(helper)], cwd=source,
                                     env=environment, capture_output=True, text=True)
            self.assertEqual(restore.returncode, 0, restore.stderr)
            self.assertIn('restore', json.loads(calls.read_text(encoding='utf-8-sig').splitlines()[-1]))
            for failure in ('FAIL_INSTALL', 'FAIL_START', 'FAIL_APPLY'):
                calls.unlink()
                failed = subprocess.run(command, cwd=root, env={**environment, failure: '1'},
                                        capture_output=True, text=True)
                self.assertNotEqual(failed.returncode, 0, failed.stdout)
                recorded = [json.loads(s) for s in calls.read_text(encoding='utf-8-sig').splitlines()]
                if failure != 'FAIL_APPLY':
                    self.assertFalse(any('apply' in call or 'plan' in call for call in recorded))
                else:
                    self.assertEqual(len(list((root / 'backups').glob('Restaurar-HOME-*.ps1'))), 2)


if __name__ == '__main__': unittest.main()
