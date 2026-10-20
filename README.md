# AdLessTV

Lanzador ligero para Android TV / Google TV y herramientas ADB para revisar aplicaciones preinstaladas. El debloat utiliza `pm disable-user --user 0`, sin root ni desinstalación.

**Objetivo:** una pantalla de inicio sobria, inspirada en la disposición de Apple TV, que muestre únicamente aplicaciones instaladas con una actividad iniciable; desactivar, de forma reversible y según el firmware, los paquetes prescindibles asociados a anuncios y recopilación de datos. La privacidad del sistema completo se evalúa en la TV concreta, no se deduce de instalar el lanzador.

**Estado:** hay un proyecto Android compilable y scripts de instalación. El cambio de HOME requiere una operación separada con respaldo. El lanzador original permanece habilitado.

## Funciones disponibles

- Inicio adaptable: una cuadrícula desplazable con favoritos al principio, tarjetas con proporción fija, reloj y accesos compactos; D-pad y clic del mando.
- Mantener OK o pulsar Menú sobre una app para añadir/quitar favorito, moverlo a izquierda o derecha u ocultarla; ajustes del lanzador para recuperar apps ocultas y restablecer favoritos.
- Tres paletas visuales opacas (Noche, Océano y Cálido) en Personalizar. Se guardan solo en la TV y cambian sin red, fondos animados ni servicios permanentes.
- Recuperación del foco por identidad al volver de una aplicación, sin volver a consultar todos los paquetes ni reconstruir el inicio.
- Accesos a entradas, ajustes de Wi-Fi y ajustes del sistema; salvapantallas de reloj opcional.
- Conexión ADB desde Python, sin instalar el ejecutable `adb`.
- Comandos y lotes con comprobación de salida: se detienen ante el primer fallo.
- Envío y recepción de archivos mediante `--push` y `--pull`.
- Debloat en modo simulación por defecto; ningún paquete cambia sin `--apply`.
- Respaldo del estado individual de los paquetes antes de modificarlos.
- Restauración de los estados originales, incluidos `default` y `enabled`; los paquetes previamente desactivados se conservan.
- Protección de los lanzadores HOME detectados, teclados, entradas de TV, servicios de accesibilidad activos, VPN permanente e infraestructura básica.
- Scripts para Bash y Windows PowerShell, pruebas automáticas en Linux y Windows.

## Preparación

Requiere Python 3.11 o posterior. En la TV, activa las opciones de desarrollador y la depuración ADB. El modo y puerto dependen del fabricante: algunas TVs exponen TCP 5555; otras requieren emparejamiento de depuración inalámbrica. Este cliente soporta ADB TCP con autorización RSA, **no el emparejamiento inalámbrico TLS**. Acepta en la TV la clave de tu ordenador.

### Windows

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
$env:TV_HOST = '192.168.1.50'
.\.venv\Scripts\python.exe tools/tv.py --probe
```

### Linux / macOS

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50
.venv/bin/python tools/tv.py --probe
```

`TV_PORT` cambia el puerto (5555 por defecto). También puedes usar `--host` y `--port` antes de la operación. Si ya existe `~/.android/adbkey`, el cliente reutiliza esa identidad ADB; si no, crea y reutiliza una clave en `tools/adbkey/`. No publiques las claves. Desactiva la depuración cuando termines.

### Stick 4K con 2 GB de RAM y 8 GB de almacenamiento

`--probe` muestra modelo, dispositivo, compilación, RAM detectada y espacio real de `/data`. La capacidad nominal de 8 GB incluye el sistema: utiliza el espacio libre detectado para decidir si cabe instalar el APK. El instalador borra su copia temporal de `/data/local/tmp` tras intentar la instalación. La interfaz carga imágenes locales opacas bajo demanda en RGB_565, con caché de 2 o 4 MiB según la clasificación de RAM baja del propio Android; cancela trabajos de carátulas obsoletos, no descarga imágenes ni mantiene tareas periódicas. Desde Personalizar puedes reducir el movimiento del foco; el borde continúa visible.

