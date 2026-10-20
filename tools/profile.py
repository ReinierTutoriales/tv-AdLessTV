#!/usr/bin/env python3
"""Prepara, sin ADB, un borrador de perfil para un inventario revisado de una TV."""

import argparse
import json
from pathlib import Path
import sys

from inventory import validate

# Candidatos limitados a informes/consentimiento opcionales. No incluir HOME,
# actualizaciones, voz, Cast, teclado, entradas, tienda ni apps del usuario.
# El nombre del paquete no demuestra ausencia de dependencias en cada firmware.
CANDIDATES = {
    "com.google.android.feedback": "Aplicación de envío de comentarios; revisar si se usa para soporte.",
    "com.android.tv.feedbackconsent": "Consentimiento de comentarios de TV; revisar interfaz de privacidad.",
    "com.google.android.tv.bugreportsender": "Envío de informes de errores de TV; revisar diagnóstico necesario.",
    "com.miui.tv.analytics": "Analítica del fabricante; comprobar integraciones del modelo.",
}

# Aparecen en distintas familias de TV, pero su nombre no permite inferir que
# sean prescindibles para este propietario o su firmware.
MANUAL_REVIEW = {
    "com.google.android.apps.tv.launcherx": "HOME: usar el flujo con respaldo de home.py, no debloat.",
    "com.google.android.tvlauncher": "HOME: usar el flujo con respaldo de home.py, no debloat.",
    "com.google.android.tungsten.setupwraith": "Configuración inicial; puede reclamar HOME o ser dueño de la red Wi-Fi.",
    "com.google.android.apps.mediashell": "Receptor Cast; conservar si se usa envío a TV.",
    "com.google.android.katniss": "Asistente y voz; comprobar micrófono del mando.",
    "com.google.android.tv.dfuservice": "Puede actualizar el firmware del mando.",
    "com.google.android.tv.remote.service": "Mando remoto desde teléfono; confirmar uso.",
    "com.google.android.apps.tv.dreamx": "Salvapantallas; revisar selección y componente activo.",
    "com.google.android.tvrecommendations": "Filas de recomendaciones; comprobar estado y dependencias.",
    "com.google.android.play.games": "Aplicación de juegos; confirmar uso.",
    "com.google.android.videos": "Aplicación de películas; confirmar uso.",
    "com.mitv.tvhome.atv": "Interfaz del fabricante; puede reclamar HOME o gestionar entradas.",
}


def draft(inventory):
    validate(inventory)
    model = inventory["device"].get("model")
    firmware = inventory["firmware"]
    review = inventory.get("review")
    if (not isinstance(model, str) or not model.strip() or not firmware.strip()
            or not isinstance(review, dict)
            or not isinstance(review.get("system_to_review"), list)
            or not isinstance(review.get("protected"), list)):
        raise ValueError("Se requiere un inventario completo creado con --review, modelo y firmware.")
    installed = set(inventory["installed"])
    disabled = set(inventory["disabled"])
    available = review["system_to_review"]
    protected = review["protected"]
    if (any(not isinstance(p, str) or p not in installed for p in available + protected)
            or len(available) != len(set(available)) or len(protected) != len(set(protected))
            or set(available) & (set(protected) | disabled)):
        raise ValueError("La revisión del inventario es incoherente; captura otra vez.")
    packages = [{"package": package, "reason": CANDIDATES[package]}
                for package in sorted(set(available) & CANDIDATES.keys())]
    manual = [{"package": package, "check": MANUAL_REVIEW[package],
               "protected": package in protected}
              for package in sorted((set(available) | set(protected)) & MANUAL_REVIEW.keys())
              if package not in disabled]
    if not packages and not manual:
        raise ValueError("No hay componentes conocidos en los paquetes de sistema activos de esta TV.")
    return {"version": 1, "model": model, "firmware": firmware,
            "review_required": True, "packages": packages, "manual_review": manual}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True,
                        help="JSON creado con tools/inventory.py --review")
    parser.add_argument("--output", type=Path, required=True,
                        help="Ruta nueva del borrador; nunca sobrescribe")
    args = parser.parse_args(argv)
    try:
        data = draft(json.loads(args.inventory.read_text(encoding="utf-8")))
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(f"Borrador: {args.output}; {len(data['packages'])} candidatos y "
              f"{len(data['manual_review'])} componentes para análisis separado. "
              "No se cambió ningún paquete.")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
