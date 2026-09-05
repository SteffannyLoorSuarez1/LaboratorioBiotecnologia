"""Genera PRE-ETIQUETAS YOLO automaticas para fotos nuevas usando un best.pt
ya entrenado (YOLO11n), para acelerar el etiquetado manual — NUNCA lo
reemplaza.

Flujo previsto (ver ml/LABELING_GUIDE.md, seccion "Pre-etiquetado asistido"):
    foto nueva -> este script genera <foto>.txt preliminar -> revision humana
    en LabelImg (corregir caja / clase, borrar falsos positivos, agregar cajas
    faltantes) -> validate_labels.py -> recien ahi se incorpora al dataset con
    prepare_dataset.py.

Las etiquetas que genera este script son SIEMPRE preliminares:
    - NO se marcan ni se tratan como definitivas en ningun lado.
    - NO se copian ni se mezclan automaticamente con ml/dataset/.
    - NO se llama a prepare_dataset.py desde aqui.
    - Requieren revision manual obligatoria en LabelImg antes de usarse para
      entrenar.

Preserva exactamente los class_id 0..6 de ml/classes.json: el script valida
que el modelo cargado tenga las mismas 7 clases, en el mismo orden, antes de
escribir un solo archivo — si no coinciden, aborta sin escribir nada (evita
mezclar class_id de un modelo/dataset distinto por error).

Por defecto NUNCA sobrescribe un .txt que ya exista en --output-labels-dir
(se asume que ya fue revisado o etiquetado a mano): lo salta y lo reporta.
Usa --overwrite solo si de verdad quieres regenerar pre-etiquetas ya
existentes (por ejemplo, tras entrenar una version mejor del modelo) — el
script avisa igual cada vez que sobrescribe.

Nota (Windows): este script importa `ultralytics`, que a su vez importa
`matplotlib` (para dibujar/anotar resultados) incluso sin pedir graficos
explicitamente. En este equipo, `matplotlib` -> `kiwisolver` esta bloqueado
por Windows Smart App Control (el mismo bloqueo real que impide entrenar
localmente, ver ml/README.md). Si este script falla aqui con un
`ImportError`/`DLL load failed` al importar ultralytics, es ese bloqueo, no
un bug del script: correlo en Google Colab (donde ya se uso `model.predict()`
sin problemas en la prueba externa) u otro entorno sin esa restriccion.

Uso:
    python scripts/prelabel_new_images.py \\
        --images-dir "<carpeta de fotos nuevas>" \\
        --output-labels-dir "<carpeta donde escribir los .txt>" \\
        --weights ml/models/preliminary/best.pt \\
        --conf 0.25
"""
import argparse
import sys
from collections import defaultdict
from pathlib import Path

import common


