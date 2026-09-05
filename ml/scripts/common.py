"""Utilidades compartidas por todos los scripts de ml/.

Lee siempre classes.json y detector_config.json como fuentes unicas de verdad.
Ningun otro script debe declarar manualmente la lista de clases, sus IDs o el
threshold de confianza: todos importan desde aqui.
"""
import hashlib
import json
import os
import shutil
from pathlib import Path

ML_ROOT = Path(__file__).resolve().parent.parent
CLASSES_JSON = ML_ROOT / "classes.json"
DETECTOR_CONFIG_JSON = ML_ROOT / "detector_config.json"
RAW_SOURCE_MAP_JSON = ML_ROOT / "raw_source_map.json"
DATA_YAML = ML_ROOT / "data.yaml"
LABELS_TXT = ML_ROOT / "labels.txt"
RAW_DIR = ML_ROOT / "raw"
DATASET_DIR = ML_ROOT / "dataset"
RUNS_DIR = ML_ROOT / "runs"
MODELS_DIR = ML_ROOT / "models"
INFERENCE_SAMPLES_DIR = ML_ROOT / "inference_samples"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
SPLITS = ("train", "val", "test")

DEFAULT_SEED = 42
SPLIT_RATIOS = {"train": 0.70, "val": 0.15, "test": 0.15}


class DatasetError(Exception):
    """Error de validacion de dataset que debe detener el pipeline."""


def long_path(path):
    """Ruta segura para operaciones de E/S en Windows, incluso cuando supera el
    limite clasico de MAX_PATH (260 caracteres) — algo que SI ocurre en este
    dataset por nombres de carpeta muy descriptivos (p. ej. la carpeta de
    electroforesis). Sin este prefijo, Python reporta silenciosamente que el
    archivo "no existe" o "no es un archivo" en vez de dar un error claro.
    No renombra ni modifica ningun archivo: solo cambia como se le pide al
    sistema operativo que localice la ruta. No-op fuera de Windows.
    """
    p = str(Path(path).resolve())
    if os.name == "nt" and not p.startswith("\\\\?\\"):
        if p.startswith("\\\\"):
            p = "\\\\?\\UNC\\" + p[2:]
        else:
            p = "\\\\?\\" + p
    return p


def path_is_file(path):
    return os.path.isfile(long_path(path))


def path_exists(path):
    return os.path.exists(long_path(path))


def open_image(path):
    """Abre una imagen con Pillow, seguro ante rutas largas en Windows."""
    from PIL import Image
    return Image.open(long_path(path))


def check_image_corrupt(path):
    """None si Pillow no esta instalado; False si la imagen esta bien; un str
    con el error si la imagen esta corrupta o no se pudo leer."""
    try:
        from PIL import Image  # noqa: F401  (solo para detectar disponibilidad)
    except ImportError:
        return None
    try:
        with open_image(path) as img:
            img.verify()
        return False
    except Exception as e:
        return str(e)


def read_text(path, encoding="utf-8"):
    with open(long_path(path), "r", encoding=encoding) as f:
        return f.read()


def copy_file(src, dst):
    shutil.copy2(long_path(src), long_path(dst))


def load_classes():
    """Devuelve la lista de clases (dicts) ordenada por class_id, validando
    que los IDs sean unicos, contiguos desde 0 y esten ordenados.
    """
    with open(CLASSES_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)

    classes = sorted(data["classes"], key=lambda c: c["class_id"])
    ids = [c["class_id"] for c in classes]

    if len(set(ids)) != len(ids):
        raise DatasetError(f"class_id duplicados en classes.json: {ids}")
    if ids != list(range(len(ids))):
        raise DatasetError(
            f"class_id deben ser contiguos empezando en 0. Encontrado: {ids}. "
            "Si acabas de agregar una clase nueva, revisa que su class_id sea "
            "exactamente el siguiente disponible (nunca reordenes las existentes)."
        )

    names = [c["name_internal"] for c in classes]
    if len(set(names)) != len(names):
        raise DatasetError(f"name_internal duplicados en classes.json: {names}")

    return classes


