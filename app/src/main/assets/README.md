# assets/

Esta carpeta está preparada para recibir, en una fase posterior del proyecto, los
archivos generados por el entrenamiento del modelo YOLO exportado a TensorFlow Lite:

- `best.tflite` — modelo entrenado y exportado.
- `labels.txt` — lista de clases detectadas, una por línea, en el mismo orden usado
  durante el entrenamiento.

Ver `docs/YOLO_SETUP.md` en la raíz del proyecto para el procedimiento completo.

**Intencionalmente no se incluyen aquí archivos `best.tflite` ni `labels.txt` de
ejemplo.** `YoloTfliteDetector` comprueba en tiempo de ejecución si ambos archivos
existen; si no existen, la aplicación sigue funcionando con la detección deshabilitada
y muestra el aviso "Modelo de detección pendiente de instalación".
