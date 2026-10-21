#!/usr/bin/env python3
"""Activa o restaura el servicio opcional de botones del mando, con respaldo de los ajustes previos."""

import argparse
import json
from pathlib import Path
import sys

from debloat import identity, require_user_zero, write_snapshot
from tv import connect, shell

SERVICE = "com.adlesstv.launcher/com.adlesstv.launcher.RemoteKeyService"
SHORT = "com.adlesstv.launcher/.RemoteKeyService"


def read_state(dev):
    services = shell(dev, "settings --user 0 get secure enabled_accessibility_services").strip()
    enabled = shell(dev, "settings --user 0 get secure accessibility_enabled").strip()
    return ("" if services.lower() == "null" else services), ("" if enabled.lower() == "null" else enabled)


def listed(services):
    return [item for item in services.split(":") if item]


def ours(item):
    return item in (SERVICE, SHORT)


def put(dev, key, value):
    if value == "":
        shell(dev, f"settings --user 0 delete secure {key}")
    else:
        shell(dev, f"settings --user 0 put secure {key} '{value}'")


def enable(dev, path):
    require_user_zero(dev)
    path = Path(path)
    out = shell(dev, "dumpsys package com.adlesstv.launcher")
    if "RemoteKeyService" not in out:
        raise ValueError("Instala una versión de AdLessTV que incluya el servicio de botones.")
    services, enabled = read_state(dev)
    if any(ours(item) for item in listed(services)):
        raise ValueError("El servicio ya está activo; conserva el respaldo con el que se activó.")
    if any(not ("/" in item) for item in listed(services)):
        raise ValueError("No se pudo interpretar la lista de servicios de accesibilidad.")
    data = {"version": 1, "device": identity(dev), "services": services, "accessibility_enabled": enabled,
            "status": "pending"}
    with path.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
    put(dev, "enabled_accessibility_services", ":".join(listed(services) + [SERVICE]))
    put(dev, "accessibility_enabled", "1")
    after, _ = read_state(dev)
    if not any(ours(item) for item in listed(after)):
        restore(dev, path)
        raise RuntimeError("El sistema no aceptó el servicio; se restauraron los ajustes anteriores.")
    data["status"] = "enabled"
    write_snapshot(path, data)
    print("Servicio de botones activado. Restaura con --restore y el mismo respaldo.")


def restore(dev, path):
    require_user_zero(dev)
    path = Path(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("services"), str)
            or not isinstance(data.get("accessibility_enabled"), str)):
        raise ValueError("Respaldo de botones inválido.")
    if identity(dev) != data.get("device"):
        raise ValueError("Este respaldo pertenece a otra TV.")
    current, _ = read_state(dev)
    # Keep services the user enabled afterwards; remove only ours and restore the saved flag.
    previous = listed(data["services"])
    kept = [item for item in listed(current) if not ours(item) and item not in previous]
    put(dev, "enabled_accessibility_services", ":".join(previous + kept))
    put(dev, "accessibility_enabled", data["accessibility_enabled"] if not kept else "1")
    after, _ = read_state(dev)
    if any(ours(item) for item in listed(after)):
        raise RuntimeError("No se confirmó la retirada del servicio. Conserva el respaldo.")
    data["status"] = "restored"
    write_snapshot(path, data)
    print("Ajustes de accesibilidad restaurados.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--enable", metavar="RESPALDO_NUEVO_JSON")
    action.add_argument("--restore", metavar="RESPALDO_JSON")
    args = parser.parse_args(argv)
    if args.enable and Path(args.enable).exists():
        parser.error("El respaldo ya existe; usa una ruta nueva.")
    dev = None
    try:
        dev = connect(args.host, args.port)
        if args.enable:
            enable(dev, args.enable)
        else:
            restore(dev, args.restore)
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if dev is not None:
            dev.close()


if __name__ == "__main__":
    sys.exit(main())
