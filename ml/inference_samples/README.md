# ml/inference_samples/

Carpeta opcional para fotografías **externas o no vistas** durante el
entrenamiento (ni en train, ni en val, ni en test), útiles para probar
manualmente un modelo ya entrenado con `yolo predict` / `model.predict(...)`
antes de integrarlo en Android.

No forma parte del dataset de entrenamiento ni de las validaciones
automáticas (`validate_labels.py`, `prepare_dataset.py` la ignoran).

Ejemplo de uso una vez exista `ml/models/best.pt`:

```bash
yolo predict model=models/best.pt source=inference_samples/ imgsz=640 conf=0.5
```
