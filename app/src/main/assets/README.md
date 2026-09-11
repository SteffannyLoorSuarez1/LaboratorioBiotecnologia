# assets/

Contiene el modelo de detección ya entrenado e integrado, y los manuales en PDF:

- `best.tflite` — modelo YOLO11m entrenado (17 clases) y exportado a TensorFlow Lite. Ya está
  integrado: `YoloTfliteDetector` lo carga en tiempo de ejecución y valida su contrato de
  entrada/salida (`[1,3,640,640]` → `[1,21,8400]`).
- `labels.txt` — las 17 clases detectadas, una por línea, en el mismo orden usado durante el
  entrenamiento (idéntico a `ml/classes.json` y `ml/dataset_final/data.yaml`).
- `manuales/` — 17 manuales en PDF, uno por equipo con manual disponible
  (`manuales/<claseDetector>.pdf`), empaquetados para visualización **sin conexión**
  (`ManualRepository`/`ManualPdfActivity`).

Ver `docs/YOLO_SETUP.md` en la raíz del proyecto para el detalle del pipeline que produjo
`best.tflite`, y `docs/BIO_OPENAI_DIRECTO.md` para cómo se usan los manuales PDF desde el chat.

Si `best.tflite` o `labels.txt` llegaran a faltar (por ejemplo en un checkout que los excluya),
`YoloTfliteDetector` lo detecta en tiempo de ejecución y deshabilita la detección sin bloquear
el resto de la aplicación, en vez de fallar o simular detecciones falsas.
