from fastapi import APIRouter

from app.config import settings
from app.models.schemas import HealthResponse

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Indica que el servidor está operativo. Debe funcionar incluso si el RAG
    todavía no está configurado (sin OPENAI_API_KEY / OPENAI_VECTOR_STORE_ID)."""
    return HealthResponse(status="ok", rag_configurado=settings.rag_configurado)
