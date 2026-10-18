#!/usr/bin/env bash
# Restaura los paquetes desactivados y vuelve al lanzador de fábrica
# Uso: TV_HOST=192.168.1.50 ./tools/restore.sh examples/mi-modelo/debloat.txt

set -euo pipefail
cd "$(dirname "$0")/.."

LIST="${1:?Uso: restore.sh <archivo-debloat.txt> [lanzador-stock]}"
STOCK="${2:-com.google.android.tvlauncher}"
PY="${PYTHON:-python3}"

{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk '{print "pm enable " $1}'
  echo "pm enable $STOCK"
  echo "cmd package set-home-activity --user 0 $STOCK"
  echo "settings put global window_animation_scale 1.0"
  echo "settings put global transition_animation_scale 1.0"
  echo "settings put global animator_duration_scale 1.0"
} | TV_HOST="$TV_HOST" "$PY" tools/tv.py --batch

echo "Restauración completada."
