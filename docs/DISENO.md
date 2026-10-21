# Diseño y estado de AdLessTV

La revisión de defectos, el objetivo visual de la interfaz y las verificaciones pendientes se mantienen en [AUDITORIA.md](AUDITORIA.md).

## Implementado

Cliente ADB TCP en Python, lotes con comprobación del resultado, push/pull, simulación de debloat, protección de HOME/teclados/paquetes básicos y restauración con registro previo de cada intento. Bash y PowerShell usan el mismo motor para evitar divergencias.

`tools/inventory.py` captura los paquetes instalados y desactivados del usuario 0 y puede comparar dos capturas de la misma identidad y compilación. Solo consulta ADB; no altera paquetes ni mide tráfico. Conserva el JSON localmente porque contiene identificadores de dispositivo.

El respaldo conserva los estados Android 0 (default), 1 (enabled), 2 (disabled), 3 (disabled-user) y 4 (disabled-until-used). Solo se desactivan estados 0 y 1. Antes del primer cambio se valida la lista completa y se guarda un archivo nuevo con la identidad de la TV. Antes de enviar cada modificación se escribe `pending`; después de comprobarla se escribe `applied`. Tanto `pending` como `applied` pueden restaurarse. Los registros posteriores se reemplazan atómicamente. Un fallo detiene la operación y conserva el respaldo para recuperación manual.

La restauración verifica la identidad y recupera únicamente los paquetes intentados. No supone que el lanzador stock tenga un nombre determinado y no modifica animaciones, HOME ni ajustes ajenos a la operación. El respaldo no incluye APKs, datos de usuario ni firmware. No ejecutar sesiones simultáneas; ante cambios posteriores hechos por otras herramientas, revisar el respaldo antes de restaurarlo.

## Lanzador implementado

Proyecto Java para Android TV (API 23+) con compilación y objetivo Android 16 (API 36), AGP 9.4.1 y AndroidX RecyclerView. Android 17 (API 37) ya se publicó, pero el `sdkmanager` usado por la CI no ofrece `platforms;android-37` en su canal actual (fallo reproducido en la ejecución 38). Antes de elevar el objetivo API hay que disponer del SDK y probar los cambios de comportamiento de Android 17, incluidos red local y redimensionamiento en pantallas grandes. La CI comprueba Android 15 y Android TV 14; la persistencia en firmware de TV reciente todavía requiere validación física. `AppRepository` descubre actividades instaladas y conserva favoritos ordenados y paquetes ocultos mediante preferencias locales. Las apps se consultan en un ejecutor al entrar por primera vez o al recibir cambios de paquetes; volver de una app conserva las vistas y el foco. Se muestra una entrada por paquete, priorizando actividades TV. `ArtworkLoader` carga iconos en dos workers y produce iconos transparentes de 96 × 96; su caché de bitmaps tiene un límite de 4 MiB (2 MiB en dispositivos low-RAM), sin almacenamiento en disco ni red. Cancela peticiones de vistas recicladas e invalida la caché al cambiar paquetes. No solicita permisos de red, identificación ni localización, ni incorpora SDK de analítica. El acceso directo a Wi-Fi abre los ajustes sin observar la conexión.

`MainActivity` separa una portada y una biblioteca. La portada usa un reloj con ancho de contenido, título de la app seleccionada de gran tamaño, dos acciones y una fila de favoritos con tarjetas amplias. No hay contenedores opacos a todo lo ancho detrás del reloj, la cabecera o el dock. Abajo abre la biblioteca y le da el espacio del catálogo; Arriba desde la primera fila o Atrás vuelve a la portada. Ambos RecyclerView reciclan sus vistas. El fondo combina un color opaco con un gradiente radial nativo calculado a partir del arte local: no usa una imagen de pantalla completa, desenfoque ni vídeo. El icono tenue de la cabecera comparte el mismo bitmap en caché que la tarjeta y queda fuera del árbol de accesibilidad. La cabecera se reduce en la biblioteca y en pantallas compactas para conservar espacio de navegación. El reloj sigue el formato horario del sistema. Las tarjetas muestran el icono pequeño en la esquina superior, el nombre en la inferior y un gradiente nativo de su color; mantienen foco con borde claro y animación breve opcional. Los favoritos se pueden fijar, ordenar y ocultar con OK prolongado; ocultar solo cambia la presentación. Los márgenes de 48 dp laterales y 27 dp verticales conservan el área segura. El ajuste visual se contrasta con capturas de portada y biblioteca en 960 × 540 y 720 × 480 con fuente 1,3, además de navegación real de D-pad.

