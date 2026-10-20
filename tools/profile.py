#!/usr/bin/env python3
"""Prepara, sin ADB, un borrador de perfil para un inventario revisado de una TV."""

import argparse
import json
from pathlib import Path
import sys

from inventory import validate
from debloat import read_packages

# Candidatos limitados a informes/consentimiento opcionales. No incluir HOME,
# actualizaciones, voz, Cast, teclado, entradas, tienda ni apps del usuario.
# El nombre del paquete no demuestra ausencia de dependencias en cada firmware.
CANDIDATES = {
    "com.google.android.feedback": "Aplicación de envío de comentarios; revisar si se usa para soporte.",
    "com.android.tv.feedbackconsent": "Consentimiento de comentarios de TV; revisar interfaz de privacidad.",
    "com.google.android.tv.bugreportsender": "Envío de informes de errores de TV; revisar diagnóstico necesario.",
    "com.miui.tv.analytics": "Analítica del fabricante; comprobar integraciones del modelo.",
    "com.xiaomi.statistic": "Estadísticas del fabricante; verificar dependencias de cuenta y sistema.",
    "com.xiaomi.tvqs.overseas.y24": "Informes de calidad; verificar funciones de soporte.",
    "com.sonyericsson.idd.agent": "Informes de uso; comprobar servicios relacionados.",
    "com.tcl.logkit": "Captura de registros; comprobar diagnóstico y recuperación.",
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
    "com.gretzky.GlobalKey": "Mando y botones directos del dispositivo; conservar.",
    "com.gretzky.hwinfo": "Componente del fabricante; comprobar funciones de hardware.",
    "com.onn.bluetoothconnect": "Emparejamiento Bluetooth del mando; conservar.",
    "com.onn.updatenotification": "Avisos de actualización del dispositivo; conservar salvo decisión informada.",
    "com.android.printspooler": "Impresión; comprobar si se utiliza antes de desactivar.",
    "com.google.android.marvin.talkback": "Lector de pantalla; conservar si se usa accesibilidad.",
    "com.google.android.tv.remotecontrol.logging": "Registros del mando; verificar funciones de control remoto.",
    "com.google.android.apps.tv.netoscope": "Diagnóstico de red; confirmar que no sea necesario.",
    "com.google.android.tvrecommendations": "Filas de recomendaciones; comprobar estado y dependencias.",
    "tv.samba.ssm": "Reconocimiento de contenido; comprobar consentimiento y funciones de TV.",
    "com.tcl.ttvs": "Puede reunir reconocimiento de contenido, actualizaciones y otras funciones.",
    "com.google.android.play.games": "Aplicación de juegos; confirmar uso.",
    "com.google.android.videos": "Aplicación de películas; confirmar uso.",
    "com.mitv.tvhome.atv": "Interfaz del fabricante; puede reclamar HOME o gestionar entradas.",
}


def draft(inventory, suggested=()):
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
    if any(not isinstance(p, str) for p in suggested):
        raise ValueError("La lista de candidatos es inválida.")
    suggestions = set(suggested)
    eligible = set(available)
    packages = [{"package": package, "reason": CANDIDATES.get(package,
                 "Candidato de la lista: verificar función y dependencias en este firmware.")}
                for package in sorted(eligible & (CANDIDATES.keys() | suggestions))
                if package not in MANUAL_REVIEW]
    manual = [{"package": package, "check": MANUAL_REVIEW[package],
               "protected": package in protected}
              for package in sorted((eligible | set(protected)) & MANUAL_REVIEW.keys())
              if package not in disabled]
    excluded = []
    for package in sorted(suggestions - {p["package"] for p in packages}):
        if package not in installed: reason = "ausente"
        elif package in disabled: reason = "ya desactivado"
        elif package in protected: reason = "protegido"
        elif package in MANUAL_REVIEW: reason = "requiere flujo o decisión separada"
        else: reason = "no figura como paquete de sistema activo revisable"
        excluded.append({"package": package, "reason": reason})
    if not packages and not manual and not excluded:
        raise ValueError("No hay componentes conocidos en los paquetes de sistema activos de esta TV.")
    return {"version": 1, "model": model, "firmware": firmware,
            "review_required": True, "packages": packages, "manual_review": manual,
            "excluded": excluded}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True,
                        help="JSON creado con tools/inventory.py --review")
    parser.add_argument("--list", type=Path,
                        help="Lista orientativa adicional: filtrar contra inventario y protecciones")
    parser.add_argument("--output", type=Path, required=True,
                        help="Ruta nueva del borrador; nunca sobrescribe")
    args = parser.parse_args(argv)
    try:
        suggestions = read_packages(args.list) if args.list else ()
        data = draft(json.loads(args.inventory.read_text(encoding="utf-8")), suggestions)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(f"Borrador: {args.output}; {len(data['packages'])} candidatos y "
              f"{len(data['manual_review'])} componentes para análisis separado; "
              f"{len(data['excluded'])} excluidos de la lista. "
              "No se cambió ningún paquete.")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
