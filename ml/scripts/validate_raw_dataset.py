"""Valida las fotografias originales ANTES de etiquetarlas o copiarlas al dataset.

NO modifica ni elimina nada. Solo reporta. Ubica las fotos de cada clase dentro de
--raw-dir usando, en orden de prioridad:

    1. ml/raw_source_map.json — ruta relativa REAL (nombres de carpeta "humanos",
       tal como los entrego el usuario: con acentos, mayusculas, subcarpetas
       duplicadas, nombres de exportacion de Google Drive, etc.).
    2. --raw-dir/<raw_folder> — convencion simple de classes.json (usada como
       respaldo para clases nuevas que todavia no tienen una ruta real mapeada).

Dentro de la carpeta resuelta de cada clase busca imagenes de forma RECURSIVA
(por si hay subcarpetas duplicadas, como ocurre en el dataset real actual).

Comprueba:
    - conteo de imagenes por clase
    - formatos de archivo permitidos (.jpg/.jpeg/.png)
    - imagenes corruptas (intenta abrirlas con Pillow)
    - duplicados exactos (hash SHA-256), dentro de cada clase y entre clases
    - carpetas con imagenes dentro de --raw-dir que NO pertenecen a ninguna clase
      mapeada (revisar manualmente; no se tocan)
    - clases mapeadas cuya carpeta ya NO existe (ruta vieja tras un renombrado/borrado)
    - clases cuya carpeta existe pero esta vacia (0 imagenes)
    - rutas de archivo largas (>=240 caracteres): se manejan de forma segura (ver
      long_path() en common.py) pero se reportan igual, para que sepas cuales
      dependen de ese workaround

Uso:
    python scripts/validate_raw_dataset.py --raw-dir "C:\\ruta\\a\\fotos"
    python scripts/validate_raw_dataset.py --raw-dir "C:\\ruta\\a\\fotos" --report reporte.json
"""
import argparse
import json
import sys
from pathlib import Path

import common

try:
    import PIL  # noqa: F401
    PILLOW_AVAILABLE = True
except ImportError:
    PILLOW_AVAILABLE = False

LONG_PATH_WARNING_THRESHOLD = common.LONG_PATH_WARNING_THRESHOLD


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-dir", default=str(common.RAW_DIR),
                         help="Carpeta raiz con las fotos originales (default: ml/raw)")
    parser.add_argument("--report", default=None, help="Ruta opcional para guardar el reporte en JSON")
    args = parser.parse_args()

    raw_dir = Path(args.raw_dir)
    if not raw_dir.exists():
        print(f"[ERROR] La carpeta no existe: {raw_dir}")
        sys.exit(1)

    classes = common.load_classes()
    source_map = common.load_raw_source_map()

    if not PILLOW_AVAILABLE:
        print("[AVISO] Pillow no esta instalado: no se detectaran imagenes corruptas. "
              "Instala con `pip install -r requirements.txt`.\n")

    report = common.scan_raw_dataset(raw_dir, classes=classes, source_map=source_map)
    cross_class_duplicates = report["duplicates_across_classes"]

    # --- Impresion legible ---
    print(f"Validacion de fotografias originales en: {raw_dir}\n")

    if report["missing_classes"]:
        print("Clases SIN carpeta encontrada (sin fotos todavia, o falta mapearla en raw_source_map.json):")
        for name in report["missing_classes"]:
            print(f"  - {name}")
        print()

    if report["empty_classes"]:
        print("Clases con carpeta encontrada pero SIN imagenes dentro:")
        for name in report["empty_classes"]:
            print(f"  - {name}")
        print()

    if report["unmapped_image_dirs"]:
        print("Carpetas con imagenes que NO corresponden a ninguna clase mapeada "
              "(revisar manualmente, no se han tocado):")
        for d in report["unmapped_image_dirs"]:
            print(f"  - {d}")
        print()

    total_images = 0
    total_problems = 0
    print(f"{'Clase':30} {'ID':>3} {'Imagenes':>9} {'Formato inv.':>13} {'Corruptas':>10} {'Dup. internos':>14}")
    for name, cr in sorted(report["classes"].items(), key=lambda kv: kv[1]["class_id"]):
        total_images += cr["total_images"]
        n_problems = len(cr["invalid_format_files"]) + len(cr["corrupt_images"]) + len(cr["duplicates_within_class"])
        total_problems += n_problems
        print(f"{name:30} {cr['class_id']:>3} {cr['total_images']:>9} "
              f"{len(cr['invalid_format_files']):>13} {len(cr['corrupt_images']):>10} "
              f"{len(cr['duplicates_within_class']):>14}")

    print(f"\nTotal de imagenes validas encontradas: {total_images}")

    if cross_class_duplicates:
        total_problems += len(cross_class_duplicates)
        print(f"\n[AVISO] {len(cross_class_duplicates)} grupo(s) de imagenes IDENTICAS repetidas "
              "entre carpetas de distinta clase. Revisar antes de etiquetar:")
        for group in cross_class_duplicates[:20]:
            print(f"  - {group}")
        if len(cross_class_duplicates) > 20:
            print(f"  ... y {len(cross_class_duplicates) - 20} mas (ver --report)")

    if report["long_path_files"]:
        print(f"\n[INFO] {len(report['long_path_files'])} archivo(s) con ruta >= "
              f"{LONG_PATH_WARNING_THRESHOLD} caracteres (manejados de forma segura, "
              "ver common.long_path()):")
        for item in report["long_path_files"][:20]:
            print(f"  - [{item['length']} car.] {item['file']}")
        if len(report["long_path_files"]) > 20:
            print(f"  ... y {len(report['long_path_files']) - 20} mas (ver --report)")

    if total_problems == 0 and not report["missing_classes"] and not report["unmapped_image_dirs"] \
            and not report["empty_classes"]:
        print("\nSin problemas detectados. Nada fue modificado ni eliminado.")
    else:
        print(f"\n{total_problems} problema(s) detectado(s) (formato invalido / corruptas / duplicados), "
              f"{len(report['missing_classes'])} clase(s) sin carpeta, "
              f"{len(report['unmapped_image_dirs'])} carpeta(s) sin mapear. "
              "Nada fue modificado ni eliminado automaticamente.")

    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"\nReporte completo guardado en: {args.report}")


if __name__ == "__main__":
    main()
