#!/usr/bin/env python3
"""Envía o retira un tema (config.json y wallpaper.jpg) sin recompilar el lanzador."""

import argparse
import json
from pathlib import Path
import shlex
import sys

from tv import RemoteError, connect, shell

PACKAGE = "com.adlesstv.launcher"
URI = "content://" + PACKAGE + ".theme/"
EXTERNAL = "/sdcard/Android/data/" + PACKAGE + "/files/"
STAGE = "/data/local/tmp/adlesstv-theme-"
FILES = ("config.json", "wallpaper.jpg")
MAX_CONFIG = 64 * 1024
MAX_WALLPAPER = 10 * 1024 * 1024
RELOAD = f"am start -n {PACKAGE}/.MainActivity --ez com.adlesstv.launcher.RELOAD_THEME true"


def check_theme(folder):
    folder = Path(folder)
    config = folder / "config.json"
    if not config.is_file():
        raise ValueError(f"No hay config.json en {folder}.")
    if config.stat().st_size > MAX_CONFIG:
        raise ValueError("config.json supera 64 KB; el lanzador lo ignoraría.")
    data = json.loads(config.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("config.json debe ser un objeto JSON.")
    wallpaper = folder / "wallpaper.jpg"
    if wallpaper.exists() and not 0 < wallpaper.stat().st_size <= MAX_WALLPAPER:
        raise ValueError("wallpaper.jpg debe ocupar entre 1 byte y 10 MB.")
    return config, wallpaper if wallpaper.is_file() else None


def installed(dev):
    output = shell(dev, f"pm path {PACKAGE} || true")
    if not any(line.startswith("package:") for line in output.splitlines()):
        raise ValueError("AdLessTV no está instalado en esta TV.")


def delete(dev, name):
    """Remove both copies so a stale file in the other location can never win."""
    shell(dev, f"rm -f {EXTERNAL}{name} 2>/dev/null; content delete --uri {URI}{name} 2>/dev/null; true")


def put(dev, local, name):
    staged = STAGE + name
    dev.push(str(local), staged, transport_timeout_s=120.0, read_timeout_s=120.0)
    try:
        # Prefer the launcher's provider (works where Android/data is locked to the shell).
        try:
            shell(dev, f"content write --uri {URI}{name} < {shlex.quote(staged)}")
            # `content` may print an error and still exit 0: confirm by reading the bytes back.
            size = shell(dev, f"content read --uri {URI}{name} 2>/dev/null | wc -c").strip()
            if size != str(Path(local).stat().st_size):
                raise RemoteError("La escritura por el proveedor no se confirmó.")
            shell(dev, f"rm -f {EXTERNAL}{name} 2>/dev/null; true")
            return "provider"
        except RemoteError:
            shell(dev, f"mkdir -p {EXTERNAL} && cp {shlex.quote(staged)} {EXTERNAL}{name}")
            shell(dev, f"content delete --uri {URI}{name} 2>/dev/null; true")
            return "Android/data"
    finally:
        shell(dev, f"rm -f {shlex.quote(staged)}")


def push(dev, folder):
    config, wallpaper = check_theme(folder)
    installed(dev)
    where = put(dev, config, "config.json")
    if wallpaper:
        put(dev, wallpaper, "wallpaper.jpg")
    else:
        delete(dev, "wallpaper.jpg")
    shell(dev, RELOAD)
    print(f"Tema aplicado ({where}). Las elecciones de columnas, movimiento y memoria vuelven a seguir el tema.")


def reset(dev):
    installed(dev)
    for name in FILES:
        delete(dev, name)
    shell(dev, RELOAD)
    print("Tema retirado: el lanzador vuelve a su configuración integrada.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("folder", nargs="?", help="Carpeta con config.json y wallpaper.jpg opcional")
    target.add_argument("--reset", action="store_true")
    args = parser.parse_args(argv)
    dev = None
    try:
        if args.folder:
            check_theme(args.folder)
        dev = connect(args.host, args.port)
        if args.reset:
            reset(dev)
        else:
            push(dev, args.folder)
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if dev is not None:
            dev.close()


if __name__ == "__main__":
    sys.exit(main())
