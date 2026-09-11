# Configuración del RAG (Retrieval-Augmented Generation)

Esta guía explica cómo está configurado el asistente inteligente para que responda usando
exclusivamente los documentos del Laboratorio de Biotecnología de la UTEQ.

> **ARQUITECTURA ACTUAL (vigente): un Vector Store POR EQUIPO.** La sección "Vector Store del
> proyecto" de abajo describe la arquitectura ANTERIOR (un único Vector Store compartido) —
> se conserva solo como referencia histórica. Ver "Arquitectura vigente" más abajo.

## Arquitectura vigente: un Vector Store por equipo

Cada una de las 17 clases del detector (`clase_detector`, ver `labels.txt` / `ml/classes.json`)
tiene su **propio** Vector Store de OpenAI, ya creado y poblado con el manual real de ese
equipo. El mapeo completo (única fuente de verdad) vive en
`backend/app/services/equipo_manual_map.py` (`EQUIPO_VECTOR_STORE_MAP`).

Cuando Android envía `clase_detector`, el backend (`rag_service.py`) resuelve directamente su
`vector_store_id` y restringe `file_search` a ESE único store — nunca consulta varios a la
vez, nunca elige uno al azar, y no existe ningún Vector Store "general" de respaldo. Si
`clase_detector` viene vacía, es `None`, o no corresponde a ninguna de las 17 clases
conocidas, el backend NO llama a OpenAI: responde directamente el mensaje estándar de "sin
información" (nunca busca en un store incorrecto ni inventa una respuesta).

Para agregar o corregir el Vector Store de un equipo: actualizar la entrada correspondiente
en `EQUIPO_VECTOR_STORE_MAP`. Los Vector Stores en sí (crearlos, subirles archivos) se
gestionan a mano en https://platform.openai.com/storage/vector_stores — este backend nunca
los crea ni los modifica.

### Variable de entorno obsoleta: `OPENAI_VECTOR_STORE_ID`

Con esta arquitectura, `OPENAI_VECTOR_STORE_ID` (en `backend/.env`) **ya no se usa** para
resolver el Vector Store de una consulta por equipo — cada equipo usa el suyo propio, definido
en código (`equipo_manual_map.py`), no en una variable de entorno. Tampoco es ya requisito
para que `rag_configurado` sea `true` en `/health` (basta con `OPENAI_API_KEY`). Se conserva
sin borrar en `Settings` (`backend/app/config.py`) solo por compatibilidad con despliegues
existentes que aún la tengan configurada; dejarla puesta o quitarla del `.env` no cambia el
comportamiento del RAG.

## Vector Store del proyecto (arquitectura ANTERIOR — histórica, ya no vigente)

Se usaba **un único Vector Store** para todo el laboratorio:

```
Laboratorio_Biotecnologia_UTEQ
```

Ahí se cargaban documentos relacionados con las 3 áreas del laboratorio:

- Cultivo de tejidos vegetales
- Microbiología
- Biología molecular

y, opcionalmente, documentos generales que no pertenecen a un área específica:

- Seguridad
- Bioseguridad
- Normas del laboratorio

El backend no distinguía el Vector Store por área: todas las preguntas (con su contexto de
`equipo`/`área`) consultaban el mismo Vector Store, y era la búsqueda semántica de
`file_search` la que recuperaba los fragmentos relevantes para cada pregunta. Este esquema fue
reemplazado por el de "Arquitectura vigente" de arriba.

## Puesta en marcha del servidor

### 1. Configurar `backend/.env`

```
OPENAI_API_KEY=sk-...
```

`OPENAI_API_KEY` puede dejarse vacía en el servidor si cada usuario va a introducir su propia
clave desde la app (ver más abajo); si se completa, actúa como clave por defecto del servidor.
No hace falta configurar ningún `OPENAI_VECTOR_STORE_ID`: cada equipo ya usa el suyo propio,
definido en código (ver "Arquitectura vigente" arriba).

