# Prueba controlada en onn 4K (2 GB RAM, 8 GB nominales)

Para probar un APK ya compilado en Windows, utiliza primero el
[instalador del paquete](INSTALAR_APK.md). Incluye una ruta HOME para varios
competidores y scripts de verificación/recuperación. El flujo siguiente de
compilación local sigue disponible para conservar una firma release propia.


Esta guía recoge observaciones del dispositivo concreto. Un emulador no prueba su firmware, el mando ni la persistencia tras apagar. Conserva los respaldos privados; pueden contener identificadores del dispositivo. No publiques la clave de firma ni los inventarios.

## 1. Preparar la identidad y el estado inicial

En la raíz del repositorio, instala `tools/requirements.txt` en un entorno Python y configura `TV_HOST` con la dirección de la TV. Activa ADB y autoriza el ordenador en la pantalla de la TV. Comprueba primero:

```bash
python tools/tv.py --probe
python tools/home.py --inspect
python tools/inventory.py --review --output backups/onn-inventario.json
```

Registra modelo, `ro.product.device`, fingerprint, versión Android, RAM y espacio libre de `/data`. La capacidad de 8 GB incluye el sistema. Si el modelo, la firma de firmware o las actividades HOME no son las esperadas, detén la sustitución y revisa los paquetes. No uses una lista de otro dispositivo por coincidencia del nombre comercial.

## 2. Crear y conservar la firma release

En el ordenador, crea una clave **una sola vez** con el JDK. `keytool` pedirá las contraseñas; no las pongas en el comando ni en Git:

```bash
keytool -genkeypair -keystore /ruta/privada/adlesstv-release.jks -alias adlesstv -keyalg RSA -keysize 3072 -validity 10000
```

Copia `launcher/keystore.properties.example` a `launcher/keystore.properties`. Completa la ruta, alias y contraseñas en ese archivo local. Protege el archivo y respalda la clave fuera del repositorio. En Windows usa una ruta absoluta en `storeFile` con barras `/` para no introducir escapes de propiedades Java. Comprueba:

```bash
bash launcher/build-and-install.sh --release --build-only
```

Debe existir `launcher/app/build/outputs/apk/release/app-release.apk`. Una compilación en CI sin el archivo local produce `app-release-unsigned.apk`; **no lo instales**. En cada actualización conserva la misma clave: Android rechaza un APK con otra firma para el mismo identificador de app.

## 3. Pasar de vista previa a HOME

Si ya instalaste un APK debug, prueba primero instalar el release **sin** cambiar HOME. Si `pm install -r` indica `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, no fuerces el reemplazo: la firma debug es distinta. Si AdLessTV ya es HOME, ejecuta `python tools/home.py --restore backups/RESPALDO_HOME.json` y verifica que abre el HOME anterior. Retira la aplicación de vista previa desde Ajustes de Android (esto borra sus preferencias locales) y vuelve a instalar el release. Conserva el respaldo anterior y usa una ruta nueva para cada activación.

```bash
bash launcher/build-and-install.sh --release
python tools/home.py --inspect
bash launcher/build-and-install.sh --release --make-home backups/onn-home-1.json
```

En PowerShell usa `-Release`, `-BuildOnly`, `-HomeSnapshot` y `-ReplaceStock`. El instalador comprueba el resultado de `pm install` y abre la actividad antes de tocar HOME. El HOME de fábrica permanece habilitado salvo que se solicite explícitamente su sustitución con el paquete exacto. Si esa sustitución se bloquea por otra actividad HOME o por la red Wi-Fi guardada, no la fuerces; conserva el respaldo y restaura si la selección quedó pendiente.

## 4. Verificar, medir y recuperar

Con el mando, abre varias apps y regresa con HOME. Comprueba foco, navegación entre dock y cuadrícula, menús con OK prolongado, Ajustes y que el botón de entradas se oculte si el stick no expone entradas. Reinicia desde el sistema sin abrir manualmente AdLessTV y comprueba:

```bash
python tools/home.py --verify-reboot backups/onn-home-1.json
python tools/tv.py 'dumpsys meminfo com.adlesstv.launcher'
python tools/tv.py 'dumpsys gfxinfo com.adlesstv.launcher framestats'
```

Repite después de apagar y encender por completo. Registra si HOME apareció solo, PSS de memoria, frames lentos y espacio libre, junto con firmware y resolución; no interpretes una sola cifra aislada como prueba de optimización. Si HOME revierte o el proceso no aparece, conserva el lanzador original y diagnostica el firmware antes de desactivarlo.

Para volver al estado anterior:

```bash
python tools/home.py --restore backups/onn-home-1.json
```

El debloat va en otra operación: revisa el borrador de `tools/profile.py --inventory backups/onn-inventario.json --catalog --output backups/onn-perfil.json`, confirma uno por uno los paquetes que deseas conservar y solo entonces simula y aplica con un respaldo **distinto**. Después verifica los estados con `tools/debloat.py verify RESPALDO_DEBLOAT.json` y prueba mando, Wi-Fi, Cast, Play Store y actualizaciones si los utilizas. No hay un perfil universal certificado para toda variante onn.
