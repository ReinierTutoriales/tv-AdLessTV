#!/usr/bin/env python3
"""Unified, reversible Android TV setup and recovery entry point."""

import argparse
from pathlib import Path
import sys

from tv import connect, shell
import home


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--inspect", action="store_true")
    actions.add_argument("--activate", metavar="NEW_HOME_BACKUP")
    actions.add_argument("--verify-reboot", metavar="HOME_BACKUP")
    actions.add_argument("--restore", metavar="HOME_BACKUP")
    parser.add_argument("--stock-package", help="Exact OEM HOME package, only with --activate")
    args = parser.parse_args(argv)
    if args.stock_package and not args.activate:
        parser.error("--stock-package requires --activate")
    if args.activate and Path(args.activate).exists():
        parser.error("Backup already exists; choose a new path.")
    device = None
    try:
        device = connect(args.host, args.port)
        home.require_user_zero(device)
        if args.inspect:
            print("Device:", home.identity(device))
            print("HOME:", home.current_home(device))
            print("HOME candidates:")
            for candidate in sorted(home.home_claimants(device)):
                print(" ", candidate)
            print("Launcher APK:", shell(device, "pm path com.adlesstv.launcher").strip())
        elif args.activate:
            home.activate(device, args.activate, args.stock_package)
            print("Next: reboot the TV, then run --verify-reboot with the same backup.")
        elif args.verify_reboot:
            home.verify_reboot(device, args.verify_reboot)
        else:
            home.restore(device, args.restore)
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if device is not None:
            device.close()


if __name__ == "__main__":
    sys.exit(main())
