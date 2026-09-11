> **HISTÓRICO — NO es el modelo final.** Estas celdas corresponden a una corrida baseline
> anterior (`yolo11s_final_17clases`, sobre una versión previa del dataset: train=1537/
> val=330/test=328, antes de llegar a las 2229 imágenes finales 1561/335/333). El modelo
> realmente integrado en Android (`app/src/main/assets/best.tflite`) es **YOLO11m**,
> entrenado en una corrida posterior y distinta (`yolo11m_17clases_FINAL`), confirmado por:
> - `resultados_finales_yolo11m_17clases.txt` (fuera de este repo, en la carpeta local de
>   entrenamiento): dice explícitamente "Modelo: YOLO11m", 17 clases, y reporta exactamente
>   las métricas finales publicadas en el `README.md` de la raíz (Precision 98.81 %, Recall
>   97.15 %, mAP50 98.81 %, mAP50-95 83.76 %, F1 97.97 %), con ruta de origen
>   `.../YOLO_Biotecnologia/yolo11m_17clases_FINAL/weights/best.pt`.
> - El `best.tflite` de esa misma carpeta es, por hash SHA256, **byte a byte idéntico** al
>   `best.tflite` integrado en `app/src/main/assets/`.
> - El tamaño de esos archivos (`best.pt` ≈ 40.5 MB fp16, `best.tflite` ≈ 80.5 MB float32)
>   coincide con los parámetros oficiales de YOLO11m (20.1 M), no con YOLO11s (9.4 M).
>
> Se conserva este documento como referencia histórica del experimento baseline, no como
> procedimiento vigente.

# Celdas Colab — YOLO11s baseline (yolo11s_final_17clases)

## Celda 1 — Montar Google Drive
```python
from google.colab import drive
drive.mount('/content/drive')
```

## Celda 2 — Comprobar GPU
```python
!nvidia-smi
```

## Celda 3 — Instalar/actualizar Ultralytics
```python
!pip install -U ultralytics
import ultralytics
ultralytics.checks()
```

## Celda 4 — Comprobar que Colab ve dataset_final en Drive
```python
import os
DRIVE_DATASET = "/content/drive/MyDrive/YOLO_Biotecnologia/dataset_final"
for split in ["train", "val", "test"]:
    n_img = len(os.listdir(f"{DRIVE_DATASET}/images/{split}"))
    n_lbl = len(os.listdir(f"{DRIVE_DATASET}/labels/{split}"))
    print(f"{split}: images={n_img} labels={n_lbl}")
print("classes.txt existe:", os.path.exists(f"{DRIVE_DATASET}/classes.txt"))
print("data.yaml existe:", os.path.exists(f"{DRIVE_DATASET}/data.yaml"))
```
Debe mostrar: train=1537, val=330, test=328.

## Celda 5 — Copiar dataset de Drive a almacenamiento local de la VM (NO entrenar desde Drive)
```python
import shutil, os

SRC = "/content/drive/MyDrive/YOLO_Biotecnologia/dataset_final"
DST = "/content/dataset_final"

if os.path.exists(DST):
    shutil.rmtree(DST)
shutil.copytree(SRC, DST)

for split in ["train", "val", "test"]:
    n_img = len(os.listdir(f"{DST}/images/{split}"))
    print(f"{split}: {n_img} imagenes copiadas localmente")
```
Debe mostrar: train=1537, val=330, test=328.

## Celda 6 — Ajustar data.yaml para que apunte a /content (rutas absolutas locales)
```python
data_yaml = f"""train: {DST}/images/train
val: {DST}/images/val
test: {DST}/images/test
nc: 17
names:
  0: autoclave_vapor_mesa_gemmy_sturdy
  1: cabina_flujo_laminar_mini_c4
  2: centrifuga_laboratorio_ohaus
  3: medidor_mesa_electroquimica_ohaus
  4: termociclador_miniamp_plus
  5: bano_maria_memmert
  6: cubeta_electroforesis_horizontal_gel
  7: espectrofotometro_visible_digital_unico
  8: horno_secado_conveccion_forzada_redline
  9: medidor_demanda_bioquimica_oxigeno
  10: balanza_analitica_ohaus
  11: camara_incubacion_uv_prc_workstation
  12: estufa_laboratorio_doble_puerta
  13: horno_secado_biobase
  14: incubador_agitacion_orbital_incu_shaker
  15: microscopio_boeco
  16: camara_seguridad_biologica_biobase
"""
with open(f"{DST}/data.yaml", "w") as f:
    f.write(data_yaml)
print(open(f"{DST}/data.yaml").read())
```

## Celda 7 — Entrenar YOLO11s (linea base, hiperparametros normales de Ultralytics)
```python
from ultralytics import YOLO

model = YOLO("yolo11s.pt")

results = model.train(
    data=f"{DST}/data.yaml",
    epochs=100,
    imgsz=640,
    seed=42,
    device=0,
    batch=-1,          # AutoBatch; si falla por memoria, cambia a batch=16
    project="/content/drive/MyDrive/YOLO_Biotecnologia",
    name="yolo11s_final_17clases",
)
```
Si `batch=-1` da error de memoria/compatibilidad, vuelve a ejecutar la celda con `batch=16`.

Ultralytics ya guarda automáticamente en Drive (por `project=".../YOLO_Biotecnologia"`) todo lo generado: `weights/best.pt`, `weights/last.pt`, `results.csv`, `results.png`, `confusion_matrix.png`, `confusion_matrix_normalized.png`, `PR_curve.png`, `P_curve.png`, `R_curve.png`, `F1_curve.png`.

## Celda 8 — Copiar explícitamente best.pt a la ruta pedida
```python
import shutil, os

best_src = "/content/drive/MyDrive/YOLO_Biotecnologia/yolo11s_final_17clases/weights/best.pt"
dest_dir = "/content/drive/MyDrive/YOLO_Biotecnologia/modelos"
os.makedirs(dest_dir, exist_ok=True)
dest_path = f"{dest_dir}/yolo11s_final_17clases_best.pt"
shutil.copy2(best_src, dest_path)
print("Guardado en:", dest_path)
```

## Celda 9 — Evaluación final sobre TEST (no solo val)
```python
from ultralytics import YOLO

model_best = YOLO(best_src)

test_results = model_best.val(
    data=f"{DST}/data.yaml",
    split="test",
    imgsz=640,
    device=0,
    project="/content/drive/MyDrive/YOLO_Biotecnologia",
    name="yolo11s_final_17clases_TEST",
)

print("Precision:", test_results.box.mp)
print("Recall:", test_results.box.mr)
print("mAP50:", test_results.box.map50)
print("mAP50-95:", test_results.box.map)

names = model_best.names
for i, ap50 in enumerate(test_results.box.ap50):
    print(f"{i:2d} {names[i]:45s} AP50={ap50:.4f}")
```
Esto guarda automáticamente los resultados de TEST (matriz de confusión, curvas, métricas por clase) en `/content/drive/MyDrive/YOLO_Biotecnologia/yolo11s_final_17clases_TEST/`.
