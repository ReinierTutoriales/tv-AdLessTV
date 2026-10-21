# Auditoría de AdLessTV

La revisión se centra en errores y recursos, conservando la interfaz, los temas, las entradas y las funciones existentes.

## Configuración vigente

- Lanzador 0.3.2 (versionCode 5). Android TV API 23+, `compileSdk` 37 y `targetSdk` 36.
- AGP 9.4.1, Gradle 9.8.2 con SHA-256 de distribución y RecyclerView 1.4.0.
- AndroidX Test runner/core 1.7.0 y ext:junit 1.3.0. Dependencias estables y acciones de CI verificadas el 21 de octubre de 2026; sin migraciones a versiones preliminares.
- Sin permiso INTERNET. La conexión y el nombre Wi-Fi usan los permisos declarados en la lista cerrada de la CI; la ubicación se solicita a demanda. El servicio de botones del mando es opcional.
- Arte local de 256 × 144 en RGB_565, caché de 4 MiB / 2 MiB en equipos low-RAM y uno o dos workers según el dispositivo.
- Los temas aceptan un JSON de hasta 64 KiB y fondo de hasta 10 MiB / 4K, con RGB_565 por defecto.

## Validación y límites

Las herramientas se comprueban con `python -m unittest discover -s tests -v`. La compilación comprueba debug/release, lint, pruebas JVM y el APK instrumentado. La CI ejecuta navegación, tamaños 960 × 540 y 720 × 480 con texto ampliado, reinicios y recuperación HOME sobre Android TV API 34 y 36 con 2 GB de RAM. API 37 es una comprobación experimental.

Los emuladores no certifican el firmware físico. Quedan por medir en el televisor concreto el arranque, la memoria máxima y los frames perdidos, además de comprobar mando, HDMI, Wi-Fi, suspensión, reinicios y restauración. Los límites de caché no demuestran por sí solos una mejora de fluidez.

`assembleRelease` genera un APK sin firmar y el instalador actual usa el APK debug. Un cambio de firma no provoca una desinstalación automática. La distribución con una clave de producción persistente requiere configuración aparte.

## Mantenimiento 0.3.1

- El respaldo del servicio de botones valida componentes, indicador y estado antes de escribir ajustes. La activación comprueba ambos ajustes, intenta recuperar un fallo parcial y sincroniza el respaldo a disco antes del primer cambio. Repetir una restauración terminada conserva las decisiones posteriores.
- La reparación del debloat revalida usuario, firmware, funciones protegidas y propietario Wi-Fi antes de cada paquete. El historial solo registra las desactivaciones confirmadas.
- La recuperación HOME mantiene el estado `restoring` hasta completar paquete y rol; puede reanudarse tras perder ADB, incluido el HOME de emergencia del sistema. Rechaza otra sesión de usuario y respaldos sin el paquete necesario.
- Las actualizaciones de apps conservan el foco de la barra superior. Inicio/Atrás conservan el acceso al catálogo cuando no hay favoritos. No se modifica la composición visual.
- Se liberan bitmaps enlazados cuando HOME está oculto y existe presión de memoria; se cancelan entregas obsoletas y los workers de arte terminan tras inactividad. Las notificaciones de red de una suscripción anterior no actualizan la interfaz después de pausarla.
- El instalador acepta únicamente una confirmación de instalación completa y rechaza errores de actividad incluso con mayúsculas mezcladas o espacios. No sobrescribe un respaldo HOME existente.

## Mantenimiento 0.3.2

- El instalador acepta la salida de `pm install` de Android 6/7, que imprime `\tpkg: <ruta>` antes de `Success` (comprobado en `cmds/pm/.../Pm.java` de Android 6). La última línea útil debe ser `Success` y ninguna puede empezar por `Failure`.
- El intent HOME solo devuelve el foco al dock si Inicio ya estaba visible; al volver de una app se conserva la tarjeta que la abrió.
- El resaltado se vuelve a medir al terminar la subida del catálogo y al regresar a Inicio.
- Un tema en `Android/data` se aplica al desbloquear el usuario, no solo al reiniciar el lanzador.
- `app/build.gradle` usa asignaciones (`propiedad = valor`): la sintaxis anterior desaparece en Gradle 10. El único aviso restante (`Configuration.setVisible`) procede del Android Gradle Plugin. Los dos usos de API obsoleta (`onBackPressed` para Android ≤ 12 y la lectura sin tipo de un metadato) están documentados y suprimidos explícitamente; el compilador no emite avisos de obsolescencia.
- Dependencias, acciones de CI y paquetes de Python comprobados de nuevo: todos en su última versión estable.

Las pruebas automatizadas cubren regresiones de herramientas y recursos. La validación física del mando, HOME, suspensión, HDMI y recuperación sigue dependiendo del firmware del televisor.
