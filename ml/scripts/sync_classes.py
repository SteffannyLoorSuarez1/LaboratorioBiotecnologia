"""Regenera data.yaml y labels.txt a partir de classes.json.

Correr este script cada vez que se edite classes.json (por ejemplo, al agregar
una clase nueva). Es la unica forma soportada de modificar data.yaml/labels.txt:
no editarlos a mano.

Uso:
    python scripts/sync_classes.py
"""
import sys

import common


def main():
    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR] classes.json invalido: {e}")
        sys.exit(1)

    common.generate_data_yaml()
    common.generate_labels_txt()

    print(f"OK: {len(classes)} clases sincronizadas.")
    print(f"  -> {common.DATA_YAML}")
    print(f"  -> {common.LABELS_TXT}")
    for c in classes:
        print(f"     {c['class_id']:>2}  {c['name_internal']}")


if __name__ == "__main__":
    main()
