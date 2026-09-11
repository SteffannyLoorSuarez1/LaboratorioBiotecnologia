"""
Servicio de recuperación aumentada por generación (RAG) contra Vector Stores de OpenAI que
contienen los documentos del Laboratorio de Biotecnología (manuales, guías de prácticas,
protocolos y normas de seguridad).

Arquitectura: UN Vector Store POR EQUIPO (no uno solo compartido para todo el laboratorio).
`clase_detector` (ver ChatRequest) se resuelve a su `vector_store_id` dedicado mediante
`equipo_manual_map.resolver_vector_store` — única fuente de verdad de ese mapeo — y
`file_search` se restringe SIEMPRE a ese único store:

    cliente.responses.create(model=..., instructions=..., input=..., tools=[
        {"type": "file_search", "vector_store_ids": [vector_store_id]}
    ])

Nunca se consultan varios Vector Stores a la vez, nunca se elige uno al azar, y no existe
ningún Vector Store "general" de respaldo: si `clase_detector` no resuelve a un store conocido
(vacía, `None`, typo, o una clase fuera de las 17 activas), no se llama a OpenAI en absoluto
— ver `consultar()`.

Diseño desacoplado a propósito:
- Si no hay ninguna OpenAI API Key disponible (ni la del servidor ni una enviada desde
  Android), se responde de forma controlada sin llamar a OpenAI.
- Si `clase_detector` no resuelve a un Vector Store conocido, TAMPOCO se llama al modelo (el
  proyecto exige que el LLM responda basándose en los documentos del equipo correcto; sin
  saber cuál es ese equipo no hay documentos seguros que recuperar).
- El LLM debe responder EXCLUSIVAMENTE con la información recuperada de los documentos.
  Si no hay información suficiente, se devuelve el mensaje estándar exigido por el proyecto.

Prioridad de la API Key (ver app/routers/chat.py):
1. La enviada por Android en el encabezado "X-OpenAI-API-Key" (uso puntual, por petición).
2. La configurada en el servidor mediante la variable de entorno OPENAI_API_KEY.
3. Si ninguna existe: respuesta controlada.

La clave recibida desde Android NUNCA se guarda (ni en archivos, ni en base de datos, ni en
logs): se usa solo en memoria, durante esta función, para construir un cliente de OpenAI
temporal, y se descarta al finalizar la petición. Tampoco se incluye en mensajes de error
visibles para el usuario ni en logs (algunos proveedores devuelven un fragmento de la clave
enviada en el texto de error de autenticación, por lo que ese texto nunca se propaga).
"""
import logging
import time
from typing import Any, Dict, List, Optional

from app.config import settings
from app.services.equipo_manual_map import resolver_vector_store

logger = logging.getLogger(__name__)

MENSAJE_SIN_INFORMACION = (
    "No tengo suficiente información, por favor pregúntele al laboratorista encargado."
)

MENSAJE_SIN_API_KEY = (
    "El asistente inteligente aún no tiene una OpenAI API Key configurada. "
    "Configúrela desde la aplicación (Ajustes) o en el servidor. "
    "Consulte al docente o responsable del Laboratorio de Biotecnología."
)

MENSAJE_SOLICITUD_INVALIDA = (
    "No se pudo procesar la consulta al asistente inteligente."
)

INSTRUCCIONES_SISTEMA = (
    "Eres el asistente del Laboratorio de Biotecnología de la UTEQ.\n\n"
    "Responde únicamente con información sustentada en los documentos recuperados.\n"
    "No uses conocimiento general para completar información técnica faltante.\n\n"
    "Puedes responder acerca de:\n"
    "- función\n"
    "- componentes\n"
    "- operación\n"
    "- seguridad\n"
    "- EPP\n"
    "- riesgos\n"
    "- mantenimiento\n"
    "- prácticas académicas\n\n"
    "Si los documentos recuperados no contienen información suficiente, responde "
    f'EXACTAMENTE:\n"{MENSAJE_SIN_INFORMACION}"'
)


class RagAutenticacionError(Exception):
    """La API Key usada (del servidor o enviada desde Android) fue rechazada por OpenAI."""


class RagCuotaExcedidaError(Exception):
    """Se alcanzó el límite de uso/cuota del proveedor de inteligencia artificial."""


class RagSolicitudInvalidaError(Exception):
    """OpenAI rechazó la solicitud por ser inválida (BadRequestError): p. ej. un
    OPENAI_VECTOR_STORE_ID con formato incorrecto o un modelo inexistente."""


class RagErrorServicio(Exception):
    """Error genérico o de conexión al consultar el proveedor de inteligencia artificial."""


