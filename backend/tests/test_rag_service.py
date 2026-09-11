"""
Pruebas de RagService.consultar() para la arquitectura "un Vector Store por equipo" (ver
app/services/rag_service.py). Nunca llaman a OpenAI de verdad: `openai.OpenAI` se reemplaza
por un mock en cada prueba (ver `_mock_openai`), así que estas pruebas corren sin red y sin
necesitar OPENAI_API_KEY. Las pruebas contra la API real (con clase_detector real) están en
`test_rag_real.py`, y solo se ejecutan si hay una API Key configurada.
"""
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from app.config import settings
from app.models.schemas import ChatResponse
from app.services.equipo_manual_map import EQUIPO_VECTOR_STORE_MAP
from app.services.rag_service import MENSAJE_SIN_API_KEY, MENSAJE_SIN_INFORMACION, rag_service


@pytest.fixture(autouse=True)
def _api_key_de_prueba(monkeypatch):
    """La mayoría de las pruebas necesitan que `consultar()` pase el chequeo de API Key para
    poder llegar a la lógica de selección de Vector Store; las que prueban el caso "sin API
    Key" la desactivan explícitamente con monkeypatch dentro del propio test."""
    monkeypatch.setattr(settings, "openai_api_key", "sk-test-no-es-una-clave-real")


def _mock_openai(texto_respuesta: str, fuentes_annotations=None):
    """Crea el mock de `openai.OpenAI` que intercepta `_consultar_openai` (import perezoso
    `from openai import OpenAI` dentro del método: parchear el atributo en el módulo `openai`
    es suficiente porque ese import se re-resuelve en cada llamada)."""
    respuesta_falsa = SimpleNamespace(output_text=texto_respuesta, output=fuentes_annotations or [])
    mock_cliente = patch("openai.OpenAI").start()
    mock_cliente.return_value.responses.create.return_value = respuesta_falsa
    return mock_cliente


class TestClaseConocida:
    def test_selecciona_el_vector_store_correcto_para_la_clase(self):
        mock_openai = _mock_openai("El horno BIOBASE se usa para secado por convección.")
        try:
            resultado = rag_service.consultar(
                equipo="Horno de secado BIOBASE",
                area="Área de microbiología",
                pregunta="¿Para qué se usa?",
                clase_detector="horno_secado_biobase",
            )
        finally:
            patch.stopall()

        llamada = mock_openai.return_value.responses.create.call_args
        herramienta = llamada.kwargs["tools"][0]
        assert herramienta["type"] == "file_search"
        assert herramienta["vector_store_ids"] == [EQUIPO_VECTOR_STORE_MAP["horno_secado_biobase"]]
        assert "filters" not in herramienta  # arquitectura nueva: sin filtro de atributo

        assert resultado["encontrado"] is True
        assert resultado["respuesta"] == "El horno BIOBASE se usa para secado por convección."

    @pytest.mark.parametrize("clase", [
        "autoclave_vapor_mesa_gemmy_sturdy",
        "medidor_demanda_bioquimica_oxigeno",
        "horno_secado_biobase",
        "microscopio_boeco",
        "camara_seguridad_biologica_biobase",
    ])
    def test_aislamiento_entre_equipos_cada_uno_usa_solo_su_propio_store(self, clase):
        """Ninguna consulta debe incluir el vector_store_id de OTRO equipo ni consultar más de
        un store a la vez (punto 12/8 del pedido)."""
        mock_openai = _mock_openai("respuesta cualquiera")
        try:
            rag_service.consultar(equipo="x", area="y", pregunta="z", clase_detector=clase)
        finally:
            patch.stopall()

        herramienta = mock_openai.return_value.responses.create.call_args.kwargs["tools"][0]
        vector_store_ids_usados = herramienta["vector_store_ids"]
        assert vector_store_ids_usados == [EQUIPO_VECTOR_STORE_MAP[clase]]
        assert len(vector_store_ids_usados) == 1

        otros_ids = {vs_id for c, vs_id in EQUIPO_VECTOR_STORE_MAP.items() if c != clase}
        assert not (set(vector_store_ids_usados) & otros_ids)


