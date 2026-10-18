---
name: adlesstv
description: "Use when working on AdLessTV: debloating Android TV/Google TV via ADB, building or configuring the clean launcher, managing reversible package disables, or using the project tools and prompt."
type: workflow
lifecycle: active
---

# AdLessTV — Lanzador limpio y debloat para Android TV

Usa este skill cuando el usuario trabaje en el repositorio AdLessTV, quiera limpiar una Android TV / Google TV de anuncios y telemetría, o necesite construir/configurar el lanzador ligero.

Compatible con Claude Code, OpenAI Codex, Cursor y otros agentes del estándar Agent Skills.

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
   - Actualmente solo existe `launcher/config.xml.example`; no hay aplicación Android ni APK.
   - Si se solicita implementarlo, sigue `docs/DISENO.md`: Java + AndroidX RecyclerView, sin red ni servicios permanentes.
   - No cambies HOME ni desactives el lanzador de fábrica con estas herramientas.

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

La configuración propuesta en `launcher/config.xml.example` no se aplica hasta implementar el lanzador:
- `dock_apps`: las 4 apps del dock inferior (orden importa).
- `hidden_apps`: apps que no deben aparecer en el grid.
- `columns`: número de columnas.
- `preferred_locale`: `es` por defecto.

## Integración con NF DNS VPN

El proyecto está pensado para convivir con bloqueadores locales de DNS/VPN (como nfdnsvpn). El debloat elimina servicios de telemetría del fabricante; el VPN local limpia el resto a nivel de red. No mezcles ambos en el mismo proceso de instalación sin probar.

## Verificación

Después de cambios importantes:
- Inyecta teclas D-pad.
- Saca capturas (`screencap`).
- Confirma que Home sigue abriendo el lanzador existente.
- Para cambios de código: ejecuta `python -m unittest discover -s tests -v`; no presentes pruebas simuladas como validación en TV.
- Confirma que Inputs y apps principales siguen funcionando.

## Referencias

- Ver `PROMPT.md` para el flujo completo con agentes.
- Ver `docs/DISENO.md` para principios de diseño del lanzador.
- Ver `examples/` para listas de debloat por modelo.
