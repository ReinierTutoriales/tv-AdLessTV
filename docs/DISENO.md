# Diseño y estado de AdLessTV

## Implementado

Cliente ADB TCP en Python, lotes con comprobación del resultado, push/pull, simulación de debloat, protección de HOME/teclados/paquetes básicos y restauración con registro previo de cada intento. Bash y PowerShell usan el mismo motor para evitar divergencias.

El respaldo conserva los estados Android 0 (default), 1 (enabled), 2 (disabled), 3 (disabled-user) y 4 (disabled-until-used). Solo se desactivan estados 0 y 1. Antes del primer cambio se valida la lista completa y se guarda un archivo nuevo con la identidad de la TV. Antes de enviar cada modificación se escribe `pending`; después de comprobarla se escribe `applied`. Tanto `pending` como `applied` pueden restaurarse. Los registros posteriores se reemplazan atómicamente. Un fallo detiene la operación y conserva el respaldo para recuperación manual.

La restauración verifica la identidad y recupera únicamente los paquetes intentados. No supone que el lanzador stock tenga un nombre determinado y no modifica animaciones, HOME ni ajustes ajenos a la operación. El respaldo no incluye APKs, datos de usuario ni firmware. No ejecutar sesiones simultáneas; ante cambios posteriores hechos por otras herramientas, revisar el respaldo antes de restaurarlo.

## Lanzador pendiente de implementar

Todavía no existen proyecto Gradle, fuentes Android, APK ni script de construcción. El XML de ejemplo documenta intenciones; no configura la TV por sí solo.

Conceptos seleccionados para el futuro lanzador, con implementación propia:

1. Dock de favoritos y cuadrícula de aplicaciones, sin contenido patrocinado.
2. Restaurar el foco por identidad de aplicación al volver desde otra app, incluso si cambia la lista.
3. Recorridos D-pad explícitos entre botones, dock y cuadrícula; foco visible sin depender del color.
4. Accesos a entradas, conectividad y ajustes, con alternativa si una acción no existe en ese firmware.
5. Favoritos y aplicaciones ocultas editables desde la TV, sin reconstruir el APK.
6. Trabajo de carga fuera del hilo de interfaz, vistas recicladas y caché acotada de iconos.
7. Sin red, reproducción automática, blur ni servicios permanentes. Reloj activo solo mientras se ve la pantalla.
8. Salvapantallas opcional con desplazamiento del reloj, después de estabilizar la navegación.

La elección de biblioteca y SDK se hará al implementar y medir el lanzador; no existe todavía evidencia de RAM, fluidez ni compatibilidad por modelo. Seguir las guías oficiales de [Android TV](https://developer.android.com/training/tv/get-started/create) y [navegación](https://developer.android.com/training/tv/get-started/navigation).

## Validación

Las herramientas tienen regresiones en Linux y Windows. Pendiente: conexión, lectura de estados, aplicación y restauración sobre firmware real. Para el futuro lanzador: pruebas de navegación en emulador, instalación sin sustituir HOME, mediciones de arranque/memoria y prueba con mando en TV antes de proponer cualquier cambio de inicio.
