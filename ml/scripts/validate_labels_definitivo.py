"""Valida etiquetas YOLO del dataset DEFINITIVO de 16 clases (Laboratorio de
Biotecnologia UTEQ), separado del dataset preliminar de 7 clases que usa
validate_labels.py + classes.json.

Lee las clases desde ml/classes_definitivo.txt (una por linea, class_id =
posicion en el archivo, empezando en 0) en vez de classes.json, para no
mezclar el esquema viejo (7 clases) con el definitivo (16 clases).

Reusa la misma logica de validacion por par imagen/label que
scripts/validate_labels.py (formato de 5 columnas, class_id en rango,
coordenadas en [0,1], width/height validos, imagenes sin label, labels sin
imagen, labels vacios, imagenes corruptas).

Ademas, si se pasa --expected-id, avisa (no bloquea) sobre lineas cuyo
class_id sea distinto al esperado para la carpeta del equipo seleccionado
(igual que hace validate_labeling_progress.py para el dataset viejo).

Uso:
    python scripts/validate_labels_definitivo.py --images-dir "<carpeta de imagenes>" --labels-dir "<carpeta - TXT>" --expected-id 0

Codigo de salida:
    0 si no hay errores graves.
    1 si hay errores graves -> no incorporar estas etiquetas todavia.
"""
import argparse
import sys
from pathlib import Path

import common
import validate_labels

CLASSES_DEFINITIVO_TXT = common.ML_ROOT / "classes_definitivo.txt"


def load_classes_definitivo():
    lines = common.read_text(CLASSES_DEFINITIVO_TXT).splitlines()
    names = [ln.strip() for ln in lines if ln.strip()]
    if len(names) != 16:
        raise common.DatasetError(
            f"{CLASSES_DEFINITIVO_TXT} tiene {len(names)} clases, se esperaban 16. "
            "No lo edites a mano salvo que sepas exactamente que estas haciendo."
        )
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--images-dir", required=True, help="Carpeta de imagenes del equipo")
    parser.add_argument("--labels-dir", required=True, help="Carpeta '- TXT' del equipo")
    parser.add_argument("--expected-id", type=int, default=None,
                         help="class_id esperado para este equipo (0-15); si se indica, avisa sobre desviaciones")
    args = parser.parse_args()

    try:
        names = load_classes_definitivo()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] {e}")
        sys.exit(1)

    num_classes = len(names)
    id_to_name = dict(enumerate(names))

    errors, warnings, class_counts, n_images = validate_labels.validate_pair(
        args.images_dir, args.labels_dir, num_classes
    )

    print("=== Resultado ===")
    print(f"  Carpeta de imagenes: {args.images_dir}")
    print(f"  Carpeta de labels:   {args.labels_dir}")
    print(f"  Total de imagenes:   {n_images}")

    print("\n=== Distribucion de instancias etiquetadas por clase (esquema de 16, definitivo) ===")
    for class_id in range(num_classes):
        count = class_counts.get(class_id, 0)
        if count:
            print(f"  {class_id:>2}  {id_to_name[class_id]:45} {count}")
    total_boxes = sum(class_counts.values())
    print(f"\nTotal de bounding boxes: {total_boxes}")

    if args.expected_id is not None:
        deviations = {cid: n for cid, n in class_counts.items() if cid != args.expected_id}
        if deviations:
            print(f"\n[REVISAR] Se esperaba solo class_id={args.expected_id} "
                  f"({id_to_name.get(args.expected_id, '?')}) en esta carpeta, pero se encontraron otras clases "
                  "(valido SOLO si esas fotos realmente muestran otro equipo tambien):")
            for cid, n in sorted(deviations.items()):
                print(f"    - class_id={cid} ({id_to_name.get(cid, 'fuera de rango')}): {n} instancia(s)")

    if warnings:
        print(f"\n=== Avisos ({len(warnings)}) ===")
        for w in warnings[:50]:
            print(f"  - {w}")
        if len(warnings) > 50:
            print(f"  ... y {len(warnings) - 50} mas")

    if errors:
        print(f"\n=== ERRORES ({len(errors)}) ===")
        for e in errors[:100]:
            print(f"  - {e}")
        if len(errors) > 100:
            print(f"  ... y {len(errors) - 100} mas")
        print("\nNO listo: corrige los archivos indicados arriba.")
        sys.exit(1)

    print("\nOK: sin errores graves.")
    sys.exit(0)


if __name__ == "__main__":
    main()