Puedes generar un borrador de perfil vinculado al modelo y firmware capturados: `python tools/inventory.py --review --output backups/inventario.json` y después `python tools/profile.py --inventory backups/inventario.json --output backups/perfil.json`. Sus pocos candidatos se comprueban manualmente; `debloat.py` rechazará el borrador hasta que hayas revisado cada entrada y cambiado `review_required` a `false`. Los detalles y límites están en [examples/README.md](examples/README.md).
Si ya tienes una lista orientativa del modelo, `tools/profile.py --list lista.txt` la cruza con ese inventario y separa nombres ausentes, desactivados, protegidos o asociados a funciones delicadas. No convierte la lista en cambios automáticos.

El stick normalmente no tiene puertos HDMI de **entrada** ni sintonizador, pero el software comprueba las entradas ofrecidas por Android antes de ocultar el acceso. Para el debloat usa un perfil del **modelo y fingerprint exactos**, y conserva mando, emparejamiento Bluetooth, Play Services/Store y las funciones que utilices. No se borran datos de apps ni cachés indiscriminadamente.

## Construcción e instalación del lanzador

Requiere **JDK 17**, Android SDK **platform 36 / build-tools 36.0.0** y acceso a los repositorios Android/Gradle. Usa Gradle 9.8.1, Android Gradle Plugin 9.4.1, Java y AndroidX RecyclerView. Compila y apunta a Android 16 (API 36), con compatibilidad declarada desde API 23. Android 17 (API 37) ya se publicó, pero el `sdkmanager` del ejecutor de CI aún no encuentra `platforms;android-37`; no se declara compatibilidad comprobada con esa imagen. La CI comprueba Android 15 y Android TV 14; falta comprobar un televisor físico con firmware más reciente. Para crear el APK sin conectar una TV:

```bash
bash launcher/build-and-install.sh --build-only
```

```powershell
.\launcher\build-and-install.ps1 -BuildOnly
```

El APK de depuración queda en `launcher/app/build/outputs/apk/debug/app-debug.apk`. Para instalarlo y **abrirlo como vista previa**, tras preparar ADB como se indica arriba:

```bash
PYTHON=.venv/bin/python bash launcher/build-and-install.sh
```

```powershell
.\launcher\build-and-install.ps1 -TvHost 192.168.1.50 -Python .\.venv\Scripts\python.exe
```

Para instalar y elegir HOME en una sola operación explícita, usa `--make-home backups/home-tv.json` en Bash o `-HomeSnapshot backups/home-tv.json` en PowerShell. El respaldo debe ser nuevo. Añade `--replace-stock <paquete-exacto>` o `-ReplaceStock <paquete-exacto>` solo tras revisar las otras funciones de ese paquete. Conserva el JSON y utiliza `tools/home.py --restore` para recuperar el inicio anterior.

Confirma con el mando que abren tus apps, que vuelves al mismo favorito, que los botones responden y que las entradas funcionan en tu firmware. La pantalla de entradas depende de los servicios de TV que exponga el fabricante; si no hay una acción compatible, usa los ajustes de la TV. En un stick sin entradas de TV detectadas se oculta ese botón y la navegación del mando pasa directamente a los otros accesos.

Para establecer AdLessTV como HOME, guarda primero el HOME anterior en un archivo **nuevo**:

Antes, comprueba qué otras actividades también reclaman HOME; una pantalla de configuración o recuperación puede tomar el control cuando desactives el lanzador de fábrica:

```bash
.venv/bin/python tools/home.py --inspect
```

El reemplazo del HOME de fábrica se detiene si aparece otra actividad además del HOME anterior y AdLessTV. Inspecciona esa actividad y sus funciones en **tu firmware**; no se desactiva automáticamente un asistente de configuración por su nombre.

La activación exige que la sesión visible de la TV sea el usuario 0; en equipos con otro usuario activo no se cambia HOME hasta adaptar y probar el flujo para ese usuario.

```bash
mkdir -p backups
.venv/bin/python tools/home.py --activate backups/home-tv.json
```

Reinicia la TV con sus controles habituales, vuelve a conectarla por ADB y comprueba si el firmware mantuvo AdLessTV como HOME:

