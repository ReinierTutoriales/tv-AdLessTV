# Listas orientativas de paquetes

Estas listas son puntos de partida para investigar, **no listas certificadas como seguras**. Un mismo nombre puede tener dependencias diferentes según el firmware.

| Carpeta | Referencia | Estado |
|---------|------------|--------|
| `xiaomi-ejemplo/` | Paquetes habituales de Xiaomi Mi TV | Orientativo, sin validación física documentada |

Crea una lista propia con un paquete por línea y comentarios `#`. Conserva las funciones que uses. Los lanzadores HOME y teclados detectados se protegen automáticamente: no intentes eludir esa comprobación para aplicar una lista completa.

El archivo de ejemplo incluye PatchWall; si es un HOME registrado en tu TV, el plan se rechazará y tendrás que retirarlo de tu lista. No se sustituye el lanzador de fábrica.

Antes de aplicar, ejecuta una simulación. Guarda el respaldo en `backups/` y úsalo para restaurar estados originales; `restore.sh` y `restore.ps1` reciben ese JSON, no la lista de paquetes.
