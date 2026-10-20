#!/usr/bin/env python3
"""Inventario ADB de paquetes del usuario 0; no modifica la TV ni mide tráfico."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

from debloat import identity, protected_packages, require_user_zero
from tv import connect, shell

PACKAGE_LINE = re.compile(r"package:([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+)")


def packages(output):
    result = set()
    for line in output.splitlines():
        match = PACKAGE_LINE.fullmatch(line.strip())
        if not match:
            raise ValueError(f"Respuesta inesperada de pm: {line[:100]!r}")
        result.add(match[1])
    return result


def capture(device, review=False):
    installed = packages(shell(device, "pm list packages --user 0"))
    disabled = packages(shell(device, "pm list packages -d --user 0"))
    if not installed or not disabled.issubset(installed):
        raise ValueError("Inventario incompleto o incoherente; no se guardará.")
    result = {"version": 1, "user": 0, "device": identity(device),
            "device_codename": shell(device, "getprop ro.product.device").strip(),
            "firmware": shell(device, "getprop ro.build.fingerprint").strip(),
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "installed": sorted(installed), "disabled": sorted(disabled)}
    if review:
        require_user_zero(device)
        system = packages(shell(device, "pm list packages -s --user 0"))
        if not system or not system.issubset(installed):
            raise ValueError("Inventario de paquetes de sistema incompleto; no se guardará.")
        protected = protected_packages(device) & installed
        result["review"] = {"protected": sorted(protected),
                            "system_to_review": sorted(system - disabled - protected)}
    return result


def validate(data):
    if (not isinstance(data, dict) or data.get("version") != 1 or data.get("user") != 0
            or not isinstance(data.get("device"), dict)
            or not isinstance(data.get("firmware"), str)
            or ("device_codename" in data and not isinstance(data["device_codename"], str))
            or not isinstance(data.get("installed"), list)
            or not isinstance(data.get("disabled"), list)):
        raise ValueError("Inventario anterior inválido.")
    installed, disabled = data["installed"], data["disabled"]
    if (not installed or any(not isinstance(p, str) or not PACKAGE_LINE.fullmatch("package:" + p)
                             for p in installed + disabled)
            or len(installed) != len(set(installed)) or len(disabled) != len(set(disabled))
            or not set(disabled).issubset(installed)):
        raise ValueError("Paquetes inválidos en el inventario anterior.")


def compare(before, after):
    validate(before)
    validate(after)
    if before["device"] != after["device"]:
        raise ValueError("Los inventarios pertenecen a TVs o perfiles diferentes.")
    if (before.get("device_codename") and after.get("device_codename")
            and before["device_codename"] != after["device_codename"]):
        raise ValueError("Cambió el identificador interno del dispositivo; revisa los inventarios.")
    if before["firmware"] != after["firmware"]:
        raise ValueError("Cambió el firmware; compara únicamente la misma compilación.")
    first, last = set(before["disabled"]), set(after["disabled"])
    old, new = set(before["installed"]), set(after["installed"])
    return {"newly_disabled": sorted(last - first), "reenabled": sorted(first - last),
            "newly_installed": sorted(new - old), "removed": sorted(old - new)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--output", type=Path, required=True, help="JSON nuevo; no se sobrescribe")
    parser.add_argument("--compare", type=Path, help="JSON previo de la misma TV y firmware")
    parser.add_argument("--review", action="store_true", help="Enumera paquetes de sistema para revisión manual, sin sugerir desactivarlos")
    args = parser.parse_args(argv)
    device = None
    try:
        if args.output.exists():
            raise ValueError("El inventario de salida ya existe; indica un archivo nuevo.")
        before = None
        if args.compare:
            before = json.loads(args.compare.read_text(encoding="utf-8"))
            validate(before)
        device = connect(args.host, args.port)
        after = capture(device, review=args.review)
        differences = compare(before, after) if before else None
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(after, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(f"Inventario guardado: {args.output}; instalados: {len(after['installed'])}; "
              f"desactivados: {len(after['disabled'])}")
        if args.review:
            print("Paquetes de sistema habilitados para revisar manualmente (NO es una lista segura para desactivar):")
            for package in after["review"]["system_to_review"]:
                print("  " + package)
        if differences is not None:
            for key, values in differences.items():
                print(f"{key} ({len(values)}): {', '.join(values) if values else 'ninguno'}")
            print("La diferencia de paquetes no mide ni atribuye tráfico de red.")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if device is not None:
            device.close()


if __name__ == "__main__":
    sys.exit(main())
