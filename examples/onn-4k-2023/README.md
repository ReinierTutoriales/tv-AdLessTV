# onn 4K Streaming Box 2023: investigación inicial

Esta carpeta corresponde **solo** al dispositivo identificado como `YOC`/`onn_4k_gtv`.
"onn 4K con 2 GB" no confirma esa revisión ni su compilación. No es un perfil
certificado ni se ha probado todavía en la TV del propietario.

`candidates.txt` contiene dos posibles componentes de comentarios. Primero captura
el modelo y el firmware reales, después genera un borrador que cruza la lista
con los paquetes de sistema habilitados y las protecciones de esa TV:

```bash
python tools/tv.py --probe
python tools/inventory.py --review --output backups/onn-inventario.json
python tools/profile.py --inventory backups/onn-inventario.json \
  --catalog --output backups/onn-borrador.json
```

El borrador requiere revisar cada motivo antes de cambiar `review_required` a
`false`, simular y aplicar con un respaldo nuevo. Mantén activos los componentes
del mando, emparejamiento Bluetooth, actualización del mando, Cast, voz y avisos
de actualización mientras no exista una prueba de sus dependencias.

La provisión inicial puede reclamar HOME y haber creado la red Wi-Fi guardada.
No la desactives como parte de una lista de limpieza: comprueba primero el dueño
de la red, que puedes abrir Ajustes y que el acceso ADB se recupera. El flujo de
HOME exige revisar cualquier otro candidato que compita y guarda el HOME anterior.
La selección de HOME y la permanencia tras reiniciar necesitan prueba física.
