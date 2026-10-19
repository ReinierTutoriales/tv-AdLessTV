#!/usr/bin/env python3
"""Planifica, aplica y restaura debloat conservando el estado de cada paquete."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile

from tv import connect, shell

PACKAGE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+")
COMPONENT = re.compile(r"([A-Za-z_][A-Za-z0-9_.]*)/[A-Za-z0-9_.$]+")
STATES = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user", 4: "disable-until-used"}
# No desactivar infraestructura básica aunque aparezca en una lista de ejemplo.
CORE = {"android", "com.android.systemui", "com.android.settings", "com.android.shell",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.google.android.gms", "com.android.vending", "com.google.android.gsf",
        "com.android.providers.settings", "com.android.networkstack",
        "com.google.android.networkstack", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"}


def read_packages(path):
    result = []
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        package = raw.split("#", 1)[0].strip()
        if not package:
            continue
        if not PACKAGE.fullmatch(package):
            raise ValueError(f"Paquete inválido en línea {number}: {package!r}")
        if package not in result:
            result.append(package)
    if not result:
        raise ValueError("La lista de paquetes está vacía.")
    return result


def identity(dev):
    android_id = shell(dev, "settings --user 0 get secure android_id").strip()
    serial = shell(dev, "getprop ro.serialno").strip()
    if android_id.lower() in ("", "null", "unknown"):
        android_id = ""
    if serial.lower() in ("", "null", "unknown"):
        serial = ""
    if not serial and not android_id:
        raise ValueError("No se pudo identificar la TV; no se modificará nada.")
    return {"serial": serial, "android_id": android_id,
            "model": shell(dev, "getprop ro.product.model").strip()}


def package_state(dev, package):
    output = shell(dev, f"dumpsys package {package}")
    user = re.search(r"^\s*User 0:([^\n]*)", output, re.MULTILINE)
    if not user or not re.search(r"\binstalled=true\b", user[1]):
        raise ValueError(f"{package}: no está instalado para el usuario 0.")
    enabled = re.search(r"\benabled=(\d+)\b", user[1])
    if not enabled or int(enabled[1]) not in STATES:
        raise ValueError(f"{package}: estado desconocido; no se modificará nada.")
    return int(enabled[1])


def protected_packages(dev):
    homes = shell(dev, "cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
    home_packages = {match[1] for match in COMPONENT.finditer(homes)}
    if not home_packages:
        raise ValueError("No se pudieron identificar los lanzadores HOME; se cancela el debloat.")
    keyboards = shell(dev, "ime list -s")
    keyboard_packages = {match[1] for match in COMPONENT.finditer(keyboards)}
    if not keyboard_packages:
        raise ValueError("No se pudieron identificar los teclados; se cancela el debloat.")
    return CORE | home_packages | keyboard_packages


def write_snapshot(path, data):
    """Reemplazo atómico; un fallo conserva el último registro recuperable."""
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def plan(dev, packages):
    protected = protected_packages(dev)
    blocked = set(packages) & protected
    if blocked:
        raise ValueError("Paquetes protegidos (HOME, teclado o sistema): " + ", ".join(sorted(blocked)))
    # Completar toda la lectura antes de permitir el primer cambio.
    return {"version": 1, "user": 0, "device": identity(dev),
            "created_at": datetime.now(timezone.utc).isoformat(),
            "packages": [{"package": p, "state": package_state(dev, p), "status": "not_started"}
                         for p in packages]}


def apply(dev, snapshot, path):
    path = Path(path)
    # Reserva exclusiva: nunca sobrescribir un respaldo de otra operación.
    with path.open("x", encoding="utf-8") as stream:
        json.dump(snapshot, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    for entry in snapshot["packages"]:
        if entry["state"] in (2, 3, 4):
            print(f"Sin cambio (ya desactivado): {entry['package']}")
            continue
        # Registrar ANTES de enviar: también permite recuperar tras una desconexión.
        entry["status"] = "pending"
        write_snapshot(path, snapshot)
        shell(dev, f"pm disable-user --user 0 {entry['package']}")
        if package_state(dev, entry["package"]) != 3:
            raise RuntimeError(f"No se confirmó la desactivación de {entry['package']}")
        entry["status"] = "applied"
        write_snapshot(path, snapshot)
        print(f"Desactivado y verificado: {entry['package']}")


def validate_snapshot(data):
    if (not isinstance(data, dict) or data.get("version") != 1 or data.get("user") != 0
            or not isinstance(data.get("device"), dict)
            or not isinstance(data.get("packages"), list) or not data["packages"]):
        raise ValueError("Formato de respaldo inválido.")
    seen = set()
    for entry in data["packages"]:
        if (not isinstance(entry, dict) or not isinstance(entry.get("package"), str)
                or not PACKAGE.fullmatch(entry["package"])
                or type(entry.get("state")) is not int or entry["state"] not in STATES
                or entry.get("status") not in ("not_started", "pending", "applied", "restored")
                or entry["package"] in seen):
            raise ValueError("Entrada de respaldo inválida.")
        seen.add(entry["package"])


def restore(dev, data, path):
    validate_snapshot(data)
    if identity(dev) != data["device"]:
        raise ValueError("El respaldo pertenece a otra TV o a otro perfil; no se restaurará.")
    for entry in data["packages"]:
        if entry["status"] not in ("pending", "applied"):
            continue
        package, state = entry["package"], entry["state"]
        if package_state(dev, package) != state:
            shell(dev, f"pm {STATES[state]} --user 0 {package}")
            if package_state(dev, package) != state:
                raise RuntimeError(f"No se confirmó la restauración de {package}")
        entry["status"] = "restored"
        write_snapshot(path, data)
        print(f"Restaurado y verificado: {package} ({STATES[state]})")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    sub = parser.add_subparsers(dest="action", required=True)
    disable = sub.add_parser("disable", help="Solo muestra el plan salvo que se indique --apply")
    disable.add_argument("list", type=Path)
    disable.add_argument("--apply", action="store_true")
    disable.add_argument("--snapshot", type=Path)
    undo = sub.add_parser("restore", help="Restaura un respaldo de una operación anterior")
    undo.add_argument("snapshot", type=Path)
    args = parser.parse_args(argv)
    if args.action == "disable" and args.apply and not args.snapshot:
        parser.error("--apply requiere --snapshot con una ruta nueva para el respaldo.")
    dev = None
    try:
        if args.action == "disable":
            packages = read_packages(args.list)
            if args.apply and args.snapshot.exists():
                raise ValueError("El respaldo ya existe. Usa una ruta nueva.")
        else:
            data = json.loads(args.snapshot.read_text(encoding="utf-8"))
            validate_snapshot(data)
        dev = connect(args.host, args.port)
        if args.action == "restore":
            restore(dev, data, args.snapshot)
        else:
            snapshot = plan(dev, packages)
            for entry in snapshot["packages"]:
                action = "sin cambio" if entry["state"] in (2, 3, 4) else "desactivar"
                print(f"{entry['package']}: {STATES[entry['state']]} → {action}")
            if args.apply:
                apply(dev, snapshot, args.snapshot)
            else:
                print("Simulación completada. Para aplicar: --apply --snapshot <archivo-nuevo.json>")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        if args.action == "disable" and args.apply:
            print(f"Si hubo cambios, restaura con: python tools/debloat.py restore {args.snapshot}", file=sys.stderr)
        return 1
    finally:
        if dev is not None:
            dev.close()


if __name__ == "__main__":
    sys.exit(main())