### 2. Iniciar FastAPI

```bash
cd backend
venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

(Las variables de `.env` solo se leen al iniciar el proceso, por lo que hay que reiniciar
uvicorn cada vez que cambie `backend/.env`.)

### 3. Probar `/health`

```bash
curl http://127.0.0.1:8000/health
```

Respuesta esperada una vez configurado correctamente:

```json
{"status": "ok", "rag_configurado": true}
```

`rag_configurado` es `true` en cuanto hay una API Key en el servidor (ya no depende de ningún
Vector Store "central"). El backend arranca y `/health` responde igual aunque falte la clave.

### 4. Probar `/api/chat`

```bash
curl -X POST http://127.0.0.1:8000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"equipo": "Horno de secado BIOBASE", "area": "", "clase_detector": "horno_secado_biobase", "pregunta": "¿Para qué se usa este equipo?"}'
```

También puedes probarlo desde la documentación interactiva en
`http://127.0.0.1:8000/docs`. Con `clase_detector` vacía, inexistente o mal escrita, la
respuesta es siempre el mensaje estándar de "sin información" (nunca un error ni una
respuesta inventada) — ver "Arquitectura vigente" arriba.

## Alternativa: API Key introducida desde la app (por usuario)

Además de `OPENAI_API_KEY` en el servidor, la app Android permite que cada usuario
introduzca su propia clave desde **Configuración** (pantalla `ConfiguracionActivity`). Esa
clave:

- se guarda cifrada en el dispositivo (Android Keystore + `EncryptedSharedPreferences`,
  ver `SecureConfigManager`), nunca en texto plano ni en el código;
- se envía al backend, en cada consulta del chat, en el encabezado HTTP
  `X-OpenAI-API-Key` (no dentro del JSON, para no mezclarla con el resto de la petición);
- el backend la usa **solo en memoria, para esa petición**, y nunca la guarda en archivos,
  base de datos ni logs.

Prioridad al resolver qué API Key usar (ver `backend/app/services/rag_service.py`):

1. La enviada por Android (`X-OpenAI-API-Key`), si está presente.
2. `OPENAI_API_KEY` del servidor, si la anterior no está presente.
3. Si ninguna existe: respuesta controlada indicando que el asistente aún no tiene clave
   configurada (sin error ni caída del backend).

Si `clase_detector` no resuelve a un Vector Store conocido (vacía, `None`, typo, o una clase
fuera de las 17 activas), tampoco se llama al modelo: se responde el mensaje estándar de "sin
información" (el proyecto exige que el asistente responda solo con base en los documentos del
equipo correcto, nunca de forma libre ni con un store incorrecto).

## Mapeo clase → Vector Store (las 17 clases activas)

Fuente única de verdad: `backend/app/services/equipo_manual_map.py`
(`EQUIPO_VECTOR_STORE_MAP`). No se duplica aquí para evitar que esta guía se desactualice del
código — para ver el mapeo completo, abrir ese archivo directamente. Validado con pruebas
automatizadas (`backend/tests/test_equipo_manual_map.py`): exactamente 17 entradas, sin IDs
vacíos ni duplicados, y las 17 clases de `labels.txt`/`ml/classes.json` presentes.

## Reglas que sigue el asistente

- Responde exclusivamente con información recuperada del Vector Store del equipo en contexto
  (`clase_detector`) — nunca de otro equipo ni de un store "general".
- No inventa datos técnicos ni usa conocimiento general para completar información técnica
  faltante.
- Puede responder sobre: función, componentes, operación, seguridad, EPP, riesgos,
  mantenimiento y prácticas académicas.
- Muestra las fuentes (nombre de archivo) realmente citadas por `file_search` en la
  respuesta — nunca inventa página o sección si la API no la devuelve.
- Si no hay información suficiente, responde exactamente:
  > "No tengo suficiente información, por favor pregúntele al laboratorista encargado."
