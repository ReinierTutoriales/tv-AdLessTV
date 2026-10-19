#!/usr/bin/env python3
"""Activa, verifica tras reinicio y restaura HOME; desactivación OEM opcional."""

import argparse
import json
from pathlib import Path
import re
import sys

from debloat import CORE, PACKAGE, identity, package_state, write_snapshot
from tv import RemoteError, connect, shell

NEW_HOME = "com.adlesstv.launcher/.MainActivity"
COMPONENT = re.compile(r"[a-zA-Z_][\w.]*/[a-zA-Z_.$][\w.$]*")
RESOLVE = "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT"
BOOT_ID = re.compile(r"(?:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|count:[0-9]+)")


def canonical(component):
    package, activity = component.split("/", 1)
    if activity.startswith(package + "."):
        activity = activity[len(package):]
    return package + "/" + activity


def foreground_home(device):
    output = shell(device, "dumpsys activity activities")
    lines = re.findall(r"(?:topResumedActivity|mResumedActivity)\s*[:=]([^\n]*)", output)
    return any(canonical(match[0]) == NEW_HOME for line in lines for match in
               re.finditer(r"[a-zA-Z_][\w.]*/[a-zA-Z_.$][\w.$]*", line))


def boot_id(device):
    try:
        value = shell(device, "cat /proc/sys/kernel/random/boot_id").strip()
    except RemoteError:
        value = ""
    if BOOT_ID.fullmatch(value):
        return value.lower()
    count = shell(device, "settings get global boot_count").strip()
    if re.fullmatch(r"[0-9]+", count):
        return "count:" + count
    raise ValueError("No se puede comprobar el identificador de arranque de esta TV.")


def read_snapshot(path, device):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if (not isinstance(data, dict) or data.get("version") not in (1, 2)
            or data.get("new") != NEW_HOME
            or data.get("status") not in ("pending", "active", "reboot_verified",
                                          "stock_pending", "stock_disabled", "stock_verified", "restored")
            or not isinstance(data.get("previous"), str)
            or not COMPONENT.fullmatch(data["previous"])
            or canonical(data["previous"]) == NEW_HOME):
        raise ValueError("Respaldo HOME inválido.")
    if data["version"] == 1:
        if data["status"] not in ("pending", "active", "restored"):
            raise ValueError("Estado inválido en respaldo HOME anterior.")
    elif (not BOOT_ID.fullmatch(str(data.get("activated_boot", "")))
          or type(data.get("previous_state")) is not int or data["previous_state"] not in (0, 1)):
        raise ValueError("Respaldo HOME inválido.")
    if identity(device) != data.get("device"):
        raise ValueError("Este respaldo HOME pertenece a otra TV.")
    if data.get("stock_package") not in (None, data["previous"].split("/", 1)[0]):
        raise ValueError("Paquete HOME inválido en el respaldo.")
    data["previous"] = canonical(data["previous"])
    return data


def current_home(device):
    output = shell(device, RESOLVE)
    candidates = [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]
    if len(candidates) != 1 or "ResolverActivity" in candidates[0]:
        raise ValueError("No se pudo determinar un HOME anterior único; configura HOME desde Ajustes.")
    return canonical(candidates[0])


def settle_configuration(device):
    # AOSP batches package/preferred-activity writes with WRITE_SETTINGS_DELAY=10s.
    # Immediate ADB reboot can otherwise lose a change already visible in memory.
    # Only installation/restoration waits; the launcher has no timer or background job.
    print("Guardando la configuración HOME antes de permitir el reinicio...", flush=True)
    shell(device, "sleep 12")
    # Android prints help with exit status 255; only capability discovery accepts it.
    help_text = shell(device, "cmd package help 2>&1 || true")
    for command in ("wait-for-handler", "wait-for-background-handler"):
        if command in help_text:
            shell(device, "cmd package " + command + " --timeout 15000")
    shell(device, "sync")


