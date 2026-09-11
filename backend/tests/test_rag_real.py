"""
Pruebas REALES contra OpenAI (RAG por equipo) — hacen llamadas de red de verdad y consumen
cuota de la OPENAI_API_KEY configurada en backend/.env. NO se ejecutan con un `pytest` normal:
se saltan automáticamente salvo que la variable de entorno RAG_REAL_TESTS=1 esté presente Y
haya una OPENAI_API_KEY configurada.

Para ejecutarlas (desde backend/, con el venv activo):

    set RAG_REAL_TESTS=1
    venv\\Scripts\\python.exe -m pytest tests/test_rag_real.py -v -s

Cubren el punto 14 del pedido: para varios equipos reales, verifica que (1) no hay excepción
sin manejar, (2) la respuesta es estructuralmente válida (encontrado bool, fuentes lista), y
(3) una pregunta cuya respuesta solo estaría en el manual de OTRO equipo produce el fallback
(encontrado=False), nunca información filtrada de ese otro equipo — prueba cruzada.
"""
import os

import pytest

from app.services.equipo_manual_map import EQUIPO_VECTOR_STORE_MAP
from app.services.rag_service import rag_service

pytestmark = pytest.mark.skipif(
    os.getenv("RAG_REAL_TESTS") != "1" or not os.getenv("OPENAI_API_KEY"),
    reason="Pruebas reales contra OpenAI: opcionales, requieren RAG_REAL_TESTS=1 y OPENAI_API_KEY.",
)

EQUIPOS_A_PROBAR = [
    "autoclave_vapor_mesa_gemmy_sturdy",
    "medidor_demanda_bioquimica_oxigeno",
    "horno_secado_biobase",
    "microscopio_boeco",
    "camara_seguridad_biologica_biobase",
]


@pytest.mark.parametrize("clase_detector", EQUIPOS_A_PROBAR)
def test_consulta_real_selecciona_su_store_y_responde_sin_crashear(clase_detector):
    resultado = rag_service.consultar(
        equipo=clase_detector,
        area="",
        pregunta="¿Para qué se usa este equipo y qué EPP se requiere?",
        clase_detector=clase_detector,
    )
    assert isinstance(resultado["respuesta"], str) and resultado["respuesta"]
    assert isinstance(resultado["encontrado"], bool)
    assert isinstance(resultado["fuentes"], list)
    print(f"\n[REAL] clase_detector={clase_detector} "
          f"vector_store={EQUIPO_VECTOR_STORE_MAP[clase_detector]}\n"
          f"       encontrado={resultado['encontrado']} fuentes={resultado['fuentes']}\n"
          f"       respuesta={resultado['respuesta'][:200]!r}")


def test_prueba_cruzada_horno_biobase_no_debe_responder_sobre_el_autoclave():
    """Selecciona horno_secado_biobase y pregunta algo específico del manual de OTRO equipo
    (autoclave). Resultado esperado: fallback (encontrado=False) — el Vector Store del horno
    no tiene ese documento, así que file_search no debería encontrar nada relevante."""
    resultado = rag_service.consultar(
        equipo="Horno de secado BIOBASE",
        area="",
        pregunta="¿Cuál es la presión y temperatura de esterilización del autoclave de vapor?",
        clase_detector="horno_secado_biobase",
    )
    print(f"\n[REAL][cruzada] encontrado={resultado['encontrado']}\n"
          f"                respuesta={resultado['respuesta']!r}")
    assert resultado["encontrado"] is False, (
        "El horno_secado_biobase respondió como si tuviera información del autoclave: "
        "posible fuga entre Vector Stores. respuesta=" + resultado["respuesta"]
    )
