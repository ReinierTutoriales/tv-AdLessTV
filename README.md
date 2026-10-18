# AdLessTV

**Tu Android TV o Google TV, sin anuncios, sin telemetría y con un lanzador limpio.**

Elimina el bloatware, los servicios de publicidad y la telemetría del fabricante mediante ADB por Wi-Fi. Sustituye la pantalla de inicio llena de anuncios por un lanzador ligero, oscuro y rápido. Todo es reversible: no se desinstala nada, no se necesita root y no se flashea firmware.

Diseñado para funcionar junto a bloqueadores locales de DNS/VPN (como NF DNS VPN) para una limpieza completa de anuncios y rastreadores.

## Qué obtienes

- Lanzador propio: fondo negro, dock de aplicaciones favoritas, grid de apps, reloj, fecha y botones de Inputs / Wi-Fi / Ajustes.
- Debloat seguro: solo `pm disable-user`. Un script restaura todo en un comando.
- Compatible con cualquier Android TV o Google TV (Xiaomi, TCL, Hisense, Sony, OnePlus, Shield, boxes, etc.).
- Herramientas en Python que no requieren el binario `adb` instalado.
- Prompt listo para usar con agentes de IA (Claude Code, Cursor, etc.) que trabajan directamente sobre la TV real.
- Configuración centralizada y soporte para español.

## Cómo empezar

1. En la TV: Ajustes → Preferencias del dispositivo → Acerca de → pulsa OK 7 veces sobre “Compilación”.
2. Activa **Depuración USB** (y depuración de red / ADB si aparece).
3. Mantén la TV encendida y en la misma red Wi-Fi que tu ordenador.
4. Clona este repositorio y usa el prompt de `PROMPT.md` con tu agente de IA favorito, o ejecuta los scripts manualmente.

```bash
export TV_HOST=192.168.1.50
python3 tools/tv.py --probe
```

## Seguridad

- Nada se borra del sistema. Los paquetes se desactivan solo para el usuario.
- Existe `tools/restore.sh` para volver al estado anterior.
- Desactiva la depuración USB cuando termines.

## Estructura

- `tools/` — cliente ADB en Python, scripts de disable/restore y push de APK.
- `launcher/` — código del lanzador (Java, AndroidX).
- `examples/` — listas de debloat por modelo.
- `PROMPT.md` — instrucciones para agentes de IA.
- `docs/` — documentación adicional.

## Licencia

MIT. Úsalo, modifícalo y mejóralo libremente.
