import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import chat, health

# Uvicorn solo configura handlers para sus propios loggers ("uvicorn", "uvicorn.access",
# "uvicorn.error"), no para el logger raíz: sin esto, cualquier logger.info(...) del código de
# la aplicación (p. ej. los logs "[RAG] clase_detector=... vector_store=..." de
# app/services/rag_service.py) se descarta en silencio, porque el logger raíz no tiene ningún
# handler y su "handler de último recurso" solo imprime WARNING o más severo. Nivel INFO:
# suficiente para diagnóstico (qué Vector Store se seleccionó) sin volverse ruidoso.
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(
    title="Asistente Laboratorio de Biotecnología UTEQ",
    description="Backend de detección de equipos y asistente inteligente con RAG.",
    version="0.1.0",
)

# CORS abierto para facilitar las pruebas durante el desarrollo desde la app Android
# y herramientas como Swagger UI. Restringir en producción si aplica.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(chat.router)
