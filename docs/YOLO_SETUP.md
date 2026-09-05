# Integración del modelo YOLO11n (detección de equipos)

Este documento describe el procedimiento para integrar la detección real de
equipos. El pipeline de dataset/entrenamiento/exportación vive en `ml/`
(ver `ml/README.md`) y ya está preparado de forma definitiva; lo que falta es
el dataset fotográfico real (fotos + etiquetas) para poder entrenar.

## Modelo y clases (definitivos)

- **Modelo oficial: YOLO11n** (`yolo11n.pt`). Se eligió sobre YOLO26n por
  estabilidad de entrenamiento, exportación LiteRT/TFLite e integración en
  Android — ver `ml/README.md`, sección "Decisión de modelo". No cambiar de
  modelo salvo impedimento técnico real y comprobado, reportándolo antes de
  modificar nada.
- **7 clases definidas**, `class_id` fijos (fuente de verdad:
  `ml/classes.json`):

  | class_id | `claseDetector` (interno) | Equipo | Área |
  |---|---|---|---|
  | 0 | `bod_sensor` | BOD Sensor | Cultivo de tejidos vegetales |
  | 1 | `lux_fc_meter` | Broad Range LUX/FC Meter | Cultivo de tejidos vegetales |
  | 2 | `medidor_multiparametro` | Medidor multiparámetro electroquímico de mesa | Cultivo de tejidos vegetales |
  | 3 | `electroforesis_owl_easycast` | Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP | Cultivo de tejidos vegetales |
  | 4 | `armario_calefactor_ule600` | Armario calefactor ULE 600 | Microbiología |
  | 5 | `horno_secado` | Horno de secado | Microbiología |
  | 6 | `uv_pcr_workstation` | UV PCR Workstation | Microbiología |

  El área de Biología molecular todavía no tiene clases; se agregarán con
  `class_id: 7, 8, ...` cuando haya fotos (nunca se reordenan los IDs 0–6).

## Flujo previsto (ver `ml/README.md` para el detalle de cada script)

1. Validar y etiquetar el dataset fotográfico real (`ml/LABELING_GUIDE.md`).
2. Entrenar `yolo11n.pt` con `ml/scripts/train.py`, obteniendo `best.pt`.
3. Evaluar sobre el conjunto test con `ml/scripts/evaluate.py`.
4. Exportar a LiteRT/TFLite con `ml/scripts/export_tflite.py` → `best.tflite`
   (en Windows puede requerir Google Colab; ver `ml/README.md` sección 6).
5. Copiar `best.tflite` + `labels.txt` (generado desde `ml/classes.json`) a
   `app/src/main/assets/` con `ml/scripts/copy_model_to_android.py`. Este
   script se niega a copiar un modelo que no sea real.

## Mapeo YOLO ↔ Equipo Android (fuente única, sin duplicar datos)

`Equipo.claseDetector` (`app/src/main/java/.../model/Equipo.java`) ya es el
campo pensado para este mapeo: guarda exactamente el `name_internal` de
`ml/classes.json` (p. ej. `"armario_calefactor_ule600"`). `LabRepository`
(`app/src/main/java/.../data/LabRepository.java`) es la única fuente de
verdad en Android para `Equipo ↔ Área`, igual que `classes.json` lo es para
`class_id ↔ claseDetector`. `LabRepository.obtenerEquipoPorClaseDetector(...)`
permite ir de una detección de `YoloTfliteDetector`
(`DetectionResult.getClassName()`) al `Equipo` correspondiente, sin mantener
una segunda lista de clases en ningún otro archivo.

`LabRepository` ya contiene los 7 equipos reales (no de demostración) listos
para esto; sus fichas técnicas (función, EPP, riesgos, etc.) están pendientes
de documentación real y se muestran como "Información pendiente" hasta que
se carguen (`EquipoDetalleActivity` ya maneja ese caso).

## Confianza (threshold) — configuración única

`DetectorConfig` (`app/src/main/java/.../detector/DetectorConfig.java`) es la
**única** constante de threshold de confianza en Android
(`CONFIDENCE_THRESHOLD = 0.5f`, igual que `ml/detector_config.json` del lado
del pipeline). Ningún otro archivo debe declarar su propio valor "quemado".

