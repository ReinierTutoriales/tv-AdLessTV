# AdLessTV

Herramientas para revisar y reducir aplicaciones preinstaladas en Android TV / Google TV mediante ADB por red. Sin root ni desinstalación: el debloat utiliza `pm disable-user --user 0`.

**Estado del proyecto:** las herramientas ADB están implementadas. El lanzador propio todavía no existe: `launcher/config.xml.example` es una propuesta de configuración, no una aplicación compilable ni un APK. No se sustituye ni se desactiva automáticamente el inicio de fábrica.

## Mejoras disponibles

- Conexión ADB desde Python, sin instalar el ejecutable `adb`.
- Comandos y lotes con comprobación de salida: se detienen ante el primer fallo.
- Envío y recepción de archivos mediante `--push` y `--pull`.
- Debloat en modo simulación por defecto; ningún paquete cambia sin `--apply`.
- Respaldo del estado individual de los paquetes antes de modificarlos.
- Restauración de los estados originales, incluidos `default` y `enabled`; los paquetes previamente desactivados se conservan.
- Protección de los lanzadores HOME detectados, teclados e infraestructura básica.
- Scripts para Bash y Windows PowerShell, pruebas automáticas en Linux y Windows.

## Preparación

Requiere Python 3.10 o posterior. En la TV, activa las opciones de desarrollador y la depuración ADB. El modo y puerto dependen del fabricante: algunas TVs exponen TCP 5555; otras requieren emparejamiento de depuración inalámbrica. Este cliente soporta ADB TCP con autorización RSA, **no el emparejamiento inalámbrico TLS**. Acepta en la TV la clave de tu ordenador.

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

## Revisar, aplicar y restaurar

Crea una lista con un paquete por línea; admite comentarios `#`. Las listas de `examples/` son orientativas, no una certificación de seguridad para tu firmware. Para descubrir paquetes:

```powershell
.\.venv\Scripts\python.exe tools/tv.py 'pm list packages'
```

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

Las pruebas cubren claves reales, salida de comandos, simulación, estados originales, paquetes protegidos y recuperación tras fallos con una TV simulada. No sustituyen la validación en un televisor físico. La CI ejecuta Python 3.10 y 3.13 en Linux y Windows.

- `tools/`: cliente ADB y operaciones de debloat/restauración.
- `tests/`: regresiones sin dispositivo.
- `examples/`: listas orientativas por fabricante.
- `launcher/config.xml.example`: propuesta para el futuro lanzador.
- `docs/DISENO.md`: decisiones y requisitos pendientes.
- `PROMPT.md`: guía para trabajar con un agente sobre una TV.

## Licencia

MIT. Consulta `LICENSE`.
