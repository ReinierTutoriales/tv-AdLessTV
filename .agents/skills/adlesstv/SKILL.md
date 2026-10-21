---
name: adlesstv
description: "Use when working on AdLessTV: debloating Android TV/Google TV via ADB, building or configuring the clean launcher, managing reversible package disables, or using the project tools and prompt."
---

# AdLessTV — Lanzador limpio y debloat para Android TV

Usa este skill cuando el usuario trabaje en el repositorio AdLessTV, quiera limpiar una Android TV / Google TV de anuncios y telemetría, o necesite construir/configurar el lanzador ligero.


## Principios inmutables

- **Nunca desinstales** paquetes del sistema. Solo `pm disable-user --user 0`.
- Todo debe ser **reversible**. Siempre genera o usa el restore.
- Para operaciones en la TV, trabaja por ADB TCP y verifica con capturas cuando sea posible. Para cambios de código sin dispositivo, ejecuta las pruebas y declara pendiente la validación física.
- Mantén el lanzador **ligero**: sin red, sin servicios en segundo plano, sin polling. Pensado para ~1 GB RAM.
- Pregunta antes de desactivar algo que pueda afectar inputs HDMI, voz, Chromecast o actualizaciones.

## Flujo principal

1. **Conectar**  
   Define `TV_HOST`. Usa `tools/tv.py --probe` para confirmar modelo, Android y build.

2. **Backup**  
   Antes de cualquier cambio, guarda lista de paquetes y propiedades.

3. **Debloat**  
   - Lista paquetes con `pm list packages`.
   - Clasifica telemetría, anuncios, contenido patrocinado y bloat.
   - Genera o edita un `examples/<modelo>/debloat.txt` comentado.
   - Simula primero con `tools/disable.sh mi-lista.txt`.
   - Tras autorizar la lista, aplica con `tools/disable.sh mi-lista.txt --apply --snapshot backups/operacion-nueva.json`.
   - Protege HOME, teclados e infraestructura básica. No ejecutes operaciones simultáneas.

4. **Lanzador**  
   - Compila con `bash launcher/build-and-install.sh --build-only` o el script PowerShell.
   - Instala y abre una vista previa con `launcher/build-and-install.sh` o `.ps1`; valida navegación y retorno.
   - Solo después, activa HOME con `tools/home.py --activate backups/home-nuevo.json`. Reinicia y comprueba con `--verify-reboot backups/home-nuevo.json`. Mantén el HOME anterior habilitado por defecto. Si se solicita desactivarlo, revisa el paquete y sus servicios; usa `--disable-stock` con `--stock-package` exacto, reinicia y verifica otra vez. Recupera con `--restore backups/home-nuevo.json`.

5. **Restore**  
   Usa `tools/restore.sh backups/operacion-nueva.json`. Restaura los estados guardados de los paquetes intentados, también tras una desconexión. No recibe `debloat.txt`. El respaldo no contiene datos de usuario ni firmware.

## Comandos útiles

```bash
export TV_HOST=192.168.1.50
python3 tools/tv.py --probe
python3 tools/tv.py 'pm list packages'
bash tools/disable.sh mi-lista.txt
mkdir -p backups
bash tools/disable.sh mi-lista.txt --apply --snapshot backups/operacion-nueva.json
bash tools/restore.sh backups/operacion-nueva.json
```

## Configuración del lanzador

Los favoritos iniciales están en `launcher/app/src/main/res/values/config.xml`. Después se pueden cambiar desde la TV manteniendo OK sobre una app. Recupera aplicaciones ocultas y restablece favoritos desde Ajustes del lanzador. Los textos se localizan según el idioma del sistema.

## Integración con NF DNS VPN

El proyecto puede convivir con bloqueadores locales de DNS/VPN. El debloat desactiva paquetes seleccionados, pero la reducción de telemetría requiere medición de red antes y después. No mezcles ambos cambios en la misma prueba.

## Verificación

Después de cambios importantes:
- Inyecta teclas D-pad.
- Saca capturas (`screencap`).
- Antes de activar, confirma que HOME sigue abriendo el lanzador existente; después confirma que abre AdLessTV y que `tools/home.py --restore` permite regresar.
- Sin ordenador, Personalizar → «Volver al lanzador original» abre otro HOME habilitado (antes de Android 10 retira también la preferencia de AdLessTV). No sustituye al respaldo: si se desactivó el lanzador de fábrica, solo `tools/home.py --restore` lo recupera.
- La fila «Entradas» solo muestra fuentes que publica `TvInputManager`; verifica nombres y estado en la TV física.
- Temas: `python tools/theme.py <carpeta>` / `--reset` (claves en `docs/TEMAS.md`). Botones del mando: `tools/keys.py --enable/--restore <respaldo>`, solo si el usuario lo pide. Tras una actualización de firmware: `tools/debloat.py verify --fix <respaldo>`.
- Para cambios de código: ejecuta `python -m unittest discover -s tests -v`; no presentes pruebas simuladas como validación en TV.
- Confirma que Inputs y apps principales siguen funcionando.

## Referencias

- Ver `PROMPT.md` para el flujo completo con agentes.
- Ver `docs/DISENO.md` para principios de diseño del lanzador.
- Ver `examples/` para listas de debloat por modelo.

