"""Aplica parches conocidos y documentados por la comunidad para bugs reales de
LabelImg 1.8.6 al correr con PyQt5 5.15.x / Python 3.12 (nada relacionado con la
instalacion de este equipo): varias llamadas pasan valores `float` a metodos Qt
que en estas versiones ya solo aceptan `int`, y LabelImg se cierra con
`TypeError: ... argument 1 has unexpected type 'float'`.

Bug 1 — libs/canvas.py (dibujar/mover el mouse sobre el lienzo):
    QPainter.drawRect()/drawLine() con coordenadas float (QPointF.x()/.y()).
    Reportado en multiples issues del propio proyecto (tzutalin/labelImg y su
    fork HumanSignal/labelImg).

Bug 2 — labelImg/labelImg.py (hacer scroll/zoom sobre la imagen):
    QScrollBar.setValue() / ZoomWidget(QSpinBox).setValue() con valores float
    resultado de divisiones/multiplicaciones (delta / (8*15), etc.).

En ambos casos la correccion es la misma: convertir el valor final a `int(...)`
antes de pasarlo a Qt.

Idempotente: si ya esta parcheado, no hace nada. Hay que volver a correrlo cada
vez que se reinstale/recree `ml/venv_labelimg` desde cero (una reinstalacion de
`labelImg` trae de vuelta los archivos originales sin parchear).

Uso:
    python scripts/patch_labelimg.py
    python scripts/patch_labelimg.py --venv-dir ..\\otra_ruta\\venv_labelimg
"""
import argparse
import sys
from pathlib import Path

import common

# Cada entrada: (ruta relativa dentro de Lib/site-packages/, [(texto_viejo, texto_nuevo), ...])
PATCHES = [
    (
        Path("libs") / "canvas.py",
        [
            (
                "            p.drawRect(left_top.x(), left_top.y(), rect_width, rect_height)\n",
                "            p.drawRect(int(left_top.x()), int(left_top.y()), int(rect_width), int(rect_height))\n",
            ),
            (
                "            p.drawLine(self.prev_point.x(), 0, self.prev_point.x(), self.pixmap.height())\n",
                "            p.drawLine(int(self.prev_point.x()), 0, int(self.prev_point.x()), int(self.pixmap.height()))\n",
            ),
            (
                "            p.drawLine(0, self.prev_point.y(), self.pixmap.width(), self.prev_point.y())\n",
                "            p.drawLine(0, int(self.prev_point.y()), int(self.pixmap.width()), int(self.prev_point.y()))\n",
            ),
        ],
    ),
    (
        Path("labelImg") / "labelImg.py",
        [
            (
                "        bar.setValue(bar.value() + bar.singleStep() * units)\n",
                "        bar.setValue(int(bar.value() + bar.singleStep() * units))\n",
            ),
            (
                "        self.zoom_widget.setValue(value)\n",
                "        self.zoom_widget.setValue(int(value))\n",
            ),
            (
                "        h_bar.setValue(new_h_bar_value)\n"
                "        v_bar.setValue(new_v_bar_value)\n",
                "        h_bar.setValue(int(new_h_bar_value))\n"
                "        v_bar.setValue(int(new_v_bar_value))\n",
            ),
        ],
    ),
]


def patch_file(path, replacements):
    if not path.exists():
        print(f"[ERROR] No se encontro {path}. ¿Esta labelImg instalado en ese venv?")
        return 0, 0, 1

    text = path.read_text(encoding="utf-8")
    applied = 0
    already_patched = 0
    missing = 0

    for old, new in replacements:
        if new in text:
            already_patched += 1
            continue
        if old not in text:
            print(f"[AVISO] {path.name}: no se encontro el texto esperado "
                  f"(¿cambio la version de labelImg?):\n  {old.strip()}")
            missing += 1
            continue
        text = text.replace(old, new, 1)
        applied += 1

    if applied:
        path.write_text(text, encoding="utf-8")

    return applied, already_patched, missing


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--venv-dir", default=str(common.ML_ROOT / "venv_labelimg"),
                         help="Carpeta del venv donde esta instalado labelImg (default: ml/venv_labelimg)")
    args = parser.parse_args()

    site_packages = Path(args.venv_dir) / "Lib" / "site-packages"

    total_applied = 0
    total_already = 0
    total_missing = 0

    for rel_path, replacements in PATCHES:
        full_path = site_packages / rel_path
        applied, already, missing = patch_file(full_path, replacements)
        total_applied += applied
        total_already += already
        total_missing += missing
        print(f"{rel_path}: {applied} aplicado(s) ahora, {already} ya estaban, {missing} no encontrado(s)")

    print(f"\nTotal: {total_applied} aplicado(s), {total_already} ya estaban, {total_missing} no encontrado(s)")

    if total_missing:
        print("[AVISO] Parche incompleto: revisa los avisos de arriba antes de usar LabelImg.")
        sys.exit(1)

    print("OK: todos los parches conocidos estan aplicados.")


if __name__ == "__main__":
    main()
