# Configuración del RAG (Retrieval-Augmented Generation)

Esta guía explica cómo preparar el asistente inteligente para que responda usando
exclusivamente los documentos del Laboratorio de Biotecnología de la UTEQ, mediante un
único Vector Store de OpenAI.

## Vector Store del proyecto

Se usa **un único Vector Store** para todo el laboratorio:

```
Laboratorio_Biotecnologia_UTEQ
```

Ahí se cargan documentos relacionados con las 3 áreas del laboratorio:

- Cultivo de tejidos vegetales
- Microbiología
- Biología molecular

y, opcionalmente, documentos generales que no pertenecen a un área específica:

- Seguridad
- Bioseguridad
- Normas del laboratorio

El backend no distingue el Vector Store por área: todas las preguntas (con su contexto de
`equipo`/`área`) consultan el mismo Vector Store, y es la búsqueda semántica de `file_search`
la que recupera los fragmentos relevantes para cada pregunta.

## Pasos (en https://platform.openai.com)

### 1. Abrir OpenAI Platform
Ingresa a https://platform.openai.com con la cuenta de la organización del proyecto.

### 2. Ir a Storage
En el menú lateral, entra a **Storage** → **Files**
(https://platform.openai.com/storage/files).

### 3. Subir los documentos reales
Sube ahí los PDF/documentos reales del laboratorio (manuales de equipos, guías de
prácticas, protocolos, normas de seguridad/bioseguridad). No se suben documentos de
ejemplo ni inventados.

### 4. Crear un Vector Store
Ve a **Storage** → **Vector stores** (https://platform.openai.com/storage/vector_stores) →
**Create**. Nómbralo `Laboratorio_Biotecnologia_UTEQ`.

### 5. Asociar los archivos
Dentro del Vector Store creado, agrega los archivos subidos en el paso 3. Espera a que el
estado de indexación de cada archivo sea **Completed** antes de probar el asistente.

### 6. Copiar el `vector_store_id`
En la página del Vector Store, copia su identificador. Tiene el formato:

```
vs_xxxxxxxxxxxxxxxxxxxxxxxx
```

### 7. Configurar `backend/.env`

```
OPENAI_API_KEY=
OPENAI_VECTOR_STORE_ID=vs_xxxxxxxxxxxxxxxxxxxxxxxx
```

`OPENAI_API_KEY` puede dejarse vacía en el servidor si cada usuario va a introducir su
propia clave desde la app (ver más abajo); si se completa, actúa como clave por defecto del
servidor. El `OPENAI_VECTOR_STORE_ID`, en cambio, **siempre** se configura aquí, en el
servidor — nunca se introduce desde Android.

### 8. Reiniciar FastAPI

```bash
cd backend
venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

(Las variables de `.env` solo se leen al iniciar el proceso, por lo que hay que reiniciar
uvicorn cada vez que cambie `backend/.env`.)

### 9. Probar `/health`

```bash
curl http://127.0.0.1:8000/health
```

Respuesta esperada una vez configurado correctamente:

```json
{"status": "ok", "rag_configurado": true}
```

`rag_configurado` es `true` solo cuando hay una API Key en el servidor **y**
`OPENAI_VECTOR_STORE_ID` configurado. El backend arranca y `/health` responde igual aunque
falte alguno de los dos.

### 10. Probar `/api/chat`

```bash
curl -X POST http://127.0.0.1:8000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"equipo": "Autoclave", "area": "Área de microbiología", "clase_detector": "", "pregunta": "¿Para qué se usa este equipo?"}'
```

También puedes probarlo desde la documentación interactiva en
`http://127.0.0.1:8000/docs`.

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

Si hay API Key (de cualquiera de las dos fuentes) pero falta `OPENAI_VECTOR_STORE_ID`,
tampoco se llama al modelo: se responde "El repositorio documental del Laboratorio de
Biotecnología aún no está configurado." (el proyecto exige que el asistente responda solo
con base en los documentos, nunca de forma libre).

## Restricción por equipo: file_search limitado a un solo manual

Cuando el usuario detecta/selecciona un equipo (cámara o ficha de equipo) y luego pregunta en
el chat, Android envía también `clase_detector` (la clase estable del detector, ej.
`bod_sensor`, NO el nombre bonito "BOD Sensor" — ver `Equipo.getClaseDetector()` /
`labels.txt`). Si esa clase tiene un manual **verificado** en
`backend/app/services/equipo_manual_map.py`, el backend agrega un filtro de atributo a la
herramienta `file_search`:

```python
tools=[{
    "type": "file_search",
    "vector_store_ids": [OPENAI_VECTOR_STORE_ID],
    "filters": {"type": "eq", "key": "equipo_clase", "value": "<valor del mapeo>"},
}]
```

Esto es una restricción real de OpenAI (excluye chunks de cualquier archivo que no tenga ese
atributo), no un simple texto agregado al prompt. Si la clase no está en el mapeo (o viene
vacía), no se agrega `filters` y la búsqueda sigue siendo sobre todo el Vector Store, igual
que antes.

### Requisito: asignar el atributo al archivo en OpenAI

El filtro solo funciona si el archivo del manual, DENTRO del Vector Store, tiene asignado el
atributo `equipo_clase` con el mismo valor que aparece en `equipo_manual_map.py`. Eso se hace
una vez por archivo, a mano (no lo hace la app ni el arranque del backend):

```python
from openai import OpenAI
client = OpenAI(api_key="...")
client.vector_stores.files.update(
    vector_store_id="vs_...",
    file_id="file_...",
    attributes={"equipo_clase": "medidor_demanda_bioquimica_oxigeno"},
)
```

Para encontrar el `file_id` y el nombre real de cada archivo:

```python
files = list(client.vector_stores.files.list(vector_store_id="vs_..."))
for vsf in files:
    f = client.files.retrieve(vsf.id)
    print(vsf.id, f.filename, vsf.attributes)
```

### Estado actual del mapeo (modelo TFLite de 7 clases, 100 épocas — el integrado hoy)

Los tres archivos siguientes ya tienen el atributo `equipo_clase` asignado en el Vector Store
(verificado por nombre Y contenido real extraído por OpenAI, no solo por el nombre del PDF):

| `clase_detector`            | Atributo `equipo_clase`                | Manual verificado (file_id)                                              | Estado |
|------------------------------|-----------------------------------------|----------------------------------------------------------------------------|--------|
| `bod_sensor`                  | `medidor_demanda_bioquimica_oxigeno`   | "Medidor demanda de bioquimica de oxigeno.pdf" (`file-4ALEV4gAa7H19KuGJsvJEQ`) — manual real de un RESPIROMETRIC Sensor | Activo |
| `armario_calefactor_ule600`   | `armario_calefactor_ule600`            | "Memmert - Armario calefactor ULE 600.pdf" (`file-WZ4qbNPqkT1csKSwpmT4YV`)  | Activo |
| `horno_secado`                | `horno_secado_biobase`                 | "Biobase incubadora  (1).pdf" (`file-TgwZrRxz6bL2soVnAKxAUN`) — contenido real: "Drying Oven/Incubator (Dual-use) - Biobase Biolab Co.,Ltd", modo "dry oven" 80-200°C | Activo |
| `lux_fc_meter`, `medidor_multiparametro`, `electroforesis_owl_easycast`, `uv_pcr_workstation` | — | — | Sin manual asociado todavía |

Nota sobre `bod_sensor`: el Vector Store tiene un SEGUNDO archivo con nombre casi idéntico
("MEDIDOR DEMANDA BIOQUIMICA DE OXIGENO.pdf", en mayúsculas) del que OpenAI no pudo extraer
texto (PDF escaneado sin OCR — "No text could be parsed..."). A propósito NO se usó ese
archivo para el atributo: restringir file_search a un archivo sin texto indexable habría hecho
que el asistente respondiera siempre "sin información", aunque el manual correcto sí exista.

Nota sobre `horno_secado`: la verificación por contenido dio el resultado inverso al que
sugerían los nombres de archivo. "Horno de secado de conveccion forzada redline.pdf" es un
manual real pero de un horno **redLINE de BINDER GmbH** (otra marca), no BIOBASE. El manual
BIOBASE real resultó ser "Biobase incubadora  (1).pdf" (uso dual horno de secado/incubadora).

Las clases del **nuevo modelo de 6 equipos** (en entrenamiento) no se agregan aquí hasta que
ese modelo esté validado e integrado.

## Reglas que sigue el asistente

- Responde exclusivamente con información recuperada de los documentos indexados en
  `Laboratorio_Biotecnologia_UTEQ`.
- No inventa datos técnicos ni usa conocimiento general para completar información técnica
  faltante.
- Puede responder sobre: función, componentes, operación, seguridad, EPP, riesgos,
  mantenimiento y prácticas académicas.
- Muestra las fuentes (nombre de archivo) realmente citadas por `file_search` en la
  respuesta — nunca inventa página o sección si la API no la devuelve.
- Si no hay información suficiente, responde exactamente:
  > "No tengo suficiente información, por favor pregúntele al laboratorista encargado."
