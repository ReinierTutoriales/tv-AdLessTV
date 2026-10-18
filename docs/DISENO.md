# Notas de diseño — AdLessTV

## Principios
- Ligero: pensado para TVs con 1 GB de RAM.
- Sin red en el lanzador (ni polling, ni servicios en segundo plano).
- Navegación D-pad determinista.
- Todo configurable en `config.xml`.
- Reversible al 100 %.

## Mejoras planificadas
- Pantalla de Ajustes dentro de la propia TV para editar el dock y apps ocultas sin reconstruir.
- Localización completa en español.
- Integración visual con bloqueadores DNS/VPN locales (acceso rápido a NF DNS VPN).
- Screensaver con reloj y color ambiente.
- Soporte explícito para Android TV boxes y sticks.

## Construcción del lanzador
Requiere JDK 17 y Android SDK (platform 34).  
El script `build-and-install.sh` (pendiente de añadir) empuja el APK por ADB y lo establece como home.
