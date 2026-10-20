#!/usr/bin/env python3
"""ADB por TCP: shell comprobado, lotes y transferencia de archivos."""

import argparse
import os
from pathlib import Path
import re
import shlex
import sys
import uuid


class RemoteError(RuntimeError):
    pass


def get_signer(keydir=None):
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner

    if keydir is not None:
        directory = Path(keydir)
    else:
        platform_key = Path.home() / ".android" / "adbkey"
        directory = platform_key.parent if platform_key.is_file() else Path(__file__).resolve().parent / "adbkey"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    key = directory / "adbkey"
    public = directory / "adbkey.pub"
    if not key.exists():
        if public.exists():
            raise RuntimeError("Falta la clave privada ADB; conserva o recupera el par de claves.")
        # keygen recibe un archivo, nunca el directorio que lo contiene.
        keygen(str(key))
    if not public.exists():
        from adb_shell.auth.keygen import write_public_keyfile
        write_public_keyfile(str(key), str(public))
    key.chmod(0o600)
    return PythonRSASigner(public.read_text(encoding="utf-8"), key.read_text(encoding="utf-8"))


def connect(host=None, port=None, timeout=60):
    from adb_shell.adb_device import AdbDeviceTcp

    host = host or os.environ.get("TV_HOST")
    if not host:
        raise ValueError("Define TV_HOST con la IP de tu TV o utiliza --host.")
    port = int(port if port is not None else os.environ.get("TV_PORT", "5555"))
    if not 1 <= port <= 65535:
        raise ValueError("TV_PORT debe estar entre 1 y 65535.")
    dev = AdbDeviceTcp(host, port, default_transport_timeout_s=10.0)
    try:
        if not dev.connect(rsa_keys=[get_signer()], auth_timeout_s=timeout):
            raise RemoteError("ADB no autorizado. Acepta la autorización en la TV.")
    except BaseException:
        dev.close()
        raise
    return dev


def shell(dev, command):
    """El protocolo shell de adb-shell no devuelve exit status: lo adjuntamos."""
    marker = "__ADLESSTV_" + uuid.uuid4().hex + "__"
    wrapped = "sh -c " + shlex.quote(command) + "; printf '\\n" + marker + "%s\\n' \"$?\""
    result = dev.shell(wrapped, read_timeout_s=120.0, timeout_s=120.0)
    match = re.search(r"\r?\n" + marker + r"(\d+)\r?\n?$", result)
    if not match:
        raise RemoteError("Respuesta ADB incompleta; no se pudo verificar el resultado.")
    output = result[:match.start()]
    if int(match[1]) != 0:
        raise RemoteError(f"Comando fallido ({match[1]}): {command}\n{output}")
    return output


def batch(dev, lines):
    for raw in lines:
        command = raw.lstrip("\ufeff").strip()
        if command and not command.startswith("#"):
            print(f"$ {command}", flush=True)
            print(shell(dev, command), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--probe", action="store_true")
    group.add_argument("--batch", action="store_true")
    group.add_argument("--push", nargs=2, metavar=("LOCAL", "REMOTO"))
    group.add_argument("--pull", nargs=2, metavar=("REMOTO", "LOCAL"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if args.command and (args.probe or args.batch or args.push or args.pull):
        parser.error("No combines un comando shell con otra operación.")
    dev = None
    try:
        dev = connect(args.host, args.port)
        if args.push:
            dev.push(*args.push, transport_timeout_s=120.0, read_timeout_s=120.0)
        elif args.pull:
            dev.pull(*args.pull, transport_timeout_s=120.0, read_timeout_s=120.0)
        elif args.batch:
            if hasattr(sys.stdin, "reconfigure"):
                sys.stdin.reconfigure(encoding="utf-8-sig")
            batch(dev, sys.stdin)
        elif args.command:
            print(shell(dev, " ".join(args.command)))
        else:
            for prop in ("ro.product.model", "ro.product.device", "ro.build.version.release",
                         "ro.build.version.sdk", "ro.build.display.id", "ro.build.fingerprint",
                         "ro.product.manufacturer"):
                print(f"{prop}: {shell(dev, 'getprop ' + prop).strip()}")
            memory = shell(dev, "cat /proc/meminfo")
            total = re.search(r"^MemTotal:\s*(\d+) kB\s*$", memory, re.MULTILINE)
            if total:
                print(f"RAM total detectada: {int(total[1]) // 1024} MiB")
            print("Almacenamiento de datos (/data):")
            print(shell(dev, "df -k /data").strip())
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if dev is not None:
            dev.close()


if __name__ == "__main__":
    sys.exit(main())
