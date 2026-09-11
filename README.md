# Asistente Móvil Inteligente para la Detección de Equipos del Laboratorio de Biotecnología de la UTEQ

Proyecto académico de la Universidad Técnica Estatal de Quevedo (UTEQ). Aplicación Android
(Java) que detecta en tiempo real, con la cámara del teléfono, los equipos del Laboratorio de
Biotecnología mediante un modelo YOLO propio (TensorFlow Lite) y ofrece un asistente
inteligente (chat escrito y por voz) basado en RAG sobre los manuales oficiales de cada equipo.

## Estado: PROYECTO IMPLEMENTADO

El detector YOLO ya está entrenado, exportado e integrado en la app; el asistente conversacional
ya consulta directamente a OpenAI sobre los manuales de cada equipo; y los manuales en PDF se
visualizan sin conexión desde la propia app. Ver la sección [Estado real verificado](#estado-real-verificado-y-pendientes-conocidos)
para el detalle de lo confirmado directamente contra el código y los assets del repositorio.

## Objetivo

1. Detectar en tiempo real, con la cámara del teléfono, los equipos del laboratorio (17 clases).
2. Permitir seleccionar un equipo detectado y ver su ficha técnica.
3. Ofrecer un chat inteligente (con voz) que responda preguntas usando exclusivamente la
   documentación oficial de cada equipo (manuales), mostrando siempre las fuentes consultadas.
4. Permitir consultar el manual PDF original de cada equipo sin conexión a Internet.

## Arquitectura actual

```
┌───────────────────────────── App Android (Java) ─────────────────────────────┐
│                                                                                │
│  CameraX ──► YoloTfliteDetector (best.tflite, local, 17 clases) ──► OverlayView│
│                                                                                │
│  ChatActivity / VozAsistenteActivity                                         │
│      └──► ChatRepository ──► HTTPS directo ──► OpenAI Responses API          │
│                                (file_search sobre 1 Vector Store por equipo) │
│                                                                                │
│  ManualPdfActivity ──► ManualRepository ──► assets/manuales/*.pdf (offline)  │
└────────────────────────────────────────────────────────────────────────────┘
```

- **Detección de equipos**: 100% local en el teléfono. La cámara (CameraX) entrega cada
  fotograma a `YoloTfliteDetector`, que corre el modelo `best.tflite` con TensorFlow
  Lite/LiteRT y dibuja las cajas delimitadoras (`OverlayView`). No requiere Internet.
- **Asistente (chat y voz)**: la app llama **directamente** desde Android a la API de OpenAI
  (`https://api.openai.com/v1/responses`) por HTTPS, sin pasar por ningún servidor propio.
  No depende de una computadora encendida, de USB ni de la IP de una laptop en la misma red.
  Ver el detalle en [`docs/BIO_OPENAI_DIRECTO.md`](docs/BIO_OPENAI_DIRECTO.md).
- **Manuales PDF**: los 17 manuales están empaquetados como assets locales en
  `app/src/main/assets/manuales/` y se visualizan sin conexión; no dependen de descargarlos
  desde OpenAI.
- **Backend FastAPI** (carpeta `backend/`): implementación **anterior/opcional**, conservada en
  el repositorio como referencia. **No es necesaria para que el APK funcione.** Ver la nota en
  la sección [Backend histórico](#backend-fastapi-histórico--opcional).

## Detección de equipos (YOLO)

- **Modelo**: YOLO11m personalizado, entrenado sobre fotografías reales del laboratorio.
- **Pipeline**: `best.pt` (pesos originales de entrenamiento) → exportado a `best.tflite`
  (LiteRT/TensorFlow Lite) → integrado en `app/src/main/assets/best.tflite`.
- **17 clases** (equipos), `class_id` 0–16, en este orden exacto (idéntico en
  `app/src/main/assets/labels.txt`, `ml/classes.json` y `ml/dataset_final/data.yaml`):

  | class_id | Clase (`claseDetector`) |
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

- **Contrato del modelo TFLite integrado** (medido sobre el `best.tflite` real por
  `YoloTfliteDetector`, que valida este contrato en tiempo de ejecución):
  - Entrada: `[1, 3, 640, 640]` float32, NCHW, sin cuantizar.
  - Salida: `[1, 21, 8400]` float32 (21 = 4 coordenadas de caja + 17 clases).

### Dataset final

- **2229 imágenes** reales del laboratorio, repartidas en:
  - `train`: 1561 imágenes
  - `val`: 335 imágenes
  - `test`: 333 imágenes
  - (split aproximado 70/15/15)
- **Anotaciones**: formato YOLO (una caja por línea, `class_id x_center y_center width height`
  normalizados), 2229 archivos `.txt` (uno por imagen), 2253 bounding boxes en total (algunas
  imágenes tienen más de un equipo visible).
- El dataset final vive en `ml/dataset_final/` (`data.yaml`, `classes.txt`); las imágenes y
  etiquetas no se versionan en git por su peso (ver `.gitignore`) y se conservan localmente /
  en Google Drive.

### Métricas finales (conjunto de test)

| Métrica | Valor |
|---|---|
| Precision | 98.81 % |
| Recall | 97.15 % |
| mAP50 | 98.81 % |
| mAP50-95 | 83.76 % |
| F1 | 97.97 % |

## Android

- **CameraX** para la vista previa y el análisis de fotogramas en tiempo real.
- **TensorFlow Lite / LiteRT** para correr `best.tflite` on-device (`YoloTfliteDetector`).
- Dibujo de **bounding boxes** en vivo sobre la cámara (`OverlayView`), con selección táctil
  de un equipo detectado.
- Pantallas de **Áreas → Equipos → Ficha técnica** además de la selección directa desde cámara.
- **Asistente escrito** (`ChatActivity`) y **asistente por voz** (`VozAsistenteActivity`), con
  **texto a voz** (`TextToSpeechManager`) y **voz a texto** (`SpeechRecognitionManager`).
- Pantalla de Configuración: permite guardar una API Key de OpenAI personal, cifrada en el
  dispositivo (Android Keystore / `EncryptedSharedPreferences`, ver `SecureConfigManager`).

## RAG (asistente inteligente)

- El chat (escrito y por voz) usa la **Responses API** de OpenAI con **File Search / Vector
  Stores**, llamada directamente desde Android (`ApiClient`, `ChatRepository`,
  `BioResponses`).
- **17 Vector Stores**, uno dedicado por clase/equipo (`BioManuales.STORES`, mismas claves que
  `labels.txt`/`ml/classes.json`). Cada consulta se restringe al Vector Store del equipo en
  contexto — nunca a un store compartido ni a varios a la vez.
- Si la clase detectada no tiene equipo/Vector Store conocido, el asistente responde el mensaje
  estándar de "sin información" en vez de inventar una respuesta.

## Manuales PDF

Los manuales originales en PDF de los equipos con manual disponible están empaquetados como
**assets locales** en `app/src/main/assets/manuales/<claseDetector>.pdf` y se visualizan sin
conexión mediante `ManualPdfActivity`/`ManualRepository` — independiente de descargar el
archivo desde OpenAI (esa vía se evaluó y se descartó: la Files API de OpenAI no permite
descargar archivos subidos con propósito `assistants`).

## Backend FastAPI (histórico / opcional)

La carpeta `backend/` contiene una implementación anterior en Python + FastAPI (RAG vía
`file_search` sobre los mismos Vector Stores) que **ya no es la arquitectura activa del APK**.
Se conserva en el repositorio como referencia y por si se necesita un backend propio en el
futuro, pero **no hace falta arrancarla para compilar ni usar la aplicación actual**: el chat y
la voz llaman directamente a OpenAI desde Android (ver arriba). Ver `backend/README.md` y
`docs/RAG_SETUP.md` para su documentación específica.

## Estructura del repositorio

```
DeteccindeequiposLaboratoriodeBiotecnologa/
├── app/                                  # App Android (Java)
│   └── src/main/
│       ├── java/.../
│       │   ├── model/                    # AreaLaboratorio, Equipo, DetectionResult
│       │   ├── data/                     # LabRepository (áreas/equipos)
│       │   ├── detector/                 # DetectorService, YoloTfliteDetector, DetectorConfig
│       │   ├── camera/                   # DeteccionActivity (CameraX), OverlayView (bounding boxes)
│       │   ├── areas/                    # AreasActivity, EquiposActivity, EquipoDetalleActivity
│       │   ├── chat/                     # ChatActivity, VozAsistenteActivity, ManualPdfActivity, ...
│       │   ├── network/                  # ApiClient, ChatRepository, BioManuales, BioResponses, ManualRepository, ...
│       │   ├── speech/                   # TextToSpeechManager, SpeechRecognitionManager
│       │   └── security/                 # SecureConfigManager (API key cifrada en el dispositivo)
│       └── assets/
│           ├── best.tflite               # modelo YOLO11m entrenado (17 clases), integrado
│           ├── labels.txt                # 17 clases, mismo orden que el modelo
│           └── manuales/                 # PDFs de manuales, empaquetados para uso offline
├── backend/                              # FastAPI — implementación anterior/opcional (ver arriba)
├── ml/                                   # Pipeline de dataset/entrenamiento/exportación YOLO
│   ├── classes.json                      # 17 clases definitivas (fuente del detector)
│   ├── dataset_final/                    # dataset final (data.yaml, classes.txt; imágenes/labels sin versionar)
│   ├── scripts/                          # validar, preparar dataset, entrenar, evaluar, exportar
│   └── README.md                         # ver ml/README.md para el detalle del pipeline
└── docs/
    ├── BIO_OPENAI_DIRECTO.md             # arquitectura actual: Android → OpenAI directo
    ├── RAG_SETUP.md                      # RAG / Vector Stores (documentación del lado backend)
    └── YOLO_SETUP.md                     # integración del modelo YOLO en Android
```

## Puesta en marcha

### Compilar y ejecutar la app Android

1. Abrir el proyecto en Android Studio (usa el `local.properties` ya generado; no requiere
   configurar ninguna IP de computadora).
2. Configurar la credencial de OpenAI que se incluye en la compilación: crear/editar
   `backend/.env` (no se versiona en git) con:

   ```
   OPENAI_API_KEY=sk-...
   OPENAI_MODEL=gpt-4o-mini   # opcional, este es el valor por defecto
   ```

   (también se puede pasar como variable de entorno `OPENAI_API_KEY`/`OPENAI_MODEL` al
   compilar). Gradle lee este archivo solo en tiempo de compilación y la incorpora al APK;
   sin esta clave la compilación falla.
3. Sincronizar Gradle y ejecutar (`Run`) o generar el APK/App Bundle firmado. Se solicitarán
   permisos de cámara y micrófono en tiempo de ejecución.
4. El teléfono solo necesita conexión a Internet para el chat/voz (consultas a OpenAI); la
   detección de equipos y la visualización de manuales PDF funcionan sin conexión.

Ver [`docs/BIO_OPENAI_DIRECTO.md`](docs/BIO_OPENAI_DIRECTO.md) para el detalle completo de esta
arquitectura, y [`docs/YOLO_SETUP.md`](docs/YOLO_SETUP.md) / [`ml/README.md`](ml/README.md) para
el pipeline de entrenamiento del modelo si se necesita reentrenar o ampliar clases.

### Backend FastAPI (opcional, no requerido para usar la app)

```bash
cd backend
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Ver `backend/README.md` y `docs/RAG_SETUP.md`.

## Estado real verificado y pendientes conocidos

Verificado directamente contra el código y los archivos del repositorio (no solo documentación):

- ✅ `app/src/main/assets/labels.txt` y `ml/classes.json` tienen las 17 clases definitivas, en
  el orden correcto (class_id 0–16).
- ✅ `app/src/main/assets/best.tflite` está presente e integrado (~80 MB).
- ✅ `YoloTfliteDetector` tiene inferencia TFLite real (no es un stub): valida en tiempo de
  ejecución que el modelo cargado tenga entrada `[1,3,640,640]` y salida `[1, 4+nc, N]`, y usa
  el número de clases de `labels.txt` de forma dinámica (no hardcodeado), por lo que funciona
  correctamente con el modelo de 17 clases / salida `[1,21,8400]`.
- ✅ `app/src/main/assets/manuales/` contiene 17 PDFs y `ManualRepository`/`ManualPdfActivity`
  los leen desde assets locales, sin red.
- ✅ El chat y la voz llaman directamente a `https://api.openai.com/v1` (`ApiClient`,
  `ChatRepository`), no a un `localhost`/IP de backend.
- ✅ `BioManuales.STORES` define 17 Vector Store IDs, uno por clase, con las mismas claves que
  `labels.txt`.
- ✅ El código de voz (`SpeechRecognitionManager`, `TextToSpeechManager`,
  `VozAsistenteActivity`) existe e está integrado en el chat.
- ⚠️ **Pendiente de actualizar** (código funcional, no se modificó en esta limpieza
  documental para no tocar código sin que el propietario lo decida): `LabRepository.java`
  (`app/src/main/java/.../data/LabRepository.java`) todavía define los **7 equipos del
  prototipo anterior** (`bod_sensor`, `lux_fc_meter`, `medidor_multiparametro`,
  `electroforesis_owl_easycast`, `armario_calefactor_ule600`, `horno_secado`,
  `uv_pcr_workstation`), cuyos `claseDetector` **no coinciden** con ninguna de las 17 clases
  reales del detector. Esto no rompe el chat/RAG (que resuelve el Vector Store directamente
  por `claseDetector`, sin pasar por `LabRepository`), pero sí significa que la pantalla
  **Áreas → Equipos → Ficha técnica** todavía lista los 7 equipos antiguos en vez de los 17
  reales, y que el nombre "amigable"/área mostrados sobre una detección en vivo pueden caer al
  nombre interno crudo si la clase no está dada de alta ahí. Requiere dar de alta los 17
  equipos reales en `LabRepository` con sus datos.
