# Botones del mando (opcional)

En algunos televisores el botón **Entradas**, el de una app (YouTube, Netflix) o el engranaje de Ajustes solo funcionan con el lanzador de fábrica. El servicio opcional «Botones del mando de AdLessTV» los recupera. Es un servicio de accesibilidad: solo filtra las teclas configuradas, no recibe eventos de accesibilidad, no lee el contenido de la pantalla y deja pasar las demás teclas. Viene desactivado.

```bash
python tools/keys.py --enable backups/botones-tv.json    # activa con respaldo de los ajustes previos
python tools/keys.py --restore backups/botones-tv.json   # retira solo este servicio y restaura los valores guardados
```

La restauración conserva otros servicios de accesibilidad que actives después. También puedes activarlo o quitarlo en Ajustes → Accesibilidad de la TV.

- **Entradas**: `KEYCODE_TV_INPUT` (178) siempre abre el selector de entradas de AdLessTV. `KEYCODE_TV` (170) suele abrir la TV en directo, así que solo se reasigna si la añades en `input_key_keycodes`.
- **Apps**: `app_keys` en el tema, como `"190:com.google.android.youtube.tv"` o `"BUTTON_3:com.netflix.ninja"`.
- **Ajustes**: `settings_key_keycodes`, por ejemplo `[83]`.

Para descubrir el código de una tecla: `python tools/tv.py "timeout 10 getevent -l"` y pulsa el botón en esos 10 segundos, o consulta las notas del modelo en `examples/`.