class TestClaseDesconocida:
    @pytest.mark.parametrize("clase_detector", [None, "", "clase_que_no_existe", "Horno_Secado_Biobase"])
    def test_no_llama_a_openai_y_devuelve_el_fallback_estandar(self, clase_detector):
        mock_openai = patch("openai.OpenAI").start()
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="¿algo?", clase_detector=clase_detector,
            )
        finally:
            patch.stopall()

        mock_openai.assert_not_called()
        assert resultado == {"respuesta": MENSAJE_SIN_INFORMACION, "encontrado": False, "fuentes": []}

    def test_clase_detector_omitida_por_completo_usa_el_valor_por_defecto(self):
        """ChatRequest permite clase_detector vacía por defecto (equipo aún no seleccionado);
        consultar() no debe exigir el argumento ni crashear si no se pasa."""
        mock_openai = patch("openai.OpenAI").start()
        try:
            resultado = rag_service.consultar(equipo="x", area="y", pregunta="¿algo?")
        finally:
            patch.stopall()

        mock_openai.assert_not_called()
        assert resultado["respuesta"] == MENSAJE_SIN_INFORMACION


class TestSinApiKey:
    def test_sin_api_key_no_llama_a_openai(self, monkeypatch):
        monkeypatch.setattr(settings, "openai_api_key", "")
        mock_openai = patch("openai.OpenAI").start()
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="¿algo?", clase_detector="horno_secado_biobase",
            )
        finally:
            patch.stopall()

        mock_openai.assert_not_called()
        assert resultado == {"respuesta": MENSAJE_SIN_API_KEY, "encontrado": False, "fuentes": []}

    def test_api_key_enviada_desde_android_tiene_prioridad_sobre_la_del_servidor(self, monkeypatch):
        monkeypatch.setattr(settings, "openai_api_key", "")  # sin clave en el servidor
        mock_openai = _mock_openai("respuesta con clave de Android")
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="¿algo?",
                clase_detector="microscopio_boeco",
                api_key_override="sk-clave-del-usuario-android",
            )
        finally:
            patch.stopall()

        mock_openai.assert_called_once_with(api_key="sk-clave-del-usuario-android")
        assert resultado["encontrado"] is True


class TestFallbackYFuentes:
    def test_fallback_cuando_el_llm_responde_el_mensaje_estandar(self):
        mock_openai = _mock_openai(MENSAJE_SIN_INFORMACION)
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="¿algo no documentado?",
                clase_detector="estufa_laboratorio_doble_puerta",
            )
        finally:
            patch.stopall()

        assert resultado == {"respuesta": MENSAJE_SIN_INFORMACION, "encontrado": False, "fuentes": []}

    def test_fallback_cuando_la_respuesta_viene_vacia(self):
        mock_openai = _mock_openai("")
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="¿algo?", clase_detector="balanza_analitica_ohaus",
            )
        finally:
            patch.stopall()

        assert resultado["encontrado"] is False
        assert resultado["respuesta"] == MENSAJE_SIN_INFORMACION

    def test_conserva_las_fuentes_citadas_por_file_search(self):
        anotacion = SimpleNamespace(type="file_citation", filename="manual_autoclave.pdf")
        contenido = SimpleNamespace(type="output_text", annotations=[anotacion])
        item = SimpleNamespace(type="message", content=[contenido])
        mock_openai = _mock_openai("El autoclave esteriliza material de laboratorio.", fuentes_annotations=[item])
        try:
            resultado = rag_service.consultar(
                equipo="Autoclave", area="x", pregunta="¿para qué sirve?",
                clase_detector="autoclave_vapor_mesa_gemmy_sturdy",
            )
        finally:
            patch.stopall()

        assert resultado["fuentes"] == [{"archivo": "manual_autoclave.pdf", "referencia": "manual_autoclave.pdf"}]


class TestPayloadCompatibleConAndroid:
    """El dict devuelto por consultar() debe seguir siendo válido como ChatResponse (lo que
    espera app/routers/chat.py para construir la respuesta HTTP que consume Android)."""

    def test_respuesta_exitosa_es_un_chatresponse_valido(self):
        anotacion = SimpleNamespace(type="file_citation", filename="manual.pdf")
        contenido = SimpleNamespace(type="output_text", annotations=[anotacion])
        item = SimpleNamespace(type="message", content=[contenido])
        _mock_openai("Respuesta real basada en el manual.", fuentes_annotations=[item])
        try:
            resultado = rag_service.consultar(
                equipo="x", area="y", pregunta="z", clase_detector="cabina_flujo_laminar_mini_c4",
            )
        finally:
            patch.stopall()

        chat_response = ChatResponse(**resultado)  # no debe lanzar ValidationError
        assert chat_response.encontrado is True
        assert chat_response.fuentes[0].archivo == "manual.pdf"

    def test_respuesta_de_fallback_es_un_chatresponse_valido(self):
        resultado = rag_service.consultar(equipo="x", area="y", pregunta="z", clase_detector=None)
        chat_response = ChatResponse(**resultado)  # no debe lanzar ValidationError
        assert chat_response.encontrado is False
        assert chat_response.fuentes == []