```bash
.venv/bin/python tools/home.py --verify-reboot backups/home-tv.json
```

**Opcional**, si HOME no se puede activar porque el lanzador del sistema tiene mayor prioridad, la activación normal devuelve el rol anterior y marca el respaldo como restaurado. Tras revisar las demás funciones del paquete, usa `tools/home.py --activate backups/home-tv.json --stock-package PAQUETE_EXACTO` con un respaldo nuevo. También puedes usar `--disable-stock` con un respaldo pendiente de una operación interrumpida. El reemplazo restaura el paquete anterior si la comprobación final falla.

Para sustituir el HOME de fábrica incluso si el fabricante revirtió la selección al reiniciar: identifica los servicios del paquete anterior y desactívalo para el usuario 0 indicando su nombre exacto. El script comprueba que AdLessTV abre, vuelve a seleccionarlo como HOME, registra el intento y desactiva el paquete anterior. Rechaza nombres distintos del respaldo, otros HOME elegidos manualmente y paquetes básicos protegidos. Desactivar un paquete puede afectar funciones adicionales del fabricante; elegir HOME como predeterminado no requiere desactivarlo.

```bash
.venv/bin/python tools/home.py --disable-stock backups/home-tv.json --stock-package com.fabricante.home
```

Tras la desactivación, reinicia una vez más y ejecuta `--verify-reboot backups/home-tv.json` de nuevo. La operación falla si el lanzador no permanece como HOME o no abre; en ese caso ejecuta `--restore`.

Para restaurar el HOME anterior, incluido su estado habilitado original (incluso si la conexión se perdió durante un cambio):

```bash
.venv/bin/python tools/home.py --restore backups/home-tv.json
```

En Windows, reemplaza `.venv/bin/python` por `.\.venv\Scripts\python.exe`. `home.py` comprueba el lanzador instalado, intenta abrirlo, conserva la identidad de la TV y verifica el HOME resultante. Si no puede detectar un HOME anterior único o el fabricante bloquea la operación, configura la aplicación de inicio desde los ajustes del televisor. El HOME anterior solo se desactiva con la operación opcional explícita; ya no exige que el firmware haya conservado antes la selección. El cambio de HOME es independiente del debloat y usa un respaldo distinto. La activación y la restauración esperan 12 segundos y las colas de PackageManager disponibles antes de sincronizar; no reinicies ni cortes la alimentación mientras el script indica que está guardando. Esta espera evita perder las escrituras diferidas de Android, pero no certifica firmware OEM. No abras aplicaciones entre el reinicio y la verificación: esta exige que AdLessTV esté ya visible, sin abrirlo desde el verificador. Comprueba el identificador de arranque (o el contador de arranques cuando el kernel no lo expone); no garantiza el resultado tras todos los futuros reinicios o actualizaciones de firmware.

El salvapantallas de reloj se selecciona manualmente en los ajustes de salvapantallas del televisor; su disponibilidad también depende del fabricante. El lanzador no solicita permisos de red, identificación ni localización y no incorpora analítica ni ejecuta servicios permanentes. El botón de red solo abre los ajustes de Wi-Fi; no lee ni vigila el estado de la conexión. Favoritos y aplicaciones ocultas se guardan en preferencias locales de la TV; el manifiesto desactiva la copia de seguridad de la aplicación y define exclusiones de transferencia. La CI comprueba que los APK debug y release no declaren permisos salvo el permiso interno que protege receptores dinámicos. Esto no controla el tráfico de otras aplicaciones instaladas ni demuestra que el firmware carezca de telemetría. `launcher/app/src/main/res/values/config.xml` proporciona favoritos iniciales. Después, las decisiones hechas con el mando se guardan en la TV y prevalecen sobre ese XML.

La cuadrícula consulta actividades iniciables `LEANBACK_LAUNCHER` y `LAUNCHER`: muestra una entrada por paquete y prioriza la entrada para TV. No enumera servicios sin interfaz. Ocultar una app en esta pantalla solo modifica la vista; para desactivar un paquete se utiliza la herramienta ADB con su respaldo.