def load_model_and_verify_classes(weights_path, expected_id_to_name):
    """Carga el modelo y aborta si sus clases no coinciden EXACTAMENTE (mismo
    id, mismo orden) con ml/classes.json. Sin esto, un best.pt de otro
    experimento podria escribir class_id que en este proyecto significan otra
    cosa, sin ningun aviso."""
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] El paquete 'ultralytics' no esta instalado. pip install -r requirements.txt")
        sys.exit(1)

    model = YOLO(str(weights_path))
    model_names = {int(k): v for k, v in model.names.items()}

    if model_names != expected_id_to_name:
        print("[ERROR] Las clases del modelo cargado NO coinciden con ml/classes.json. "
              "No se escribira ninguna pre-etiqueta (para no asignar class_id incorrectos).")
        print(f"  Clases del modelo   ({len(model_names)}): {model_names}")
        print(f"  Clases de classes.json ({len(expected_id_to_name)}): {expected_id_to_name}")
        sys.exit(1)

    return model


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--images-dir", required=True, help="Carpeta con las fotos nuevas a pre-etiquetar")
    parser.add_argument("--output-labels-dir", required=True,
                         help="Carpeta donde escribir los .txt preliminares (se crea si no existe)")
    parser.add_argument("--weights", required=True, help="Ruta a un best.pt entrenado (YOLO11n)")
    parser.add_argument("--conf", type=float, default=0.25, help="Umbral de confianza minimo (default: 0.25)")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", default=None, help="Forzar device ('0', 'cpu', etc.). Default: auto")
    parser.add_argument("--overwrite", action="store_true",
                         help="Sobrescribir .txt ya existentes en --output-labels-dir (default: NO, se saltan)")
    args = parser.parse_args()

    images_dir = Path(args.images_dir)
    output_labels_dir = Path(args.output_labels_dir)
    weights_path = Path(args.weights)

    if not common.path_exists(images_dir):
        print(f"[ERROR] No existe --images-dir: {images_dir}")
        sys.exit(1)
    if not common.path_is_file(weights_path):
        print(f"[ERROR] No existe --weights: {weights_path}")
        sys.exit(1)

    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] classes.json invalido: {e}")
        sys.exit(1)
    expected_id_to_name = {c["class_id"]: c["name_internal"] for c in classes}

    all_images = list(common.iter_images(images_dir))
    if not all_images:
        print(f"[AVISO] No se encontraron imagenes ({sorted(common.IMAGE_EXTENSIONS)}) en {images_dir}.")
        sys.exit(0)

    common.ensure_dirs(output_labels_dir)

    to_process = []
    skipped_existing = []
    for img_path in all_images:
        label_path = output_labels_dir / f"{img_path.stem}.txt"
        if common.path_is_file(label_path) and not args.overwrite:
            skipped_existing.append(img_path.name)
            continue
        to_process.append(img_path)

    if skipped_existing:
        print(f"[AVISO] {len(skipped_existing)} imagen(es) ya tenian un .txt en {output_labels_dir} "
              "y se SALTARON (usa --overwrite para regenerarlas):")
        for name in skipped_existing[:20]:
            print(f"  - {name}")
        if len(skipped_existing) > 20:
            print(f"  ... y {len(skipped_existing) - 20} mas")
        print()

    if not to_process:
        print("Nada que procesar (todas las imagenes ya tenian .txt y no se paso --overwrite).")
        sys.exit(0)

    model = load_model_and_verify_classes(weights_path, expected_id_to_name)

    print(f"Pre-etiquetando {len(to_process)} imagen(es) con {weights_path.name} "
          f"(conf={args.conf}, imgsz={args.imgsz}, device={args.device or 'auto'})...\n")

    with_detections = 0
    without_detections = 0
    total_boxes = 0
    boxes_per_class = defaultdict(int)
    conf_sum_per_class = defaultdict(float)
    overwritten = 0

    results = model.predict(
        source=[common.long_path(p) for p in to_process],
        imgsz=args.imgsz,
        conf=args.conf,
        device=args.device,
        verbose=False,
        stream=True,
    )

    for img_path, result in zip(to_process, results):
        label_path = output_labels_dir / f"{img_path.stem}.txt"
        was_existing = common.path_is_file(label_path)

        lines = []
        n_boxes = len(result.boxes)
        for box in result.boxes:
            class_id = int(box.cls[0])
            conf = float(box.conf[0])
            x, y, w, h = (float(v) for v in box.xywhn[0])  # ya normalizado 0..1, centro
            lines.append(f"{class_id} {x:.6f} {y:.6f} {w:.6f} {h:.6f}")
            boxes_per_class[class_id] += 1
            conf_sum_per_class[class_id] += conf
            total_boxes += 1

        content = "\n".join(lines) + ("\n" if lines else "")
        with open(common.long_path(label_path), "w", encoding="utf-8") as f:
            f.write(content)

        if was_existing:
            overwritten += 1

        if n_boxes > 0:
            with_detections += 1
            print(f"  {img_path.name}: {n_boxes} deteccion(es)")
        else:
            without_detections += 1
            print(f"  {img_path.name}: SIN DETECCIONES (conf < {args.conf}) — .txt vacio generado")

    print("\n" + "=" * 70)
    print("RESUMEN — PRE-ETIQUETAS (PRELIMINARES, requieren revision manual)")
    print("=" * 70)
    print(f"Imagenes procesadas:        {len(to_process)}")
    print(f"  con deteccion:            {with_detections}")
    print(f"  sin deteccion:            {without_detections}")
    print(f"Imagenes saltadas (ya tenian .txt): {len(skipped_existing)}")
    if args.overwrite:
        print(f"  de las cuales sobrescritas: {overwritten}")
    print(f"Total de bounding boxes generados: {total_boxes}")

    print("\nDetecciones por clase (cantidad, confianza media):")
    for class_id in range(len(classes)):
        n = boxes_per_class.get(class_id, 0)
        name = expected_id_to_name[class_id]
        if n:
            mean_conf = conf_sum_per_class[class_id] / n
            print(f"  {class_id}  {name:35} {n:4d} boxes   conf. media={mean_conf:.3f}")
        else:
            print(f"  {class_id}  {name:35}    0 boxes")

    print("\n" + "=" * 70)
    print("ESTAS ETIQUETAS SON PRE-ETIQUETAS, NO DEFINITIVAS.")
    print(f"Revisalas manualmente en LabelImg antes de usarlas (pasa --output-labels-dir")
    print(f"como TERCER argumento para que LabelImg cargue/guarde ahi, no en images-dir):")
    print(f"  ml\\venv_labelimg\\Scripts\\labelImg.exe \"{images_dir}\" "
          f"\"{common.LABELS_TXT}\" \"{output_labels_dir}\"")
    print("(LabelImg cargara automaticamente estos .txt como punto de partida —")
    print(" corrige cajas, corrige clases, borra falsos positivos, agrega cajas faltantes.)")
    print("Recien despues de revisar y validar (validate_labels.py) se deben")
    print("incorporar al dataset con prepare_dataset.py. Este script NO lo hace.")
    print("=" * 70)


if __name__ == "__main__":
    main()
