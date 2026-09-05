"""Validacion GLOBAL de todo el progreso de etiquetado, clase por clase.

A diferencia de validate_labels.py (que valida UN par imagenes/labels a la vez,
o el dataset ya dividido en ml/dataset/), este script recorre las 7 clases de
classes.json, resuelve la carpeta real de imagenes de cada una (via
raw_source_map.json) y su carpeta de etiquetas por CONVENCION: la misma ruta
con el sufijo " - txt" como carpeta hermana (p. ej. ".../UV PCR Workstation"
-> ".../UV PCR Workstation - txt"). Esa es la convencion que se ha usado para
etiquetar manualmente con LabelImg en este proyecto.

Ademas de lo que ya hace validate_labels.py por clase (imagenes sin label,
labels sin imagen, labels vacios, lineas mal formadas, class_id fuera de
0..6, coordenadas fuera de [0,1], width/height invalidos, imagenes corruptas),
este script agrega verificaciones GLOBALES:

    - class_id inesperado dentro de la carpeta de una clase (una clase puede
      legitimamente tener otro class_id si la foto contiene mas de uno de
      nuestros equipos; se reporta para que el usuario lo confirme, no se
      trata como error automatico)
    - nombres de archivo (stem) duplicados entre carpetas de distinta clase,
      que podrian colisionar al construir el dataset (prepare_dataset.py ya
      los renombra con el prefijo de carpeta, pero se reportan para que el
      usuario lo sepa)
    - duplicados exactos de fotografias (dentro y entre clases), reutilizando
      common.scan_raw_dataset (misma logica que validate_raw_dataset.py)
    - rutas de archivo largas (manejo seguro en Windows)
    - imagenes corruptas
    - que raw_source_map.json siga apuntando a carpetas que realmente existen

NO modifica ni corrige nada automaticamente. Solo reporta.

Uso:
    python scripts/validate_labeling_progress.py
    python scripts/validate_labeling_progress.py --raw-root "C:\\ruta\\a\\RECOPILACION..."
"""
import argparse
import sys
from collections import defaultdict
from pathlib import Path

import common
import validate_labels

derive_labels_dir = common.derive_labels_dir


