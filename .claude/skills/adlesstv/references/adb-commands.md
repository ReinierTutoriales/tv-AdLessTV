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
El lanzador propio no está implementado. No cambies HOME.

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
