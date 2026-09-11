# Integración del modelo YOLO (detección de equipos) — YA INTEGRADO

Este documento describe cómo quedó integrada la detección real de equipos en la app Android, y
cómo repetir el proceso (reentrenar / agregar clases) en el futuro. El modelo **ya está
entrenado, exportado e integrado**: `app/src/main/assets/best.tflite` +
`app/src/main/assets/labels.txt` son el modelo y las etiquetas reales que usa la app.

## Modelo y clases (estado final integrado)

- **Modelo integrado: YOLO11m**, entrenado sobre las 17 clases del dataset final
  (`ml/dataset_final/`, 2229 imágenes; ver `README.md` de la raíz para las métricas). El
  entrenamiento y la exportación a TFLite se hicieron externamente en Google Colab/Drive.
- **17 clases**, `class_id` fijos 0–16 (fuente de verdad: `ml/classes.json`, replicada en
  `app/src/main/assets/labels.txt` y `ml/dataset_final/data.yaml`):

  | class_id | `claseDetector` (interno) |
  |---|---|
  | 0 | `autoclave_vapor_mesa_gemmy_sturdy` |
  | 1 | `cabina_flujo_laminar_mini_c4` |
  | 2 | `centrifuga_laboratorio_ohaus` |
  | 3 | `medidor_mesa_electroquimica_ohaus` |
  | 4 | `termociclador_miniamp_plus` |
  | 5 | `bano_maria_memmert` |
  | 6 | `cubeta_electroforesis_horizontal_gel` |
  | 7 | `espectrofotometro_visible_digital_unico` |
  | 8 | `horno_secado_conveccion_forzada_redline` |
  | 9 | `medidor_demanda_bioquimica_oxigeno` |
  | 10 | `balanza_analitica_ohaus` |
  | 11 | `camara_incubacion_uv_prc_workstation` |
  | 12 | `estufa_laboratorio_doble_puerta` |
  | 13 | `horno_secado_biobase` |
  | 14 | `incubador_agitacion_orbital_incu_shaker` |
  | 15 | `microscopio_boeco` |
  | 16 | `camara_seguridad_biologica_biobase` |

  **Nunca reordenar ni reutilizar estos `class_id`.**

> Nota sobre `ml/`: los scripts y archivos generados en la **raíz** de `ml/` (`classes.json`,
> `data.yaml`, `labels.txt`, `ml/dataset/`) corresponden al pipeline local **original**, que se
> quedó en las 7 clases del prototipo y no se regeneró tras la migración a 17 clases. El dataset
> y las clases realmente usadas para el modelo integrado están en `ml/dataset_final/`. Ver el
> aviso al inicio de `ml/README.md`.

## Contrato del modelo TFLite integrado (medido, no supuesto)

`YoloTfliteDetector` valida este contrato contra el modelo real cargado, en tiempo de
ejecución (`validarContrato()`), y falla de forma controlada (detección deshabilitada) si no
coincide:

- Entrada: `[1, 3, 640, 640]` float32, NCHW, sin cuantizar, valores 0..1.
- Salida: `[1, 21, 8400]` float32 (21 = 4 coordenadas de caja + 17 clases; los canales de score
  ya tienen sigmoid aplicado, sin canal de objectness separado). Sin NMS embebido: se aplica en
  `YoloTfliteDetector.nmsPorClase`.

El número de clases (17) se toma dinámicamente de `labels.txt`, no está hardcodeado, así que el
mismo código soporta agregar clases nuevas en el futuro sin recompilar lógica.

## Mapeo YOLO ↔ Equipo Android

`Equipo.claseDetector` (`app/src/main/java/.../model/Equipo.java`) guarda el `name_internal` de
`ml/classes.json`/`labels.txt`. `LabRepository`
(`app/src/main/java/.../data/LabRepository.java`) es la fuente de verdad en Android para
`Equipo ↔ Área`. `LabRepository.obtenerEquipoPorClaseDetector(...)` permite ir de una detección
de `YoloTfliteDetector` (`DetectionResult.getClassName()`) al `Equipo` correspondiente.

