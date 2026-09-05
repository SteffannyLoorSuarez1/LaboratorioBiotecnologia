# ml/models/

Destino final de los modelos **realmente** entrenados y exportados:

- `best.pt` — copiado aquí solo por `scripts/export_tflite.py`, tras un
  entrenamiento (`train.py`) y evaluación (`evaluate.py`) reales.
- `best.tflite` — copiado aquí solo por `scripts/export_tflite.py`, tras
  validar que el archivo exportado carga correctamente como modelo TFLite.

**Intencionalmente vacía por ahora.** Ningún script de este proyecto genera
archivos falsos o de relleno aquí: si `best.tflite` no existe, es porque
todavía no hay un modelo entrenado y exportado con datos reales.

`scripts/copy_model_to_android.py` es el único script que copia estos
archivos (junto con `labels.txt`, regenerado desde `../classes.json`) a
`app/src/main/assets/`, y se niega a hacerlo si `best.tflite` no existe o
está vacío.