## CPU vs. GPU delegate en Android

**CPU (XNNPACK) debe ser la ruta segura/por defecto.** GPU delegate puede
añadirse después como optimización opcional, pero la app **no debe depender
de GPU** para funcionar — si el GPU delegate falla en un dispositivo
concreto, debe poder seguir funcionando en CPU. Esto es más relevante aún
tras confirmar (`ml/README.md`) que hubo problemas reales de compatibilidad
GPU delegate con YOLO26 en Android; aunque el proyecto usa YOLO11n
(sin ese bug reportado), se mantiene CPU como ruta base por robustez frente a
la fragmentación de dispositivos Android reales.

## Integración en Android (pendiente hasta tener un `best.tflite` real)

La app ya está preparada para este momento:

- `DetectorService` (`app/src/main/java/.../detector/DetectorService.java`) es la interfaz
  que usan las Activities (por ejemplo `DeteccionActivity`) para pedir detecciones, sin
  conocer los detalles de TensorFlow Lite.
- `YoloTfliteDetector` (`app/src/main/java/.../detector/YoloTfliteDetector.java`) es la
  implementación futura. Por ahora solo comprueba si `best.tflite` y `labels.txt` existen
  en `assets/`; si no existen, la detección queda deshabilitada sin bloquear el resto de la
  aplicación (se muestra el aviso "Modelo de detección pendiente de instalación").
- `DetectionResult` ya soporta 0, 1 o múltiples detecciones simultáneas
  (`DeteccionActivity`/`OverlayView` reciben `List<DetectionResult>`, no un
  único resultado).

Cuando el modelo esté listo, los siguientes pasos (fuera del alcance de esta fase) serán:

1. Agregar la dependencia de TensorFlow Lite/LiteRT para Android (a decidir en
   ese momento entre `org.tensorflow:tensorflow-lite` + `tensorflow-lite-support`
   o el artefacto LiteRT más reciente — verificar la opción vigente cuando se
   implemente, no asumirla ahora) en `app/build.gradle`, con CPU/XNNPACK como
   delegate por defecto.
2. Cargar el intérprete dentro de `YoloTfliteDetector` (constructor), usando
   `context.getAssets()` para abrir `best.tflite`.
3. Implementar el preprocesamiento (resize/normalización), la inferencia y el
   post-procesamiento (NMS con `DetectorConfig.CONFIDENCE_THRESHOLD`, escalado
   de cajas al tamaño original) dentro de `detectar(Bitmap frame)`.
4. En `DeteccionActivity`, añadir un `ImageAnalysis` de CameraX que entregue cada
   fotograma a `detectorService.detectar(...)` y pase los resultados a
   `overlayView.setResultados(resultados, anchoImagen, altoImagen)`.
5. Al seleccionar una detección, usar `LabRepository.obtenerEquipoPorClaseDetector(
   resultado.getClassName())` para abrir la ficha/chat del equipo correcto.

Ninguno de estos pasos requiere modificar las demás pantallas de la aplicación (áreas,
ficha técnica, chat), ya que dependen del modelo de datos (`Equipo`, `AreaLaboratorio`,
`DetectionResult`) y no de la implementación concreta del detector.

## Estado actual

- [x] Interfaz `DetectorService` y stub `YoloTfliteDetector` (sin inferencia real).
- [x] `OverlayView` lista para dibujar cajas cuando existan detecciones (0, 1 o varias).
- [x] Carpeta `app/src/main/assets/` preparada (ver `assets/README.md`).
- [x] Clases definitivas (7) y pipeline de dataset/entrenamiento/evaluación/exportación (`ml/`).
- [x] `LabRepository` con los 7 equipos reales y mapeo `claseDetector` ↔ Equipo ↔ Área.
- [x] `DetectorConfig` con el threshold de confianza único.
- [ ] Dataset fotográfico real (fotos + etiquetas).
- [ ] Entrenamiento del modelo YOLO11n.
- [ ] Exportación a `best.tflite` + `labels.txt` reales.
- [ ] Integración de inferencia real en `YoloTfliteDetector`.
