"""Exporta un modelo YOLO11n entrenado (best.pt) a LiteRT/TFLite para Android.

IMPORTANTE (verificado contra la documentacion oficial de Ultralytics):
    - El export a LiteRT/TFLite (format="litert", que reemplaza al antiguo
      format="tflite") solo esta soportado oficialmente en Linux x86_64 y
      macOS. En Windows este paso puede fallar o no estar soportado.
    - Si falla aqui, exporta en Google Colab (Linux) en su lugar: sube
      best.pt, instala `ultralytics`, corre este mismo script alli, y
      descarga best.tflite. Ver ml/README.md, seccion "Exportar en Google
      Colab".

Este script NUNCA genera un archivo best.tflite falso. Si la exportacion
falla, no se crea ni se copia ningun archivo a ml/models/.

Tras exportar, valida que el archivo resultante:
    - existe y no esta vacio
    - puede abrirse como interprete TFLite (si hay un runtime disponible)

Uso:
    python scripts/export_tflite.py --weights runs/train/weights/best.pt
"""
import argparse
import shutil
import sys
from pathlib import Path

import common


def validate_tflite_file(tflite_path):
    """Intenta cargar el .tflite con un interprete real. Devuelve (ok, detalle)."""
    if not tflite_path.exists() or tflite_path.stat().st_size == 0:
        return False, "El archivo no existe o esta vacio."

    interpreter_cls = None
    try:
        from tensorflow.lite import Interpreter as interpreter_cls  # type: ignore
    except ImportError:
        try:
            from ai_edge_litert.interpreter import Interpreter as interpreter_cls  # type: ignore
        except ImportError:
            try:
                import tflite_runtime.interpreter as tflite_rt
                interpreter_cls = tflite_rt.Interpreter
            except ImportError:
                interpreter_cls = None

    if interpreter_cls is None:
        size_kb = tflite_path.stat().st_size / 1024
        return True, (f"Archivo presente ({size_kb:.0f} KB). No se pudo cargar con un interprete "
                       "TFLite real (ninguno instalado): validacion parcial, solo de existencia/tamano.")

    try:
        interp = interpreter_cls(model_path=str(tflite_path))
        interp.allocate_tensors()
        input_details = interp.get_input_details()
        output_details = interp.get_output_details()
        return True, (f"Interprete cargado correctamente. Entradas: {len(input_details)}, "
                       f"salidas: {len(output_details)}.")
    except Exception as e:
        return False, f"El archivo existe pero no se pudo cargar como modelo TFLite valido: {e}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", required=True, help="Ruta a best.pt entrenado")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--no-copy-to-models", action="store_true",
                         help="No copiar el resultado a ml/models/ (solo exportar)")
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

    print(f"Exportando {weights_path} a LiteRT/TFLite (imgsz={args.imgsz})...")
    model = YOLO(str(weights_path))

    try:
        exported_path = model.export(format="litert", imgsz=args.imgsz)
    except Exception as e:
        print(f"\n[ERROR] La exportacion fallo: {e}")
        print("\nSi esto ocurrio en Windows, es esperado: LiteRT/TFLite export solo esta soportado "
              "oficialmente en Linux x86_64 y macOS. Exporta en Google Colab en su lugar "
              "(ver ml/README.md, seccion 'Exportar en Google Colab'). No se genero ningun archivo.")
        sys.exit(1)

    exported_path = Path(exported_path)
    if not exported_path.exists():
        print(f"[ERROR] Ultralytics reporto exito pero no se encontro el archivo esperado: {exported_path}")
        sys.exit(1)

    ok, detail = validate_tflite_file(exported_path)
    print(f"\nArchivo exportado: {exported_path}")
    print(f"Validacion: {'OK' if ok else 'FALLO'} - {detail}")

    if not ok:
        print("\n[ERROR] El .tflite exportado no paso la validacion. No se copiara a ml/models/.")
        sys.exit(1)

    if args.no_copy_to_models:
        print("\n--no-copy-to-models indicado: el archivo queda solo en la ruta de exportacion de arriba.")
        return

    common.ensure_dirs(common.MODELS_DIR)
    dest_tflite = common.MODELS_DIR / "best.tflite"
    dest_pt = common.MODELS_DIR / "best.pt"
    shutil.copy2(exported_path, dest_tflite)
    shutil.copy2(weights_path, dest_pt)

    print(f"\nCopiado a:\n  {dest_pt}\n  {dest_tflite}")
    print("\nSiguiente paso: python scripts/copy_model_to_android.py")


if __name__ == "__main__":
    main()
