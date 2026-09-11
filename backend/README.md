> Este backend se conserva como herramienta opcional. La app Android consulta directamente
> a OpenAI por HTTPS; no necesita ejecutar este servidor. Consulta
> [Bio directo](../docs/BIO_OPENAI_DIRECTO.md). `backend/.env` se lee al compilar para incluir
> la credencial actual en el APK, según la decisión del propietario.

# Backend — Asistente Laboratorio de Biotecnología UTEQ

Backend en Python + FastAPI, independiente del módulo Android. Expone:

- `GET /health` — comprueba que el servidor está operativo.
- `POST /api/chat` — recibe `equipo`, `area` y `pregunta`, y responde usando RAG sobre un
  Vector Store de OpenAI (ver `../docs/RAG_SETUP.md`).

## Instalación

```bash
cd backend
python -m venv venv
venv\Scripts\activate        # Windows
# source venv/bin/activate   # Linux/Mac
pip install -r requirements.txt
```

## Configuración

```bash
copy .env.example .env       # Windows
# cp .env.example .env       # Linux/Mac
```

Editar `.env` y completar `OPENAI_API_KEY`. El servidor funciona igualmente si no está
configurada: `/health` seguirá respondiendo y `/api/chat` devolverá un mensaje controlado
indicando que el RAG aún no está configurado. No hace falta ningún `OPENAI_VECTOR_STORE_ID`:
cada equipo (`clase_detector`) usa su propio Vector Store, ya definido en código — ver
`app/services/equipo_manual_map.py` y `docs/RAG_SETUP.md`.

Alternativamente, cada petición a `/api/chat` puede incluir el encabezado
`X-OpenAI-API-Key` (usado por la app Android cuando el usuario configura su propia clave
desde Ajustes). Esa clave tiene prioridad sobre `OPENAI_API_KEY` y nunca se guarda en el
servidor (ni en archivos, ni en logs): solo se usa en memoria para esa petición.

## Ejecución

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

`--host 0.0.0.0` es necesario para que un teléfono real en la misma red pueda alcanzar el
servidor (ver `ApiClient.BASE_URL` en la app Android).

## Pruebas rápidas

```bash
curl http://localhost:8000/health

curl -X POST http://localhost:8000/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"equipo\": \"Autoclave\", \"area\": \"Área de microbiología\", \"pregunta\": \"¿Para qué se usa?\"}"
```

También puede probarse desde la documentación interactiva en `http://localhost:8000/docs`.