## Revisar, aplicar y restaurar

Antes de modificar paquetes, guarda un inventario de solo lectura; al terminar, compara el estado del mismo firmware:

```bash
mkdir -p backups
.venv/bin/python tools/inventory.py --output backups/antes.json
# Vista de paquetes del sistema que requieren revisión individual; no cambia la TV:
.venv/bin/python tools/inventory.py --review --output backups/revision.json
# Tras aplicar el plan de paquetes:
.venv/bin/python tools/inventory.py --compare backups/antes.json --output backups/despues.json
```

En Windows, utiliza `.\.venv\Scripts\python.exe` y `New-Item -ItemType Directory -Force backups`. El informe separa paquetes recién desactivados, reactivados, instalados y retirados; se detiene si la identidad o la compilación de firmware cambian. `--review` separa paquetes protegidos de paquetes de sistema habilitados que requieren investigación: **no los considera seguros para desactivar ni infiere telemetría por el nombre**. Los JSON contienen el identificador de la TV y el listado de paquetes: guárdalos localmente, no los publiques. **Este inventario no mide tráfico de red ni demuestra eliminación de telemetría.**

Crea una lista con un paquete por línea; admite comentarios `#`. Las listas de `examples/` son orientativas, no una certificación de seguridad para tu firmware. Para descubrir paquetes:

```powershell
.\.venv\Scripts\python.exe tools/tv.py 'pm list packages'
```

Para un flujo repetible, copia `examples/profile.template.json`, indica el resultado exacto de `getprop ro.product.model` y `getprop ro.build.fingerprint`, y documenta el motivo de cada paquete. El perfil se rechaza si cambia el modelo o la compilación. El ejemplo contiene valores ficticios y nunca debe aplicarse tal cual:

Revisa cada paquete de tu TV con `dumpsys package NOMBRE_DEL_PAQUETE`: busca sus actividades, servicios y permisos, comprueba si lo necesitan las entradas HDMI, el mando, los ajustes, las apps que utilizas o las actualizaciones y decide qué funciones conservar. Una lista de otro modelo es solo una pista de investigación. Elige paquetes de anuncios o recopilación de datos confirmados para **ese firmware**; la herramienta exige un motivo por paquete en el perfil y luego verifica estados y recuperación. El nombre del paquete por sí solo no demuestra su función ni elimina todo el tráfico del dispositivo.

```bash
.venv/bin/python tools/tv.py 'getprop ro.product.model'
.venv/bin/python tools/tv.py 'getprop ro.build.fingerprint'
.venv/bin/python tools/debloat.py disable --profile mi-perfil.json
# Tras revisar: .venv/bin/python tools/debloat.py disable --profile mi-perfil.json --apply --snapshot backups/perfil-tv.json
```

El envoltorio de Windows también acepta `-Profile mi-perfil.json` en lugar de `-List mi-lista.txt`.

Primero simula (consulta la TV, sin modificarla):

```powershell
.\.venv\Scripts\python.exe tools/debloat.py disable mi-lista.txt
```

Después de revisar el plan, aplica con un archivo de respaldo **nuevo**:

```powershell
New-Item -ItemType Directory -Force backups
.\.venv\Scripts\python.exe tools/debloat.py disable mi-lista.txt --apply --snapshot backups/tv-01.json
```

Después de reiniciar la TV, comprueba sin modificar nada si el firmware conservó los estados:

```powershell
.\.venv\Scripts\python.exe tools/debloat.py verify backups/tv-01.json
```

Para deshacer esa operación:

```powershell
.\.venv\Scripts\python.exe tools/debloat.py restore backups/tv-01.json
```

En Linux/macOS usa `.venv/bin/python` en los mismos comandos y `mkdir -p backups`. También existen los envoltorios `tools/disable.sh`, `tools/restore.sh`, `tools/disable.ps1` y `tools/restore.ps1`:

