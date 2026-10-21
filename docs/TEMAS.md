# Temas sin recompilar

Un tema es una carpeta con `config.json` y, opcionalmente, `wallpaper.jpg`. Se envía con:

```bash
python tools/theme.py themes/noche-azul          # aplica y recarga Inicio
python tools/theme.py --reset                     # vuelve a la configuración integrada
```

El lanzador lee el tema como una capa sobre `launcher/app/src/main/res/values/config.xml`. Cada clave es opcional: una clave desconocida o con tipo erróneo se ignora y un archivo que no se puede leer se ignora entero, así que un tema nunca impide abrir Inicio. Las elecciones hechas con el mando (columnas, movimiento, memoria) prevalecen hasta elegir **Personalizar → Seguir el tema**; enviar o retirar un tema también las borra.

`tools/theme.py` escribe primero a través del proveedor del lanzador (`content write`, solo acepta llamadas del shell de ADB) y comprueba los bytes; si el firmware no lo permite, copia a `Android/data`. Siempre elimina la copia del otro lugar para que un archivo antiguo no gane. La recarga se hace con un `intent`, sin forzar la parada: el servicio opcional de botones sigue activo.

| Clave | Tipo | Efecto |
|---|---|---|
| `dock_apps` | lista de paquetes | Favoritos iniciales, en orden; los ausentes se omiten |
| `fill_dock` | booleano | Completa hasta 4 favoritos con las primeras apps (`true`) |
| `hidden_apps` | lista de paquetes | Nunca aparecen como tarjeta; se pueden mostrar desde Aplicaciones ocultas |
| `app_labels` | lista `"paquete=Nombre"` | Nombre de la tarjeta en lugar del de la app |
| `live_tv_package` | texto | App de TV en directo para el sintonizador cuando el servicio de entrada no tiene pantalla propia |
| `columns` | 2 a 8 | Apps por fila; con mínimo de 88 dp por tarjeta |
| `reduced_motion` / `animations` | booleano | Movimiento reducido (`animations: false` equivale a reducido) |
| `show_stats` | booleano | Memoria libre y almacenamiento junto a la fecha (`true`) |
| `show_network_pill` | booleano | Tipo de conexión y nombre de la Wi-Fi en el botón de red (`true`) |
| `glass_highlight` | booleano | Resaltado flotante que se desliza entre tarjetas (`true`) |
| `module_cards` | booleano | Buscar tarjetas de otras apps y preguntar por ellas (`true`) |
| `greeting_enabled`, `greeting_name` | booleano, texto | Saludo según la hora y nombre opcional |
| `background_color` | `#RRGGBB` | Color de fondo en lugar de la paleta |
| `wallpaper` | booleano | Mostrar `wallpaper.jpg` (`true`) |
| `wallpaper_dim` | 0 a 90 | Oscurecimiento del fondo, en % |
| `wallpaper_true_color` | booleano | Decodificar en 32 bits (el doble de memoria; para 2 GB o más) |
| `home_idle_clock_minutes` | 0 a 1440 | Reloj a pantalla completa tras N minutos sin tocar Inicio; cualquier tecla vuelve. 0 desactiva |
| `app_keys` | lista `"tecla:paquete"` | Botones que abren una app (servicio opcional de botones) |
| `input_key_keycodes` | lista de enteros | Teclas adicionales que abren Entradas |
| `settings_key_keycodes` | lista de enteros | Teclas que abren los Ajustes |

## Fondo de pantalla

`wallpaper.jpg` se muestra a pantalla completa recortado al centro. Se decodifica en segundo plano con el muestreo mínimo que cubre la pantalla y en RGB_565 por defecto (unos 4 MB a 1080p). Se ignoran archivos de más de 10 MB o que, ya reducidos, superen 4K. Usa imágenes oscuras: el texto está diseñado para fondo negro.

Algunas carpetas de `examples/` incluyen un `config.json` de su modelo; las claves que este lanzador no implementa se ignoran.
