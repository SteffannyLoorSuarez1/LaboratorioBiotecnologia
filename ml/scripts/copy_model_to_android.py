"""Copia el modelo REAL (ya entrenado, evaluado y exportado) y labels.txt a Android.

NUNCA crea un best.tflite falso: se detiene si ml/models/best.tflite no existe,
esta vacio, o no fue generado por scripts/export_tflite.py.

labels.txt se regenera siempre desde classes.json (fuente unica de verdad) justo
antes de copiar, para garantizar que Android y el modelo entrenado coinciden
exactamente en orden de clases.

Uso:
    python scripts/copy_model_to_android.py
"""
import shutil
import sys

import common

ANDROID_ASSETS_DIR = common.ML_ROOT.parent / "app" / "src" / "main" / "assets"


def main():
    tflite_src = common.MODELS_DIR / "best.tflite"

    if not tflite_src.exists() or tflite_src.stat().st_size == 0:
        print(f"[ERROR] No existe (o esta vacio) {tflite_src}.")
        print("        Este archivo solo debe existir despues de: entrenar (train.py) + ")
        print("        evaluar (evaluate.py) + exportar (export_tflite.py) un modelo REAL.")
        print("        No se copiara nada a Android.")
        sys.exit(1)

    if not ANDROID_ASSETS_DIR.exists():
        print(f"[ERROR] No existe la carpeta de assets de Android: {ANDROID_ASSETS_DIR}")
        sys.exit(1)

    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] classes.json invalido: {e}")
        sys.exit(1)

    labels_content = common.generate_labels_txt(write=True)  # actualiza tambien ml/labels.txt

    dest_tflite = ANDROID_ASSETS_DIR / "best.tflite"
    dest_labels = ANDROID_ASSETS_DIR / "labels.txt"

    shutil.copy2(tflite_src, dest_tflite)
    dest_labels.write_text(labels_content, encoding="utf-8")

    size_mb = dest_tflite.stat().st_size / (1024 * 1024)
    print("Copiado a Android:")
    print(f"  {dest_tflite}  ({size_mb:.2f} MB)")
    print(f"  {dest_labels}  ({len(classes)} clases)")
    print("\nOrden de clases (debe coincidir con las salidas del modelo entrenado):")
    for c in classes:
        print(f"  {c['class_id']:>2}  {c['name_internal']}")
    print("\nSiguiente paso: implementar la inferencia real en YoloTfliteDetector "
          "(ver docs/YOLO_SETUP.md) y probar en un dispositivo fisico.")


if __name__ == "__main__":
    main()
