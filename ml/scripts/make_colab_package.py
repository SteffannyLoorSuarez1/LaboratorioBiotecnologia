"""Empaqueta EXACTAMENTE lo necesario para entrenar en Google Colab en un .zip.

Por que estos archivos y no otros: el paquete reproduce la misma estructura
relativa que ml/ (data.yaml, classes.json, dataset/, scripts/common.py,
scripts/validate_labels.py, scripts/train.py), asi que common.ML_ROOT
(calculado como `Path(__file__).resolve().parent.parent` dentro de
common.py) resuelve correctamente sin ningun cambio de codigo, sin importar
si se ejecuta en Windows (aqui) o en Colab (Linux). No se toca ninguna
carpeta original: solo se LEE ml/ y se escribe el .zip.

Incluye:
    - data.yaml, classes.json, requirements.txt
    - dataset/images/{train,val,test}, dataset/labels/{train,val,test}
    - scripts/common.py, scripts/validate_labels.py, scripts/train.py

NO incluye (deliberado): raw/ (fotos originales sin procesar), raw_source_map.json,
runs/ (entrenamientos previos), models/, venv*/, inference_samples/, __pycache__,
labels.txt/detector_config.json (no los usa train.py), scripts que no hacen
falta para entrenar (prepare_dataset.py, evaluate.py, export_tflite.py,
copy_model_to_android.py, patch_labelimg.py, validate_raw_dataset.py,
validate_labeling_progress.py, sync_classes.py, make_colab_package.py),
documentacion, app/ (Android), backend/ (RAG).

yolo11n.pt NO se incluye: se descarga solo en Colab (mas rapido ahi que
subirlo, y evita duplicar un archivo que ya esta versionado por Ultralytics).

Uso:
    python scripts/make_colab_package.py
    python scripts/make_colab_package.py --out ../mi_paquete.zip
"""
import argparse
import sys
import zipfile
from pathlib import Path

import common

FILES_TO_INCLUDE = [
    "data.yaml",
    "classes.json",
    "requirements.txt",
]

SCRIPTS_TO_INCLUDE = [
    "common.py",
    "validate_labels.py",
    "train.py",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=str(common.ML_ROOT / "colab_training_package.zip"))
    args = parser.parse_args()

    out_path = Path(args.out)

    missing = [f for f in FILES_TO_INCLUDE if not (common.ML_ROOT / f).exists()]
    missing += [f"scripts/{f}" for f in SCRIPTS_TO_INCLUDE if not (common.ML_ROOT / "scripts" / f).exists()]
    if missing:
        print(f"[ERROR] Faltan archivos requeridos: {missing}")
        sys.exit(1)

    if not common.DATASET_DIR.exists():
        print(f"[ERROR] No existe {common.DATASET_DIR}. Corre prepare_dataset.py primero.")
        sys.exit(1)

    n_files = 0
    total_bytes = 0

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for f in FILES_TO_INCLUDE:
            src = common.ML_ROOT / f
            zf.write(src, arcname=f)
            n_files += 1
            total_bytes += src.stat().st_size

        for f in SCRIPTS_TO_INCLUDE:
            src = common.ML_ROOT / "scripts" / f
            zf.write(src, arcname=f"scripts/{f}")
            n_files += 1
            total_bytes += src.stat().st_size

        for split in common.SPLITS:
            for kind in ("images", "labels"):
                split_dir = common.DATASET_DIR / kind / split
                for f in sorted(split_dir.iterdir()):
                    if not f.is_file() or f.name == ".gitkeep":
                        continue
                    arcname = f"dataset/{kind}/{split}/{f.name}"
                    zf.write(f, arcname=arcname)
                    n_files += 1
                    total_bytes += f.stat().st_size

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"Paquete creado: {out_path}")
    print(f"  {n_files} archivos, {total_bytes / (1024*1024):.1f} MB sin comprimir, "
          f"{size_mb:.1f} MB el .zip final")
    print("\nNO incluido (a proposito): raw/, raw_source_map.json, runs/, models/, "
          "venv*/, app/, backend/, documentacion.")


if __name__ == "__main__":
    main()
