# Comandos ADB útiles para AdLessTV

## Probe y conexión
```bash
TV_HOST=192.168.1.50 python3 tools/tv.py --probe
TV_HOST=192.168.1.50 python3 tools/tv.py 'getprop ro.product.model'
```

## Paquetes
```bash
# Listar todos
python3 tools/tv.py 'pm list packages'

# Solo desactivados
python3 tools/tv.py 'pm list packages -d'

# Simular una lista revisada
python3 tools/debloat.py disable mi-lista.txt

# Aplicar con respaldo nuevo (crear backups/ primero)
mkdir -p backups
python3 tools/debloat.py disable mi-lista.txt --apply --snapshot backups/operacion-nueva.json

# Restaurar estados exactos, no activar todos indiscriminadamente
python3 tools/debloat.py restore backups/operacion-nueva.json
```

## Pantalla
Compila e instala una vista previa antes de cambiar HOME. Conserva el respaldo para volver al lanzador anterior.

```bash
# Captura de pantalla
python3 tools/tv.py 'screencap -p /sdcard/s.png'
python3 tools/tv.py --pull /sdcard/s.png s.png
```

## Inputs y teclas
```bash
python3 tools/tv.py 'input keyevent KEYCODE_HOME'
python3 tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN'
```

## Activar y recuperar HOME
```bash
python3 tools/home.py --activate backups/home-nuevo.json
python3 tools/home.py --verify-reboot backups/home-nuevo.json
# Opcional, tras revisar funciones del paquete:
python3 tools/home.py --disable-stock backups/home-nuevo.json --stock-package com.fabricante.home
# Reiniciar y ejecutar --verify-reboot otra vez.
python3 tools/home.py --restore backups/home-nuevo.json
```
