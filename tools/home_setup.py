#!/usr/bin/env python3
"""Instala HOME de forma planificada, incluidos los competidores conocidos de Google TV."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

import home
from debloat import (check_wifi_provisioning, identity, package_state,
                     protected_packages, require_user_zero, write_snapshot)
from inventory import packages
from tv import RemoteError, connect, shell

STOCK = {"com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
         "com.google.android.leanbacklauncher"}
SETUP = "com.google.android.tungsten.setupwraith"
SUPPORTED = STOCK | {SETUP}


class NativeAdb:
    """Reuse Platform-Tools and its existing authorization; no pip dependency needed."""
    def __init__(self, executable, host, port):
        address = f"[{host}]" if ":" in host and not host.startswith("[") else host
        self.prefix = [executable, "-s", f"{address}:{port}"]
        self.run([executable, "connect", f"{address}:{port}"])
        if self.run(self.prefix + ["get-state"]).strip() != "device":
            raise RemoteError("ADB no autorizado; acepta el aviso en la TV.")

    @staticmethod
    def run(arguments):
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RemoteError((result.stderr or result.stdout).strip() or "ADB falló.")
        return result.stdout

    def shell(self, command, **kwargs):
        return self.run(self.prefix + ["shell", command])

    def close(self):
        pass


def save_new(path, data):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())



def check_setup_functions(device, targets):
    if SETUP not in targets:
        return
    codename = shell(device, "getprop ro.product.device").strip()
    report = shell(device, "dumpsys package " + SETUP)
    if not report.strip() or "Permission Denial" in report:
        raise ValueError("No se pudieron revisar las funciones del asistente; se conserva activo.")
    if codename in {"sabrina", "boreal", "kirkwood"} or "castinfoprovider" in report.lower():
        raise ValueError("Este asistente proporciona credenciales de Cast; se conserva activo. "
                         "El reemplazo HOME completo no se aplicará a este firmware.")


def plan(device):
    require_user_zero(device)
    previous = home.current_home(device)
    previous_package = previous.split("/", 1)[0]
    if previous_package not in SUPPORTED:
        raise ValueError("HOME actual no es un componente de Google TV reconocido: " + previous
                         + ". Conserva el respaldo si AdLessTV ya es HOME.")
    claimants = home.home_claimants(device)
    if previous not in claimants or home.NEW_HOME not in claimants:
        raise ValueError("Instala y abre AdLessTV antes de preparar el reemplazo HOME.")
    unexpected = {c for c in claimants - home.SYSTEM_FALLBACK_HOMES - {home.NEW_HOME}
                  if c.split("/", 1)[0] not in SUPPORTED}
    if unexpected:
        raise ValueError("HOME no reconocido; no se cambiará: " + ", ".join(sorted(unexpected)))
    installed = packages(shell(device, "pm list packages --user 0"))
    targets = {c.split("/", 1)[0] for c in claimants
               if c.split("/", 1)[0] in SUPPORTED}
    # The setup package can re-enable the stock home even when its HOME activity is not resolved now.
    if SETUP in installed and package_state(device, SETUP) in (0, 1):
        targets.add(SETUP)
    blocked = targets & protected_packages(device, include_home=False)
    if blocked:
        raise ValueError("Un competidor HOME cumple otra función protegida: " + ", ".join(sorted(blocked)))
    check_setup_functions(device, targets)
    check_wifi_provisioning(device, targets)
    entries = []
    for package in sorted(targets, key=lambda p: (p != SETUP, p)):
        state = package_state(device, package)
        if state not in (0, 1):
            raise ValueError("Cambió un candidato HOME; vuelve a preparar el plan.")
        entries.append({"package": package, "state": state,
                        "reason": "Asistente que puede recuperar HOME" if package == SETUP
                        else "Launcher de fábrica que compite con AdLessTV"})
    firmware = shell(device, "getprop ro.build.fingerprint").strip()
    if not firmware:
        raise ValueError("No se pudo identificar el firmware.")
    return {"version": 1, "kind": "adlesstv-home-plan", "new": home.NEW_HOME,
            "device": identity(device), "firmware": firmware, "previous": previous,
            "claimants": sorted(claimants), "packages": entries}


def validate_plan(data):
    if (not isinstance(data, dict) or data.get("version") != 1
            or data.get("kind") != "adlesstv-home-plan" or data.get("new") != home.NEW_HOME
            or not isinstance(data.get("device"), dict)
            or not isinstance(data.get("firmware"), str) or not data["firmware"]
            or not isinstance(data.get("previous"), str) or not home.COMPONENT.fullmatch(data["previous"])
            or data["previous"].split("/", 1)[0] not in SUPPORTED
            or not isinstance(data.get("claimants"), list) or not data["claimants"]
            or any(not isinstance(c, str) or not home.COMPONENT.fullmatch(c) for c in data["claimants"])
            or not isinstance(data.get("packages"), list) or not data["packages"]):
        raise ValueError("Plan HOME inválido.")
    seen = set()
    for entry in data["packages"]:
        if (not isinstance(entry, dict) or not isinstance(entry.get("package"), str)
                or entry["package"] not in SUPPORTED
                or entry["package"] in seen or type(entry.get("state")) is not int
                or entry["state"] not in (0, 1)):
            raise ValueError("Candidato inválido en plan HOME.")
        seen.add(entry["package"])
    if data["previous"].split("/", 1)[0] not in seen:
        raise ValueError("El plan no conserva el estado del HOME anterior.")


def read_journal(path, device):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if (not isinstance(data, dict) or data.get("version") != 3
            or data.get("kind") != "adlesstv-home-journal"
            or data.get("status") not in ("pending", "active", "verified", "restored")
            or not home.BOOT_ID.fullmatch(str(data.get("changed_boot", "")))):
        raise ValueError("Respaldo de instalación HOME inválido.")
    candidate = {**data, "kind": "adlesstv-home-plan", "version": 1}
    validate_plan(candidate)
    if identity(device) != data["device"]:
        raise ValueError("El respaldo pertenece a otra TV.")
    for entry in data["packages"]:
        if entry.get("status") not in ("not_started", "pending", "applied", "restored"):
            raise ValueError("Estado inválido en respaldo HOME.")
    return data


def restore(device, path):
    require_user_zero(device)
    data = read_journal(path, device)
    if data["status"] == "restored":
        if home.current_home(device) != data["previous"]:
            raise ValueError("HOME cambió después de restaurar; no se modificará.")
        return
    failures = []
    # Restore every attempted package, even if one other entry cannot be restored.
    for entry in reversed(data["packages"]):
        if entry["status"] not in ("pending", "applied"):
            continue
        try:
            require_user_zero(device)
            if package_state(device, entry["package"]) != entry["state"]:
                action = "default-state" if entry["state"] == 0 else "enable"
                shell(device, f"pm {action} --user 0 {entry['package']}")
                if package_state(device, entry["package"]) != entry["state"]:
                    raise RuntimeError("No se confirmó el estado original.")
            entry["status"] = "restored"
            write_snapshot(path, data)
        except Exception as error:
            failures.append(f"{entry['package']}: {error}")
    if failures:
        raise RuntimeError("Restauración incompleta; conserva el respaldo:\n" + "\n".join(failures))
    try:
        current = home.current_home(device)
    except ValueError:
        current = None
    if current is not None and current not in set(data["claimants"]) | {home.NEW_HOME}:
        raise ValueError("Otro HOME fue seleccionado; paquetes recuperados, selección manual conservada.")
    shell(device, "cmd package set-home-activity --user 0 " + data["previous"])
    if home.current_home(device) != data["previous"]:
        raise RuntimeError("No se confirmó el HOME original; conserva el respaldo.")
    home.settle_configuration(device)
    data["status"] = "restored"
    write_snapshot(path, data)
    print("HOME y estados originales de todos los paquetes intentados restaurados.")


def apply(device, candidate, path):
    if Path(path).exists():
        raise ValueError("El respaldo ya existe; usa una ruta nueva.")
    validate_plan(candidate)
    # Rebuild the whole plan before any mutation: a new HOME, OTA, Wi-Fi owner,
    # user or changed package state invalidates the previously reviewed plan.
    fresh = plan(device)
    for key in ("device", "firmware", "previous", "claimants"):
        if fresh[key] != candidate[key]:
            raise ValueError("Cambió la TV o la configuración; prepara un plan nuevo.")
    if [(p["package"], p["state"]) for p in fresh["packages"]] != [
            (p["package"], p["state"]) for p in candidate["packages"]]:
        raise ValueError("Cambió la lista o el estado de HOME; prepara un plan nuevo.")
    if not home.launched_successfully(shell(device, "am start -W -n " + home.NEW_HOME)):
        raise ValueError("AdLessTV no abre; no se modificará HOME.")
    data = {**fresh, "version": 3, "kind": "adlesstv-home-journal", "status": "pending",
            "changed_boot": home.boot_id(device),
            "packages": [{**p, "status": "not_started"} for p in fresh["packages"]]}
    save_new(path, data)
    try:
        shell(device, "cmd package set-home-activity --user 0 " + home.NEW_HOME)
        for entry in data["packages"]:
            require_user_zero(device)
            if shell(device, "getprop ro.build.fingerprint").strip() != data["firmware"]:
                raise ValueError("Cambió el firmware durante la operación.")
            if home.home_claimants(device) - set(data["claimants"]):
                raise ValueError("Apareció un HOME no revisado durante la operación.")
            if entry["package"] in protected_packages(device, include_home=False):
                raise ValueError("El paquete pasó a cumplir una función protegida.")
            check_setup_functions(device, [entry["package"]])
            check_wifi_provisioning(device, [entry["package"]])
            if package_state(device, entry["package"]) != entry["state"]:
                raise ValueError("Cambió el estado del paquete durante la operación.")
            entry["status"] = "pending"
            write_snapshot(path, data)
            shell(device, f"pm disable-user --user 0 {entry['package']}")
            if package_state(device, entry["package"]) != 3:
                raise RuntimeError("No se confirmó la desactivación de " + entry["package"])
            entry["status"] = "applied"
            write_snapshot(path, data)
            shell(device, "cmd package set-home-activity --user 0 " + home.NEW_HOME)
        if home.current_home(device) != home.NEW_HOME:
            raise RuntimeError("El firmware sigue seleccionando otro HOME.")
        if not home.launched_successfully(shell(device, "am start -W -n " + home.NEW_HOME)):
            raise RuntimeError("No se confirmó el inicio final de AdLessTV.")
        home.settle_configuration(device)
        data["status"] = "active"
        write_snapshot(path, data)
    except Exception as error:
        try:
            restore(device, path)
        except Exception as recovery:
            raise RuntimeError(f"Instalación interrumpida: {error}. Recuperación pendiente: {recovery}. "
                               f"Usa restore con {path} cuando vuelva ADB.") from error
        raise RuntimeError(f"Instalación cancelada y estado anterior restaurado: {error}") from error
    print("AdLessTV es HOME; competidores revisados desactivados. Reinicia y ejecuta verify.")


def verify(device, path):
    require_user_zero(device)
    data = read_journal(path, device)
    if data["status"] not in ("active", "verified"):
        raise ValueError("Este respaldo no contiene una activación terminada.")
    current_boot = home.boot_id(device)
    if current_boot == data.get("verified_boot", data["changed_boot"]):
        raise ValueError("Reinicia antes de verificar la persistencia.")
    if shell(device, "getprop ro.build.fingerprint").strip() != data["firmware"]:
        raise ValueError("Cambió el firmware; revisa la instalación.")
    if home.current_home(device) != home.NEW_HOME or not home.foreground_home(device):
        raise ValueError("AdLessTV no apareció como HOME por sí solo después de reiniciar.")
    for entry in data["packages"]:
        if entry["status"] != "applied" or package_state(device, entry["package"]) != 3:
            raise ValueError("Un competidor recuperó su estado; revisa el respaldo.")
    data["verified_boot"] = current_boot
    data["status"] = "verified"
    write_snapshot(path, data)
    print("Persistencia HOME y estados de todos los competidores verificados tras reinicio.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=os.environ.get("TV_HOST"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("TV_PORT", "5555")))
    parser.add_argument("--adb", help="Ruta a adb/adb.exe; reutiliza Platform-Tools sin instalar dependencias Python")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("plan").add_argument("--output", type=Path, required=True)
    execute = sub.add_parser("apply")
    execute.add_argument("--plan", type=Path, required=True)
    execute.add_argument("--snapshot", type=Path, required=True)
    for action in ("restore", "verify"):
        sub.add_parser(action).add_argument("snapshot", type=Path)
    args = parser.parse_args(argv)
    device = None
    try:
        if not args.host or not 1 <= args.port <= 65535:
            raise ValueError("Indica --host y un puerto ADB válido.")
        output = args.output if args.action == "plan" else args.snapshot
        if args.action in ("plan", "apply") and output.exists():
            raise ValueError("El archivo de salida ya existe; utiliza una ruta nueva.")
        candidate = json.loads(args.plan.read_text(encoding="utf-8")) if args.action == "apply" else None
        if candidate is not None:
            validate_plan(candidate)
        device = NativeAdb(args.adb, args.host, args.port) if args.adb else connect(args.host, args.port)
        if args.action == "plan":
            data = plan(device)
            save_new(output, data)
            for entry in data["packages"]:
                print(entry["package"] + ": " + entry["reason"])
            print("Plan guardado; todavía no se modificó HOME ni se desactivó ningún paquete.")
        elif args.action == "apply": apply(device, candidate, output)
        elif args.action == "restore": restore(device, output)
        else: verify(device, output)
        return 0
    except Exception as error:
        print("Error: " + str(error), file=sys.stderr)
        return 1
    finally:
        if device is not None:
            device.close()


if __name__ == "__main__":
    sys.exit(main())
