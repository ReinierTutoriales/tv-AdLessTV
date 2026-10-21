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
import home_setup as setup
import debloat

STOCK = 'com.google.android.apps.tv.launcherx'
SETUP = setup.SETUP


class Device:
    def __init__(self):
        self.current = STOCK + '/.Home'
        self.initial_claimants = {self.current, SETUP + '/.RecoveryActivity', home.NEW_HOME,
                                 'com.android.tv.settings/.system.FallbackHome'}
        self.states = {STOCK: 1, SETUP: 0}
        self.ident = {'serial': 'serial', 'android_id': 'id', 'model': 'onn'}
        self.firmware = 'onn/test/firmware'
        self.boot = '11111111-1111-1111-1111-111111111111'
        self.wifi_report = 'creatorName=android.uid.system'
        self.user = '0'
        self.codename = 'coffey'
        self.setup_report = 'Package [com.google.android.tungsten.setupwraith] installed=true'
        self.extra_protected = set()
        self.commands = []
        self.fail_package = None
        self.disconnect = False
        self.front = home.NEW_HOME

    def shell(self, command):
        self.commands.append(command)
        if self.disconnect: raise RuntimeError('ADB disconnected')
        if command == 'am get-current-user': return self.user
        if command == 'dumpsys wifi': return self.wifi_report
        if command == 'getprop ro.product.device': return self.codename
        if command == 'dumpsys package ' + SETUP: return self.setup_report
        if command == 'getprop ro.build.fingerprint': return self.firmware
        if command == home.RESOLVE: return self.current
        if command == home.QUERY_HOME:
            return '\n'.join(c for c in self.initial_claimants if self.states.get(c.split('/')[0], 0) in (0, 1))
        if command == 'pm list packages --user 0': return 'package:android\n' + '\n'.join('package:' + p for p in self.states)
        if command == 'cat /proc/sys/kernel/random/boot_id': return self.boot
        if command == 'dumpsys activity activities': return 'mResumedActivity: ActivityRecord{ u0 ' + self.front + ' t1}'
        if command in ('sleep 12', 'sync', 'cmd package help 2>&1 || true'): return ''
        if command.startswith('am start -W '): return 'Status: ok'
        if command.startswith('cmd package set-home-activity --user 0 '):
            self.current = command.split()[-1]
            return ''
        if command.startswith('pm disable-user --user 0 '):
            p = command.split()[-1]
            if p == self.fail_package: raise RuntimeError('disable failed')
            self.states[p] = 3
            # Android can forget the selected HOME when candidates disappear.
            self.current = 'android/com.android.internal.app.ResolverActivity'
            return 'disabled'
        if command.startswith(('pm default-state --user 0 ', 'pm enable --user 0 ')):
            self.states[command.split()[-1]] = 0 if 'default-state' in command else 1
            return 'restored'
        raise AssertionError(command)


class HomeSetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'home.json'
        self.dev = Device()
        for module in (setup, home, debloat):
            p = patch.object(module, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd))
            p.start(); self.addCleanup(p.stop)
        for name, result in [('identity', lambda dev: dev.ident),
                             ('package_state', lambda dev, pkg: dev.states[pkg]),
                             ('protected_packages', lambda dev, **kw: debloat.CORE | dev.extra_protected)]:
            p = patch.object(setup, name, side_effect=result)
            p.start(); self.addCleanup(p.stop)
        self.quiet = contextlib.redirect_stdout(io.StringIO())
        self.quiet.__enter__(); self.addCleanup(self.quiet.__exit__, None, None, None)

    def test_multiple_home_claimants_are_replaced_verified_and_restored_exactly(self):
        candidate = setup.plan(self.dev)
        self.assertEqual([p['package'] for p in candidate['packages']], [SETUP, STOCK])
        self.assertFalse(any(c.startswith('pm disable') for c in self.dev.commands))
        setup.apply(self.dev, candidate, self.path)
        self.assertEqual(self.dev.states, {STOCK: 3, SETUP: 3})
        self.assertEqual(self.dev.current, home.NEW_HOME)
        with self.assertRaisesRegex(ValueError, 'Reinicia'): setup.verify(self.dev, self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        setup.verify(self.dev, self.path)
        setup.restore(self.dev, self.path)
        self.assertEqual(self.dev.states, {STOCK: 1, SETUP: 0})
        self.assertEqual(self.dev.current, STOCK + '/.Home')
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')

    def test_saved_wifi_owner_blocks_before_any_home_mutation(self):
        for report in ('creatorName=' + SETUP, '', 'unreadable owner fields'):
            self.dev.wifi_report = report
            with self.assertRaises(ValueError): setup.plan(self.dev)
        self.assertFalse(any(c.startswith(('pm disable', 'cmd package set-home')) for c in self.dev.commands))

    def test_cast_credentials_dependency_blocks_before_changes(self):
        for codename, report in [('boreal', 'Package installed'), ('kirkwood', 'Package installed'),
                                 ('coffey', 'Provider ...castinfo.CastInfoProvider'), ('coffey', '')]:
            self.dev.codename, self.dev.setup_report = codename, report
            with self.assertRaises(ValueError): setup.plan(self.dev)
        self.assertFalse(any(c.startswith(('pm disable', 'cmd package set-home')) for c in self.dev.commands))

    def test_setup_is_included_even_without_an_enabled_home_activity(self):
        self.dev.initial_claimants.remove(SETUP + '/.RecoveryActivity')
        self.assertIn(SETUP, [p['package'] for p in setup.plan(self.dev)['packages']])

    def test_previously_disabled_setup_is_left_unchanged(self):
        self.dev.states[SETUP] = 3
        candidate = setup.plan(self.dev)
        setup.apply(self.dev, candidate, self.path)
        setup.restore(self.dev, self.path)
        self.assertEqual(self.dev.states[SETUP], 3)

    def test_unknown_home_candidate_is_not_silently_disabled(self):
        self.dev.initial_claimants.add('com.other.launcher/.Home')
        with self.assertRaisesRegex(ValueError, 'no reconocido'): setup.plan(self.dev)
        self.assertFalse(self.path.exists())

    def test_a_home_with_another_protected_role_is_blocked(self):
        self.dev.extra_protected.add(STOCK)
        with self.assertRaisesRegex(ValueError, 'protegida'): setup.plan(self.dev)

    def test_plan_changes_and_tampering_are_rejected_before_backup_and_changes(self):
        for change in ('firmware', 'wifi', 'user', 'candidate', 'state'):
            self.dev = Device()
            candidate = setup.plan(self.dev)
            if change == 'firmware': self.dev.firmware = 'different'
            elif change == 'wifi': self.dev.wifi_report = 'creatorName=' + SETUP
            elif change == 'user': self.dev.user = '10'
            elif change == 'candidate': candidate['packages'].pop(0)
            else: self.dev.states[STOCK] = 0
            self.dev.commands.clear()
            with self.assertRaises(ValueError): setup.apply(self.dev, candidate, self.path)
            self.assertFalse(self.path.exists())
            self.assertFalse(any(c.startswith(('pm disable', 'cmd package set-home')) for c in self.dev.commands))

    def test_partial_failure_restores_both_original_states(self):
        candidate = setup.plan(self.dev)
        self.dev.fail_package = STOCK
        with self.assertRaisesRegex(RuntimeError, 'estado anterior restaurado'):
            setup.apply(self.dev, candidate, self.path)
        self.assertEqual(self.dev.states, {STOCK: 1, SETUP: 0})
        self.assertEqual(self.dev.current, STOCK + '/.Home')

    def test_journal_is_written_before_each_disable_and_can_recover_disconnect(self):
        candidate = setup.plan(self.dev)
        original = self.dev.shell
        def respond(command):
            if command.startswith('pm disable-user'):
                journal = json.loads(self.path.read_text())
                p = command.split()[-1]
                self.assertEqual(next(e for e in journal['packages'] if e['package'] == p)['status'], 'pending')
                original(command)
                self.dev.disconnect = True
                raise RuntimeError('lost after sending disable')
            return original(command)
        with patch.object(self.dev, 'shell', side_effect=respond):
            with self.assertRaisesRegex(RuntimeError, 'Recuperación pendiente'):
                setup.apply(self.dev, candidate, self.path)
        self.dev.disconnect = False
        setup.restore(self.dev, self.path)
        self.assertEqual(self.dev.states, {STOCK: 1, SETUP: 0})

    def test_new_home_during_apply_triggers_recovery(self):
        candidate = setup.plan(self.dev)
        original = self.dev.shell
        def respond(command):
            result = original(command)
            if command.startswith('pm disable-user'):
                self.dev.initial_claimants.add('com.other.launcher/.Home')
            return result
        with patch.object(self.dev, 'shell', side_effect=respond):
            with self.assertRaisesRegex(RuntimeError, 'estado anterior restaurado'):
                setup.apply(self.dev, candidate, self.path)
        self.assertEqual(self.dev.states, {STOCK: 1, SETUP: 0})

    def test_resumed_competitor_and_non_foreground_home_fail_verification(self):
        setup.apply(self.dev, setup.plan(self.dev), self.path)
        self.dev.boot = '22222222-2222-2222-2222-222222222222'
        self.dev.states[SETUP] = 0
        with self.assertRaisesRegex(ValueError, 'competidor'): setup.verify(self.dev, self.path)
        self.dev.states[SETUP] = 3
        self.dev.front = STOCK + '/.Home'
        with self.assertRaisesRegex(ValueError, 'por sí solo'): setup.verify(self.dev, self.path)

    def test_snapshot_and_identity_guards_preserve_existing_backup(self):
        setup.apply(self.dev, setup.plan(self.dev), self.path)
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'ya existe'):
            setup.apply(self.dev, {'invalid': True}, self.path)
        self.assertEqual(self.path.read_bytes(), before)
        self.dev.ident = {'serial': 'other'}
        with self.assertRaisesRegex(ValueError, 'otra TV'): setup.restore(self.dev, self.path)


