# Prompt para trabajar con AdLessTV

Pega este texto en un agente con acceso a shell y a tu red local.

---

Quiero revisar mi Android TV / Google TV y reducir paquetes innecesarios de forma recuperable.
Modelo y firmware: [ESCRIBE AQUÍ]. IP y puerto ADB: [ESCRIBE AQUÍ].

Lee primero el código y el README. No anuncies funciones no implementadas: `launcher/` contiene solo un XML de ejemplo, no un lanzador compilable.

## Conexión e inventario

- Usa un entorno virtual e instala `tools/requirements.txt`.
- Conecta con `tools/tv.py --probe`; espera a que autorice la clave en la TV.
- ADB TCP con RSA está soportado; el emparejamiento TLS no está implementado.
- Guarda en `backups/` el inventario y las propiedades necesarias; esto no es una copia del firmware ni de los datos.
- Pregúntame qué apps, voz, Chromecast y actualizaciones quiero conservar.

## Debloat

- Identifica cada paquete y sus dependencias en mi firmware; no apliques listas de ejemplo sin revisarlas.
- Genera una lista comentada. No incluyas HOME, teclados, actualizaciones que quiero conservar ni servicios críticos.
- Ejecuta `tools/debloat.py disable mi-lista.txt` para mostrar el plan sin cambios.
- Tras mi autorización de esa lista, aplica con `--apply --snapshot backups/operacion-nueva.json`.
- Usa un respaldo nuevo por operación y no ejecutes sesiones simultáneas.
- Ante un fallo, detente. Informa de lo aplicado y conserva el respaldo; no encadenes cambios a ciegas.
- Para recuperar: `tools/debloat.py restore backups/operacion-nueva.json`.
- No desinstales paquetes, no cambies HOME, no modifiques DNS/VPN ni ajustes de red para realizar este trabajo.

## Verificación y entrega

- Valida el funcionamiento de las apps conservadas, ajustes, teclado, mando y regreso a HOME.
- Captura la pantalla cuando sea posible; una entrada HDMI puede impedir la captura.
- Entrega la lista aplicada, la ruta del respaldo, los comandos de restauración y las pruebas reales realizadas.
- Si no tienes acceso a la TV, declara esa limitación; las pruebas simuladas no demuestran compatibilidad física.

Si después solicito construir un lanzador, impleméntalo como una tarea separada siguiendo `docs/DISENO.md`. Comprueba el APK sin sustituir el inicio de fábrica. No desactives el HOME original.
