"""
Mapeo centralizado "clase estable del detector -> Vector Store de OpenAI dedicado a ese
equipo" (arquitectura de UN Vector Store POR EQUIPO, no un único Vector Store compartido para
todo el laboratorio — ver rag_service.py). Única fuente de verdad de este mapeo en todo el
backend: ningún endpoint ni servicio debe repetirlo.

Clave: el `clase_detector` que envía Android (ver ChatRequest / Equipo.getClaseDetector()),
        idéntico a los `name_internal` de ml/classes.json y a las líneas de labels.txt del
        modelo TFLite integrado (YOLO11m, 17 clases — ver
        ml/classes.json#_comment_migracion_17_clases). Este archivo NO decide ni valida esas
        clases: son responsabilidad del pipeline de ml/ y de LabRepository.java.

Valor: el `id` (formato `vs_...`) del Vector Store de OpenAI dedicado EXCLUSIVAMENTE a ese
       equipo. Los 17 Vector Stores YA EXISTEN en OpenAI (creados y poblados manualmente fuera
       de este backend, uno por equipo, en
       https://platform.openai.com/storage/vector_stores) — este archivo solo los referencia
       por su ID; nunca los crea, sube archivos a ellos, ni los modifica.

Aislamiento real: `file_search` se restringe a un único `vector_store_id` por consulta (ver
RagService._consultar_openai) — nunca se consultan los 17 a la vez, nunca se elige uno al
azar, y no existe ningún Vector Store "general" de respaldo. Si `clase_detector` no resuelve a
ninguna entrada de este mapeo (vacía, `None`, con errores de tipeo, o una clase que no está en
las 17), `resolver_vector_store` devuelve `None` y RagService NO llama a OpenAI en absoluto:
responde con el mensaje estándar de "sin información" (ver
rag_service.MENSAJE_SIN_INFORMACION) en vez de inventar una respuesta o buscar en un store
equivocado.

OBSOLETO (arquitectura anterior: un solo Vector Store compartido para todo el laboratorio,
con un filtro por atributo `equipo_clase` asignado a mano a archivos individuales dentro de
ese store) — reemplazada por completo por este mapeo 1:1 clase→store. La variable de entorno
`OPENAI_VECTOR_STORE_ID` (ver app/config.py) ya NO participa en la resolución del store de una
consulta por equipo; se conserva sin borrar solo por si algún despliegue existente todavía la
tiene configurada (ver app/config.py para el detalle de qué sigue usándola).

Para agregar o corregir un equipo: obtener su `vector_store_id` real (el Vector Store ya debe
existir y estar poblado) y actualizar la entrada correspondiente aquí. Nunca reutilizar un ID
entre dos equipos distintos, ni reordenar las entradas.
"""
from typing import Optional

# Fuente única de verdad: clase_detector (labels.txt / ml/classes.json, 17 clases,
# class_id 0-16) -> vector_store_id dedicado de ese equipo. NO reordenar, NO reutilizar IDs,
# NO agregar clases que no existan en labels.txt.
EQUIPO_VECTOR_STORE_MAP = {
    # class_id 0
    "autoclave_vapor_mesa_gemmy_sturdy": "vs_6aa2af15cde08191830970b3607acc12",
    # class_id 1
    "cabina_flujo_laminar_mini_c4": "vs_6aa2af2949708191b9b1bb13bfb4d08b",
    # class_id 2
    "centrifuga_laboratorio_ohaus": "vs_6aa2af345c08819194f81ee566f43f4e",
    # class_id 3
    "medidor_mesa_electroquimica_ohaus": "vs_6aa2af43ca308191b57b1a0ebc5d0685",
    # class_id 4
    "termociclador_miniamp_plus": "vs_6aa2af52a49881918d0391c6c3ba67f5",
    # class_id 5
    "bano_maria_memmert": "vs_6aa2af5afc5c8191889a54d68ce7948a",
    # class_id 6
    "cubeta_electroforesis_horizontal_gel": "vs_6aa2af6813588191b7a3645b0ad5ddb9",
    # class_id 7
    "espectrofotometro_visible_digital_unico": "vs_6aa2af7224208191bd95a834ae0c70cf",
    # class_id 8
    "horno_secado_conveccion_forzada_redline": "vs_6aa2af7bf3bc81919401dd2973930511",
    # class_id 9
    "medidor_demanda_bioquimica_oxigeno": "vs_6aa2af8464e48191bf2b516422716e24",
    # class_id 10
    "balanza_analitica_ohaus": "vs_6aa2af8f5b08819184a82939f9595fe4",
    # class_id 11
    "camara_incubacion_uv_prc_workstation": "vs_6aa2af9ba7288191ab1cadac5069b9e2",
    # class_id 12
    "estufa_laboratorio_doble_puerta": "vs_6aa2afa52ebc8191ad4da1aa876f512b",
    # class_id 13
    "horno_secado_biobase": "vs_6aa2afafd1c08191b0618fada76b4763",
    # class_id 14
    "incubador_agitacion_orbital_incu_shaker": "vs_6aa2afb7c12881918da57c95c7ae2215",
    # class_id 15
    "microscopio_boeco": "vs_6aa2afc024c48191be6541083305a21a",
    # class_id 16
    "camara_seguridad_biologica_biobase": "vs_6aa2afc8c3f88191a9c07603ba6791ea",
}


def resolver_vector_store(clase_detector: Optional[str]) -> Optional[str]:
    """Devuelve el `vector_store_id` dedicado a `clase_detector`, o `None` si la clase viene
    vacía, es `None`, o no corresponde a ninguna de las 17 clases conocidas. Nunca lanza una
    excepción por una clase inválida/desconocida: el llamador (RagService) debe tratar `None`
    como "no se puede determinar un Vector Store seguro para esta consulta"."""
    if not clase_detector:
        return None
    return EQUIPO_VECTOR_STORE_MAP.get(clase_detector)
