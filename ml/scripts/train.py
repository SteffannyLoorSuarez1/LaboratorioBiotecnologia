"""Entrena el modelo YOLO11n sobre ml/dataset/.

Modelo oficial del proyecto: YOLO11n (yolo11n.pt), elegido por estabilidad de
entrenamiento, exportacion LiteRT/TFLite e integracion en telefonos Android
reales frente a versiones mas nuevas (ver ml/README.md, seccion "Decision de
modelo"). No cambiar de familia sin detectar primero un impedimento tecnico
real y comprobado, y reportarlo antes de modificar nada.

Por defecto corre una validacion de labels (scripts/validate_labels.py) antes
de entrenar y ABORTA si hay errores graves. Usa --skip-validation solo si ya
validaste manualmente.

No asume GPU NVIDIA: detecta el dispositivo disponible con torch y avisa si
el entrenamiento correra en CPU (lento). Si no tienes GPU adecuada, entrena en
Google Colab en su lugar — ver la seccion "Entrenar en Google Colab" de
ml/README.md; este mismo script y data.yaml funcionan sin cambios alli.

Uso:
    python scripts/train.py --epochs 100 --imgsz 640 --batch 16
    python scripts/train.py --epochs 5 --imgsz 640 --batch 8   # prueba rapida/smoke test
"""
import argparse
import subprocess
import sys

import common


def check_ultralytics():
    try:
        import ultralytics
    except ImportError:
        print("[ERROR] El paquete 'ultralytics' no esta instalado.")
        print("        Instala las dependencias con: pip install -r requirements.txt")
        sys.exit(1)
    return ultralytics.__version__


def detect_device():
    try:
        import torch
    except ImportError:
        print("[AVISO] PyTorch no esta instalado todavia (lo instala 'ultralytics' como dependencia).")
        return "cpu"

    if torch.cuda.is_available():
        name = torch.cuda.get_device_name(0)
        print(f"GPU NVIDIA detectada: {name}. Se usara device=0 (CUDA).")
        return "0"

    print("[AVISO] No se detecto GPU NVIDIA/CUDA en este equipo. El entrenamiento correra en CPU "
          "y sera considerablemente mas lento. Para entrenamientos largos se recomienda usar "
          "Google Colab (ver ml/README.md, seccion 'Entrenar en Google Colab').")
    return "cpu"


def run_label_validation():
    print("Validando dataset/labels antes de entrenar (scripts/validate_labels.py)...\n")
    result = subprocess.run([sys.executable, str(common.ML_ROOT / "scripts" / "validate_labels.py")])
    if result.returncode != 0:
        print("\n[ERROR] La validacion de labels encontro errores graves. NO se iniciara el "
              "entrenamiento. Corrige el dataset (o vuelve a correr prepare_dataset.py) y reintenta.")
        sys.exit(1)
    print()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="yolo11n.pt",
                         help="Pesos base (default: yolo11n.pt; se descarga automaticamente si "
                              "no existe localmente y hay conexion a internet)")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--device", default=None, help="Forzar device ('0', 'cpu', etc.). Default: auto-detectado")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--seed", type=int, default=common.DEFAULT_SEED)
    parser.add_argument("--project", default=str(common.RUNS_DIR))
    parser.add_argument("--name", default="train")
    parser.add_argument("--exist-ok", action="store_true",
                         help="Permitir sobrescribir/reusar la carpeta de resultados si ya existe "
                              "(default: NO, ultralytics crea '<name>2', '<name>3', etc. en su lugar)")
    parser.add_argument("--skip-validation", action="store_true",
                         help="Omite la validacion automatica de labels antes de entrenar (no recomendado)")
    args = parser.parse_args()

    version = check_ultralytics()
    print(f"ultralytics version instalada: {version}")

    if not common.DATA_YAML.exists():
        print("[ERROR] No existe ml/data.yaml. Corre: python scripts/sync_classes.py")
        sys.exit(1)

    if not args.skip_validation:
        run_label_validation()
    else:
        print("[AVISO] Validacion de labels omitida (--skip-validation).")

    device = args.device or detect_device()

    from ultralytics import YOLO

    print(f"\nCargando modelo base: {args.model}")
    model = YOLO(args.model)

    print(f"Iniciando entrenamiento: epochs={args.epochs} imgsz={args.imgsz} batch={args.batch} "
          f"device={device} workers={args.workers} seed={args.seed}")
    model.train(
        data=str(common.DATA_YAML),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=device,
        workers=args.workers,
        seed=args.seed,
        project=args.project,
        name=args.name,
        exist_ok=args.exist_ok,
        pretrained=True,
    )

    print("\nEntrenamiento finalizado. Revisa los resultados en:")
    print(f"  {args.project}/{args.name}/")
    print("Pesos: weights/best.pt y weights/last.pt")
    print("Siguiente paso: python scripts/evaluate.py --weights <ruta a best.pt>")


if __name__ == "__main__":
    main()
