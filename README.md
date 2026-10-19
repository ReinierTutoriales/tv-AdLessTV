# AdLessTV

Lanzador ligero para Android TV / Google TV y herramientas ADB para revisar aplicaciones preinstaladas. El debloat utiliza `pm disable-user --user 0`, sin root ni desinstalación.

**Objetivo:** una pantalla de inicio sobria, inspirada en la disposición de Apple TV, que muestre únicamente aplicaciones instaladas con una actividad iniciable; desactivar, de forma reversible y según el firmware, los paquetes prescindibles asociados a anuncios y recopilación de datos. La privacidad del sistema completo se evalúa en la TV concreta, no se deduce de instalar el lanzador.

**Estado:** hay un proyecto Android compilable y scripts de instalación. El cambio de HOME requiere una operación separada con respaldo. El lanzador original permanece habilitado.

## Funciones disponibles

- Inicio adaptable: una cuadrícula desplazable con favoritos al principio, tarjetas con proporción fija, reloj y accesos compactos; D-pad y clic del mando.
- Mantener OK o pulsar Menú sobre una app para añadir/quitar favorito, moverlo a izquierda o derecha u ocultarla; ajustes del lanzador para recuperar apps ocultas y restablecer favoritos.
- Recuperación del foco por identidad al volver de una aplicación, sin volver a consultar todos los paquetes ni reconstruir el inicio.
- Accesos a entradas, estado de conectividad y ajustes; salvapantallas de reloj opcional.
- Conexión ADB desde Python, sin instalar el ejecutable `adb`.
- Comandos y lotes con comprobación de salida: se detienen ante el primer fallo.
- Envío y recepción de archivos mediante `--push` y `--pull`.
- Debloat en modo simulación por defecto; ningún paquete cambia sin `--apply`.
- Respaldo del estado individual de los paquetes antes de modificarlos.
- Restauración de los estados originales, incluidos `default` y `enabled`; los paquetes previamente desactivados se conservan.
- Protección de los lanzadores HOME detectados, teclados e infraestructura básica.
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

`TV_PORT` cambia el puerto (5555 por defecto). También puedes usar `--host` y `--port` antes de la operación. Las claves se guardan en `tools/adbkey/` y se reutilizan; no las publiques. Desactiva la depuración cuando termines.

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

Confirma con el mando que abren tus apps, que vuelves al mismo favorito, que los botones responden y que las entradas funcionan en tu firmware. La pantalla de entradas depende de los servicios de TV que exponga el fabricante; si no hay una acción compatible, usa los ajustes de la TV.

Para establecer AdLessTV como HOME, guarda primero el HOME anterior en un archivo **nuevo**:

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
# Tras aplicar el plan de paquetes:
.venv/bin/python tools/inventory.py --compare backups/antes.json --output backups/despues.json
```

En Windows, utiliza `.\.venv\Scripts\python.exe` y `New-Item -ItemType Directory -Force backups`. El informe separa paquetes recién desactivados, reactivados, instalados y retirados; se detiene si la identidad o la compilación de firmware cambian. Los JSON contienen el identificador de la TV y el listado de paquetes: guárdalos localmente, no los publiques. **Este inventario no mide tráfico de red ni demuestra eliminación de telemetría.**

Crea una lista con un paquete por línea; admite comentarios `#`. Las listas de `examples/` son orientativas, no una certificación de seguridad para tu firmware. Para descubrir paquetes:

```powershell
.\.venv\Scripts\python.exe tools/tv.py 'pm list packages'
```

Para un flujo repetible, copia `examples/profile.template.json`, indica el resultado exacto de `getprop ro.product.model` y `getprop ro.build.fingerprint`, y documenta el motivo de cada paquete. El perfil se rechaza si cambia el modelo o la compilación. El ejemplo contiene valores ficticios y nunca debe aplicarse tal cual:

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

Cada paquete se verifica después del cambio. Si falla la conexión, se detiene el proceso y el respaldo conserva qué paquetes llegaron a intentarse. Vuelve a conectar y restaura con ese archivo. No se ejecuta una restauración automática sobre una conexión que podría haberse perdido.

El respaldo solo restaura el estado habilitado/deshabilitado de los paquetes tocados, para el usuario 0 y la misma TV. **No es una copia completa del sistema ni de datos de aplicaciones.** No cambia el HOME, las animaciones, DNS, VPN, Chromecast ni ajustes de red. Evita operaciones de debloat simultáneas y restaura antes de introducir otros cambios manuales en esos paquetes: la restauración vuelve al estado guardado.

Si no se pueden leer el estado de un paquete, los lanzadores, los teclados o la identidad de la TV, se cancela antes del primer cambio. La protección no identifica todas las dependencias de cada fabricante; selecciona solo paquetes cuyo propósito hayas comprobado.

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
