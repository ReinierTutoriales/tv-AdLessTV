# Prompt para AdLessTV

Pega este texto en Claude Code, Cursor o cualquier agente con acceso a shell y red local.

---

Quiero limpiar mi Android TV / Google TV de anuncios y telemetría y sustituir el lanzador de fábrica por uno limpio y ligero.

Modelo de mi TV: [ESCRIBE AQUÍ EL MODELO].

Trabaja sobre el dispositivo real por ADB (Wi-Fi, puerto 5555). Verifica cada paso con capturas de pantalla. Pregúntame antes de desactivar algo que pueda afectar funciones que uso.

### Conexión
- Encuentra la TV en la red (o pide la IP).
- Conecta con las herramientas de este repositorio (`tools/tv.py`).
- Haz un backup completo de paquetes, propiedades y ajustes antes de cambiar nada.

### Debloat
- Lista todos los paquetes instalados.
- Clasifica y propón desactivar: telemetría del fabricante, servicios de anuncios, filas de contenido patrocinado, apps preinstaladas que no uso y servicios en segundo plano innecesarios.
- Usa únicamente `pm disable-user --user 0`. Nunca desinstales paquetes del sistema.
- Pregúntame qué apps de streaming, voz, Chromecast o actualizaciones quiero conservar.
- Genera un `debloat.txt` comentado y un script de restore.

### Lanzador
- Construye o adapta el lanzador de `launcher/` (Java, AndroidX RecyclerView, ligero, sin red ni servicios en segundo plano).
- Instálalo por ADB, establécelo como home y desactiva el lanzador de fábrica.
- Diseño: fondo negro puro, dock inferior con mis 4 apps favoritas, grid de aplicaciones, reloj y fecha, botones de Inputs / Wi-Fi / Ajustes.
- Navegación D-pad determinista.
- Edita `launcher/app/src/main/res/values/config.xml` según mis preferencias.
- Incluye soporte de localización en español.

### Verificación
Después de cada cambio importante, inyecta teclas y saca capturas para confirmar que todo funciona.

Al final entrega:
- Lista de paquetes desactivados.
- Script de restore.
- Instrucciones para volver a activar apps concretas.
- Confirmación de que el lanzador es el home por defecto.

Todo debe ser reversible.
