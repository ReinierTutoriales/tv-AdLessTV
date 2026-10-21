#!/usr/bin/env python3
"""Punto de entrada unificado y reversible para configurar y recuperar la TV."""

import argparse
import json
from pathlib import Path
import sys

from tv import connect, shell
import home
import debloat


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--inspect", action="store_true")
    actions.add_argument("--activate", metavar="NEW_HOME_BACKUP")
    actions.add_argument("--verify-reboot", metavar="HOME_BACKUP")
    actions.add_argument("--restore", metavar="HOME_BACKUP")
    actions.add_argument("--debloat-plan", metavar="PROFILE_JSON")
    actions.add_argument("--debloat-apply", metavar="PROFILE_JSON")
    actions.add_argument("--debloat-verify", metavar="BACKUP_JSON")
    actions.add_argument("--debloat-restore", metavar="BACKUP_JSON")
    parser.add_argument("--debloat-backup", metavar="NEW_BACKUP_JSON", help="Obligatorio con --debloat-apply")
    parser.add_argument("--stock-package", help="Paquete HOME de fábrica exacto; solo con --activate")
    args = parser.parse_args(argv)
    if args.debloat_apply and not args.debloat_backup:
        parser.error("--debloat-apply requiere --debloat-backup")
    if args.debloat_backup and not args.debloat_apply:
        parser.error("--debloat-backup requiere --debloat-apply")
    if args.debloat_backup and Path(args.debloat_backup).exists():
        parser.error("El respaldo de debloat ya existe; usa una ruta nueva.")
    if args.stock_package and not args.activate:
        parser.error("--stock-package requiere --activate")
    if args.activate and Path(args.activate).exists():
        parser.error("El respaldo ya existe; usa una ruta nueva.")
    device = None
    try:
        device = connect(args.host, args.port)
        home.require_user_zero(device)
        if args.inspect:
            print("TV:", home.identity(device))
            print("HOME:", home.current_home(device))
            print("Actividades HOME habilitadas:")
            for candidate in sorted(home.home_claimants(device)):
                print(" ", candidate)
            print("APK de AdLessTV:", shell(device, "pm path com.adlesstv.launcher || true").strip() or "no instalado")
        elif args.debloat_plan or args.debloat_apply:
            profile = debloat.read_profile(Path(args.debloat_plan or args.debloat_apply))
            debloat.check_profile(device, profile)
            packages = [entry["package"] for entry in profile["packages"]]
            snapshot = debloat.plan(device, packages)
            for entry in snapshot["packages"]:
                print(entry["package"], debloat.STATES[entry["state"]])
            if args.debloat_apply:
                debloat.apply(device, snapshot, Path(args.debloat_backup))
            else:
                print("Solo simulación. No se cambió ningún paquete.")
        elif args.debloat_verify or args.debloat_restore:
            path = Path(args.debloat_verify or args.debloat_restore)
            data = json.loads(path.read_text(encoding="utf-8"))
            debloat.validate_snapshot(data)
            if args.debloat_verify:
                debloat.verify(device, data)
            else:
                debloat.restore(device, data, path)
        elif args.activate:
            home.activate(device, args.activate, args.stock_package)
            print("Siguiente: reinicia la TV y ejecuta --verify-reboot con el mismo respaldo.")
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
