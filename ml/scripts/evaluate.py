"""Evalua un modelo YOLO11n entrenado sobre el conjunto TEST (nunca usado en entrenamiento).

Reporta, tal cual los devuelve Ultralytics (sin inventar ni ajustar numeros):
    - precision, recall
    - mAP50, mAP50-95
    - metricas por clase
    - matriz de confusion (guardada como imagen por Ultralytics en el directorio de resultados)

Guarda un reporte JSON con las metricas en el mismo directorio de resultados.

Uso:
    python scripts/evaluate.py --weights runs/train/weights/best.pt
"""
import argparse
import json
import sys
from pathlib import Path

import common


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", required=True, help="Ruta a best.pt (o last.pt) entrenado")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--device", default=None)
    parser.add_argument("--project", default=str(common.RUNS_DIR))
    parser.add_argument("--name", default="evaluate")
    args = parser.parse_args()

    weights_path = Path(args.weights)
    if not weights_path.exists():
        print(f"[ERROR] No existe el archivo de pesos: {weights_path}")
        sys.exit(1)

    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] El paquete 'ultralytics' no esta instalado. pip install -r requirements.txt")
        sys.exit(1)

    id_to_name = common.class_names_by_id()

    model = YOLO(str(weights_path))
    print(f"Evaluando {weights_path} sobre el split 'test' de {common.DATA_YAML}...\n")

    metrics = model.val(
        data=str(common.DATA_YAML),
        split="test",
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        project=args.project,
        name=args.name,
        plots=True,
    )

    box = metrics.box
    report = {
        "weights": str(weights_path),
        "split": "test",
        "precision_mean": float(box.mp),
        "recall_mean": float(box.mr),
        "map50": float(box.map50),
        "map50_95": float(box.map),
        "per_class": {},
    }

    class_indices = list(getattr(metrics, "ap_class_index", []))
    for i, class_id in enumerate(class_indices):
        class_id = int(class_id)
        report["per_class"][id_to_name.get(class_id, str(class_id))] = {
            "class_id": class_id,
            "precision": float(box.p[i]) if i < len(box.p) else None,
            "recall": float(box.r[i]) if i < len(box.r) else None,
            "ap50": float(box.ap50[i]) if i < len(box.ap50) else None,
            "ap50_95": float(box.ap[i]) if i < len(box.ap) else None,
        }

    save_dir = Path(metrics.save_dir)
    report_path = save_dir / "metrics_report.json"
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("\n=== Metricas (conjunto TEST, valores reales devueltos por Ultralytics) ===")
    print(f"  Precision (media): {report['precision_mean']:.4f}")
    print(f"  Recall (media):    {report['recall_mean']:.4f}")
    print(f"  mAP50:             {report['map50']:.4f}")
    print(f"  mAP50-95:          {report['map50_95']:.4f}")
    print("\n  Por clase:")
    for name, m in report["per_class"].items():
        print(f"    {name:35} P={m['precision']:.3f} R={m['recall']:.3f} "
              f"AP50={m['ap50']:.3f} AP50-95={m['ap50_95']:.3f}")

    print(f"\nMatriz de confusion y graficos guardados en: {save_dir}")
    print(f"Reporte JSON guardado en: {report_path}")


if __name__ == "__main__":
    main()
