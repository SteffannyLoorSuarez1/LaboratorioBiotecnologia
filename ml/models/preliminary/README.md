# ml/models/preliminary/

Zona de aterrizaje para pesos (`best.pt`) de entrenamientos **preliminares o
en progreso** (por ejemplo, descargados de una sesion de Google Colab),
usados solo para:

- `scripts/prelabel_new_images.py` (pre-etiquetado asistido de fotos nuevas).
- pruebas externas / inspeccion manual con `model.predict()`.

**Esto NO es lo mismo que `ml/models/best.pt`.** Esa otra ruta esta
reservada exclusivamente para el resultado de `scripts/export_tflite.py`
(un `best.pt` que ya paso por entrenamiento + evaluacion + export a
LiteRT/TFLite validados) — ver `ml/models/README.md`. Mientras un modelo
siga siendo preliminar (como el primer entrenamiento YOLO11n de este
proyecto), sus pesos van aqui, no alli, para que nunca haya ambiguedad sobre
si un modelo en `ml/models/best.pt` ya fue exportado/validado o no.

Coloca aqui el `best.pt` descargado de Colab, sin renombrarlo:

```
ml/models/preliminary/best.pt
```

No versionado en git (pesos, ver `.gitignore`).
