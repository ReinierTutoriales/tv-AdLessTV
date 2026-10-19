#!/usr/bin/env python3
"""Exercise HOME selection, replacement, boot and restore on a disposable emulator only."""
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import home


def adb(*arguments, timeout=120):
    return subprocess.check_output(['adb', *arguments], text=True, timeout=timeout)


class Device:
    def shell(self, command, **kwargs):
        return adb('shell', command)


def reboot():
    adb('reboot')
    adb('wait-for-device', timeout=180)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if adb('shell', 'getprop sys.boot_completed').strip() == '1':
            return
        time.sleep(2)
    raise RuntimeError('Emulator boot timed out')


def wait_for_home(device):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if home.foreground_home(device):
            return
        time.sleep(1)
    raise RuntimeError('HOME was not displayed automatically after boot')


def main():
    if adb('shell', 'getprop ro.kernel.qemu').strip() != '1':
        raise RuntimeError('This reboot test is restricted to an emulator')
    output = Path(os.environ.get('UI_RESULTS', Path(__file__).resolve().parents[1] / 'ui-results')) / 'boot'
    output.mkdir(parents=True, exist_ok=True)
    snapshot = output / 'home.json'
    device = Device()
    adb('shell', 'locksettings set-disabled true')
    try:
        try:
            home.activate(device, snapshot)
        except RuntimeError as error:
            if ('se restauró el rol anterior' not in str(error) or not snapshot.exists()
                    or home.read_snapshot(snapshot, device)['status'] != 'restored'
                    or home.current_home(device) != home.read_snapshot(snapshot, device)['previous']):
                raise
            previous = home.read_snapshot(snapshot, device)['previous'].split('/', 1)[0]
            print('The stock HOME priority overrides the role; testing explicit replacement.')
            snapshot = output / 'home-stock.json'
            home.activate(device, snapshot, previous)
        reboot()
        wait_for_home(device)
        home.verify_reboot(device, snapshot)
        previous = home.read_snapshot(snapshot, device)['previous'].split('/', 1)[0]
        if home.read_snapshot(snapshot, device)['status'] != 'stock_verified':
            home.disable_stock(device, snapshot, previous)
        reboot()
        wait_for_home(device)
        home.verify_reboot(device, snapshot)
        (output / 'activity-after-boot.txt').write_text(adb('shell', 'dumpsys activity activities'))
        with (output / 'home-after-boot.png').open('wb') as stream:
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=stream, check=True)
    finally:
        for label, command in {
                'home-resolve': home.RESOLVE,
                'home-resolve-without-default': home.RESOLVE.replace(' -c android.intent.category.DEFAULT', ''),
                'roles': 'dumpsys role',
                'activities': 'dumpsys activity activities',
                'packages': 'dumpsys package',
                'boot-logcat': 'logcat -d -t 2000',
        }.items():
            try: (output / (label + '.txt')).write_text(adb('shell', command))
            except Exception as error: print(f'Diagnostic {label}: {error}', file=sys.stderr)
        if snapshot.exists(): home.restore(device, snapshot)
    print('HOME persisted through two reboots and the original launcher was restored.')


if __name__ == '__main__':
    main()