class RagService:

    def consultar(self, equipo: str, area: str, pregunta: str, clase_detector: str = "",
                   api_key_override: Optional[str] = None) -> Dict[str, Any]:
        api_key = (api_key_override or "").strip() or settings.openai_api_key

        if not api_key:
            return {
                "respuesta": MENSAJE_SIN_API_KEY,
                "encontrado": False,
                "fuentes": [],
            }

        # Resuelve el Vector Store DEDICADO de este equipo (arquitectura 1 equipo : 1 store,
        # ver equipo_manual_map.py). clase_detector vacía/None/typo/clase desconocida ->
        # vector_store_id es None -> NO se llama a OpenAI (nunca se busca en un store
        # "general" ni se elige uno al azar): se responde el fallback estándar directamente.
        vector_store_id = resolver_vector_store(clase_detector)
        if not vector_store_id:
            logger.info("[RAG] clase_detector=%s -> sin Vector Store asociado; no se consulta a OpenAI.",
                        clase_detector or "(vacia)")
            return {
                "respuesta": MENSAJE_SIN_INFORMACION,
                "encontrado": False,
                "fuentes": [],
            }

        logger.info("[RAG] clase_detector=%s vector_store=%s", clase_detector, vector_store_id)
        return self._consultar_openai(equipo, area, pregunta, vector_store_id, api_key)

    def _consultar_openai(self, equipo: str, area: str, pregunta: str, vector_store_id: str,
                           api_key: str) -> Dict[str, Any]:
        # Import perezoso: evita que el backend falle al arrancar si el paquete "openai"
        # no está instalado todavía.
        from openai import (
            APIConnectionError,
            APIError,
            AuthenticationError,
            BadRequestError,
            OpenAI,
            RateLimitError,
        )

        cliente = OpenAI(api_key=api_key)

        # El equipo y el área se incluyen como contexto para ayudar a recuperar los
        # fragmentos documentales relevantes; nunca se envían documentos completos.
        contexto = f"Área: {area or 'no especificada'}. Equipo: {equipo or 'no especificado'}."
        entrada_usuario = f"{contexto}\nPregunta: {pregunta}"

        # Aislamiento real por equipo: file_search se restringe al Vector Store DEDICADO de
        # este equipo (resuelto por el llamador, ver consultar()) — ya no hace falta ningún
        # filtro de atributo adicional (arquitectura anterior de un store único compartido),
        # porque este store no contiene manuales de ningún otro equipo.
        herramienta_file_search: Dict[str, Any] = {
            "type": "file_search",
            "vector_store_ids": [vector_store_id],
        }

        # Un fallo de conexión (p. ej. una resolución DNS intermitente hacia OpenAI) ocurre
        # ANTES de que la petición llegue a OpenAI, así que reintentarlo una sola vez es
        # seguro: nunca se duplica una consulta ya recibida por OpenAI.
        intentos_conexion_restantes = 1
        while True:
            try:
                respuesta = cliente.responses.create(
                    model=settings.openai_model,
                    instructions=INSTRUCCIONES_SISTEMA,
                    input=entrada_usuario,
                    tools=[herramienta_file_search],
                )
                break
            except AuthenticationError:
                # No se registra el mensaje de la excepción: algunos proveedores incluyen un
                # fragmento de la clave enviada en el texto del error.
                logger.warning("Autenticación con OpenAI rechazada (clave inválida).")
                raise RagAutenticacionError() from None
            except RateLimitError:
                logger.warning("Límite de uso/cuota de OpenAI alcanzado.")
                raise RagCuotaExcedidaError() from None
            except BadRequestError:
                logger.warning("OpenAI rechazó la solicitud por ser inválida (revisar model/vector_store_id).")
                raise RagSolicitudInvalidaError() from None
            except APIConnectionError:
                if intentos_conexion_restantes > 0:
                    intentos_conexion_restantes -= 1
                    logger.warning("Fallo de conexión hacia OpenAI (posible DNS/red transitoria); reintentando una vez.")
                    time.sleep(0.5)
                    continue
                logger.warning("No se pudo conectar con OpenAI tras reintentar (error de red del servidor hacia OpenAI).")
                raise RagErrorServicio() from None
            except APIError:
                logger.exception("Error de la API de OpenAI (detalle omitido).")
                raise RagErrorServicio() from None
            except Exception:
                logger.exception("Error inesperado al consultar OpenAI (detalle omitido).")
                raise RagErrorServicio() from None

        texto_respuesta = (respuesta.output_text or "").strip()
        fuentes = self._extraer_fuentes(respuesta)

        if not texto_respuesta or texto_respuesta == MENSAJE_SIN_INFORMACION:
            return {
                "respuesta": MENSAJE_SIN_INFORMACION,
                "encontrado": False,
                "fuentes": [],
            }

        return {
            "respuesta": texto_respuesta,
            "encontrado": True,
            "fuentes": fuentes,
        }

    def _extraer_fuentes(self, respuesta) -> List[Dict[str, str]]:
        """
        Extrae las fuentes citadas en la respuesta final, a partir de las anotaciones
        `file_citation` de los bloques `output_text` (ver openai.types.responses
        .response_output_text.AnnotationFileCitation). Solo ese tipo de anotación trae
        `filename`; el SDK no expone número de página ni sección, así que nunca se inventa
        ese dato: `referencia` se deja igual a `archivo` cuando no hay nada más específico.
        """
        fuentes: List[Dict[str, str]] = []
        try:
            for item in getattr(respuesta, "output", None) or []:
                if getattr(item, "type", None) != "message":
                    continue
                for contenido in getattr(item, "content", None) or []:
                    if getattr(contenido, "type", None) != "output_text":
                        continue
                    for anotacion in getattr(contenido, "annotations", None) or []:
                        if getattr(anotacion, "type", None) != "file_citation":
                            continue
                        archivo = getattr(anotacion, "filename", None)
                        if archivo:
                            fuentes.append({"archivo": archivo, "referencia": archivo})
        except Exception:  # noqa: BLE001 - las fuentes son informativas, no críticas
            return []

        # Elimina duplicados conservando el orden.
        vistos = set()
        unicas = []
        for fuente in fuentes:
            if fuente["archivo"] not in vistos:
                vistos.add(fuente["archivo"])
                unicas.append(fuente)
        return unicas


rag_service = RagService()
