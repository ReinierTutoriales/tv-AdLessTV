import contextlib
import io
import json
from pathlib import Path
import re
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import debloat
import tv


class TvTests(unittest.TestCase):
    def test_real_key_generation_reuse_and_public_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / 'adbkey'
            tv.get_signer(directory)
            first = key.read_bytes()
            tv.get_signer(directory)
            self.assertEqual(first, key.read_bytes())
            public = key.with_suffix('.pub')
            original = public.read_bytes()
            public.unlink()
            tv.get_signer(directory)
            self.assertEqual(first, key.read_bytes())
            self.assertEqual(original, public.read_bytes())
            if sys.platform != 'win32':
                self.assertEqual(key.stat().st_mode & 0o777, 0o600)

    def test_orphan_public_key_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            public = Path(directory) / 'adbkey.pub'
            public.write_text('keep', encoding='utf-8')
            with self.assertRaises(RuntimeError):
                tv.get_signer(directory)
            self.assertEqual(public.read_text(), 'keep')

    def test_prefers_existing_platform_adb_key_without_generating_a_second_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            platform = Path(directory) / '.android'
            tv.get_signer(platform)
            key = platform / 'adbkey'
            before = key.read_bytes()
            with patch.object(Path, 'home', return_value=Path(directory)):
                tv.get_signer()
            self.assertEqual(key.read_bytes(), before)

    @unittest.skipIf(sys.platform == 'win32', 'Requires POSIX shell')
    def test_real_shell_exit_status_quoting_and_missing_newline(self):
        dev = Mock()
        dev.shell.side_effect = lambda command, **kwargs: subprocess.check_output(
            ['sh', '-c', command], text=True, stderr=subprocess.STDOUT)
        self.assertEqual(tv.shell(dev, "printf 'hello'"), 'hello')
        self.assertEqual(tv.shell(dev, "printf '%s' \"it's quoted\""), "it's quoted")
        with self.assertRaises(tv.RemoteError):
            tv.shell(dev, 'printf error; exit 7')

    def test_truncated_response_is_failure(self):
        dev = Mock()
        dev.shell.return_value = 'partial output'
        with self.assertRaises(tv.RemoteError):
            tv.shell(dev, 'anything')

    def test_batch_strips_bom_comments_and_stops_at_failure(self):
        with patch.object(tv, 'shell', side_effect=['ok', tv.RemoteError('fail')]) as execute:
            with self.assertRaises(tv.RemoteError), contextlib.redirect_stdout(io.StringIO()):
                tv.batch(None, ['\ufeff# comment', '', '\ufefffirst', 'second', 'third'])
        self.assertEqual([c.args[1] for c in execute.call_args_list], ['first', 'second'])

    def test_main_closes_connection_on_failure(self):
        dev = Mock()
        with patch.object(tv, 'connect', return_value=dev), patch.object(tv, 'shell', side_effect=RuntimeError('broken')):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(tv.main(['--probe']), 1)
        dev.close.assert_called_once()

    def test_connect_closes_on_authentication_failure(self):
        dev = Mock()
        dev.connect.return_value = False
        with patch('adb_shell.adb_device.AdbDeviceTcp', return_value=dev), patch.object(tv, 'get_signer'):
            with self.assertRaises(tv.RemoteError):
                tv.connect('192.0.2.1')
        dev.close.assert_called_once()

    def test_cli_help_without_host_or_connection(self):
        result = subprocess.run([sys.executable, str(Path(tv.__file__)), '--help'], capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn(b'--push', result.stdout)


class Device:
    """Fake Android service with explicit states, identity and injected failures."""
    def __init__(self):
        self.states = {'com.test.first': 0, 'com.test.second': 1, 'com.test.disabled': 2,
                       'com.test.userdisabled': 3, 'com.test.untilused': 4,
                       'com.test.home': 0, 'com.test.keyboard': 0}
        self.writes = []
        self.fail = None
        self.ignore = False
        self.device_id = 'tv-one'
        self.on_write = lambda: None
        self.current_user = '0'
        self.firmware = 'brand/tv/build:1'
        self.tv_input = 'com.test.input/.Input'
        self.accessibility = 'null'
        self.vpn = 'null'

    def shell(self, command):
        if command == 'am get-current-user':
            return self.current_user
        if command == 'settings --user 0 get secure android_id':
            return self.device_id
        if command == 'getprop ro.serialno':
            return 'serial-one'
        if command == 'getprop ro.product.model':
            return 'Test TV'
        if command == 'getprop ro.build.fingerprint':
            return self.firmware
        if command.startswith('cmd package query-activities'):
            return 'com.test.home/.Home\ncom.other.launcher/.Home'
        if command == 'ime list -s':
            return 'com.test.keyboard/.Keyboard'
        if command.startswith('cmd package query-services'):
            return self.tv_input
        if command == 'settings --user 0 get secure enabled_accessibility_services':
            return self.accessibility
        if command == 'settings --user 0 get secure always_on_vpn_app':
            return self.vpn
        if command.startswith('dumpsys package '):
            package = command.split()[-1]
            if package not in self.states:
                return 'Unable to find package'
            return f'  User 0: installed=true hidden=false enabled={self.states[package]}\n  User 10: installed=true enabled=1'
        match = re.fullmatch(r'pm (\S+) --user 0 (\S+)', command)
        if match:
            action, package = match.groups()
            self.writes.append(command)
            self.on_write()
            # Simulate disconnect AFTER Android has performed the change.
            if not self.ignore:
                self.states[package] = next(s for s, a in debloat.STATES.items() if a == action)
            if package == self.fail:
                raise tv.RemoteError('connection lost')
            return 'Package changed'
        raise AssertionError(command)


class DebloatTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'backup.json'
        self.dev = Device()
        self.patcher = patch.object(debloat, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd))
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        output = contextlib.redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def test_list_validation_bom_comments_and_deduplication(self):
        path = Path(self.directory.name) / 'list.txt'
        path.write_text('\ufeff# list\ncom.test.first # note\ncom.test.first\n', encoding='utf-8')
        self.assertEqual(debloat.read_packages(path), ['com.test.first'])
        for value in ('com.test.first; reboot', 'com.test.first other', '', '$(reboot)'):
            path.write_text(value, encoding='utf-8')
            with self.assertRaises(ValueError):
                debloat.read_packages(path)

    def test_profile_is_scoped_to_exact_firmware_and_requires_reasons(self):
        path = Path(self.directory.name) / 'profile.json'
        data = {'version': 1, 'model': 'Test TV', 'firmware': 'brand/tv/build:1',
                'packages': [{'package': 'com.test.first', 'reason': 'Unused optional component'}]}
        path.write_text(json.dumps(data), encoding='utf-8')
        profile = debloat.read_profile(path)
        debloat.check_profile(self.dev, profile)
        self.assertEqual(self.dev.writes, [])
        profile['firmware'] = 'other/build'
        with self.assertRaisesRegex(ValueError, 'otra TV'):
            debloat.check_profile(self.dev, profile)
        data['packages'][0]['reason'] = ''
        path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaises(ValueError): debloat.read_profile(path)

    def test_plan_is_read_only(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        self.assertEqual(snapshot['packages'][0]['state'], 0)
        self.assertEqual(self.dev.writes, [])
        self.assertFalse(self.path.exists())

    def test_protects_all_homes_keyboards_and_core(self):
        for package in ('com.test.home', 'com.other.launcher', 'com.test.keyboard',
                        'com.android.systemui', 'com.android.tv.settings',
                        'com.adlesstv.launcher', 'com.android.bluetooth', 'com.android.providers.tv',
                        'com.test.input'):
            with self.assertRaisesRegex(ValueError, 'protegidos'):
                debloat.plan(self.dev, [package])
        self.assertEqual(self.dev.writes, [])

    def test_protects_active_accessibility_and_vpn(self):
        self.dev.accessibility = 'com.test.first/.Service:com.other.accessibility/.Service'
        self.dev.vpn = 'com.test.second'
        for package in ('com.test.first', 'com.test.second', 'com.other.accessibility'):
            with self.assertRaisesRegex(ValueError, 'protegidos'):
                debloat.plan(self.dev, [package])
        self.assertEqual(self.dev.writes, [])

    def test_refuses_other_foreground_users_and_unreadable_roles(self):
        self.dev.current_user = '10'
        with self.assertRaisesRegex(ValueError, 'usuario visible'):
            debloat.plan(self.dev, ['com.test.first'])
        self.dev.current_user = '0'
        self.dev.accessibility = 'unreadable'
        with self.assertRaisesRegex(ValueError, 'accesibilidad'):
            debloat.plan(self.dev, ['com.test.first'])
        self.assertEqual(self.dev.writes, [])

    def test_apply_aborts_when_state_or_protection_changes(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        self.dev.states['com.test.first'] = 1
        with self.assertRaisesRegex(ValueError, 'Cambió el estado'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(self.dev.writes, [])
        self.dev.states['com.test.first'] = 0
        self.dev.vpn = 'com.test.first'
        with self.assertRaisesRegex(ValueError, 'protección'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(self.dev.writes, [])

    def test_later_entry_drift_blocks_whole_batch_and_firmware_is_recorded(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        self.assertEqual(snapshot['firmware'], self.dev.firmware)
        self.dev.states['com.test.second'] = 0
        with self.assertRaisesRegex(ValueError, 'com.test.second'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertFalse(self.path.exists())
        self.assertEqual(self.dev.writes, [])

    def test_firmware_change_blocks_apply_or_verify_but_not_recovery(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        self.dev.firmware = 'brand/tv/build:2'
        with self.assertRaisesRegex(ValueError, 'firmware'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertFalse(self.path.exists())
        self.dev.firmware = snapshot['firmware']
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.firmware = 'brand/tv/build:2'
        with self.assertRaisesRegex(ValueError, 'firmware'):
            debloat.verify(self.dev, json.loads(self.path.read_text()))
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)

    def test_user_or_role_change_mid_batch_stops_before_second_change(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        def switched_user():
            self.dev.current_user = '10'
        self.dev.on_write = switched_user
        with self.assertRaisesRegex(ValueError, 'usuario visible'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(len(self.dev.writes), 1)
        self.dev.current_user = '0'
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)

    def test_newly_protected_package_stops_batch_and_can_restore(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        self.dev.on_write = lambda: setattr(self.dev, 'vpn', 'com.test.second')
        with self.assertRaisesRegex(ValueError, 'función protegida'):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(len(self.dev.writes), 1)
        self.assertEqual(self.dev.states['com.test.second'], 1)
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)

    def test_verify_after_reboot_detects_reenabled_or_pending_packages(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        debloat.apply(self.dev, snapshot, self.path)
        saved = json.loads(self.path.read_text())
        debloat.verify(self.dev, saved)
        self.dev.states['com.test.first'] = 0
        with self.assertRaisesRegex(ValueError, 'no conserva'):
            debloat.verify(self.dev, saved)
        self.dev.states['com.test.first'] = 3
        saved['packages'][0]['status'] = 'pending'
        with self.assertRaisesRegex(ValueError, 'interrumpido'):
            debloat.verify(self.dev, saved)
        self.assertEqual(len(self.dev.writes), 2)

    def test_missing_home_aborts_before_writes(self):
        with patch.object(debloat, 'shell', side_effect=lambda dev, cmd:
                          'No activities found' if cmd.startswith('cmd package query-activities') else dev.shell(cmd)):
            with self.assertRaisesRegex(ValueError, 'HOME'):
                debloat.plan(self.dev, ['com.test.first'])
        self.assertEqual(self.dev.writes, [])

    def test_missing_package_aborts_entire_plan(self):
        with self.assertRaises(ValueError):
            debloat.plan(self.dev, ['com.test.first', 'com.missing.app'])
        self.assertEqual(self.dev.writes, [])

    def test_roundtrip_preserves_exact_states_and_is_idempotent(self):
        original = dict(self.dev.states)
        snapshot = debloat.plan(self.dev, [p for p in original if p not in ('com.test.home', 'com.test.keyboard')])
        debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(len(self.dev.writes), 2)
        self.assertEqual(self.dev.states['com.test.first'], 3)
        self.assertEqual(self.dev.states['com.test.second'], 3)
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(original, self.dev.states)
        writes = len(self.dev.writes)
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(len(self.dev.writes), writes)

    def test_journal_precedes_write_and_recovers_partial_failure(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        self.dev.fail = 'com.test.first'
        self.dev.on_write = lambda: self.assertEqual(json.loads(self.path.read_text())['packages'][0]['status'], 'pending')
        with self.assertRaises(tv.RemoteError):
            debloat.apply(self.dev, snapshot, self.path)
        self.dev.fail = None
        self.dev.on_write = lambda: None
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved['packages'][1]['status'], 'not_started')
        debloat.restore(self.dev, saved, self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)
        self.assertEqual(self.dev.states['com.test.second'], 1)
        self.assertEqual(len(self.dev.writes), 2)

    def test_existing_backup_not_overwritten(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        self.path.write_text('original', encoding='utf-8')
        with self.assertRaises(FileExistsError):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(self.path.read_text(), 'original')
        self.assertEqual(self.dev.writes, [])

    def test_failed_snapshot_write_prevents_device_write(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        with patch.object(debloat, 'write_snapshot', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(self.dev.writes, [])

    def test_false_success_rejected_and_restorable(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        self.dev.ignore = True
        with self.assertRaises(RuntimeError):
            debloat.apply(self.dev, snapshot, self.path)
        self.assertEqual(json.loads(self.path.read_text())['packages'][0]['status'], 'pending')

    def test_wrong_device_cannot_restore(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.device_id = 'another-tv'
        count = len(self.dev.writes)
        with self.assertRaisesRegex(ValueError, 'otra TV'):
            debloat.restore(self.dev, snapshot, self.path)
        self.assertEqual(len(self.dev.writes), count)

    def test_invalid_snapshot_cannot_execute_shell(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        for bad in ('com.test.first; reboot', 'invalid'):
            snapshot['packages'][0]['package'] = bad
            with self.assertRaises(ValueError):
                debloat.restore(self.dev, snapshot, self.path)
        self.assertEqual(self.dev.writes, [])

    def test_restoration_failure_stays_recoverable(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.fail = 'com.test.first'
        with self.assertRaises(tv.RemoteError):
            debloat.restore(self.dev, snapshot, self.path)
        self.assertEqual(json.loads(self.path.read_text())['packages'][0]['status'], 'applied')
        self.dev.fail = None
        debloat.restore(self.dev, json.loads(self.path.read_text()), self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)


class WrapperTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('bash'), 'Requires Bash')
    def test_bash_preserves_caller_paths_spaces_and_exit_status(self):
        if sys.platform == 'win32':
            self.skipTest('POSIX executable fixture')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='tv tools ') as directory:
            fake = Path(directory) / 'fake python'
            fake.write_text('#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\nsys.exit(7)\n', encoding='utf-8')
            fake.chmod(0o700)
            for script, expected in [('disable.sh', ['disable', 'my list.txt']),
                                     ('restore.sh', ['restore', 'my backup.json'])]:
                result = subprocess.run(['bash', str(root / 'tools' / script), expected[1]],
                                        cwd=directory, env={**os.environ, 'PYTHON': str(fake)},
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertEqual(json.loads(result.stdout)[1:], expected)

    @unittest.skipUnless(shutil.which('pwsh'), 'Requires PowerShell (also tested in CI)')
    def test_powershell_forwards_arguments_and_exit_status(self):
        root = Path(__file__).resolve().parents[1]
        for script, arguments, expected in [
            ('disable.ps1', "-List 'my list.txt' -Apply -Snapshot 'my backup.json'",
             ['--host', '192.0.2.1', 'disable', 'my list.txt', '--apply', '--snapshot', 'my backup.json']),
            ('restore.ps1', "-Snapshot 'my backup.json'",
             ['--host', '192.0.2.1', 'restore', 'my backup.json'])]:
            command = ("function FakePython { ConvertTo-Json -Compress -InputObject @($args); $global:LASTEXITCODE = 7 }; "
                       "& $env:TEST_WRAPPER " + arguments + " -TvHost 192.0.2.1 -Python FakePython; exit $LASTEXITCODE")
            result = subprocess.run(['pwsh', '-NoProfile', '-Command', command], capture_output=True,
                                    text=True, env={**os.environ, 'TEST_WRAPPER': str(root / 'tools' / script)})
            self.assertEqual(result.returncode, 7, result.stderr)
            self.assertEqual(json.loads(result.stdout)[1:], expected)


if __name__ == '__main__':
    unittest.main()
