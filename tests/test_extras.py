import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import debloat
import keys
import theme
import tv
from test_tools import Device as DebloatDevice


def quiet(test):
    output = contextlib.redirect_stdout(io.StringIO())
    output.__enter__()
    test.addCleanup(output.__exit__, None, None, None)


class ThemeDevice:
    """Records pushes and shell commands; `content` can be made to fail like locked firmware."""
    def __init__(self, provider=True):
        self.provider = provider
        self.files = {}
        self.commands = []
        self.installed = True

    def push(self, local, remote, **kwargs):
        self.files[remote] = Path(local).read_bytes()

    def shell(self, command):
        self.commands.append(command)
        if command.startswith('pm path'):
            return 'package:/data/app/base.apk' if self.installed else ''
        if command.startswith('content write'):
            if not self.provider:
                raise tv.RemoteError('content: unknown command')
            uri, staged = command.split('--uri ')[1].split(' < ')
            self.files[uri] = self.files[staged.strip("'")]
            return ''
        if command.startswith('content read'):
            uri = command.split('--uri ')[1].split(' ')[0]
            return str(len(self.files.get(uri, b'')))
        return ''


class ThemeTests(unittest.TestCase):
    def setUp(self):
        quiet(self)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.folder = Path(self.directory.name)
        (self.folder / 'config.json').write_text('{"columns": 6}', encoding='utf-8')
        patcher = patch.object(theme, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_push_through_provider_reloads_and_clears_stale_copies(self):
        dev = ThemeDevice()
        theme.push(dev, self.folder)
        self.assertEqual(dev.files[theme.URI + 'config.json'], b'{"columns": 6}')
        joined = '\n'.join(dev.commands)
        self.assertIn('rm -f ' + theme.EXTERNAL + 'config.json', joined)
        self.assertIn('content delete --uri ' + theme.URI + 'wallpaper.jpg', joined)
        self.assertIn('rm -f /data/local/tmp/adlesstv-theme-config.json', joined)
        self.assertEqual(dev.commands[-1], theme.RELOAD)

    def test_locked_provider_falls_back_to_android_data(self):
        dev = ThemeDevice(provider=False)
        (self.folder / 'wallpaper.jpg').write_bytes(b'jpeg')
        theme.push(dev, self.folder)
        joined = '\n'.join(dev.commands)
        self.assertIn('cp /data/local/tmp/adlesstv-theme-wallpaper.jpg ' + theme.EXTERNAL + 'wallpaper.jpg', joined)
        self.assertIn('content delete --uri ' + theme.URI + 'config.json', joined)

    def test_invalid_theme_is_rejected_before_connecting(self):
        (self.folder / 'config.json').write_text('[1, 2]', encoding='utf-8')
        with self.assertRaises(ValueError):
            theme.check_theme(self.folder)
        (self.folder / 'config.json').write_text('{not json', encoding='utf-8')
        with self.assertRaises(ValueError):
            theme.check_theme(self.folder)
        (self.folder / 'config.json').write_text('{}', encoding='utf-8')
        (self.folder / 'wallpaper.jpg').write_bytes(b'')
        with self.assertRaises(ValueError):
            theme.check_theme(self.folder)

    def test_reset_removes_both_files_everywhere(self):
        dev = ThemeDevice()
        theme.reset(dev)
        joined = '\n'.join(dev.commands)
        for name in theme.FILES:
            self.assertIn('rm -f ' + theme.EXTERNAL + name, joined)
            self.assertIn('content delete --uri ' + theme.URI + name, joined)
        self.assertEqual(dev.commands[-1], theme.RELOAD)

    def test_missing_launcher_is_reported(self):
        dev = ThemeDevice()
        dev.installed = False
        with self.assertRaisesRegex(ValueError, 'no está instalado'):
            theme.push(dev, self.folder)


class KeysDevice(DebloatDevice):
    def __init__(self, services='null', enabled='0'):
        super().__init__()
        self.secure = {'enabled_accessibility_services': services, 'accessibility_enabled': enabled}
        self.refuse = False

    def shell(self, command):
        if command == 'dumpsys package com.adlesstv.launcher':
            return 'Service Resolver Table: com.adlesstv.launcher/.RemoteKeyService'
        for key in self.secure:
            if command == f'settings --user 0 get secure {key}':
                return self.secure[key]
            if command.startswith(f'settings --user 0 put secure {key} '):
                if not self.refuse:
                    self.secure[key] = command.split(' ', 6)[6].strip("'")
                return ''
            if command == f'settings --user 0 delete secure {key}':
                self.secure[key] = 'null'
                return ''
        return super().shell(command)


class KeysTests(unittest.TestCase):
    def setUp(self):
        quiet(self)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'keys.json'
        for module in (keys, debloat):
            patcher = patch.object(module, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd))
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_enable_appends_and_restore_returns_exact_previous_values(self):
        dev = KeysDevice('com.vendor/.Talk', '1')
        keys.enable(dev, self.path)
        self.assertEqual(dev.secure['enabled_accessibility_services'], 'com.vendor/.Talk:' + keys.SERVICE)
        keys.restore(dev, self.path)
        self.assertEqual(dev.secure, {'enabled_accessibility_services': 'com.vendor/.Talk',
                                      'accessibility_enabled': '1'})
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')

    def test_restore_from_empty_deletes_the_settings(self):
        dev = KeysDevice()
        keys.enable(dev, self.path)
        keys.restore(dev, self.path)
        self.assertEqual(dev.secure['enabled_accessibility_services'], 'null')
        self.assertEqual(dev.secure['accessibility_enabled'], '0')

    def test_services_enabled_later_are_kept(self):
        dev = KeysDevice()
        keys.enable(dev, self.path)
        dev.secure['enabled_accessibility_services'] += ':com.later/.Reader'
        keys.restore(dev, self.path)
        self.assertEqual(dev.secure['enabled_accessibility_services'], 'com.later/.Reader')
        self.assertEqual(dev.secure['accessibility_enabled'], '1')

    def test_refused_change_is_rolled_back_and_backup_is_exclusive(self):
        dev = KeysDevice()
        dev.refuse = True
        with self.assertRaises(RuntimeError):
            keys.enable(dev, self.path)
        with self.assertRaises(FileExistsError):
            keys.enable(KeysDevice(), self.path)

    def test_other_tv_is_rejected(self):
        dev = KeysDevice()
        keys.enable(dev, self.path)
        other = KeysDevice()
        other.device_id = 'tv-two'
        with self.assertRaisesRegex(ValueError, 'otra TV'):
            keys.restore(other, self.path)

    def test_tampered_backup_cannot_execute_shell_text(self):
        dev = KeysDevice()
        keys.enable(dev, self.path)
        original = json.loads(self.path.read_text())
        for field, value in (('services', "com.vendor/.Talk'; reboot; '"),
                             ('services', 'com.vendor/.Talk::com.other/.Reader'),
                             ('accessibility_enabled', "1'; reboot; '"),
                             ('status', 'unknown')):
            with self.subTest(field=field, value=value):
                self.path.write_text(json.dumps({**original, field: value}))
                before = dict(dev.secure)
                with self.assertRaises(ValueError): keys.restore(dev, self.path)
                self.assertEqual(dev.secure, before)

    def test_partial_enable_rolls_back_and_restore_is_idempotent(self):
        dev = KeysDevice('com.vendor/.Talk', '0')
        original = dev.shell
        failed = False
        def response(command):
            nonlocal failed
            if command == 'settings --user 0 put secure accessibility_enabled 1' and not failed:
                failed = True
                raise tv.RemoteError('connection lost')
            return original(command)
        with patch.object(dev, 'shell', side_effect=response):
            with self.assertRaises(tv.RemoteError): keys.enable(dev, self.path)
        self.assertEqual(dev.secure, {'enabled_accessibility_services': 'com.vendor/.Talk',
                                      'accessibility_enabled': '0'})
        self.assertEqual(json.loads(self.path.read_text())['status'], 'restored')
        dev.secure['enabled_accessibility_services'] = 'com.later/.Reader'
        dev.secure['accessibility_enabled'] = '1'
        keys.restore(dev, self.path)
        self.assertEqual(dev.secure['enabled_accessibility_services'], 'com.later/.Reader')

    def test_enable_requires_both_service_and_enabled_flag(self):
        dev = KeysDevice()
        original = dev.shell
        def response(command):
            if command == 'settings --user 0 put secure accessibility_enabled 1': return ''
            return original(command)
        with patch.object(dev, 'shell', side_effect=response):
            with self.assertRaisesRegex(RuntimeError, 'todos los ajustes'): keys.enable(dev, self.path)
        self.assertEqual(dev.secure['enabled_accessibility_services'], 'null')
        self.assertEqual(dev.secure['accessibility_enabled'], '0')