Las entradas se enumeran mediante `TvInputManager`; Android o el fabricante pueden no proporcionar una actividad que abra una entrada. Se notifica el fallo y se ofrecen los ajustes. `ClockDream` es un salvapantallas opcional con reloj que cambia de posición mientras está activo.

El script de instalación compila, instala y abre una vista previa; `--make-home`/`-HomeSnapshot` permite seleccionar HOME con respaldo en la misma operación. `tools/home.py` activa HOME por separado, guarda el componente anterior y el identificador de arranque, y lo restaura solo en la misma TV. Una verificación posterior exige otro arranque y confirma que HOME persistió y está visible sin abrirlo desde el verificador. La desactivación opcional puede corregir una selección que el fabricante revirtió: exige el paquete anterior exacto, un preflight de apertura y un diario previo; la restauración reactiva el paquete antes de cambiar HOME. Antes de confirmar activación, reemplazo o restauración se espera la escritura diferida de PackageManager (AOSP la agrupa con 10 segundos de demora), se esperan sus handlers cuando el comando existe y se sincroniza el almacenamiento. Esta espera acotada ocurre en la herramienta de configuración, no en el lanzador. Esto no garantiza que un firmware OEM no revierta la selección en un futuro reinicio. El debloat usa su propio respaldo; los dos archivos no deben intercambiarse. El preflight rechaza respuestas de apertura con `Error:` aunque incluyan `Status: ok`; al desactivar el HOME anterior, se vuelve a seleccionar AdLessTV antes de verificar la resolución efectiva.

`tools/debloat.py disable --profile archivo.json` admite perfiles de modelo y fingerprint exactos con motivo por paquete. Sigue simulando salvo `--apply --snapshot`. No hay perfiles certificados sin prueba física; `examples/profile.template.json` es solo un esquema ilustrativo.

`tools/profile.py` prepara offline un borrador para el modelo y fingerprint capturados por `tools/inventory.py --review`. Solo sugiere paquetes reconocidos de informes o analítica cuando están instalados, activos y no protegidos; sus motivos describen qué comprobar, no certifican ausencia de dependencias. `review_required: true` impide usar el borrador directamente con `debloat.py`. Tras revisión local explícita puede marcarse `false`, simularse y aplicarse con respaldo. No se infiere que el modelo permita reemplazar HOME.
El catálogo inicial de informes y analítica incluye paquetes adicionales de varios fabricantes. Una lista orientativa también puede pasarse a `tools/profile.py --list`, que cruza sus nombres con el inventario real, separa HOME y componentes delicados, y registra exclusiones sin importar nada de manera automática. Los candidatos no reconocidos requieren identificar su función antes de aplicarlos.

Antes de desactivar un paquete de provisión Wi-Fi conocido, tanto debloat como sustitución opcional de HOME examinan localmente `dumpsys wifi` para detectar si es creador de alguna red guardada. La sustitución comprueba antes del primer cambio y otra vez antes de desactivar. Si hay dependencia o no se puede leer el informe, cancela; no almacena SSIDs ni el informe. Este chequeo acotado no certifica la seguridad de desactivar otros paquetes que un fabricante haya acoplado a la red. Si ADB depende de Wi-Fi, conviene comprobar reconexión y recuperación antes de aplicar el perfil.

El menú del lanzador permite reducir el movimiento de las tarjetas conservando el borde de foco. La elección se guarda en preferencias locales. Ofrece tres colores base de fondo, combinados con un acento radial obtenido de los iconos ya cargados. Los gradientes de las tarjetas reutilizan sus drawables al cambiar de app. Los iconos de 96 × 96 ARGB_8888 conservan transparencia, ocupan 36 KiB cada uno y se comparten con el icono decorativo de la cabecera; la caché se mantiene acotada. Se cancelan los trabajos de una generación anterior cuando cambian los paquetes. Queda pendiente medir memoria de proceso y fluidez real en hardware con poca RAM.

La UI comprueba la actividad que resuelve el intent HOME antes de decir que AdLessTV es el inicio efectivo: poseer `ROLE_HOME` puede no bastar en determinadas imágenes de TV. La activación normal por ADB restaura el rol anterior si otro HOME sigue resolviéndose; solo la operación opcional con paquete exacto puede desactivar el anterior, con respaldo y restauración. La espera de 12 segundos cubre la demora de 10 segundos observada en AOSP, pero no ofrece una garantía contractual para todos los fabricantes.

