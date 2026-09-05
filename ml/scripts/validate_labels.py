"""Valida etiquetas YOLO antes de entrenar.

Dos modos de uso:

1) Validar el dataset final ya dividido (train/val/test), el default:

    python scripts/validate_labels.py

2) Validar un par imagenes/labels suelto (por ejemplo, tus fotos ya etiquetadas
   antes de correr prepare_dataset.py):

    python scripts/validate_labels.py --images-dir "C:\\ruta\\imagenes" --labels-dir "C:\\ruta\\labels"

Comprueba, por cada par imagen.jpg <-> imagen.txt:
    - imagenes sin label
    - labels sin imagen
    - class_id fuera de rango (segun classes.json)
    - coordenadas x_center/y_center fuera de [0, 1]
    - width/height invalidos (<=0 o >1)
    - archivos de label vacios (0 objetos etiquetados en esa imagen)
    - lineas mal formadas (numero de columnas distinto de 5)
    - imagenes corruptas (si Pillow esta disponible)

Ademas reporta distribucion de instancias por clase y, en modo dataset completo,
cantidades por split (train/val/test).

Codigo de salida:
    0 si no hay errores graves (los "avisos" como labels vacios no cuentan).
    1 si hay errores graves -> el pipeline NO debe entrenar con este dataset.
"""
import argparse
import sys
from collections import Counter, defaultdict
from pathlib import Path

import common


# Archivos auxiliares de herramientas de etiquetado que NO son un label YOLO
# por imagen (p. ej. LabelImg guarda un "classes.txt" con la lista de clases
# usada en la carpeta, no una anotacion).
NON_LABEL_FILENAMES = {"classes.txt"}


def validate_pair(images_dir, labels_dir, num_classes, split_name=""):
    """Devuelve (errors, warnings, class_counts, image_count)."""
    images_dir = Path(images_dir)
    labels_dir = Path(labels_dir)
    errors = []
    warnings = []
    class_counts = Counter()

    images = {p.stem: p for p in common.iter_images(images_dir)}
    labels = {
        p.stem: p for p in labels_dir.glob("*.txt")
        if p.name.lower() not in NON_LABEL_FILENAMES
    } if common.path_exists(labels_dir) else {}

    prefix = f"[{split_name}] " if split_name else ""

    for stem in sorted(set(images) - set(labels)):
        errors.append(f"{prefix}Imagen sin label: {images[stem].name}")

    for stem in sorted(set(labels) - set(images)):
        errors.append(f"{prefix}Label sin imagen: {labels[stem].name}")

    for stem in sorted(set(images) & set(labels)):
        img_path = images[stem]
        label_path = labels[stem]

        corrupt = common.check_image_corrupt(img_path)
        if corrupt:
            errors.append(f"{prefix}Imagen corrupta: {img_path.name} ({corrupt})")
            continue

        text = common.read_text(label_path).strip()
        if not text:
            warnings.append(f"{prefix}Label vacio (sin objetos etiquetados): {label_path.name}")
            continue

        for line_no, line in enumerate(text.splitlines(), start=1):
            parts = line.strip().split()
            if len(parts) != 5:
                errors.append(f"{prefix}{label_path.name}:{line_no} formato invalido "
                               f"(esperaba 5 columnas, encontro {len(parts)}): '{line}'")
                continue

            try:
                class_id = int(parts[0])
                x, y, w, h = (float(v) for v in parts[1:])
            except ValueError:
                errors.append(f"{prefix}{label_path.name}:{line_no} valores no numericos: '{line}'")
                continue

            if not (0 <= class_id < num_classes):
                errors.append(f"{prefix}{label_path.name}:{line_no} class_id fuera de rango "
                               f"(0..{num_classes - 1}): {class_id}")
                continue

            if not (0.0 <= x <= 1.0) or not (0.0 <= y <= 1.0):
                errors.append(f"{prefix}{label_path.name}:{line_no} x_center/y_center fuera de "
                               f"[0,1]: x={x} y={y}")

            if not (0.0 < w <= 1.0) or not (0.0 < h <= 1.0):
                errors.append(f"{prefix}{label_path.name}:{line_no} width/height invalidos "
                               f"(deben ser >0 y <=1): w={w} h={h}")

            class_counts[class_id] += 1

    return errors, warnings, class_counts, len(images)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--images-dir", default=None, help="Carpeta de imagenes (modo par suelto)")
    parser.add_argument("--labels-dir", default=None, help="Carpeta de labels (modo par suelto)")
    args = parser.parse_args()

    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] classes.json invalido: {e}")
        sys.exit(1)
    num_classes = len(classes)
    id_to_name = {c["class_id"]: c["name_internal"] for c in classes}

    all_errors = []
    all_warnings = []
    total_class_counts = Counter()
    split_counts = {}

    if args.images_dir or args.labels_dir:
        if not (args.images_dir and args.labels_dir):
            print("[ERROR] Debes indicar --images-dir y --labels-dir juntos.")
            sys.exit(1)
        errors, warnings, counts, n_images = validate_pair(args.images_dir, args.labels_dir, num_classes)
        all_errors += errors
        all_warnings += warnings
        total_class_counts += counts
        split_counts["(personalizado)"] = n_images
    else:
        for split in common.SPLITS:
            images_dir = common.DATASET_DIR / "images" / split
            labels_dir = common.DATASET_DIR / "labels" / split
            errors, warnings, counts, n_images = validate_pair(images_dir, labels_dir, num_classes, split_name=split)
            all_errors += errors
            all_warnings += warnings
            total_class_counts += counts
            split_counts[split] = n_images

    print("=== Distribucion de imagenes por split ===")
    for split, n in split_counts.items():
        print(f"  {split:15} {n} imagenes")

    print("\n=== Distribucion de instancias etiquetadas por clase ===")
    for class_id in range(num_classes):
        print(f"  {class_id:>2}  {id_to_name[class_id]:35} {total_class_counts.get(class_id, 0)}")

    total_boxes = sum(total_class_counts.values())
    print(f"\nTotal de bounding boxes (todas las clases): {total_boxes}")

    if all_warnings:
        print(f"\n=== Avisos ({len(all_warnings)}) ===")
        for w in all_warnings[:50]:
            print(f"  - {w}")
        if len(all_warnings) > 50:
            print(f"  ... y {len(all_warnings) - 50} mas")

    if all_errors:
        print(f"\n=== ERRORES ({len(all_errors)}) — el dataset NO es apto para entrenar ===")
        for e in all_errors[:100]:
            print(f"  - {e}")
        if len(all_errors) > 100:
            print(f"  ... y {len(all_errors) - 100} mas")
        sys.exit(1)

    print("\nOK: sin errores graves.")
    sys.exit(0)


if __name__ == "__main__":
    main()
