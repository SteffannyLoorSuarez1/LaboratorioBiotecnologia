"""Genera el dataset final (train/val/test) a partir de las 7 clases YA ETIQUETADAS
y ya validadas con validate_labeling_progress.py.

Recorre classes.json y, para cada clase, resuelve su carpeta real de imagenes
(via raw_source_map.json) y su carpeta de labels por CONVENCION: la carpeta
hermana con sufijo " - txt" (la misma que usa validate_labeling_progress.py).
NUNCA modifica ni borra esas carpetas de entrada: solo lee.

Split ESTRATIFICADO POR CLASE (no global): cada clase se divide de forma
independiente en ~70% train / ~15% val / ~15% test, garantizando al menos 1
imagen en val y en test aunque la clase tenga pocas fotos (p. ej. las 6 de
uv_pcr_workstation quedan repartidas en las 3 particiones en vez de arriesgarse
a que el redondeo global la deje fuera de alguna).

Dentro de cada clase, agrupa imagenes casi-identicas (rafagas de camara) con
un hash perceptual (average hash 8x8) para que un grupo de rafaga quede
COMPLETO dentro de un unico split — evita data leakage por fotos casi iguales
repartidas entre train/val/test. Los grupos se asignan con una semilla fija
(reproducible), priorizando cubrir los cupos de val y test antes que train.

Tras copiar, corre automaticamente una validacion completa del dataset
generado (equivalente a validate_labels.py en modo default) MAS una
comprobacion de duplicados exactos (hash SHA-256) entre train/val/test, para
detectar cualquier fuga de datos entre particiones.

IMPORTANTE — regeneracion:
    Cada corrida RECONSTRUYE ml/dataset/ completo desde el conjunto de
    imagenes etiquetadas actual (se borra el contenido previo de
    ml/dataset/images/ y ml/dataset/labels/, nunca las carpetas originales).
    Con la misma semilla y el mismo conjunto de fotos, el resultado es
    reproducible; si agregas fotos nuevas, el split se recalcula sobre el
    total disponible en ese momento (no es incremental).

Uso:
    python scripts/prepare_dataset.py
    python scripts/prepare_dataset.py --seed 42 --raw-root "C:\\ruta\\...\\RECOPILACION..."
"""
import argparse
import json
import random
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

import common
import validate_labels

try:
    import PIL  # noqa: F401
    PILLOW_AVAILABLE = True
except ImportError:
    PILLOW_AVAILABLE = False


# ---------------------------------------------------------------- hashing ---

def average_hash(path, hash_size=8):
    """Average hash (aHash) simple, solo con Pillow. None si no se pudo abrir."""
    if not PILLOW_AVAILABLE:
        return None
    try:
        from PIL import Image
        with common.open_image(path) as img:
            img = img.convert("L").resize((hash_size, hash_size), Image.LANCZOS)
            pixels = list(img.getdata())
    except Exception:
        return None
    avg = sum(pixels) / len(pixels)
    bits = 0
    for p in pixels:
        bits = (bits << 1) | (1 if p >= avg else 0)
    return bits


def hamming(a, b):
    return bin(a ^ b).count("1")


class UnionFind:
    def __init__(self, n):
        self.parent = list(range(n))

    def find(self, x):
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[ra] = rb


def group_by_similarity(image_paths, hamming_threshold=5):
    """Agrupa indices de image_paths cuyas imagenes son casi identicas."""
    hashes = [average_hash(p) for p in image_paths]
    uf = UnionFind(len(image_paths))
    for i in range(len(image_paths)):
        if hashes[i] is None:
            continue
        for j in range(i + 1, len(image_paths)):
            if hashes[j] is None:
                continue
            if hamming(hashes[i], hashes[j]) <= hamming_threshold:
                uf.union(i, j)
    groups = defaultdict(list)
    for i in range(len(image_paths)):
        groups[uf.find(i)].append(i)
    return list(groups.values())


# ------------------------------------------------------------ split logic ---

def stratified_counts(n, ratios):
    """Cuenta cuantas imagenes de una clase van a train/val/test, garantizando
    al menos 1 en val y en test cuando n >= 3 (aunque el redondeo 70/15/15
    puro diera 0)."""
    if n <= 0:
        return 0, 0, 0
    if n == 1:
        return 1, 0, 0
    if n == 2:
        return 1, 1, 0

    n_val = max(1, round(n * ratios["val"]))
    n_test = max(1, round(n * ratios["test"]))
    n_train = n - n_val - n_test
    while n_train < 1 and (n_val > 1 or n_test > 1):
        if n_val >= n_test and n_val > 1:
            n_val -= 1
        elif n_test > 1:
            n_test -= 1
        n_train = n - n_val - n_test
    return n_train, n_val, n_test