def activate(device, snapshot_path, stock_package=None):
    path = Path(snapshot_path)
    previous = current_home(device)
    if previous == NEW_HOME:
        raise ValueError("AdLessTV ya es HOME; conserva el respaldo de la activación anterior.")
    if stock_package is not None and (stock_package != previous.split("/", 1)[0] or stock_package in CORE):
        raise ValueError("Indica el paquete HOME anterior exacto y no protegido.")
    original_state = package_state(device, previous.split("/", 1)[0])
    if original_state not in (0, 1):
        raise ValueError("El HOME anterior no está habilitado; no se modificará.")
    initial_boot = boot_id(device)
    installed = shell(device, "pm path com.adlesstv.launcher")
    if not any(line.startswith("package:") for line in installed.splitlines()):
        raise ValueError("Instala y abre AdLessTV antes de activarlo como HOME.")
    # Preflight the activity itself before changing HOME.
    launched = shell(device, "am start -W -n " + NEW_HOME)
    if "Status: ok" not in launched:
        raise ValueError("No se confirmó el inicio de AdLessTV; HOME sigue intacto.")
    data = {"version": 2, "device": identity(device), "previous": previous,
            "previous_state": original_state, "activated_boot": initial_boot,
            "new": NEW_HOME, "status": "pending"}
    with path.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.flush()
        import os
        os.fsync(stream.fileno())
    shell(device, "cmd package set-home-activity --user 0 " + NEW_HOME)
    selected = current_home(device)
    if stock_package is not None:
        disable_stock(device, path, stock_package)
        return
    if selected != NEW_HOME:
        # The role may be granted while a higher-priority TV HOME still resolves.
        # Undo the role change before reporting failure; keep the journal if recovery fails.
        restore(device, path)
        raise RuntimeError(f"HOME sigue en {selected}; se restauró el rol anterior. "
                           "Revisa el paquete anterior antes de usar --stock-package con un respaldo nuevo.")
    settle_configuration(device)
    data["status"] = "active"
    write_snapshot(path, data)
    print("HOME activado. El HOME anterior sigue instalado y habilitado.")


def verify_reboot(device, snapshot_path):
    path = Path(snapshot_path)
    data = read_snapshot(path, device)
    if data["version"] != 2 or data["status"] not in ("active", "reboot_verified",
                                                      "stock_disabled", "stock_verified"):
        raise ValueError("Este respaldo no corresponde a una activación pendiente de verificar.")
    current_boot = boot_id(device)
    last_boot = data.get("verified_boot", data.get("changed_boot", data["activated_boot"]))
    if current_boot == last_boot:
        raise ValueError("La TV aún no ha reiniciado desde que se activó HOME.")
    if current_home(device) != NEW_HOME:
        raise ValueError("El firmware no conservó AdLessTV como HOME tras reiniciar.")
    expected = 3 if data["status"] in ("stock_disabled", "stock_verified") else data["previous_state"]
    if package_state(device, data["previous"].split("/", 1)[0]) != expected:
        raise ValueError("Cambió el estado del HOME anterior; revisa la TV antes de continuar.")
    if not foreground_home(device):
        raise ValueError("AdLessTV es HOME pero no está visible tras arrancar. No abras apps antes de verificar.")
    data["verified_boot"] = current_boot
    data["status"] = "stock_verified" if expected == 3 else "reboot_verified"
    write_snapshot(path, data)
    print("HOME verificado después del reinicio; conserva el respaldo para restaurar.")


