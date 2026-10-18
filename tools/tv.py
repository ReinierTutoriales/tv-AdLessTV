#!/usr/bin/env python3
"""Cliente ADB mínimo por TCP para Android TV (sin necesidad del binario adb).

Uso:
  TV_HOST=192.168.1.50 python3 tools/tv.py --probe
  TV_HOST=192.168.1.50 python3 tools/tv.py pm list packages
  TV_HOST=192.168.1.50 python3 tools/tv.py --batch < comandos.txt
"""

import os
import sys
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_HOST")
PORT = int(os.environ.get("TV_PORT", "5555"))

if not HOST:
    sys.exit("Define TV_HOST con la IP de tu TV. Ejemplo: TV_HOST=192.168.1.50 python3 tools/tv.py --probe")

KEYDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adbkey")

def connect():
    if not os.path.exists(KEYDIR):
        os.makedirs(KEYDIR, exist_ok=True)
        keygen(KEYDIR)
    with open(os.path.join(KEYDIR, "adbkey"), "r") as f:
        priv = f.read()
    with open(os.path.join(KEYDIR, "adbkey.pub"), "r") as f:
        pub = f.read()
    signer = PythonRSASigner(pub, priv)
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=9.0)
    dev.connect(rsa_keys=[signer], auth_timeout_s=0.1)
    return dev

def main():
    args = sys.argv[1:]
    dev = connect()
    if not args or args == ["--probe"]:
        props = [
            "ro.product.model",
            "ro.product.device",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.build.display.id",
            "ro.product.manufacturer",
        ]
        for p in props:
            print(f"{p}: {dev.shell(f'getprop {p}').strip()}")
        return
    if args[0] == "--batch":
        for line in sys.stdin:
            line = line.strip()
            if line and not line.startswith("#"):
                print(f"$ {line}")
                print(dev.shell(line))
        return
    print(dev.shell(" ".join(args)))

if __name__ == "__main__":
    main()