def scan_class_id_deviations(labels_dir, expected_class_id):
    """Relee los .txt validos (formato correcto) de una carpeta y devuelve una
    lista de (archivo, linea, class_id_encontrado) para cada linea cuyo
    class_id sea distinto del esperado para esa clase."""
    deviations = []
    if not common.path_exists(labels_dir):
        return deviations
    for label_path in sorted(Path(labels_dir).glob("*.txt")):
        if label_path.name.lower() in validate_labels.NON_LABEL_FILENAMES:
            continue
        text = common.read_text(label_path).strip()
        if not text:
            continue
        for line_no, line in enumerate(text.splitlines(), start=1):
            parts = line.strip().split()
            if len(parts) != 5:
                continue
            try:
                class_id = int(parts[0])
            except ValueError:
                continue
            if class_id != expected_class_id:
                deviations.append((label_path.name, line_no, class_id, line.strip()))
    return deviations


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-root", default=None,
                         help="Carpeta raiz de las fotografias originales (default: root_hint de raw_source_map.json)")
    args = parser.parse_args()

    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] classes.json invalido: {e}")
        sys.exit(1)
    num_classes = len(classes)
    id_to_name = {c["class_id"]: c["name_internal"] for c in classes}
    name_to_id = {c["name_internal"]: c["class_id"] for c in classes}

    source_map = common.load_raw_source_map()
    if args.raw_root:
        raw_root = Path(args.raw_root)
    else:
        import json
        with open(common.RAW_SOURCE_MAP_JSON, "r", encoding="utf-8") as f:
            raw_root = Path(json.load(f)["root_hint"])

    print(f"Raiz de fotografias originales: {raw_root}\n")

    # --- 1) Escaneo tipo validate_raw_dataset.py: duplicados, corruptas, rutas largas, mapeo ---
    raw_report = common.scan_raw_dataset(raw_root, classes=classes, source_map=source_map)

    if raw_report["missing_classes"]:
        print("[ERROR] raw_source_map.json apunta a carpetas que YA NO EXISTEN para estas clases:")
        for name in raw_report["missing_classes"]:
            cls = next(c for c in classes if c["name_internal"] == name)
            print(f"  - {name}: {cls['class_id']} -> {common.resolve_class_source_dir(raw_root, cls, source_map)}")
        print()

    # --- 2) Por clase: validate_labels.validate_pair + chequeo de class_id inesperado ---
    per_class = {}
    all_stems = defaultdict(list)  # stem -> [(class_name, image_path), ...]

    for cls in classes:
        name = cls["name_internal"]
        expected_id = cls["class_id"]
        images_dir = common.resolve_class_source_dir(raw_root, cls, source_map)
        labels_dir = derive_labels_dir(images_dir)

        if not common.path_exists(images_dir):
            per_class[name] = {
                "class_id": expected_id, "images_dir": str(images_dir), "labels_dir": str(labels_dir),
                "n_images": 0, "n_labels": 0, "boxes": 0, "errors": ["Carpeta de imagenes no encontrada"],
                "warnings": [], "deviations": [], "found": False,
            }
            continue

        errors, warnings, class_counts, n_images = validate_labels.validate_pair(
            images_dir, labels_dir, num_classes, split_name=name)

        n_labels = len(list(labels_dir.glob("*.txt"))) if common.path_exists(labels_dir) else 0
        n_labels_real = len([
            p for p in (labels_dir.glob("*.txt") if common.path_exists(labels_dir) else [])
            if p.name.lower() not in validate_labels.NON_LABEL_FILENAMES
        ])

        deviations = scan_class_id_deviations(labels_dir, expected_id)

        per_class[name] = {
            "class_id": expected_id, "images_dir": str(images_dir), "labels_dir": str(labels_dir),
            "n_images": n_images, "n_labels": n_labels_real, "boxes": sum(class_counts.values()),
            "errors": errors, "warnings": warnings, "deviations": deviations, "found": True,
        }

        for img_path in common.iter_images(images_dir):
            all_stems[img_path.stem].append((name, str(img_path)))

    # --- 3) Colisiones de nombre de archivo entre clases distintas ---
    cross_class_stem_collisions = {
        stem: entries for stem, entries in all_stems.items()
        if len({cname for cname, _ in entries}) > 1
    }

    # --- Reporte ---
    total_errors = 0
    print("=== Detalle por clase ===\n")
    for cls in classes:
        name = cls["name_internal"]
        info = per_class[name]
        print(f"[{info['class_id']}] {name}")
        print(f"    imagenes: {info['images_dir']}")
        print(f"    labels:   {info['labels_dir']}")
        if not info["found"]:
            print(f"    ESTADO: NO APROBADA — {info['errors'][0]}")
            total_errors += 1
            print()
            continue

        print(f"    imagenes={info['n_images']}  labels={info['n_labels']}  bounding_boxes={info['boxes']}")

        if info["warnings"]:
            print(f"    Avisos ({len(info['warnings'])}):")
            for w in info["warnings"]:
                print(f"      - {w}")

        if info["deviations"]:
            print(f"    [REVISAR] {len(info['deviations'])} linea(s) con class_id distinto al esperado "
                  f"({info['class_id']} = {name}) — valido SOLO si la foto realmente muestra otro equipo:")
            for fname, line_no, found_id, line in info["deviations"]:
                found_name = id_to_name.get(found_id, f"id {found_id} fuera de rango")
                print(f"      - {fname}:{line_no} class_id={found_id} ({found_name}) -> linea: '{line}'")

        if info["errors"]:
            print(f"    ERRORES ({len(info['errors'])}):")
            for e in info["errors"]:
                print(f"      - {e}")
            total_errors += len(info["errors"])
            print(f"    ESTADO: NO APROBADA")
        else:
            print(f"    ESTADO: APROBADA")
        print()

    # --- Duplicados de fotografias (dentro y entre clases) ---
    print("=== Duplicados exactos de fotografias ===")
    any_dup = False
    for name, cr in raw_report["classes"].items():
        if cr["duplicates_within_class"]:
            any_dup = True
            print(f"  [{name}] duplicados dentro de la clase:")
            for group in cr["duplicates_within_class"]:
                print(f"    - {group}")
    if raw_report["duplicates_across_classes"]:
        any_dup = True
        print("  Duplicados ENTRE clases distintas:")
        for group in raw_report["duplicates_across_classes"]:
            print(f"    - {group}")
    if not any_dup:
        print("  Ninguno.")

    # --- Colisiones de nombre de archivo entre clases ---
    print("\n=== Nombres de archivo duplicados entre carpetas de clases distintas ===")
    if cross_class_stem_collisions:
        for stem, entries in cross_class_stem_collisions.items():
            print(f"  '{stem}' aparece en: {[c for c, _ in entries]}")
            print(f"    (prepare_dataset.py los renombrara con prefijo de carpeta al construir el dataset)")
    else:
        print("  Ninguno.")

    # --- Rutas largas ---
    print(f"\n=== Rutas de archivo largas (>= {common.LONG_PATH_WARNING_THRESHOLD} caracteres) ===")
    if raw_report["long_path_files"]:
        print(f"  {len(raw_report['long_path_files'])} archivo(s), manejados de forma segura (ver common.long_path()).")
    else:
        print("  Ninguna.")

    # --- Imagenes corruptas (resumen, ya integrado en validate_pair como error tambien) ---
    total_corrupt = sum(len(cr["corrupt_images"]) for cr in raw_report["classes"].values())
    print(f"\n=== Imagenes corruptas ===")
    print(f"  {total_corrupt} encontrada(s)." if total_corrupt else "  Ninguna.")

    # --- raw_source_map.json ---
    print(f"\n=== Validez de raw_source_map.json ===")
    if raw_report["missing_classes"]:
        print(f"  [ERROR] {len(raw_report['missing_classes'])} clase(s) con ruta invalida: "
              f"{raw_report['missing_classes']}")
        total_errors += len(raw_report["missing_classes"])
    else:
        print("  Las 7 clases resuelven a carpetas que existen realmente.")

    # --- class_id global fuera de rango: ya cubierto por validate_pair (errors) por clase ---

    # --- Tabla final ---
    print("\n=== Tabla final ===")
    print(f"{'class_id':>8} | {'clase':30} | {'imagenes':>8} | {'labels':>6} | {'bounding_boxes':>14} | estado")
    global_approved = not raw_report["missing_classes"]
    for cls in classes:
        name = cls["name_internal"]
        info = per_class[name]
        if not info["found"] or info["errors"]:
            estado = "NO APROBADA"
            global_approved = False
        else:
            estado = "APROBADA"
        print(f"{info['class_id']:>8} | {name:30} | {info['n_images']:>8} | {info['n_labels']:>6} | "
              f"{info['boxes']:>14} | {estado}")

    if cross_class_stem_collisions or any_dup:
        # No se marcan como bloqueantes por si mismos (se reportan para revision),
        # pero se listan explicitamente para que el usuario decida.
        print("\n(Nota: hay duplicados de fotografia y/o nombres de archivo repetidos entre clases "
              "reportados arriba — no bloquean la aprobacion por si solos, pero revisalos.)")

    print()
    if global_approved:
        print("VALIDACIÓN GLOBAL APROBADA")
        sys.exit(0)
    else:
        print("VALIDACIÓN GLOBAL NO APROBADA")
        sys.exit(1)


if __name__ == "__main__":
    main()
