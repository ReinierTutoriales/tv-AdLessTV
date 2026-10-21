# Revisión operativa — 0.3.0-preview.2

Revisión del 21 de octubre de 2026, sobre la rama de pruebas del onn. La
comparación se realiza por mecanismos del código, no por popularidad ni por
capturas promocionales. No certifica el firmware del dispositivo del propietario.

## Qué se corrigió

| Área | Hallazgo | Cambio |
| --- | --- | --- |
| Tarjetas | La revisión anterior dibujaba un icono de 38 dp en una tarjeta genérica. No utilizaba el banner para TV de la app. | Se carga primero el banner local de actividad/aplicación. Se conserva su proporción; los banners cuadrados usan el icono como alternativa. Tarjeta 16:9, nombre separado y borde de foco por encima del arte. |
| Composición | El dock ocupaba todo el ancho aunque tuviera pocas apps; tipografía pesada y repetición del icono en la cabecera. | Banda de ancho calculado, centrada; hasta cuatro tarjetas visibles y desplazamiento para favoritos adicionales. Reloj y título ligeros, fondo negro con acento nativo; acciones con texto e icono. Se retiran los botones redundantes de la cabecera: OK abre la tarjeta y OK prolongado ofrece información/organización. |
| Pulsación HOME | `singleTask` reutilizaba la actividad sin cerrar la biblioteca. | `onNewIntent` devuelve la portada y la última selección del dock. Atrás conserva también esa selección. |
| Carga de arte | Dos vistas de la misma app podían crear dos decodificaciones. | Una petición compartida por componente/generación; cancelar una vista no cancela las restantes. Dos workers, cancelación y caché LRU de 2/4 MiB. |
| Texto ampliado | Reducir movimiento no se respetaba al volver a enlazar una tarjeta con foco. | El enlace aplica escala 1 cuando la opción está activa. La proporción del arte es independiente del tamaño del texto. |
| Entradas | Se ignoraban los nombres personalizados de HDMI. | Se usa el nombre personalizado del sistema y, si está vacío, el del proveedor. |
| Restauración HOME | Faltaba comprobar el usuario visible al restaurar. | Se exige usuario 0 antes de modificar HOME o el paquete anterior. |
| Instaladores | Bash no normalizaba CRLF. El éxito de instalación se buscaba como una subcadena y el respaldo existente se detectaba después de instalar. | Normalización CRLF, línea `Success` completa y comprobación previa del archivo de respaldo en Bash/PowerShell. |
| CI | Acciones antiguas y referencias a etiquetas mutables. Los scripts de instalación quedaban fuera del análisis sintáctico de herramientas. | Acciones estables verificadas y fijadas por SHA, análisis de ambos directorios, límites de tamaño y SHA-256 del APK junto al commit. |

## Recursos y versiones

Los banners finales son opacos, 256 × 144 en RGB_565: **73 728 bytes por bitmap**.
Eso es mayor que el icono cuadrado de la revisión previa y permite representar el
arte real de TV; no se presenta como una reducción respecto a esos iconos.
El límite de la caché sigue siendo 2 MiB en dispositivos low-RAM y 4 MiB en los
demás. El límite no incluye memoria de proceso, recursos originales cargados por
PackageManager, vistas que conservan imágenes ni memoria del renderizador.

No se añaden red, permisos de ubicación, servicios de accesibilidad, fondos de
vídeo ni temporizadores de estadísticas. Los módulos, fondos externos y paneles
de red son funciones adicionales que no se implementan en esta revisión. No hay
una medición que demuestre paridad total o superioridad de rendimiento.

| Dependencia | Versión verificada | Decisión |
| --- | --- | --- |
| AGP | 9.4.1 | Conservar; última estable en los metadatos de Google consultados. |
| Gradle | 9.8.2 | Actualizar desde 9.8.1, con SHA-256 oficial del ZIP. |
| RecyclerView | 1.4.0 | Conservar; última estable publicada. |
| AndroidX Test runner/core | 1.7.0 | Conservar; versiones estables. |
| AndroidX Test JUnit | 1.3.0 | Conservar; versión estable. |
| adb-shell | 0.4.4 | Conservar; versión publicada en PyPI. |
| SDK compilación/objetivo | 36 | Conservar hasta disponer de plataforma 37 en CI y validar sus cambios de comportamiento. |

No se usan versiones dinámicas ni se fusionan actualizaciones automáticamente.
Cada actualización debe compilar debug/release, pasar lint, pruebas y revisión
visual. La actualización de herramientas de compilación no acelera por sí sola
la navegación del launcher.

Fuentes primarias: [AGP](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
[metadatos AGP](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml),
[Gradle](https://docs.gradle.org/9.8.2/release-notes.html),
[RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview),
[AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test),
[adb-shell](https://pypi.org/project/adb-shell/).

## Instalación, HOME y limpieza

Se conserva la separación entre vista previa, activación HOME y debloat. Instalar
un APK no ejecuta limpieza. La activación abre primero la actividad, crea el
diario antes de modificar preferencias y verifica el HOME efectivo. Desactivar el
HOME anterior requiere paquete exacto, comprobar otros candidatos y proteger el
propietario de Wi-Fi. Restaurar recupera el estado original, sin desinstalar
paquetes del sistema.

Las listas por modelo son entradas de investigación: una lista de otro firmware
no es una prueba de seguridad. Se cruza inventario real, protecciones, modelo y
fingerprint antes de generar un borrador revisable. Voz, Cast, mando, actualizaciones
y entradas no se desactivan por copiar una lista ajena.

## Evidencia y límites

La CI conserva capturas normales y compactas con texto 1,3, recorrido real de
D-pad, vuelta mediante HOME, pruebas de imagen compartida/cancelación, arranque
tras reinicio y restauración en emulador. También conserva memoria y frames del
emulador: son evidencia de ese entorno, no del onn.

Para aceptar el dispositivo físico se deben ejecutar las pruebas de
[PRUEBA_ONN.md](PRUEBA_ONN.md): instalación, navegación y regreso desde apps,
reinicios y suspensión, memoria/frames con la misma secuencia, Wi-Fi, mando y
restauración. La foto del APK anterior no valida esta nueva revisión.

## Cierre del flujo de instalación HOME

La revisión de persistencia detectó una diferencia adicional: la ruta antigua
solo admitía un HOME anterior y bloqueaba cualquier segundo competidor. El motor
`tools/home_setup.py` permite planificar juntos los launchers Google TV conocidos
y el asistente que puede recuperarlos; conserva estado original, identidad,
fingerprint y diario por paquete. Revalida antes de aplicar y antes de cada
modificación; restaura tras fallos o permite reanudar la recuperación después de
una desconexión. Un propietario Wi-Fi dependiente o un HOME desconocido bloquean
el reemplazo antes de modificar la selección.

`launcher/install-apk.ps1` usa el APK ya compilado y Platform-Tools. El paquete CI
incluye el APK, el instalador y las herramientas Python; Platform-Tools se aporta
por separado. El transporte ADB nativo no exige pip. `-MakeHome` realiza la
configuración completa; `-HomeOnly` configura una app ya instalada y `-PlanOnly`
muestra el plan.
El respaldo y los scripts ejecutables de verificar/restaurar se preparan antes de
cambiar HOME. Véase [INSTALAR_APK.md](INSTALAR_APK.md).

También se corrigió el inventario para admitir `package:android`, el paquete del
framework que Android devuelve realmente junto a los nombres con puntos. El
respaldo múltiple tiene versión 3 y se maneja con `home_setup.py`; los respaldos
anteriores conservan su herramienta original.
