"""
Mapeo centralizado "clase estable del detector -> manual del equipo" usado para restringir
file_search a un único documento dentro del Vector Store (ver rag_service.py).

Clave: el `clase_detector` que envía Android (ver ChatRequest / Equipo.getClaseDetector()),
        idéntico a los `name_internal` de `ml/classes.json` y a las líneas de `labels.txt` del
        modelo TFLite ACTUALMENTE integrado (7 clases, el de 100 épocas). Este archivo NO decide
        ni valida esas clases: son responsabilidad del pipeline de ml/ y de LabRepository.java.
        No agregar aquí clases del nuevo modelo de 6 equipos hasta validarlo.

Valor: el valor del atributo `equipo_clase` que se asigna, UNA VEZ y a mano, al archivo
       correspondiente dentro del Vector Store de OpenAI:

           client.vector_stores.files.update(
               vector_store_id=<OPENAI_VECTOR_STORE_ID>,
               file_id=<file_id del manual>,
               attributes={"equipo_clase": "<valor de este mapeo>"},
           )

       (ver script de verificación/asignación en docs/RAG_SETUP.md). Mientras un archivo no
       tenga ese atributo asignado, el filtro de rag_service.py no encontrará nada para esa
       clase y busca sin restricción; asignar el atributo es un paso manual, no algo que este
       backend haga solo.

Para agregar un equipo nuevo: 1) confirmar la clase exacta en labels.txt/classes.json,
2) confirmar a mano cuál archivo del Vector Store es su manual real (por nombre Y contenido,
nunca solo por coincidencia de texto en el nombre), 3) asignarle el atributo `equipo_clase` en
OpenAI, 4) agregar la entrada aquí. Sin los 3 primeros pasos, agregar la entrada aquí no tiene
efecto (o, peor, restringiría la búsqueda a un archivo que no es el correcto).
"""
from typing import Optional

CLAVE_ATRIBUTO_VECTOR_STORE = "equipo_clase"

# Solo las clases YA VERIFICADAS (nombre Y contenido del manual confirmados a mano en el
# Vector Store) están activas aquí. Ver docs/RAG_SETUP.md para el estado de cada una.
CLASE_DETECTOR_A_ATRIBUTO_EQUIPO = {
    # bod_sensor = "BOD Sensor" en ml/classes.json (class_id 0) = medidor de Demanda
    # Bioquímica de Oxígeno. Manual confirmado por CONTENIDO (no solo por nombre) en el Vector
    # Store: "Medidor demanda de bioquimica de oxigeno.pdf" (file-4ALEV4gAa7H19KuGJsvJEQ),
    # manual real de un "RESPIROMETRIC Sensor". Atención: existe un segundo archivo con nombre
    # casi idéntico en mayúsculas ("MEDIDOR DEMANDA BIOQUIMICA DE OXIGENO.pdf") que es un PDF
    # escaneado del que OpenAI NO pudo extraer texto ("No text could be parsed..."); ese NO es
    # el que se etiquetó, a propósito, porque restringir la búsqueda a un archivo sin texto
    # indexable rompería el RAG (siempre respondería "sin información").
    "bod_sensor": "medidor_demanda_bioquimica_oxigeno",

    # armario_calefactor_ule600 = "Armario calefactor ULE 600" en ml/classes.json (class_id 4).
    # Manual confirmado por nombre Y contenido en el Vector Store: "Memmert - Armario
    # calefactor ULE 600.pdf" (contenido real: manual de hornos MEMMERT UE/BE/ULE).
    "armario_calefactor_ule600": "armario_calefactor_ule600",

    # horno_secado (class_id 5, "Horno de secado" / BIOBASE). Verificado por CONTENIDO, no por
    # nombre — el resultado fue el inverso de lo que sugerían los nombres de archivo:
    #   - "Horno de secado de conveccion forzada redline.pdf": manual real y extenso, pero de
    #     un horno "redLINE" de BINDER GmbH (marca alemana) — NO es BIOBASE. Descartado pese a
    #     que su nombre de archivo es el que más se parece a "horno de secado".
    #   - "Biobase incubadora  (1).pdf": su contenido real dice "Drying Oven/Incubator
    #     (Dual-use) - Biobase Biolab Co.,Ltd", documenta explícitamente el modo "dry oven"
    #     (80-200°C) y modelos BOV-20/D53/D87/D149/D248. Es, por contenido, un horno de secado
    #     BIOBASE (de uso dual como incubadora) — confirmado con el usuario antes de activar.
    "horno_secado": "horno_secado_biobase",
}

# Clases del NUEVO modelo de 6 equipos (en entrenamiento): NO agregar entradas aquí todavía.
# Se hará cuando ese modelo esté validado y el usuario lo indique explícitamente.


def resolver_atributo_equipo(clase_detector: Optional[str]) -> Optional[str]:
    """Devuelve el valor de atributo `equipo_clase` asociado a una clase del detector, o
    ``None`` si la clase viene vacía o todavía no tiene un manual verificado y activo."""
    if not clase_detector:
        return None
    return CLASE_DETECTOR_A_ATRIBUTO_EQUIPO.get(clase_detector)
