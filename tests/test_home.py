import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import home


class Device:
    def __init__(self):
        self.current = 'com.factory.home/.Home'
        self.ident = {'serial': 'serial', 'android_id': 'id', 'model': 'TV'}
        self.commands = []
        self.disconnect = False
        self.boot = '11111111-1111-1111-1111-111111111111'
        self.stock_state = 0
        self.foreground = self.current

    def shell(self, command):
        self.commands.append(command)
        if command in ("sleep 12", "sync", "cmd package help 2>&1 || true"): return ""
        if command == home.RESOLVE: return f'priority=0\n{self.current}\n'
        if command == 'cat /proc/sys/kernel/random/boot_id': return self.boot
        if command.startswith('pm path '): return 'package:/data/app/app.apk\n'
        if command.startswith('am start -W '):
            self.foreground = home.NEW_HOME
            return 'Status: ok\n'
        if command == 'dumpsys activity activities':
            return 'mResumedActivity: ActivityRecord{ u0 ' + self.foreground + ' t1}'
        if command == 'pm disable-user --user 0 com.factory.home':
            self.stock_state = 3
            return 'disabled'
        if command == 'pm default-state --user 0 com.factory.home':
            self.stock_state = 0
            return 'restored'
        if command.startswith('cmd package set-home-activity --user 0 '):
            self.current = command.split()[-1]
            if self.disconnect: raise RuntimeError('lost connection')
            return ''
        raise AssertionError(command)


class HomeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'home.json'
        self.dev = Device()
        calls = [patch.object(home, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd)),
                 patch.object(home, 'identity', side_effect=lambda dev: dev.ident),
                 patch.object(home, 'package_state', side_effect=lambda dev, package: dev.stock_state)]
        for call in calls:
            call.start()
            self.addCleanup(call.stop)

    def test_explicit_replacement_handles_stock_priority_over_home_role(self):
        original = self.dev.shell
        def response(command):
            if command == home.RESOLVE and self.dev.stock_state != 3:
                self.dev.commands.append(command)
                return 'com.factory.home/.Home'
            return original(command)
        with patch.object(self.dev, 'shell', side_effect=response), contextlib.redirect_stdout(io.StringIO()):
            home.activate(self.dev, self.path, 'com.factory.home')
            self.assertEqual(json.loads(self.path.read_text())['status'], 'stock_disabled')
            self.assertEqual(self.dev.stock_state, 3)
            home.restore(self.dev, self.path)
            self.assertEqual(self.dev.stock_state, 0)

    def test_failed_replacement_restores_stock_automatically(self):
        original = self.dev.shell
        def response(command):
            if command == home.RESOLVE: return 'com.factory.home/.Home'
            return original(command)
        with patch.object(self.dev, 'shell', side_effect=response), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'se restauró'):
                home.activate(self.dev, self.path, 'com.factory.home')
        self.assertEqual(self.dev.stock_state, 0)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')

    def test_stock_priority_without_explicit_replacement_restores_role(self):
        original = self.dev.shell
        def response(command):
            if command == home.RESOLVE and self.dev.stock_state != 3:
                self.dev.commands.append(command)
                return 'com.factory.home/.Home'
            return original(command)
        with patch.object(self.dev, 'shell', side_effect=response), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'se restauró el rol anterior'):
                home.activate(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')
        self.assertEqual(self.dev.stock_state, 0)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')
        self.assertFalse(any(c.startswith('pm disable-user') for c in self.dev.commands))

    def test_activation_restoration_and_repetition(self):
        with contextlib.redirect_stdout(io.StringIO()):
            home.activate(self.dev, self.path)
            self.assertEqual(self.dev.current, home.NEW_HOME)
            self.assertEqual(json.loads(self.path.read_text())['status'], 'active')
            home.restore(self.dev, self.path)
            home.restore(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')

    def test_disconnect_after_home_change_is_recoverable(self):
        self.dev.disconnect = True
        with self.assertRaisesRegex(RuntimeError, 'lost connection'):
            home.activate(self.dev, self.path)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'pending')
        self.dev.disconnect = False
        with contextlib.redirect_stdout(io.StringIO()): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')

    def test_reboot_verification_then_optional_disable_and_restore(self):
        with contextlib.redirect_stdout(io.StringIO()): home.activate(self.dev, self.path)
        with self.assertRaisesRegex(ValueError, 'aún no ha reiniciado'):
            home.verify_reboot(self.dev, self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        with contextlib.redirect_stdout(io.StringIO()):
            home.verify_reboot(self.dev, self.path)
            home.disable_stock(self.dev, self.path, 'com.factory.home')
        self.assertEqual(self.dev.stock_state, 3)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'stock_disabled')
        self.dev.boot = '33333333-3333-3333-3333-333333333333'
        with contextlib.redirect_stdout(io.StringIO()): home.verify_reboot(self.dev, self.path)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'stock_verified')
        with contextlib.redirect_stdout(io.StringIO()): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.stock_state, 0)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')

    def test_disable_stock_rejects_different_package_or_manual_home_change(self):
        with contextlib.redirect_stdout(io.StringIO()): home.activate(self.dev, self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        with contextlib.redirect_stdout(io.StringIO()): home.verify_reboot(self.dev, self.path)
        with self.assertRaises(ValueError): home.disable_stock(self.dev, self.path, 'com.other.home')
        self.dev.current = 'com.other.launcher/.Home'
        with self.assertRaises(ValueError): home.disable_stock(self.dev, self.path, 'com.factory.home')
        self.assertEqual(self.dev.stock_state, 0)

    def test_firmware_reverts_home_after_reboot_and_stock_stays_available(self):
        with contextlib.redirect_stdout(io.StringIO()): home.activate(self.dev, self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        self.dev.current = 'com.factory.home/.Home'
        with self.assertRaisesRegex(ValueError, 'no conservó'):
            home.verify_reboot(self.dev, self.path)
        self.assertEqual(self.dev.stock_state, 0)
        self.assertEqual(json.loads(self.path.read_text())['status'], 'active')
        with contextlib.redirect_stdout(io.StringIO()):
            home.disable_stock(self.dev, self.path, 'com.factory.home')
        self.assertEqual(self.dev.current, home.NEW_HOME)
        self.assertEqual(self.dev.stock_state, 3)

    def test_boot_verification_cannot_claim_success_by_opening_home_itself(self):
        with contextlib.redirect_stdout(io.StringIO()): home.activate(self.dev, self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        self.dev.foreground = 'com.vendor.startup/.Activity'
        before = len(self.dev.commands)
        with self.assertRaisesRegex(ValueError, 'no está visible'):
            home.verify_reboot(self.dev, self.path)
        self.assertFalse(any(c.startswith('am start') for c in self.dev.commands[before:]))

    def test_canonicalizes_fully_qualified_activity_names(self):
        self.dev.current = 'com.adlesstv.launcher/com.adlesstv.launcher.MainActivity'
        self.assertEqual(home.current_home(self.dev), home.NEW_HOME)

    def test_restores_older_snapshot_without_disabling_stock(self):
        self.path.write_text(json.dumps({'version': 1, 'device': self.dev.ident,
            'previous': 'com.factory.home/.Home', 'new': home.NEW_HOME, 'status': 'active'}))
        self.dev.current = home.NEW_HOME
        with contextlib.redirect_stdout(io.StringIO()): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')

    def test_rejects_resolver_or_ambiguous_previous_home(self):
        self.dev.current = 'com.android.internal.app/.ResolverActivity'
        with self.assertRaises(ValueError): home.activate(self.dev, self.path)
        self.assertFalse(self.path.exists())

    def test_rejects_existing_snapshot_before_writing(self):
        self.path.write_text('existing')
        with self.assertRaises(FileExistsError): home.activate(self.dev, self.path)
        self.assertEqual(self.path.read_text(), 'existing')
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')

    def test_preflight_requires_confirmed_launch_before_switching_home(self):
        original = self.dev.shell
        def response(command):
            if command.startswith('am start -W '): return 'Status: timeout\n'
            return original(command)
        with patch.object(self.dev, 'shell', side_effect=response):
            with self.assertRaisesRegex(ValueError, 'No se confirmó'):
                home.activate(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.factory.home/.Home')
        self.assertFalse(self.path.exists())

    def test_restore_rejects_wrong_device_or_manual_change(self):
        with contextlib.redirect_stdout(io.StringIO()): home.activate(self.dev, self.path)
        self.dev.ident = {'serial': 'another', 'android_id': 'id', 'model': 'TV'}
        with self.assertRaises(ValueError): home.restore(self.dev, self.path)
        self.dev.ident = {'serial': 'serial', 'android_id': 'id', 'model': 'TV'}
        self.dev.current = 'com.other.home/.Home'
        with self.assertRaises(ValueError): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.current, 'com.other.home/.Home')

    def test_rejects_backup_that_names_the_new_launcher_as_previous_with_full_class(self):
        self.path.write_text(json.dumps({'version': 1, 'device': self.dev.ident,
            'previous': 'com.adlesstv.launcher/com.adlesstv.launcher.MainActivity',
            'new': home.NEW_HOME, 'status': 'pending'}))
        with self.assertRaises(ValueError): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.commands, [])

    def test_restore_rejects_command_injection_in_snapshot(self):
        self.path.write_text(json.dumps({'version': 1, 'device': self.dev.ident,
            'previous': 'com.factory.home/.Home; reboot', 'new': home.NEW_HOME, 'status': 'pending'}))
        with self.assertRaises(ValueError): home.restore(self.dev, self.path)
        self.assertEqual(self.dev.commands, [])


if __name__ == '__main__': unittest.main()
