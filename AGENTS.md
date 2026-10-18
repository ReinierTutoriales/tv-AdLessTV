# AdLessTV — Instrucciones para agentes

Proyecto para limpiar Android TV / Google TV de anuncios y telemetría y sustituir el lanzador de fábrica por uno ligero y limpio.

Compatible con Claude Code, OpenAI Codex, Cursor y otros agentes que lean el estándar Agent Skills.

## Reglas críticas

- **Nunca** uses `pm uninstall` en paquetes del sistema. Solo `pm disable-user --user 0`.
- Todo cambio debe ser reversible. Usa o genera `tools/restore.sh`.
- Para operaciones en la TV, usa ADB real (TV_HOST + tools/tv.py). Para código sin dispositivo, ejecuta las pruebas y declara pendiente la validación física.
- El lanzador debe ser ligero: Java + AndroidX RecyclerView, sin red, sin servicios en segundo plano.
- Pregunta al usuario antes de desactivar apps o servicios que pueda querer conservar (voz, Chromecast, actualizaciones, Live TV).
- Idioma preferido: español.

## Estructura clave

- `tools/tv.py` — cliente ADB puro Python.
- `tools/disable.sh` / `tools/restore.sh` — scripts reversibles.
- `examples/` — listas de debloat por modelo.
- `launcher/` — solo propuesta `config.xml.example`; todavía no hay lanzador ni APK.
- `PROMPT.md` — prompt listo para agentes.
- `.agents/skills/adlesstv/` — skill canónico (Codex, Cursor, OpenCode, etc.).
- `.claude/skills/adlesstv/` — skill para Claude Code.

## Flujo recomendado

1. Probe con `tools/tv.py --probe`.
2. Backup.
3. Generar una lista revisada y simular con `tools/debloat.py disable mi-lista.txt`.
4. Tras autorización de la lista, aplicar con `--apply --snapshot backups/operacion-nueva.json`.
5. Restaurar con `tools/debloat.py restore backups/operacion-nueva.json`. No cambiar HOME.
6. Verificar con capturas y teclas.
7. Documentar el modelo en `examples/`.

## Skills disponibles

Usa el skill `adlesstv` cuando trabajes en debloat, configuración del lanzador o herramientas ADB de este repo.

## Validación del código

Ejecuta `python -m unittest discover -s tests -v`. Las pruebas simuladas no sustituyen las pruebas físicas. `restore.sh` y `restore.ps1` reciben el respaldo JSON, no la lista de paquetes. Conserva el estado previo; no restaures animaciones ni ajustes ajenos a la operación.