class NativeAdbTests(unittest.TestCase):
    def test_native_transport_keeps_paths_separate_and_uses_checked_shell(self):
        from subprocess import CompletedProcess
        with patch.object(setup.subprocess, 'run') as run:
            run.side_effect = [CompletedProcess([], 0, 'connected', ''),
                               CompletedProcess([], 0, 'device\n', '')]
            dev = setup.NativeAdb('C:/ADB folder/adb.exe', '192.0.2.1', 5555)
            with patch('tv.uuid.uuid4') as marker:
                marker.return_value.hex = 'fixture'
                run.return_value = CompletedProcess([], 0, 'hello\n__ADLESSTV_fixture__0\n', '')
                run.side_effect = None
                self.assertEqual(setup.shell(dev, 'echo hello'), 'hello')
                self.assertEqual(run.call_args.args[0][:3],
                                 ['C:/ADB folder/adb.exe', '-s', '192.0.2.1:5555'])
                run.return_value = CompletedProcess([], 0, 'bad\n__ADLESSTV_fixture__1\n', '')
                with self.assertRaises(setup.RemoteError): setup.shell(dev, 'false')

    def test_home_exclusion_keeps_other_protected_roles(self):
        def output(dev, command):
            if command == home.QUERY_HOME: return STOCK + '/.Home'
            if command == 'ime list -s': return STOCK + '/.Keyboard'
            if 'query-services' in command: return 'com.vendor.input/.Input'
            if 'accessibility' in command: return 'com.reader.service/.Reader'
            if 'always_on_vpn' in command: return 'com.vpn.service'
            raise AssertionError(command)
        with patch.object(debloat, 'shell', side_effect=output):
            protected = debloat.protected_packages(None, include_home=False)
        self.assertTrue({STOCK, 'com.vendor.input', 'com.reader.service', 'com.vpn.service'} <= protected)


if __name__ == '__main__': unittest.main()