class FixTests(unittest.TestCase):
    def setUp(self):
        quiet(self)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'backup.json'
        self.dev = DebloatDevice()
        patcher = patch.object(debloat, 'shell', side_effect=lambda dev, cmd: dev.shell(cmd))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_fix_disables_what_an_update_reenabled_and_restore_still_returns_originals(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.firmware = 'brand/tv/build:2'
        self.dev.states['com.test.first'] = 1
        data = json.loads(self.path.read_text())
        self.assertEqual(debloat.fix(self.dev, data, self.path), 0)
        self.assertEqual(self.dev.states['com.test.first'], 3)
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved['fixed'][-1]['packages'], ['com.test.first'])
        debloat.restore(self.dev, saved, self.path)
        self.assertEqual(self.dev.states['com.test.first'], 0)
        self.assertEqual(self.dev.states['com.test.second'], 1)

    def test_fix_leaves_newly_protected_packages_and_reports_them(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.states['com.test.first'] = 1
        self.dev.tv_input = 'com.test.first/.Input'
        self.assertEqual(debloat.fix(self.dev, json.loads(self.path.read_text()), self.path), 1)
        self.assertEqual(self.dev.states['com.test.first'], 1)

    def test_fix_refuses_an_interrupted_backup(self):
        snapshot = debloat.plan(self.dev, ['com.test.first'])
        debloat.apply(self.dev, snapshot, self.path)
        data = json.loads(self.path.read_text())
        data['packages'][0]['status'] = 'pending'
        with self.assertRaisesRegex(ValueError, 'interrumpido'):
            debloat.fix(self.dev, data, self.path)

    def prepare_drift(self):
        snapshot = debloat.plan(self.dev, ['com.test.first', 'com.test.second'])
        debloat.apply(self.dev, snapshot, self.path)
        self.dev.states['com.test.first'] = 0
        self.dev.states['com.test.second'] = 1
        self.dev.writes.clear()
        return json.loads(self.path.read_text())

    def test_fix_rechecks_user_between_package_changes(self):
        data = self.prepare_drift()
        self.dev.on_write = lambda: setattr(self.dev, 'current_user', '10')
        with self.assertRaisesRegex(ValueError, 'usuario visible'):
            debloat.fix(self.dev, data, self.path)
        self.assertEqual(len(self.dev.writes), 1)
        self.assertEqual(self.dev.states['com.test.second'], 1)

    def test_fix_rechecks_newly_protected_package(self):
        data = self.prepare_drift()
        self.dev.on_write = lambda: setattr(self.dev, 'vpn', 'com.test.second')
        self.assertEqual(debloat.fix(self.dev, data, self.path), 1)
        self.assertEqual(len(self.dev.writes), 1)
        self.assertEqual(self.dev.states['com.test.second'], 1)

    def test_fix_refuses_missing_firmware(self):
        data = self.prepare_drift()
        self.dev.firmware = ''
        with self.assertRaisesRegex(ValueError, 'firmware'): debloat.fix(self.dev, data, self.path)
        self.assertEqual(self.dev.writes, [])



class ExamplesTests(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[1]

    def test_every_model_list_parses_and_never_targets_core_packages(self):
        lists = sorted((self.ROOT / 'examples').glob('*/debloat.txt'))
        self.assertGreater(len(lists), 150)
        for path in lists:
            with self.subTest(path=path.parent.name):
                packages = debloat.read_packages(path)
                self.assertFalse(set(packages) & {'android', 'com.adlesstv.launcher', 'com.android.systemui'})

    def test_every_theme_and_example_config_is_a_valid_theme(self):
        configs = list((self.ROOT / 'themes').glob('*/config.json')) + list((self.ROOT / 'examples').glob('*/config.json'))
        self.assertTrue(configs)
        for path in configs:
            with self.subTest(path=str(path.relative_to(self.ROOT))):
                theme.check_theme(path.parent)


if __name__ == '__main__':
    unittest.main()
