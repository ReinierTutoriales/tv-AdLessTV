# Instalar y configurar AdLessTV desde el APK

El APK solo instala la interfaz. Para que el botón Inicio y el arranque abran
AdLessTV, hay que configurar HOME y, en algunos Google TV, desactivar los
componentes conocidos que lo recuperan. El instalador incluido realiza esa
operación por ADB, con plan, respaldo, comprobaciones y restauración.

## Windows: paquete completo

Descarga el artefacto **AdLessTV-debug-apk** de la rama de prueba y extrae el ZIP.
La carpeta contiene `app-debug.apk`, `install-apk.ps1`, `tools/` y evidencia de CI.
Necesitas Python 3.11 o posterior y Platform-Tools de Google. No requiere Android
Studio, Java, compilar el proyecto ni instalar paquetes Python cuando usas adb.exe.

Coloca los archivos de Platform-Tools en esa misma carpeta (con sus DLL), o pasa
la ruta de `adb.exe` con `-Adb`. Autoriza la conexión en la TV.

En PowerShell, dentro de la carpeta extraída, ejecuta **una línea**:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-apk.ps1 -TvHost 192.168.1.175 -MakeHome
```

`-ExecutionPolicy Bypass` solo se aplica a este proceso de PowerShell. No cambia
la política permanente del equipo. Sustituye la IP cuando corresponda.

Para instalar y abrir únicamente la vista previa, omite `-MakeHome`. Para instalar
el APK y guardar el plan HOME sin aplicarlo, utiliza `-PlanOnly`.

El modo completo instala y abre la app, identifica el HOME efectivo y los
competidores, comprueba usuario/firmware/funciones protegidas/Wi-Fi, guarda los
estados originales y desactiva exclusivamente esos competidores conocidos. Se
vuelve a seleccionar AdLessTV después de cada cambio de candidatos. No toca
voz, Cast, actualizaciones, tienda, teclado ni paquetes desconocidos.

Un fallo de instalación o firma detiene el flujo antes de HOME. No se desinstala
la app anterior para forzar una firma distinta. Las claves debug de ejecuciones
CI diferentes pueden ser distintas: conserva el error si Android rechaza la
actualización. Para uso permanente y futuras actualizaciones con la misma firma,
usar un release firmado con una clave local conservada sigue siendo la ruta
recomendada; puede pasarse ese APK al mismo instalador con `-Apk`.

## Respaldo, reinicio y recuperación

En `backups/` se crean un plan, un diario HOME y dos scripts: `Verificar-HOME-*.ps1`
y `Restaurar-HOME-*.ps1`. Estos scripts se generan antes de comenzar el reemplazo.
Conserva la carpeta; no publiques los JSON porque identifican el dispositivo.

Reinicia el televisor y ejecuta el script de verificación **antes de abrir otra
app**. Debe comprobar otro identificador de arranque, AdLessTV seleccionado y
visible, y todos los competidores aún desactivados. El instalador no reinicia
la TV automáticamente. La TV física sigue requiriendo esta prueba.

Ante un fallo con conexión disponible, el motor intenta restaurar los estados
originales y el HOME anterior. Si se corta ADB, el diario conserva la operación
pendiente: cuando vuelva la conexión, ejecuta `Restaurar-HOME-*.ps1`. No habilita
indiscriminadamente paquetes que ya estaban desactivados antes de la operación.

## Si aparece un bloqueo de Wi-Fi

En ciertos onn/Google TV, el asistente `setupwraith` creó la red guardada. Android
puede borrar esa red al desactivar su propietario. El flujo completo se detiene
antes de cambiar HOME si detecta esa dependencia o no puede leer los propietarios.

Comprueba primero que conoces la contraseña y puedes recuperar la conexión.
Desde los ajustes del televisor, olvidar y volver a conectar la red puede asignar
su creación al sistema. Vuelve a generar el plan; la herramienta debe comprobarlo
de nuevo. No hay una opción para ignorar esa protección y continuar a ciegas.

## CLI y limpieza de otras aplicaciones

Con el repositorio o las herramientas del paquete:

```powershell
py -3 tools\home_setup.py --adb .\adb.exe --host 192.168.1.175 plan --output backups\plan-nuevo.json
py -3 tools\home_setup.py --adb .\adb.exe --host 192.168.1.175 apply --plan backups\plan-nuevo.json --snapshot backups\home-nuevo.json
py -3 tools\home_setup.py --adb .\adb.exe --host 192.168.1.175 verify backups\home-nuevo.json
py -3 tools\home_setup.py --adb .\adb.exe --host 192.168.1.175 restore backups\home-nuevo.json
```

Este respaldo versión 3 se verifica y restaura con `home_setup.py`, no con el
comando antiguo de `home.py`. Las versiones anteriores conservan su herramienta
y formato. Los dos flujos no deben ejecutarse simultáneamente.

La limpieza de otras aplicaciones conserva su propio perfil revisado de modelo y
firmware, simulación y respaldo mediante `debloat.py`. Configurar HOME no autoriza
ni necesita borrar todas las demás aplicaciones del televisor.