```powershell
.\tools\disable.ps1 -TvHost 192.168.1.50 -List mi-lista.txt -Python .\.venv\Scripts\python.exe
.\tools\disable.ps1 -TvHost 192.168.1.50 -List mi-lista.txt -Apply -Snapshot backups/tv-02.json -Python .\.venv\Scripts\python.exe
.\tools\restore.ps1 -TvHost 192.168.1.50 -Snapshot backups/tv-02.json -Python .\.venv\Scripts\python.exe
```

```bash
PYTHON=.venv/bin/python bash tools/disable.sh mi-lista.txt
PYTHON=.venv/bin/python bash tools/disable.sh mi-lista.txt --apply --snapshot backups/tv-02.json
PYTHON=.venv/bin/python bash tools/restore.sh backups/tv-02.json
```

Antes del primer cambio se comprueban la identidad, el firmware y **todos** los estados del lote; si alguno cambió desde la planificación, el lote no empieza. Durante la ejecución se vuelven a comprobar el usuario visible, las funciones protegidas y el estado de cada paquete. Cada desactivación se verifica después del cambio. Si falla la conexión o cambia una función a mitad del lote, se detiene y el respaldo conserva qué paquetes llegaron a intentarse. Vuelve a conectar y restaura con ese archivo. No se ejecuta una restauración automática sobre una conexión que podría haberse perdido.

El respaldo solo restaura el estado habilitado/deshabilitado de los paquetes tocados, para el usuario 0 y la misma TV. **No es una copia completa del sistema ni de datos de aplicaciones.** La verificación advierte si cambió el firmware; la restauración sigue disponible para recuperar estados incluso después de una actualización. No cambia el HOME, las animaciones, DNS, VPN, Chromecast ni ajustes de red. Evita operaciones de debloat simultáneas y restaura antes de introducir otros cambios manuales en esos paquetes: la restauración vuelve al estado guardado.

Si el usuario visible no es el 0 o no se pueden leer el estado de un paquete, los lanzadores, los teclados, los servicios de entrada, la accesibilidad activa, la VPN permanente o la identidad de la TV, se cancela antes del primer cambio. Una desactivación también se detiene si un paquete cambió desde la lectura previa. La protección no identifica todas las dependencias de cada fabricante; selecciona solo paquetes cuyo propósito hayas comprobado.

## Archivos y comandos

```powershell
.\.venv\Scripts\python.exe tools/tv.py --push app.apk /data/local/tmp/app.apk
.\.venv\Scripts\python.exe tools/tv.py --pull /sdcard/captura.png captura.png
Get-Content comandos.txt | .\.venv\Scripts\python.exe tools/tv.py --batch
```

`--push` usa el protocolo de archivos de ADB; no instala el APK ni cambia el lanzador. Una transferencia de archivo fallida puede dejar un archivo parcial; vuelve a transferirlo antes de utilizarlo. Los comandos shell manuales y `--batch` son herramientas avanzadas: las protecciones de paquetes corresponden a `debloat.py`.

## Desarrollo

```bash
python -m pip install -r tools/requirements.txt
python -m unittest discover -s tests -v
```

Las pruebas cubren claves reales, salida de comandos, simulación, estados originales, paquetes protegidos y recuperación tras fallos con una TV simulada. No sustituyen la validación en un televisor físico. La CI ejecuta Python 3.11 y 3.13 en Linux y Windows, compila los APK debug/release y prueba el recorrido de filas. La tarea de interfaz ejecuta pulsaciones reales y guarda capturas en un emulador Android a 960 × 540 dp y 720 × 480 dp con texto ampliado. El catálogo largo de esas pruebas es una fixture, no aplicaciones descargadas ni una captura de una TV física.

- `tools/`: cliente ADB, inventario de paquetes, debloat/restauración y activación/restauración de HOME.
- `tests/`: regresiones sin dispositivo.
- `examples/`: listas orientativas por fabricante.
- `launcher/`: proyecto Android, código del lanzador y scripts de compilación e instalación.
- `docs/DISENO.md`: decisiones, compatibilidad y pruebas pendientes.
- `PROMPT.md`: guía para trabajar con un agente sobre una TV.

## Licencia

MIT. Consulta `LICENSE`.
