# ml/ — Pipeline de detección de equipos (YOLO11n)

Pipeline completo y **reutilizable** para el detector de equipos del
Laboratorio de Biotecnología UTEQ:

```
Fotos reales → dataset YOLO → etiquetas → train/val/test → entrenamiento
→ evaluación → exportación TFLite → app/src/main/assets/ → Android
```

Diseñado para que, cada vez que se agreguen fotografías nuevas, **no haya que
rehacer nada**: solo agregar fotos, etiquetarlas y volver a correr el mismo
pipeline (ver [sección 8](#8-agregar-fotografías-nuevas-en-el-futuro)).

## 0. Decisión de modelo: YOLO11n (oficial, fija)

**YOLO11n (`yolo11n.pt`) es el modelo oficial de este proyecto.** No cambiar
de familia/modelo salvo que aparezca un impedimento técnico real y
comprobado; si ocurre, detenerse y reportarlo antes de modificar nada.

Motivo de la decisión (2026-08-30): se evaluó también YOLO26n (la versión más
reciente de Ultralytics en ese momento) y se descartó porque:

- Existe un bug conocido y confirmado en la documentación/issues oficiales de
  Ultralytics: modelos YOLO26 exportados a TFLite fallan al cargar en el
  **GPU delegate** de Android por operadores INT64/CAST/RESHAPE no
  soportados ([ultralytics#23282](https://github.com/ultralytics/ultralytics/issues/23282),
  cerrado "as not planned").
- YOLO11n no tiene ese problema y tiene un camino de exportación a
  LiteRT/TFLite e integración en Android mucho más probado y estable.

Se prioriza estabilidad de entrenamiento, exportación y funcionamiento real
en teléfonos físicos por encima de usar la versión más nueva.

## 1. Clases y class_id (fuente de verdad: `classes.json`)

**Nunca reordenar ni reutilizar un `class_id` existente.** Están definidos
así hasta que se agreguen clases nuevas al final:

| class_id | nombre interno (YOLO) | nombre visible | área |
|---|---|---|---|
| 0 | `bod_sensor` | BOD Sensor | Cultivo de tejidos vegetales |
| 1 | `lux_fc_meter` | Broad Range LUX/FC Meter | Cultivo de tejidos vegetales |
| 2 | `medidor_multiparametro` | Medidor multiparámetro electroquímico de mesa | Cultivo de tejidos vegetales |
| 3 | `electroforesis_owl_easycast` | Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP | Cultivo de tejidos vegetales |
| 4 | `armario_calefactor_ule600` | Armario calefactor ULE 600 | Microbiología |
| 5 | `horno_secado` | Horno de secado | Microbiología |
| 6 | `uv_pcr_workstation` | UV PCR Workstation | Microbiología |

El área de **Biología molecular** todavía no tiene equipos/fotos: cuando los
haya, se agregan como `class_id: 7, 8, ...` (ver
[sección 9](#9-agregar-una-clase-nueva-en-el-futuro)).

`ml/classes.json` es la **única** fuente de verdad. `ml/data.yaml` (usado por
Ultralytics) y `ml/labels.txt` (usado por Android) se **generan** desde ahí
con `python scripts/sync_classes.py` — nunca se editan a mano.

## 2. Estructura

```
ml/
├── README.md                  # este archivo
├── LABELING_GUIDE.md           # política de etiquetado
├── requirements.txt
├── classes.json                # FUENTE DE VERDAD de clases/áreas
├── detector_config.json        # FUENTE DE VERDAD del confidence threshold (lado ml/)
├── raw_source_map.json         # class -> ruta relativa REAL dentro de la carpeta de fotos
│                                # del usuario (nombres "humanos"; no renombra nada, ver raw/README.md)
├── data.yaml                   # generado desde classes.json (sync_classes.py)
├── labels.txt                  # generado desde classes.json (sync_classes.py)
├── raw/                        # fotos originales por clase (o apuntar --raw-dir a otra ruta)
│   └── <raw_folder por clase>/
├── dataset/                    # GENERADO por prepare_dataset.py — no editar a mano
│   ├── images/{train,val,test}/
│   └── labels/{train,val,test}/
├── scripts/
│   ├── common.py                    # utilidades compartidas (lee classes.json)
│   ├── sync_classes.py              # regenera data.yaml y labels.txt
│   ├── patch_labelimg.py            # corrige un bug real conocido de LabelImg 1.8.6 + PyQt5
│   ├── validate_labeling_progress.py # validacion global de las 7 clases (imagenes+labels reales)
│   ├── make_colab_package.py        # arma ml/colab_training_package.zip para entrenar en Colab
│   ├── validate_raw_dataset.py      # valida fotos originales (sin tocarlas)
│   ├── prepare_dataset.py           # etiquetas -> dataset train/val/test
│   ├── validate_labels.py           # valida el dataset antes de entrenar
│   ├── train.py                     # entrena YOLO11n
│   ├── evaluate.py                  # evalúa sobre test
│   ├── export_tflite.py             # exporta a LiteRT/TFLite + valida
│   └── copy_model_to_android.py     # copia best.tflite + labels.txt reales a Android
├── runs/                        # resultados de entrenamiento/evaluación (generado)
├── models/                      # best.pt / best.tflite REALES (generado, ver models/README.md)
└── inference_samples/           # fotos externas opcionales para probar el modelo
```

## 3. Instalación

```bash
cd ml
python -m venv venv
venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

## 4. Flujo completo (primera vez y cada vez que se agreguen fotos)

```bash
# 1. Validar las fotos originales (no modifica nada)
python scripts/validate_raw_dataset.py --raw-dir "<ruta a tus fotos>"

# 2. Etiquetar (ver LABELING_GUIDE.md) — produce imagen.txt junto a cada imagen.jpg

# 3. Generar el dataset train/val/test desde las fotos ya etiquetadas
python scripts/prepare_dataset.py --labeled-dir "<ruta a tus fotos etiquetadas>"

# 4. Validar el dataset generado (obligatorio antes de entrenar)
python scripts/validate_labels.py

# 5. Entrenar (local si hay GPU/tiempo, o en Colab — ver sección 6)
python scripts/train.py --epochs 100 --imgsz 640 --batch 16

# 6. Evaluar sobre el conjunto TEST (nunca visto en entrenamiento)
python scripts/evaluate.py --weights runs/train/weights/best.pt

# 7. Exportar a LiteRT/TFLite (puede requerir Colab en Windows, ver sección 6)
python scripts/export_tflite.py --weights runs/train/weights/best.pt

# 8. Copiar a Android SOLO si el modelo es real
python scripts/copy_model_to_android.py
```

Cada script se detiene (exit code distinto de 0) ante errores graves en vez
de continuar con datos inválidos.

## 5. Validaciones antes de entrenar

`scripts/validate_labels.py` comprueba, y bloquea el entrenamiento si
encuentra: imágenes sin label, labels sin imagen, `class_id` fuera de rango,
coordenadas fuera de `[0,1]`, `width`/`height` inválidos, labels vacíos
(aviso, no bloquea), imágenes corruptas, y reporta la distribución de
instancias por clase y por split. `scripts/train.py` lo ejecuta
automáticamente antes de entrenar (se puede omitir con `--skip-validation`,
no recomendado).

## 6. Entrenar / exportar en Google Colab

Este equipo no tiene GPU NVIDIA (GPU integrada AMD), el export a
LiteRT/TFLite **solo está soportado oficialmente en Linux x86_64 y macOS**
(no Windows), y además **Smart App Control de Windows bloquea la carga de
`kiwisolver` (dependencia de `matplotlib`, usada por Ultralytics)**, lo que
impide entrenar en este equipo incluso en CPU — confirmado el 2026-08-31, ver
`ml/colab_training_yolo11n.ipynb` para el detalle. Por eso el entrenamiento y
la exportación se hacen en Google Colab (Linux, con GPU gratuita):

1. `python scripts/make_colab_package.py` — genera `ml/colab_training_package.zip`
   con exactamente lo necesario para entrenar (`data.yaml`, `classes.json`,
   `requirements.txt`, `dataset/`, `scripts/{common,validate_labels,train}.py`).
   No incluye fotos originales sin procesar, `runs/`, `venv*/`, Android ni backend.
2. Abrir `ml/colab_training_yolo11n.ipynb` en [Google Colab](https://colab.research.google.com/)
   (subirlo ahí, o abrirlo desde Google Drive). Tiene las celdas ya preparadas:
   activar GPU, subir el `.zip` (por Drive, recomendado para ~500 MB, o subida
   directa), descomprimir, instalar Ultralytics, verificar CUDA/versiones,
   verificar `data.yaml`, entrenar con `scripts/train.py` (el mismo script,
   sin cambios de lógica) y descargar `best.pt`/`last.pt`/resultados al final.
3. `data.yaml` es portable a propósito: no tiene una clave `path:` absoluta,
   así que Ultralytics usa el directorio donde vive el propio `data.yaml`
   como raíz — el mismo archivo funciona igual en Windows y en Colab (ver
   docstring de `common.generate_data_yaml()`).
4. Descargar `best.pt` (y `last.pt`, y el `.zip` de resultados) y colocar
   `best.pt` en `ml/models/best.pt` en este equipo.
5. Revisar las métricas (`ml/scripts/evaluate.py`, cuando se autorice) antes
   de exportar. Para exportar a TFLite: `python scripts/export_tflite.py
   --weights ml/models/best.pt` — puede hacerse en Colab también, con la misma
   limitación de plataforma (Linux) ya cubierta ahí.
6. Colocar `best.pt` + `best.tflite` en `ml/models/` en este equipo y correr
   `python scripts/copy_model_to_android.py`.

Cuando se agreguen más fotos en el futuro y haya que re-entrenar: repetir
`prepare_dataset.py` (local) → `make_colab_package.py` (local) → resubir el
`.zip` nuevo a Colab → reentrenar.

## 7. Métricas

`scripts/evaluate.py` reporta, tal cual los devuelve Ultralytics sobre el
split **test** (nunca usado en entrenamiento): precision, recall, mAP50,
mAP50-95, métricas por clase, y guarda la matriz de confusión (generada por
Ultralytics) además de un `metrics_report.json` con los números exactos. No
se inventan ni se ajustan resultados manualmente.

## 8. Agregar fotografías nuevas en el futuro

No se rehace el proyecto. El flujo es siempre:

1. Agregar las fotos nuevas a la carpeta original correspondiente (o a
   `ml/raw/<clase>/` si las copiaste ahí).
2. Etiquetarlas (`LABELING_GUIDE.md`).
3. `python scripts/validate_raw_dataset.py --raw-dir <ruta>`
4. `python scripts/prepare_dataset.py --labeled-dir <ruta>` (reconstruye
   `ml/dataset/` desde el total de fotos etiquetadas disponibles — ver nota
   de reproducibilidad en el docstring del script)
5. `python scripts/validate_labels.py`
6. `python scripts/train.py ...`
7. `python scripts/evaluate.py --weights ...`
8. `python scripts/export_tflite.py --weights ...`
9. `python scripts/copy_model_to_android.py`
10. Probar en un dispositivo físico.

## 9. Agregar una clase nueva en el futuro

1. Editar `ml/classes.json`: agregar la clase con `class_id` = siguiente
   disponible (nunca reordenar ni reutilizar IDs existentes) y su
   `area_id` (usa `area_biologia_molecular` para las primeras clases de esa
   área).
2. `python scripts/sync_classes.py` (regenera `data.yaml` y `labels.txt`).
3. Crear `ml/raw/<raw_folder de la clase>/` y poner/etiquetar sus fotos.
4. Seguir el flujo de la sección 8 desde el paso 3.
5. Actualizar `LabRepository` en Android con el nuevo equipo (mismo
   `claseDetector` que el `name_internal` usado aquí) — ver
   `docs/YOLO_SETUP.md`.

## 10. Versionado

- `ml/classes.json`, `data.yaml`, `labels.txt`, scripts y documentación: **sí**
  se versionan en git.
- Fotografías (`ml/raw/`, `ml/dataset/images/`) y artefactos entrenados
  (`ml/runs/`, `ml/models/*.pt`, `ml/models/*.tflite`): **no** se versionan
  (ver `.gitignore` en la raíz del proyecto) — pesan mucho y se regeneran
  con los scripts. Las etiquetas `.txt` de `ml/dataset/labels/` sí se
  versionan (son texto pequeño y representan trabajo humano de etiquetado).
- Sugerencia de nomenclatura para builds del modelo (no automatizada): `v0.1`
  para la primera prueba real con las 7 clases actuales, `v1.0` cuando el
  dataset se considere "completo" para las 3 áreas.
