#!/usr/bin/env bash
# Uso: TV_HOST=<ip> restore.sh <respaldo.json>
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export MSYS_NO_PATHCONV=1
exec "${PYTHON:-python3}" "$ROOT/debloat.py" restore "$@"