**Pendiente conocido:** `LabRepository` todavía define los **7 equipos del prototipo
anterior** (`bod_sensor`, `lux_fc_meter`, etc.), cuyos `claseDetector` no coinciden con
ninguna de las 17 clases reales listadas arriba. El chat/RAG no depende de `LabRepository`
(resuelve el Vector Store directamente por `claseDetector`, ver `BioManuales.STORES`), pero la
pantalla **Áreas → Equipos → Ficha técnica** y el nombre/área "amigables" mostrados sobre una
detección en vivo sí dependen de esta clase y hoy no reconocen las 17 clases reales. Dar de
alta los 17 equipos reales (con sus datos) en `LabRepository` queda pendiente como tarea de
contenido, no de detección.

## Confianza (threshold) — configuración única

`DetectorConfig` (`app/src/main/java/.../detector/DetectorConfig.java`) es la **única**
constante de threshold de confianza en Android (`CONFIDENCE_THRESHOLD = 0.5f`, igual que
`ml/detector_config.json` del lado del pipeline). Ningún otro archivo debe declarar su propio
valor "quemado".

## CPU vs. GPU delegate en Android

**CPU (XNNPACK) es la ruta por defecto.** GPU delegate puede añadirse después como optimización
opcional, pero la app no debe depender de GPU para funcionar.

## Cómo está integrado en Android (referencia)

- `DetectorService` (`app/src/main/java/.../detector/DetectorService.java`) es la interfaz que
  usan las Activities (por ejemplo `DeteccionActivity`) para pedir detecciones, sin conocer los
  detalles de TensorFlow Lite.
- `YoloTfliteDetector` (`app/src/main/java/.../detector/YoloTfliteDetector.java`) es la
  implementación real: carga `best.tflite`/`labels.txt` desde `assets/`, corre la inferencia
  (letterbox 640×640 + tensor NCHW + decodificación + NMS por clase) y devuelve
  `List<DetectionResult>`. Si los archivos no existen en `assets/`, la detección queda
  deshabilitada sin bloquear el resto de la app.
- `DeteccionActivity` usa CameraX (`ImageAnalysis`) para entregar cada fotograma a
  `detectorService.detectar(...)` y pasa los resultados a `overlayView` para dibujar las cajas.
- Al seleccionar una detección, se usa `LabRepository.obtenerEquipoPorClaseDetector(...)` para
  resolver el equipo (con la limitación descrita arriba) y abrir la ficha/chat correspondiente.

## Reentrenar o agregar clases nuevas en el futuro

1. Actualizar `ml/classes.json` (y, si se sigue el pipeline local, `ml/data.yaml`/
   `ml/labels.txt` vía `python scripts/sync_classes.py`) con la clase nueva y su `class_id`
   siguiente disponible (nunca reordenar ni reutilizar IDs existentes).
2. Etiquetar las fotos nuevas (`ml/LABELING_GUIDE.md`) y regenerar/ampliar el dataset.
3. Entrenar (`ml/scripts/train.py` localmente, o en Colab como se hizo para el modelo final de
   17 clases — ver `ml/README.md` sección 6) y evaluar sobre el split test
   (`ml/scripts/evaluate.py`).
4. Exportar a TFLite (`ml/scripts/export_tflite.py`) y copiar a Android
   (`ml/scripts/copy_model_to_android.py`), que se niega a copiar un modelo que no sea real.
5. Actualizar `LabRepository` en Android con los equipos nuevos (mismo `claseDetector` que el
   `name_internal` usado en `ml/classes.json`).

## Estado

- [x] `DetectorService` + `YoloTfliteDetector` con inferencia TFLite real (no stub).
- [x] `OverlayView` dibujando cajas en tiempo real (0, 1 o varias detecciones simultáneas).
- [x] `best.tflite` + `labels.txt` reales integrados en `app/src/main/assets/`.
- [x] 17 clases definitivas y dataset final (`ml/dataset_final/`, 2229 imágenes).
- [x] Modelo YOLO11m entrenado y evaluado (ver métricas en el `README.md` de la raíz).
- [x] `DetectorConfig` con el threshold de confianza único.
- [ ] `LabRepository` con los 17 equipos reales (ver "Pendiente conocido" arriba) — hoy sigue
      teniendo los 7 equipos del prototipo anterior.
- [ ] Fichas técnicas documentales (función, EPP, riesgos, etc.) de los equipos en
      `LabRepository`.
