"""Esquemas Pydantic usados por la API del backend."""
from typing import List

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    equipo: str = Field(default="", description="Nombre del equipo consultado, si aplica.")
    area: str = Field(default="", description="Nombre del área del laboratorio, si aplica.")
    clase_detector: str = Field(
        default="",
        description=(
            "Clase estable del detector YOLO para el equipo seleccionado (labels.txt / "
            "Equipo.claseDetector), NO el nombre bonito de 'equipo'. Se usa para resolver el "
            "Vector Store dedicado de ese equipo (ver equipo_manual_map.py); puede venir "
            "vacía si no hay equipo seleccionado o si esa clase no tiene Vector Store asociado."
        ),
    )
    pregunta: str = Field(..., min_length=1, description="Pregunta formulada por el usuario.")


class Fuente(BaseModel):
    archivo: str
    referencia: str = ""


class ChatResponse(BaseModel):
    respuesta: str
    encontrado: bool
    fuentes: List[Fuente] = []


class HealthResponse(BaseModel):
    status: str
    rag_configurado: bool