def disable_stock(device, snapshot_path, expected_package):
    path = Path(snapshot_path)
    data = read_snapshot(path, device)
    stock = data["previous"].split("/", 1)[0]
    if (data["version"] != 2 or data["status"] not in ("pending", "active", "reboot_verified") or expected_package != stock
            or not PACKAGE.fullmatch(expected_package) or stock in CORE):
        raise ValueError("Se requiere una activación guardada y el paquete HOME exacto.")
    if current_home(device) not in (NEW_HOME, data["previous"]):
        raise ValueError("Se seleccionó un HOME diferente; no se reemplazará.")
    if package_state(device, stock) != data["previous_state"]:
        raise ValueError("Cambió el estado del HOME anterior; no se desactivará.")
    if "Status: ok" not in shell(device, "am start -W -n " + NEW_HOME):
        raise ValueError("AdLessTV no abre; no se desactivará el HOME anterior.")
    data["stock_package"] = stock
    data["changed_boot"] = boot_id(device)
    data.pop("verified_boot", None)
    data["status"] = "stock_pending"
    write_snapshot(path, data)
    shell(device, "cmd package set-home-activity --user 0 " + NEW_HOME)
    # A higher-priority system HOME can win even when the HOME role was granted.
    # Opening our activity was checked before the journal and the exact-package change.
    shell(device, f"pm disable-user --user 0 {stock}")
    if package_state(device, stock) != 3 or current_home(device) != NEW_HOME:
        restore(device, path)
        raise RuntimeError("El reemplazo no funcionó; se restauró el HOME anterior.")
    settle_configuration(device)
    data["status"] = "stock_disabled"
    write_snapshot(path, data)
    print("HOME anterior desactivado para usuario 0; conserva el respaldo para recuperarlo.")


def restore(device, snapshot_path):
    path = Path(snapshot_path)
    data = read_snapshot(path, device)
    if data["status"] == "restored":
        if current_home(device) == data["previous"]:
            print("HOME original ya restaurado.")
            return
        raise ValueError("HOME cambió después de la restauración; no se alterará.")
    # Recover the stock package first: a failed HOME resolution must not prevent recovery.
    if data["status"] in ("stock_pending", "stock_disabled", "stock_verified"):
        stock = data["stock_package"]
        if package_state(device, stock) != data["previous_state"]:
            action = "default-state" if data["previous_state"] == 0 else "enable"
            shell(device, f"pm {action} --user 0 {stock}")
            if package_state(device, stock) != data["previous_state"]:
                raise RuntimeError("No se confirmó la restauración del HOME anterior.")
        data["status"] = "active"
        write_snapshot(path, data)
    # If a user selected a different HOME meanwhile, respect that choice.
    try:
        current = current_home(device)
    except ValueError:
        current = None  # Resolver/no preferred HOME after a failed replacement: recover the saved HOME.
    if current is not None and current not in (NEW_HOME, data["previous"]):
        raise ValueError("HOME fue cambiado manualmente; el paquete anterior está restaurado.")
    # Restore the role as well: stock priority can hide a still-assigned replacement role.
    shell(device, "cmd package set-home-activity --user 0 " + data["previous"])
    if current_home(device) != data["previous"]:
        raise RuntimeError("No se confirmó HOME anterior. Conserva el respaldo.")
    settle_configuration(device)
    data["status"] = "restored"
    write_snapshot(path, data)
    print("HOME anterior restaurado.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--activate", metavar="RESPALDO_NUEVO_JSON")
    actions.add_argument("--verify-reboot", metavar="RESPALDO_JSON")
    actions.add_argument("--disable-stock", metavar="RESPALDO_JSON")
    actions.add_argument("--restore", metavar="RESPALDO_JSON")
    parser.add_argument("--stock-package", help="Paquete exacto del HOME de fábrica; solo con --disable-stock")
    args = parser.parse_args(argv)
    if (args.disable_stock and not args.stock_package) or (args.stock_package and not (args.activate or args.disable_stock)):
        parser.error("--stock-package solo acompaña --activate o --disable-stock; --disable-stock lo requiere.")
    path = Path(args.activate or args.verify_reboot or args.disable_stock or args.restore)
    if args.activate and path.exists():
        parser.error("El respaldo ya existe. Conserva el anterior y usa una ruta nueva.")
    device = None
    try:
        device = connect(args.host, args.port)
        if args.activate: activate(device, path, args.stock_package)
        elif args.verify_reboot: verify_reboot(device, path)
        elif args.disable_stock: disable_stock(device, path, args.stock_package)
        else: restore(device, path)
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        if args.activate:
            print("Si se creó el respaldo, puedes restaurar con --restore.", file=sys.stderr)
        return 1
    finally:
        if device is not None: device.close()


if __name__ == "__main__":
    sys.exit(main())
