#!/usr/bin/env bash
# Solo simula por defecto. Para aplicar: <lista> --apply --snapshot <respaldo-nuevo.json>
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export MSYS_NO_PATHCONV=1
exec "${PYTHON:-python3}" "$ROOT/debloat.py" disable "$@"
