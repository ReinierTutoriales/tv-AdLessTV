#!/usr/bin/env bash
# Desactiva los paquetes listados en un archivo debloat.txt
# Uso: TV_HOST=192.168.1.50 ./tools/disable.sh examples/mi-modelo/debloat.txt

set -euo pipefail
cd "$(dirname "$0")/.."

LIST="${1:?Uso: disable.sh <archivo-debloat.txt>}"
PY="${PYTHON:-python3}"

{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk '{print "pm disable-user --user 0 " $1}'
} | TV_HOST="$TV_HOST" "$PY" tools/tv.py --batch

echo "Listo. Revisa con: pm list packages -d"
