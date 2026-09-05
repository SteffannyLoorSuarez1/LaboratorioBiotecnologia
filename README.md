# Asistente Móvil Inteligente para la Detección de Equipos del Laboratorio de Biotecnología de la UTEQ

Proyecto académico de la Universidad Técnica Estatal de Quevedo (UTEQ). Aplicación Android
(Java) que permitirá identificar equipos del Laboratorio de Biotecnología mediante visión
por computador (YOLO + TensorFlow Lite) y consultar un asistente inteligente basado en RAG
sobre los documentos oficiales del laboratorio.

## Objetivo

Construir un asistente móvil que:

1. Detecte en tiempo real, con la cámara del teléfono, los equipos del laboratorio.
2. Permita seleccionar un equipo detectado y ver su ficha técnica.
3. Ofrezca un chat inteligente (con voz) que responda preguntas usando exclusivamente
   documentación oficial del laboratorio (manuales, guías, protocolos, normas de seguridad),
   mostrando siempre las fuentes consultadas.

## Áreas del laboratorio (definitivas)

1. Área de cultivo de tejidos vegetales
2. Área de microbiología
3. Área de biología molecular

## Arquitectura

```
DeteccindeequiposLaboratoriodeBiotecnologa/
├── app/                        # App Android (Java)
│   └── src/main/java/.../
│       ├── model/              # AreaLaboratorio, Equipo, DetectionResult
│       ├── data/                # LabRepository (datos de áreas/equipos)
│       ├── detector/             # DetectorService (interfaz) + YoloTfliteDetector (stub)
│       ├── camera/               # DeteccionActivity (CameraX) + OverlayView
│       ├── areas/                 # AreasActivity, EquiposActivity, EquipoDetalleActivity
│       ├── chat/                   # ChatActivity, ChatAdapter, ChatMensaje
│       ├── network/                 # ApiClient (Volley), ChatRepository, DTOs
│       └── speech/                   # TextToSpeechManager, SpeechRecognitionManager
├── backend/                    # Backend Python + FastAPI (independiente del módulo Android)
│   └── app/
│       ├── main.py, config.py
│       ├── models/               # Esquemas Pydantic
│       ├── routers/              # /health, /api/chat
│       └── services/             # rag_service.py (OpenAI Vector Store)
├── ml/                          # Pipeline de dataset/entrenamiento/exportación YOLO11n
│   ├── classes.json                # fuente única de verdad de las 7 clases y sus class_id
│   ├── scripts/                     # validar, preparar dataset, entrenar, evaluar, exportar
│   └── README.md                    # ver ml/README.md para el flujo completo
└── docs/
    ├── RAG_SETUP.md            # Cómo configurar el RAG con OpenAI
    └── YOLO_SETUP.md           # Cómo integrar el modelo YOLO11n/.tflite
```

La app Android nunca llama directamente al LLM ni conoce documentos completos: solo envía
`equipo`, `area` y `pregunta` al backend (`POST /api/chat`); el backend hace la recuperación
(RAG) y la consulta al modelo.

## Tecnologías

- **Android**: Java, Material Design 3, CameraX, Volley, `TextToSpeech`, `SpeechRecognizer`.
- **Backend**: Python, FastAPI, OpenAI (Responses API + Vector Store / file_search).
- **Detección futura**: YOLO11n (entrenamiento personalizado, ver `ml/`) exportado a
  LiteRT/TensorFlow Lite.

## Puesta en marcha — Android

1. Abrir el proyecto en Android Studio (no requiere cambios de SDK adicionales; usa el
   `local.properties` ya generado).
2. Antes de probar en un **teléfono físico**, editar
   `app/src/main/java/.../network/ApiClient.java` y cambiar:

   ```java
   public static final String BASE_URL = "http://192.168.X.X:8000";
   ```

   por la IP de la computadora donde corre el backend en tu red local (nunca `localhost`
   desde un teléfono real).
3. Ejecutar la app (`Run`). Se solicitarán permisos de cámara y micrófono en tiempo de
   ejecución.

## Puesta en marcha — Backend

```bash
cd backend
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Ver `backend/README.md` y `docs/RAG_SETUP.md` para el detalle de configuración del RAG con
OpenAI (Vector Store, `OPENAI_API_KEY`, `OPENAI_VECTOR_STORE_ID`).

## Estado actual

### Funcional ahora (no depende del modelo YOLO)

- Pantalla de inicio con accesos a Detección, Áreas y Asistente, y resumen de las 3 áreas.
- Pantalla de Áreas → Equipos → Ficha técnica, con datos dinámicos (`LabRepository`).
- Pantalla de cámara en tiempo real con CameraX y `OverlayView` (capa de dibujo lista para
  las futuras cajas de detección).
- Chat inteligente completo: historial, envío de preguntas, indicador "Consultando
  documentos...", fuentes, texto a voz (`TextToSpeechManager`) y voz a texto
  (`SpeechRecognitionManager`).
- Pantalla de Configuración (`ConfiguracionActivity`): el usuario puede introducir su propia
  OpenAI API Key, guardada cifrada en el dispositivo (`SecureConfigManager`, Android
  Keystore) y enviada al backend solo por el encabezado `X-OpenAI-API-Key` (nunca
  hardcodeada, nunca en logs, nunca completa en pantalla).
- Backend FastAPI con `/health` y `/api/chat`, capaz de arrancar sin `OPENAI_API_KEY` ni
  `OPENAI_VECTOR_STORE_ID` (responde con un mensaje controlado en ese caso). Acepta la clave
  del servidor o la enviada desde Android (con prioridad para esta última), sin guardarla
  nunca del lado del servidor.
- Comunicación Android↔backend vía Volley, con `BASE_URL` centralizada y manejo de errores
  HTTP (401/429/5xx) traducido a mensajes comprensibles, sin exponer detalles técnicos.

### PENDIENTE (depende del dataset y del modelo)

- Fotografías reales del laboratorio y su etiquetado (bounding boxes YOLO). Las 7 clases y
  sus `class_id` ya están definidos de forma fija en `ml/classes.json` (ver `ml/README.md`).
- Entrenamiento del modelo YOLO11n personalizado (modelo oficial del proyecto, ver
  `ml/README.md` sección "Decisión de modelo") sobre ese dataset.
- Exportación del modelo a `best.tflite` + `labels.txt` y su integración en
  `YoloTfliteDetector` (ver `docs/YOLO_SETUP.md`).
- Carga real de documentos del laboratorio al Vector Store de OpenAI (ver
  `docs/RAG_SETUP.md`) — el código del RAG ya está listo, falta indexar los documentos
  reales y configurar las variables de entorno.
- Fichas técnicas documentales (función, EPP, riesgos, etc.) de los 7 equipos reales ya
  dados de alta en `LabRepository` (los equipos ya no son datos de demostración; solo falta
  su documentación, que se muestra como "Información pendiente" hasta cargarse).

**Ninguna funcionalidad que dependa del detector YOLO ha sido marcada como terminada.**

## Ubicación futura del modelo TFLite

`app/src/main/assets/best.tflite` y `app/src/main/assets/labels.txt` (ver
`app/src/main/assets/README.md` y `docs/YOLO_SETUP.md`).
