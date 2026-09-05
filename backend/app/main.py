from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import chat, health

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