def assign_groups_for_class(groups, target_train, target_val, target_test, seed):
    """Asigna cada grupo (rafaga) de una clase a un split, priorizando cubrir
    los cupos de val/test (los mas fragiles) antes que train."""
    rng = random.Random(seed)
    order = list(range(len(groups)))
    rng.shuffle(order)
    order.sort(key=lambda gi: -len(groups[gi]))  # grupos grandes primero

    remaining = {"train": target_train, "val": target_val, "test": target_test}
    assignment = {}
    for gi in order:
        size = len(groups[gi])
        candidates = [s for s in ("val", "test", "train") if remaining[s] > 0]
        if not candidates:
            candidates = ["train"]
        chosen = max(candidates, key=lambda s: remaining[s])
        assignment[gi] = chosen
        remaining[chosen] -= size
    return assignment


# ------------------------------------------------------------------- main ---

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-root", default=None,
                         help="Carpeta raiz de las fotografias originales (default: root_hint de raw_source_map.json)")
    parser.add_argument("--seed", type=int, default=common.DEFAULT_SEED)
    parser.add_argument("--hamming-threshold", type=int, default=5,
                         help="Distancia maxima de hash para considerar dos imagenes 'rafaga' (default 5/64)")
    args = parser.parse_args()

    try:
        classes = common.load_classes()
    except common.DatasetError as e:
        print(f"[ERROR FATAL] classes.json invalido: {e}")
        sys.exit(1)
    num_classes = len(classes)
    id_to_name = {c["class_id"]: c["name_internal"] for c in classes}

    source_map = common.load_raw_source_map()
    if args.raw_root:
        raw_root = Path(args.raw_root)
    else:
        with open(common.RAW_SOURCE_MAP_JSON, "r", encoding="utf-8") as f:
            raw_root = Path(json.load(f)["root_hint"])

    print(f"Raiz de fotografias originales: {raw_root}")
    print(f"Semilla: {args.seed}\n")

    # --- 1) Recolectar pares (imagen, label) validos por clase ---
    class_pairs = {}   # name -> [(img_path, label_path), ...]
    total_unlabeled = 0

    for cls in classes:
        name = cls["name_internal"]
        images_dir = common.resolve_class_source_dir(raw_root, cls, source_map)
        labels_dir = common.derive_labels_dir(images_dir)

        pairs = []
        if common.path_exists(images_dir):
            for img_path in common.iter_images(images_dir):
                label_path = labels_dir / f"{img_path.stem}.txt"
                if common.path_exists(label_path):
                    pairs.append((img_path, label_path))
                else:
                    total_unlabeled += 1
        class_pairs[name] = pairs
        print(f"  [{cls['class_id']}] {name}: {len(pairs)} pares imagen/label encontrados")

    if total_unlabeled:
        print(f"\n[AVISO] {total_unlabeled} imagen(es) sin label seran IGNORADAS (no entran al dataset).")

    if all(len(p) == 0 for p in class_pairs.values()):
        print("\n[ERROR] No se encontro ningun par imagen/label en ninguna clase. Nada que hacer.")
        sys.exit(1)

    # --- 2) Agrupar por rafaga y asignar splits, POR CLASE ---
    assignment_by_class = {}   # name -> {group_index: split}
    groups_by_class = {}       # name -> [[idx,...], ...]
    counts_by_class = {}       # name -> (n_train, n_val, n_test)

    for cls in classes:
        name = cls["name_internal"]
        pairs = class_pairs[name]
        if not pairs:
            groups_by_class[name] = []
            assignment_by_class[name] = {}
            counts_by_class[name] = (0, 0, 0)
            continue

        image_paths = [p[0] for p in pairs]
        groups = group_by_similarity(image_paths, hamming_threshold=args.hamming_threshold)
        n_train, n_val, n_test = stratified_counts(len(pairs), common.SPLIT_RATIOS)

        # Semilla derivada por clase para que el orden de las clases no afecte
        # el resultado de cada una, pero siga siendo reproducible.
        class_seed = args.seed + cls["class_id"]
        assignment = assign_groups_for_class(groups, n_train, n_val, n_test, class_seed)

        groups_by_class[name] = groups
        assignment_by_class[name] = assignment
        counts_by_class[name] = (n_train, n_val, n_test)

    # --- 3) Limpiar SOLO el dataset generado, nunca las carpetas de entrada ---
    for split in common.SPLITS:
        img_dir = common.DATASET_DIR / "images" / split
        lbl_dir = common.DATASET_DIR / "labels" / split
        if img_dir.exists():
            shutil.rmtree(img_dir)
        if lbl_dir.exists():
            shutil.rmtree(lbl_dir)
        common.ensure_dirs(img_dir, lbl_dir)

    # --- 4) Copiar ---
    copied = 0
    used_names = defaultdict(set)
    per_class_actual = {}   # name -> {split: n_images}
    per_split_class_boxes = {s: Counter() for s in common.SPLITS}

    for cls in classes:
        name = cls["name_internal"]
        pairs = class_pairs[name]
        groups = groups_by_class[name]
        assignment = assignment_by_class[name]
        actual = {s: 0 for s in common.SPLITS}

        for gi, indices in enumerate(groups):
            split = assignment[gi]
            img_dir = common.DATASET_DIR / "images" / split
            lbl_dir = common.DATASET_DIR / "labels" / split
            for idx in indices:
                img_path, label_path = pairs[idx]
                out_name = img_path.stem
                if out_name in used_names[split]:
                    out_name = f"{img_path.parent.name}_{out_name}"
                used_names[split].add(out_name)

                common.copy_file(img_path, img_dir / f"{out_name}{img_path.suffix.lower()}")
                common.copy_file(label_path, lbl_dir / f"{out_name}.txt")
                copied += 1
                actual[split] += 1

                text = common.read_text(label_path).strip()
                n_boxes = len([l for l in text.splitlines() if l.strip()]) if text else 0
                per_split_class_boxes[split][cls["class_id"]] += n_boxes

        per_class_actual[name] = actual

    print(f"\nArchivos copiados a ml/dataset/: {copied} imagenes (+ sus labels)")
    print("ml/raw/ y las carpetas originales no fueron modificadas ni borradas.\n")

    # --- 5) Tabla: clase | total | train | val | test ---
    print("=== clase | total | train | val | test ===")
    for cls in classes:
        name = cls["name_internal"]
        a = per_class_actual[name]
        total = sum(a.values())
        print(f"  {name:30} total={total:>3}  train={a['train']:>3}  val={a['val']:>3}  test={a['test']:>3}")

    # --- 6) Tabla: split | imagenes | labels | porcentaje ---
    print("\n=== split | imagenes | labels | porcentaje ===")
    split_img_counts = {s: sum(per_class_actual[n][s] for n in per_class_actual) for s in common.SPLITS}
    grand_total = sum(split_img_counts.values())
    for s in common.SPLITS:
        n = split_img_counts[s]
        pct = 100 * n / grand_total if grand_total else 0
        # labels == imagenes por construccion (se copian siempre en pareja)
        print(f"  {s:6} imagenes={n:>3}  labels={n:>3}  {pct:5.1f}%")

    # --- 7) Validar el dataset generado (equivalente a validate_labels.py default) ---
    print("\n=== Validacion del dataset generado ===")
    all_errors = []
    all_warnings = []
    total_class_counts = Counter()
    hashes_by_split = {}

    for split in common.SPLITS:
        images_dir = common.DATASET_DIR / "images" / split
        labels_dir = common.DATASET_DIR / "labels" / split
        errors, warnings, counts, n_images = validate_labels.validate_pair(
            images_dir, labels_dir, num_classes, split_name=split)
        all_errors += errors
        all_warnings += warnings
        total_class_counts += counts

        hashes_by_split[split] = {}
        for img_path in common.iter_images(images_dir):
            hashes_by_split[split][common.sha256_of_file(img_path)] = img_path.name

    # --- 8) Fugas entre splits: mismo hash exacto en 2+ splits ---
    leak_pairs = []
    splits_list = list(common.SPLITS)
    for i in range(len(splits_list)):
        for j in range(i + 1, len(splits_list)):
            s1, s2 = splits_list[i], splits_list[j]
            common_hashes = set(hashes_by_split[s1]) & set(hashes_by_split[s2])
            for h in common_hashes:
                leak_pairs.append((s1, hashes_by_split[s1][h], s2, hashes_by_split[s2][h]))

    if all_warnings:
        print(f"Avisos ({len(all_warnings)}):")
        for w in all_warnings[:30]:
            print(f"  - {w}")

    if all_errors:
        print(f"ERRORES ({len(all_errors)}):")
        for e in all_errors[:50]:
            print(f"  - {e}")

    if leak_pairs:
        print(f"[ERROR] {len(leak_pairs)} fuga(s) de datos: la MISMA imagen (hash identico) aparece en 2 splits:")
        for s1, f1, s2, f2 in leak_pairs:
            print(f"  - {s1}/{f1}  ==  {s2}/{f2}")

    print(f"\nTotal imagenes en dataset: {grand_total}")
    print(f"Total labels en dataset:   {grand_total}")
    print(f"Total bounding boxes:      {sum(total_class_counts.values())}")

    print("\n=== Distribucion de bounding boxes por clase y split ===")
    header = f"{'clase':30}" + "".join(f"{s:>8}" for s in common.SPLITS) + f"{'total':>8}"
    print(header)
    for cls in classes:
        row = f"{cls['name_internal']:30}"
        total = 0
        for s in common.SPLITS:
            n = per_split_class_boxes[s].get(cls["class_id"], 0)
            total += n
            row += f"{n:>8}"
        row += f"{total:>8}"
        print(row)

    print(f"\ndata.yaml: {common.DATA_YAML}")
    if common.DATA_YAML.exists():
        print(common.read_text(common.DATA_YAML))
    else:
        print("[AVISO] No existe data.yaml. Corre: python scripts/sync_classes.py")

    ok = not all_errors and not leak_pairs and common.DATA_YAML.exists()
    print()
    if ok:
        print("DATASET 70/15/15 APROBADO")
        sys.exit(0)
    else:
        print("DATASET 70/15/15 NO APROBADO")
        sys.exit(1)


if __name__ == "__main__":
    main()
