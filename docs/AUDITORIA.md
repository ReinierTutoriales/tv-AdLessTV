# Auditoría de `main` y objetivo visual

Revisión estática del código y de la interfaz. La imagen de referencia aportada en la conversación define proporciones, foco y composición; el catálogo y los rótulos reales deben salir solo de las aplicaciones instaladas. Ninguna captura de prueba sustituye la validación en una TV física.

## Interfaz

| Elemento | Estado | Criterio |
| --- | --- | --- |
| Cabecera con reloj y accesos | Implementado | Reloj local, entradas disponibles, acceso a ajustes de Wi-Fi y ajustes; sin observar el estado de red ni ejecutar servicios de fondo. |
| Zona destacada | Implementado inicialmente | El nombre cambia con el foco; Abrir e Información funcionan sobre la selección. Queda comprobar escala, recorte y navegación con mando en los dos emuladores y una TV. |
| Tarjetas redondeadas y foco | Implementado inicialmente | Borde visible y animación corta. El arte procede de los APK instalados, con caché acotada; ninguna imagen se descarga. |
| Catálogo de instaladas | Implementado | Dock horizontal de favoritos y resto de instaladas en cuadrícula desplazable, una entrada por paquete. No hay recomendaciones ni contenido remoto. |
| Entradas HDMI visibles en fila | Implementado | Sección «Entradas» al inicio del catálogo con las fuentes que devuelve `TvInputManager` (paso directo y sintonizador), nombre personalizado (API 24+), entradas ocultas excluidas y estado conectada/en espera/sin señal. Se enumera en el hilo de escaneo y se actualiza con `TvInputCallback`; no hay sondeo. Algunos fabricantes no exponen sus entradas por la API pública: en ese caso la fila no aparece. |
| Memoria libre | Implementado | `ActivityManager.getMemoryInfo` al volver a Inicio, sin temporizador ni permisos; se puede ocultar. |
| Liberador de RAM, clima, estado externo | Descartado con motivo | `killBackgroundProcesses` requiere un permiso adicional y Android vuelve a crear los procesos; un botón así no libera memoria de forma medible. El clima exige red, que el lanzador no usa. |
| Salida al lanzador original | Implementado | Personalizar → «Volver al lanzador original» abre otro HOME habilitado; antes de Android 10 retira además la preferencia propia (`clearPackagePreferredActivities`, solo sobre el paquete propio). En Android 10+ HOME es un rol y el cambio definitivo se hace en Ajustes o con `tools/home.py --restore`. |

La composición mantiene fondo oscuro opaco, márgenes seguros y foco inequívoco. La zona destacada es compacta para dejar altura al catálogo en 720 × 480 y con fuentes ampliadas. No se usa desenfoque, vídeo de fondo, polling ni SDK de telemetría. Esta decisión favorece la TV de poca memoria; la mejora de fluidez debe medirse con `gfxinfo` y `meminfo` antes de afirmarse.

## Correcciones verificadas

- La sintaxis Bash de los dos scripts se comprueba por separado en CI; `bash -n archivo1 archivo2` no valida ambos.
- Python mínimo 3.11; 3.10 terminó soporte el 1 de octubre de 2026.
- `com.android.tv.settings` queda protegido en la lista básica de debloat. Algunos fabricantes usan otros paquetes: inventariar antes de aplicar.
- El debloat comprueba la configuración Wi-Fi antes de desactivar un paquete conocido de provisión. Si aparece como creador de la red guardada o no se puede leer la información, cancela sin registrar nombres de red en el respaldo. Se vuelve a consultar justo antes de modificarlo; los formatos de `dumpsys wifi` varían según el fabricante y no justifican una promesa universal.
- La sustitución opcional del HOME anterior usa la misma comprobación Wi-Fi, tanto antes de cambiar HOME como inmediatamente antes de desactivar el paquete. Si el paquete creó una red guardada o el estado no puede determinarse, no se desactiva. El flujo ordinario deja el HOME anterior habilitado.
- Las carátulas opacas se renderizan en RGB_565 (73 728 bytes por tarjeta de 256 × 144) y el resultado se incorpora a la caché únicamente si la petición aún pertenece a la generación actual. Las peticiones anteriores se cancelan al cambiar el catálogo. La opción local «Reducir movimiento» quita la escala animada del foco sin ocultar su borde.
- El acceso directo a Wi-Fi ya no consulta la red ni necesita `ACCESS_NETWORK_STATE`. Los APK debug y release solo pueden declarar el permiso interno `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` que protege receptores dinámicos; no concede acceso a la red.
- El ZIP del wrapper Gradle 9.8.1 se valida con su SHA-256 oficial; el JAR presente coincide con la suma publicada por Gradle. `gradlew` ya tiene modo Git `100755`.

