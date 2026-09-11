"""
Configuración central del backend.

Todas las claves y secretos se leen exclusivamente desde variables de entorno.
NUNCA se codifican valores reales aquí ni se envían al cliente Android.
"""
import os

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    # python-dotenv es opcional: si no está instalado, simplemente se
    # usan las variables de entorno ya presentes en el sistema.
    pass


class Settings:
    """Lee la configuración desde variables de entorno, con valores por defecto seguros."""

    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    # OBSOLETA para el flujo de RAG por equipo: desde que cada clase_detector resuelve a su
    # propio Vector Store dedicado (ver app/services/equipo_manual_map.py), esta variable ya
    # NO se usa para decidir qué Vector Store consultar — cada consulta usa exclusivamente el
    # store del equipo detectado, nunca uno "general". Se conserva sin borrar (leída, no
    # eliminada) solo por si algún despliegue existente todavía la tiene configurada; no
    # rompe nada dejarla, pero ya no participa en RagService.consultar().
    openai_vector_store_id: str = os.getenv("OPENAI_VECTOR_STORE_ID", "")
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

    @property
    def rag_configurado(self) -> bool:
        # Ya NO exige openai_vector_store_id (ver comentario arriba): con un Vector Store por
        # equipo ya no hay un único ID "central" que deba estar configurado para que el RAG
        # funcione — basta con que haya una API Key disponible. Si clase_detector no resuelve
        # a un equipo conocido, RagService.consultar() ya maneja ese caso con el fallback
        # estándar (ver equipo_manual_map.py), sin necesidad de bloquear el chat aquí.
        return bool(self.openai_api_key)


settings = Settings()
