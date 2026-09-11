from typing import Optional

from fastapi import APIRouter, Header, HTTPException

from app.models.schemas import ChatRequest, ChatResponse
from app.services.rag_service import (
    RagAutenticacionError,
    RagCuotaExcedidaError,
    RagErrorServicio,
    RagSolicitudInvalidaError,
    rag_service,
)

router = APIRouter()


@router.post("/api/chat", response_model=ChatResponse)
def chat(
    request: ChatRequest,
    x_openai_api_key: Optional[str] = Header(default=None, alias="X-OpenAI-API-Key"),
) -> ChatResponse:
    """
    Recibe únicamente equipo, área, clase_detector y pregunta (nunca documentos completos).
    clase_detector es la clase estable del detector YOLO (labels.txt), usada para resolver el
    Vector Store dedicado de ese equipo y restringir file_search a él (ver
    equipo_manual_map.py); puede venir vacía. Opcionalmente,
    Android puede enviar una OpenAI API Key propia en el encabezado "X-OpenAI-API-Key"; si no
    la envía, se usa la OPENAI_API_KEY del servidor (si existe). Esa clave nunca se guarda:
    se usa en memoria solo para esta petición.

    Toda la recuperación de documentos (RAG) y la consulta al LLM ocurren aquí, en el
    backend, nunca en el dispositivo Android.
    """
    try:
        resultado = rag_service.consultar(
            equipo=request.equipo,
            area=request.area,
            pregunta=request.pregunta,
            clase_detector=request.clase_detector,
            api_key_override=x_openai_api_key,
        )
    except RagAutenticacionError:
        raise HTTPException(
            status_code=401,
            detail="No se pudo autenticar con el proveedor de inteligencia artificial.",
        )
    except RagCuotaExcedidaError:
        raise HTTPException(
            status_code=429,
            detail="Se alcanzó el límite de uso del proveedor de inteligencia artificial.",
        )
    except RagSolicitudInvalidaError:
        raise HTTPException(
            status_code=400,
            detail="No se pudo procesar la consulta al asistente inteligente.",
        )
    except RagErrorServicio:
        raise HTTPException(
            status_code=500,
            detail="Ocurrió un error al consultar el asistente inteligente.",
        )

    return ChatResponse(**resultado)