## Revisión del 21 de octubre de 2026

- Regresión corregida: `test_install` fallaba porque el script ya exige un APK no vacío y el stub de Gradle no lo generaba. El stub ahora produce un APK y la prueba cubre la comprobación previa de API y la clave distinta.
- Instalación: comprobación previa de `ro.build.version.sdk` contra `minSdk` y de `android.software.leanback` antes de transferir el APK. Ante `INSTALL_FAILED_UPDATE_INCOMPATIBLE` no se desinstala (se perderían favoritos y, si es HOME, el inicio).
- Rendimiento: la cabecera reutiliza un único `GradientDrawable` y solo se repinta cuando cambia el acento; el selector de entradas ya no consulta `TvInputManager` en el hilo principal en cada `onResume`; `TRIM_MEMORY_UI_HIDDEN` reduce la caché a la mitad en vez de vaciarla; un hilo de decodificación en equipos `isLowRamDevice` o de dos núcleos; banners e iconos a xhdpi/xxhdpi en pantallas de baja densidad.
- Debloat: los paquetes ausentes se informan todos juntos antes de cancelar, sin cambiar la política de abortar el plan completo.
- Dependencias: Gradle 9.8.2 (SHA-256 oficial `21bd5da2…9607`), `compileSdk` 37 (identificador del SDK: `platforms;android-37.0`); `targetSdk` se mantiene en 36 porque es lo validado en emuladores de TV, AGP 9.4.1, RecyclerView 1.4.0 y AndroidX Test 1.7.0/1.3.0 ya eran las últimas estables. JDK 21 en CI. Acciones fijadas por SHA: checkout v7.0.1, setup-java v6.1.0, setup-python v7.0.0, setup-android v4.0.4, upload-artifact v7.0.2, android-emulator-runner v2.38.0.
- Matriz de interfaz: Android TV 14 x86 con 2 GB de RAM y Android TV 16 x86_64 con 2 GB (obligatorias, pasan completas incluidos dos reinicios con restauración del HOME). Android 17 `google_apis` es informativa: su imagen de CI pierde `/sdcard` o el servicio `package` tras arrancar, antes de ejecutar las pruebas.

## Pendientes con evidencia insuficiente

1. Las pruebas de compilación, lint e interfaz pasaron en los emuladores de TV API 34 y Android API 35 para el cambio visual anterior; repetir la matriz al publicar cada nueva modificación. Las capturas cubren 960 × 540 y 720 × 480, pero no prueban fluidez ni todos los mandos.
2. Repetir en una TV concreta la activación HOME, dos reinicios, HDMI, mando y restauración. El firmware puede imponer su lanzador y la prueba de emulador no lo predice.
3. Medir inicio en frío, memoria máxima y frames perdidos antes y después de cambiar tamaños de arte o caché. Los 4 MiB / 2 MiB actuales son límites, no una mejora de rendimiento demostrada.
4. API 37: resuelto el identificador (`platforms;android-37.0`); compilación y lint pasan. Subir `targetSdk` a 37 cuando la imagen de Android 17 sea estable en CI o exista una TV con Android 17 donde probarlo.
5. Decidir la distribución firmada. `assembleRelease` produce un APK sin firmar; el script instala el debug. Crear un flujo de firma privado y actualización sin comprometer la clave antes de usarlo como HOME permanente.
6. Acciones de CI fijadas por SHA completo (hecho). Queda revisar el indicador de HOME de `dumpsys activity`, que no es API pública.
7. Comprobar en una TV con entradas HDMI reales los nombres, el orden y el estado de la fila «Entradas»: los emuladores no publican entradas de paso.

La ausencia de permisos de red, identificación y localización en el APK, junto con la revisión del código, respalda que el lanzador no incorpora telemetría propia. Esto no verifica el tráfico de otras apps o del firmware: reducir la telemetría del dispositivo requiere el inventario y la medición de red por firmware descritos en `DISENO.md`.
