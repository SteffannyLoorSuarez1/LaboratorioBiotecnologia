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
    openai_vector_store_id: str = os.getenv("OPENAI_VECTOR_STORE_ID", "")
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

    @property
    def rag_configurado(self) -> bool:
        return bool(self.openai_api_key) and bool(self.openai_vector_store_id)


settings = Settings()
