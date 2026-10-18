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

# Desactivar uno
python3 tools/tv.py 'pm disable-user --user 0 com.ejemplo.paquete'

# Reactivar uno
python3 tools/tv.py 'pm enable com.ejemplo.paquete'
```

## Lanzador
```bash
# Establecer home
python3 tools/tv.py 'cmd package set-home-activity --user 0 com.adlesstv.launcher/.MainActivity'

# Captura de pantalla
python3 tools/tv.py 'screencap -p /sdcard/s.png'
python3 tools/tv.py --pull /sdcard/s.png s.png
```

## Inputs y teclas
```bash
python3 tools/tv.py 'input keyevent KEYCODE_HOME'
python3 tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN'
```