Referencias oficiales: [RoleManager / ROLE_HOME](https://developer.android.com/reference/android/app/role/RoleManager), [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot), [AOSP PackageManagerService / WRITE_SETTINGS_DELAY](https://android.googlesource.com/platform/frameworks/base/+/android17-release/services/core/java/com/android/server/pm/PackageManagerService.java), [AOSP CTS / prioridad de HOME en TV](https://android.googlesource.com/platform/cts/+/c0e975a9eff/hostsidetests/devicepolicy/src/com/android/cts/devicepolicy/OrgOwnedProfileOwnerTest.java).

Versiones y comportamiento: [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk), [cambios al apuntar a API 37](https://developer.android.com/about/versions/17/behavior-changes-17), [compatibilidad AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes).

## Límites conocidos

- Sin pruebas físicas aún en firmware específico; validar mando, conexiones HDMI/TV y regreso desde apps.
- La lista de entradas depende de la API y aplicaciones del fabricante.
- La enumeración y carga de iconos depende de los paquetes instalados; falta medir arranque y memoria en TVs con 1 GB.
- La selección del salvapantallas puede no estar expuesta en algunas TVs.
- El banner tiene el nombre de la aplicación en un recurso xhdpi de 320 × 180 píxeles; antes de publicar en una tienda, verificar sus requisitos de ficha y activos.

## Objetivo de privacidad y criterios de aceptación

La ausencia de permisos de red, identificación y localización, y de SDKs de analítica es una propiedad verificable del APK del lanzador; no equivale a eliminar la telemetría del sistema, de las apps conservadas o del fabricante. La desactivación de paquetes reduce componentes seleccionados en el usuario 0, sin interceptar tráfico, desinstalarlos ni demostrar por sí misma una reducción de conexiones.

Para cada firmware que se quiera declarar compatible:

1. Registrar modelo, versión de Android y compilación, paquetes habilitados y HOME actual. Identificar por función y dependencias cada candidato; conservar servicios necesarios para reproducción, entradas, teclado, actualizaciones y recuperación según las preferencias del dueño.
2. Medir en la misma red el tráfico saliente de esa TV antes del cambio, durante un intervalo y tareas reproducibles; registrar duración, fecha, dominios/IP y método de captura. Evitar asociar cada conexión a un paquete sin evidencia adicional.
3. Ejecutar `tools/inventory.py --review` para separar los paquetes protegidos de los que requieren investigación; ese informe no recomienda desactivar todos los demás. Simular un perfil con motivos por paquete, guardar un respaldo nuevo, aplicar los cambios y verificar uno a uno sus estados. Tras reiniciar, ejecutar `tools/debloat.py verify RESPALDO.json`. Repetir la misma medición y los mismos usos con las apps elegidas; documentar conexiones que persisten y posibles interrupciones funcionales.
4. Verificar el botón HOME, mando, apps, HDMI, Wi-Fi, suspensión, reinicio y restauración con el respaldo en el mismo dispositivo. Repetir la medición si cambia el firmware, las apps o la configuración de red.

Publicar el perfil de ese modelo solo con resultados observados y límites de la captura. El criterio «telemetría eliminada» requiere evidencia de ausencia en el alcance medido; no extrapolarlo a todos los modelos ni prometer ausencia permanente. El criterio de rendimiento requiere medir arranque en frío, memoria y respuesta del mando en TV real antes de proclamar mejoras cuantificables.

## Validación

Las herramientas tienen regresiones en Linux y Windows. Pendiente: conexión, lectura de estados, aplicación y restauración sobre firmware real. Para el lanzador: la CI compila debug y release, ejecuta Android Lint y comprueba que ninguno de los APK solicite permisos fuera del interno para proteger receptores dinámicos; la tarea de emulador prueba tamaños 960 × 540 y 720 × 480 con fuente ampliada, nombres largos, cambios de sección y visibilidad del foco. Guarda capturas de apps instaladas y de un catálogo artificial de prueba. Faltan pruebas en TV física, mediciones comparables de arranque/memoria y validación de entradas por fabricante.

El Activity HOME es `singleTask` y Direct-Boot aware. Desde API 24, sus preferencias locales se guardan en almacenamiento protegido del dispositivo y migran las elecciones anteriores después de desbloquear. No usa un servicio permanente ni un receptor de arranque que intente eludir las restricciones de actividades en segundo plano. La CI de emulador selecciona HOME, reinicia, comprueba que aparece solo, desactiva el HOME anterior, repite el reinicio y restaura. Un firmware de TV que imponga otra actividad al arrancar requiere validación específica.


## Revisión 0.3.0-preview.2

La [revisión operativa](REVISION_OPERATIVA.md) sustituye las descripciones visuales
de preview.1: las tarjetas usan banners locales 16:9 de 256 × 144 RGB_565, el dock
se centra en una banda ajustada y se elimina el icono decorativo de la cabecera.
El nombre se sitúa fuera del arte. La carga se comparte por componente y
generación. HOME cierra la biblioteca y conserva la selección del dock.
