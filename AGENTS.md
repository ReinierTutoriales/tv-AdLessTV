# AdLessTV — Instrucciones para agentes

Proyecto para limpiar Android TV / Google TV de anuncios y telemetría y sustituir el lanzador de fábrica por uno ligero y limpio.

Compatible con Claude Code, OpenAI Codex, Cursor y otros agentes que lean el estándar Agent Skills.

## Reglas críticas

- **Nunca** uses `pm uninstall` en paquetes del sistema. Solo `pm disable-user --user 0`.
- Todo cambio debe ser reversible. Usa o genera `tools/restore.sh`.
- Trabaja sobre la TV real por ADB (TV_HOST + tools/tv.py).
- El lanzador debe ser ligero: Java + AndroidX RecyclerView, sin red, sin servicios en segundo plano.
- Pregunta al usuario antes de desactivar apps o servicios que pueda querer conservar (voz, Chromecast, actualizaciones, Live TV).
- Idioma preferido: español.

## Estructura clave

- `tools/tv.py` — cliente ADB puro Python.
- `tools/disable.sh` / `tools/restore.sh` — scripts reversibles.
- `examples/` — listas de debloat por modelo.
- `launcher/` — código del lanzador (config en `config.xml`).
- `PROMPT.md` — prompt listo para agentes.
- `.agents/skills/adlesstv/` — skill canónico (Codex, Cursor, OpenCode, etc.).
- `.claude/skills/adlesstv/` — skill para Claude Code.

## Flujo recomendado

1. Probe con `tools/tv.py --probe`.
2. Backup.
3. Generar/aplicar debloat.txt.
4. Configurar y instalar lanzador.
5. Establecer como home.
6. Verificar con capturas y teclas.
7. Documentar el modelo en `examples/`.

## Skills disponibles

Usa el skill `adlesstv` cuando trabajes en debloat, configuración del lanzador o herramientas ADB de este repo.