def load_areas():
    with open(CLASSES_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data["areas"]


def class_names_by_id():
    return {c["class_id"]: c["name_internal"] for c in load_classes()}


def class_id_by_name():
    return {c["name_internal"]: c["class_id"] for c in load_classes()}


def load_raw_source_map():
    """Devuelve {name_internal: ruta_relativa_real} si ml/raw_source_map.json existe,
    o {} si no (por ejemplo, para clases nuevas cuya carpeta real aun no se ha mapeado).
    """
    if not path_exists(RAW_SOURCE_MAP_JSON):
        return {}
    with open(RAW_SOURCE_MAP_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("classes", {})


def resolve_class_source_dir(raw_dir, cls, source_map=None):
    """Resuelve la carpeta real de una clase dentro de raw_dir.

    Prioridad: raw_source_map.json (rutas reales con nombres humanos) > carpeta
    plana raw_dir/<raw_folder> (convencion simple, usada por defecto para clases
    nuevas que aun no tienen una carpeta real mapeada).
    """
    if source_map is None:
        source_map = load_raw_source_map()
    rel = source_map.get(cls["name_internal"])
    if rel:
        return Path(raw_dir) / rel
    return Path(raw_dir) / cls["raw_folder"]


def load_detector_config():
    with open(DETECTOR_CONFIG_JSON, "r", encoding="utf-8") as f:
        return json.load(f)


def generate_data_yaml(write=True):
    """Genera el contenido de data.yaml a partir de classes.json.

    data.yaml es un artefacto DERIVADO: no lo edites a mano, vuelve a correr
    este generador (o sync_classes.py) despues de tocar classes.json.

    A PROPOSITO no incluye una clave 'path:' absoluta. Ultralytics, cuando
    'path' no esta presente, usa el directorio que contiene al propio
    data.yaml como raiz del dataset (ver ultralytics/data/utils.py,
    check_det_dataset: `Path(data.get("path") or Path(data["yaml_file"]).parent)`).
    Con train/val/test apuntando a 'dataset/images/...' relativo a esa raiz,
    el mismo data.yaml funciona sin cambios tanto en este equipo (Windows)
    como subido tal cual a Google Colab (Linux) u otra maquina, sin importar
    el directorio de trabajo desde el que se invoque train.py.
    """
    classes = load_classes()
    lines = [
        "# GENERADO desde classes.json por scripts/common.py / scripts/sync_classes.py",
        "# No editar a mano: los cambios se perderan en la siguiente regeneracion.",
        "# Sin 'path:' a proposito (portable Windows/Colab/Linux) - ver docstring de",
        "# common.generate_data_yaml(). La raiz del dataset es la carpeta de este archivo.",
        "train: dataset/images/train",
        "val: dataset/images/val",
        "test: dataset/images/test",
        "names:",
    ]
    for c in classes:
        lines.append(f"  {c['class_id']}: {c['name_internal']}")
    content = "\n".join(lines) + "\n"

    if write:
        DATA_YAML.write_text(content, encoding="utf-8")
    return content


def generate_labels_txt(write=True):
    """Genera labels.txt (una clase por linea, en orden de class_id) desde
    classes.json. Este es el archivo que se copia a app/src/main/assets/.
    """
    classes = load_classes()
    content = "\n".join(c["name_internal"] for c in classes) + "\n"
    if write:
        LABELS_TXT.write_text(content, encoding="utf-8")
    return content


def sha256_of_file(path, chunk_size=1024 * 1024):
    h = hashlib.sha256()
    with open(long_path(path), "rb") as f:
        for chunk in iter(lambda: f.read(chunk_size), b""):
            h.update(chunk)
    return h.hexdigest()


def iter_images(directory):
    directory = Path(directory)
    for path in sorted(directory.rglob("*")):
        if path.suffix.lower() in IMAGE_EXTENSIONS and path_is_file(path):
            yield path


def ensure_dirs(*dirs):
    for d in dirs:
        Path(d).mkdir(parents=True, exist_ok=True)


LONG_PATH_WARNING_THRESHOLD = 240  # por debajo del limite MAX_PATH real (260) de Windows

LABELS_DIR_SUFFIX = " - txt"


def derive_labels_dir(images_dir):
    """Convencion del proyecto para el etiquetado manual con LabelImg: la
    carpeta de labels es la carpeta HERMANA de la carpeta de imagenes, con el
    sufijo ' - txt' (p. ej. '.../UV PCR Workstation' -> '.../UV PCR Workstation - txt').
    """
    images_dir = Path(images_dir)
    return images_dir.parent / f"{images_dir.name}{LABELS_DIR_SUFFIX}"


def find_all_image_dirs(raw_dir):
    """Todas las carpetas que contienen al menos una imagen (para comparar
    carpeta a carpeta contra las rutas resueltas de cada clase)."""
    dirs = set()
    for img in iter_images(raw_dir):
        dirs.add(img.parent.resolve())
    return dirs


def scan_raw_dataset(raw_dir, classes=None, source_map=None):
    """Escanea raw_dir (fotos originales) y devuelve el mismo dict de reporte
    que usa scripts/validate_raw_dataset.py: conteo por clase, formatos
    invalidos, corruptas, duplicados (dentro y entre clases), carpetas sin
    mapear, clases sin carpeta o vacias, y archivos con ruta larga. Solo lee,
    nunca modifica ni elimina nada. Compartido con scripts/validate_labeling_progress.py
    para no duplicar esta logica.
    """
    from collections import defaultdict

    raw_dir = Path(raw_dir)
    classes = classes if classes is not None else load_classes()
    source_map = source_map if source_map is not None else load_raw_source_map()

    report = {
        "raw_dir": str(raw_dir),
        "classes": {},
        "unmapped_image_dirs": [],
        "missing_classes": [],
        "empty_classes": [],
        "long_path_files": [],
    }

    hashes_global = defaultdict(list)
    resolved_dirs = {}

    for cls in classes:
        name = cls["name_internal"]
        class_dir = resolve_class_source_dir(raw_dir, cls, source_map)
        dir_found = path_exists(class_dir)
        resolved_dirs[name] = class_dir.resolve() if dir_found else None

        class_report = {
            "class_id": cls["class_id"],
            "resolved_dir": str(class_dir),
            "found": dir_found,
            "total_images": 0,
            "invalid_format_files": [],
            "corrupt_images": [],
            "duplicates_within_class": [],
        }

        if not dir_found:
            report["missing_classes"].append(name)
            report["classes"][name] = class_report
            continue

        all_files = [p for p in sorted(class_dir.rglob("*")) if path_is_file(p)]
        hashes_local = defaultdict(list)

        for f in all_files:
            if f.suffix.lower() not in IMAGE_EXTENSIONS:
                class_report["invalid_format_files"].append(str(f.relative_to(class_dir)))
                continue

            class_report["total_images"] += 1

            full_len = len(str(f.resolve()))
            if full_len >= LONG_PATH_WARNING_THRESHOLD:
                report["long_path_files"].append({"class": name, "file": str(f), "length": full_len})

            corrupt = check_image_corrupt(f)
            if corrupt:
                class_report["corrupt_images"].append({"file": str(f.relative_to(class_dir)), "error": corrupt})
                continue

            digest = sha256_of_file(f)
            hashes_local[digest].append(str(f.relative_to(class_dir)))
            hashes_global[digest].append((name, str(f)))

        for digest, names in hashes_local.items():
            if len(names) > 1:
                class_report["duplicates_within_class"].append(names)

        if class_report["total_images"] == 0:
            report["empty_classes"].append(name)

        report["classes"][name] = class_report

    all_image_dirs = find_all_image_dirs(raw_dir)
    claimed_dirs = {d for d in resolved_dirs.values() if d is not None}
    for d in sorted(all_image_dirs):
        if d not in claimed_dirs:
            report["unmapped_image_dirs"].append(str(d))

    report["duplicates_across_classes"] = [
        [path for _, path in entries]
        for entries in hashes_global.values()
        if len({class_name for class_name, _ in entries}) > 1
    ]

    return report
