# Auditoría de `main` y objetivo visual

Revisión estática del código y de la interfaz. La imagen de referencia aportada en la conversación define proporciones, foco y composición; el catálogo y los rótulos reales deben salir solo de las aplicaciones instaladas. Ninguna captura de prueba sustituye la validación en una TV física.

## Interfaz

| Elemento | Estado | Criterio |
| --- | --- | --- |
| Cabecera con reloj y accesos | Implementado | Reloj local, entradas disponibles, acceso a ajustes de Wi-Fi y ajustes; sin observar el estado de red ni ejecutar servicios de fondo. |
| Zona destacada | Implementado inicialmente | El nombre cambia con el foco; Abrir e Información funcionan sobre la selección. Queda comprobar escala, recorte y navegación con mando en los dos emuladores y una TV. |
| Tarjetas redondeadas y foco | Implementado inicialmente | Borde visible y animación corta. El arte procede de los APK instalados, con caché acotada; ninguna imagen se descarga. |
| Catálogo de instaladas | Implementado | Dock horizontal de favoritos y resto de instaladas en cuadrícula desplazable, una entrada por paquete. No hay recomendaciones ni contenido remoto. |
| Entradas HDMI visibles en fila | Pendiente | El selector de entradas ya existe, pero las entradas enumeradas no aparecen como fila en HOME. Mostrar solo entradas reales detectadas; algunos fabricantes no las exponen por la API pública. |
| Estado externo, clima, liberador de RAM | Fuera del objetivo actual | No mostrar controles ficticios ni números no verificables. Integraciones externas requerirían permisos, compatibilidad y coste de recursos específicos. |

La composición mantiene fondo oscuro opaco, márgenes seguros y foco inequívoco. La zona destacada es compacta para dejar altura al catálogo en 720 × 480 y con fuentes ampliadas. No se usa desenfoque, vídeo de fondo, polling ni SDK de telemetría. Esta decisión favorece la TV de poca memoria; la mejora de fluidez debe medirse con `gfxinfo` y `meminfo` antes de afirmarse.

## Correcciones verificadas

- La sintaxis Bash de los dos scripts se comprueba por separado en CI; `bash -n archivo1 archivo2` no valida ambos.
- Python mínimo 3.11; 3.10 terminó soporte el 1 de octubre de 2026.
- `com.android.tv.settings` queda protegido en la lista básica de debloat. Algunos fabricantes usan otros paquetes: inventariar antes de aplicar.
- El debloat comprueba la configuración Wi-Fi antes de desactivar un paquete conocido de provisión. Si aparece como creador de la red guardada o no se puede leer la información, cancela sin registrar nombres de red en el respaldo. Se vuelve a consultar justo antes de modificarlo; los formatos de `dumpsys wifi` varían según el fabricante y no justifican una promesa universal.
- Las carátulas opacas se renderizan en RGB_565 (73 728 bytes por tarjeta de 256 × 144) y el resultado se incorpora a la caché únicamente si la petición aún pertenece a la generación actual. Las peticiones anteriores se cancelan al cambiar el catálogo. La opción local «Reducir movimiento» quita la escala animada del foco sin ocultar su borde.
- El acceso directo a Wi-Fi ya no consulta la red ni necesita `ACCESS_NETWORK_STATE`. Los APK debug y release solo pueden declarar el permiso interno `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` que protege receptores dinámicos; no concede acceso a la red.
- El ZIP del wrapper Gradle 9.8.1 se valida con su SHA-256 oficial; el JAR presente coincide con la suma publicada por Gradle. `gradlew` ya tiene modo Git `100755`.

## Pendientes con evidencia insuficiente

1. Compilar y ejecutar los tests de interfaz y HOME tras esta modificación; comprobar 960 × 540 y 720 × 480, fuente 1,3, foco, recorte, retorno y arranque. Una prueba de Python no compila Java ni recursos.
2. Repetir en una TV concreta la activación HOME, dos reinicios, HDMI, mando y restauración. El firmware puede imponer su lanzador y la prueba de emulador no lo predice.
3. Medir inicio en frío, memoria máxima y frames perdidos antes y después de cambiar tamaños de arte o caché. Los 4 MiB / 2 MiB actuales son límites, no una mejora de rendimiento demostrada.
4. Investigar la disponibilidad de `platforms;android-37.x` en el SDK usado por CI y después probar API 37. El fallo previo de `platforms;android-37` no demuestra cuál es el identificador correcto ni justifica cambiar `targetSdk` sin ensayo.
5. Decidir la distribución firmada. `assembleRelease` produce un APK sin firmar; el script instala el debug. Crear un flujo de firma privado y actualización sin comprometer la clave antes de usarlo como HOME permanente.
6. Fijar las acciones de CI por SHA completo cuando se hayan comprobado las revisiones concretas. La etiqueta mayor puede moverse. Revisar además el indicador de HOME de `dumpsys activity`, que no es API pública.

La ausencia de permisos de red, identificación y localización en el APK, junto con la revisión del código, respalda que el lanzador no incorpora telemetría propia. Esto no verifica el tráfico de otras apps o del firmware: reducir la telemetría del dispositivo requiere el inventario y la medición de red por firmware descritos en `DISENO.md`.
