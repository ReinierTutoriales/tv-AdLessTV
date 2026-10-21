#!/usr/bin/env python3
"""Activa o restaura el servicio opcional de botones del mando, con respaldo de los ajustes previos."""

import argparse
import json
import os
from pathlib import Path
import sys
import shlex

from debloat import COMPONENT, identity, require_user_zero, write_snapshot
from tv import connect, shell

SERVICE = "com.adlesstv.launcher/com.adlesstv.launcher.RemoteKeyService"
SHORT = "com.adlesstv.launcher/.RemoteKeyService"


def read_state(dev):
    services = shell(dev, "settings --user 0 get secure enabled_accessibility_services").strip()
    enabled = shell(dev, "settings --user 0 get secure accessibility_enabled").strip()
    services = "" if services.lower() == "null" else services
    enabled = "" if enabled.lower() == "null" else enabled
    validate_state(services, enabled)
    return services, enabled


def validate_state(services, enabled):
    if (not isinstance(services, str) or not isinstance(enabled, str)
            or enabled not in ("", "0", "1")
            or (services and any(not COMPONENT.fullmatch(item) for item in services.split(":")))):
        raise ValueError("Estado de accesibilidad inválido; no se modificarán los ajustes.")


def listed(services):
    return [item for item in services.split(":") if item]


def ours(item):
    return item in (SERVICE, SHORT)


def put(dev, key, value):
    if value == "":
        shell(dev, f"settings --user 0 delete secure {key}")
    else:
        shell(dev, f"settings --user 0 put secure {key} {shlex.quote(value)}")


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
        stream.flush()
        os.fsync(stream.fileno())
    expected = ":".join(listed(services) + [SERVICE])
    try:
        put(dev, "enabled_accessibility_services", expected)
        require_user_zero(dev)
        put(dev, "accessibility_enabled", "1")
        after, flag = read_state(dev)
        if after != expected or flag != "1":
            raise RuntimeError("El sistema no aceptó todos los ajustes del servicio.")
    except Exception:
        try:
            restore(dev, path)
        except Exception:
            print(f"Recuperación pendiente: usa --restore {path} cuando vuelva ADB.", file=sys.stderr)
        raise
    data["status"] = "enabled"
    write_snapshot(path, data)
    print("Servicio de botones activado. Restaura con --restore y el mismo respaldo.")


def restore(dev, path):
    require_user_zero(dev)
    path = Path(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("services"), str)
            or not isinstance(data.get("accessibility_enabled"), str)
            or data.get("status") not in ("pending", "enabled", "restored")):
        raise ValueError("Respaldo de botones inválido.")
    validate_state(data["services"], data["accessibility_enabled"])
    if any(ours(item) for item in listed(data["services"])):
        raise ValueError("El respaldo no corresponde al estado anterior a la activación.")
    if identity(dev) != data.get("device"):
        raise ValueError("Este respaldo pertenece a otra TV.")
    if data["status"] == "restored":
        print("El respaldo de botones ya se restauró; se conservan las decisiones posteriores.")
        return
    current, _ = read_state(dev)
    # Keep services the user enabled afterwards; remove only ours and restore the saved flag.
    previous = listed(data["services"])
    kept = [item for item in listed(current) if not ours(item) and item not in previous]
    expected = ":".join(previous + kept)
    flag = data["accessibility_enabled"] if not kept else "1"
    put(dev, "enabled_accessibility_services", expected)
    require_user_zero(dev)
    put(dev, "accessibility_enabled", flag)
    after, enabled = read_state(dev)
    if after != expected or enabled != flag:
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
