"""
Pruebas del mapeo centralizado clase_detector -> Vector Store (ver
app/services/equipo_manual_map.py). Cubren exactamente lo pedido: 17 entradas, las 17 clases
esperadas presentes, sin duplicados (clases ni IDs), sin IDs vacíos, y que cada clase resuelva
exactamente su ID esperado (incluida la pareja horno_secado_biobase / horno_secado_conveccion_
forzada_redline, que no deben compartir Vector Store).
"""
import pytest

from app.services.equipo_manual_map import EQUIPO_VECTOR_STORE_MAP, resolver_vector_store

# Las 17 clases del modelo YOLO11m integrado (ver ml/classes.json / labels.txt), en el mismo
# orden (class_id 0-16). Si este test falla por una clase faltante/sobrante, es la primera
# señal de que el mapeo se desincronizó del modelo real.
CLASES_ESPERADAS = [
    "autoclave_vapor_mesa_gemmy_sturdy",
    "cabina_flujo_laminar_mini_c4",
    "centrifuga_laboratorio_ohaus",
    "medidor_mesa_electroquimica_ohaus",
    "termociclador_miniamp_plus",
    "bano_maria_memmert",
    "cubeta_electroforesis_horizontal_gel",
    "espectrofotometro_visible_digital_unico",
    "horno_secado_conveccion_forzada_redline",
    "medidor_demanda_bioquimica_oxigeno",
    "balanza_analitica_ohaus",
    "camara_incubacion_uv_prc_workstation",
    "estufa_laboratorio_doble_puerta",
    "horno_secado_biobase",
    "incubador_agitacion_orbital_incu_shaker",
    "microscopio_boeco",
    "camara_seguridad_biologica_biobase",
]

MAPEO_ESPERADO = {
    "autoclave_vapor_mesa_gemmy_sturdy": "vs_6aa2af15cde08191830970b3607acc12",
    "cabina_flujo_laminar_mini_c4": "vs_6aa2af2949708191b9b1bb13bfb4d08b",
    "centrifuga_laboratorio_ohaus": "vs_6aa2af345c08819194f81ee566f43f4e",
    "medidor_mesa_electroquimica_ohaus": "vs_6aa2af43ca308191b57b1a0ebc5d0685",
    "termociclador_miniamp_plus": "vs_6aa2af52a49881918d0391c6c3ba67f5",
    "bano_maria_memmert": "vs_6aa2af5afc5c8191889a54d68ce7948a",
    "cubeta_electroforesis_horizontal_gel": "vs_6aa2af6813588191b7a3645b0ad5ddb9",
    "espectrofotometro_visible_digital_unico": "vs_6aa2af7224208191bd95a834ae0c70cf",
    "horno_secado_conveccion_forzada_redline": "vs_6aa2af7bf3bc81919401dd2973930511",
    "medidor_demanda_bioquimica_oxigeno": "vs_6aa2af8464e48191bf2b516422716e24",
    "balanza_analitica_ohaus": "vs_6aa2af8f5b08819184a82939f9595fe4",
    "camara_incubacion_uv_prc_workstation": "vs_6aa2af9ba7288191ab1cadac5069b9e2",
    "estufa_laboratorio_doble_puerta": "vs_6aa2afa52ebc8191ad4da1aa876f512b",
    "horno_secado_biobase": "vs_6aa2afafd1c08191b0618fada76b4763",
    "incubador_agitacion_orbital_incu_shaker": "vs_6aa2afb7c12881918da57c95c7ae2215",
    "microscopio_boeco": "vs_6aa2afc024c48191be6541083305a21a",
    "camara_seguridad_biologica_biobase": "vs_6aa2afc8c3f88191a9c07603ba6791ea",
}


def test_existen_exactamente_17_entradas():
    assert len(EQUIPO_VECTOR_STORE_MAP) == 17


def test_las_17_clases_esperadas_estan_presentes_sin_duplicados():
    # Un dict de Python ya no puede tener claves duplicadas, pero comparar contra el set
    # esperado además detecta clases de más/de menos o con errores de tipeo.
    assert set(EQUIPO_VECTOR_STORE_MAP.keys()) == set(CLASES_ESPERADAS)
    assert len(CLASES_ESPERADAS) == 17


def test_no_hay_ids_vacios():
    for clase, vector_store_id in EQUIPO_VECTOR_STORE_MAP.items():
        assert vector_store_id, f"{clase} tiene un vector_store_id vacío/None"
        assert vector_store_id.startswith("vs_"), f"{clase}: '{vector_store_id}' no parece un vector_store_id válido"


def test_no_hay_vector_store_ids_duplicados():
    ids = list(EQUIPO_VECTOR_STORE_MAP.values())
    assert len(ids) == len(set(ids)), "Dos o más clases apuntan al mismo Vector Store"


@pytest.mark.parametrize("clase,vector_store_id_esperado", sorted(MAPEO_ESPERADO.items()))
def test_cada_clase_devuelve_exactamente_su_id_esperado(clase, vector_store_id_esperado):
    assert resolver_vector_store(clase) == vector_store_id_esperado


def test_horno_secado_biobase_no_comparte_store_con_equipos_parecidos():
    """Regresión explícita: horno_secado_biobase, horno_secado_conveccion_forzada_redline y
    estufa_laboratorio_doble_puerta son equipos distintos y deben resolver a Vector Stores
    distintos entre sí (ver punto 12 del pedido)."""
    biobase = resolver_vector_store("horno_secado_biobase")
    redline = resolver_vector_store("horno_secado_conveccion_forzada_redline")
    estufa = resolver_vector_store("estufa_laboratorio_doble_puerta")

    ids = {biobase, redline, estufa}
    assert len(ids) == 3, "horno_secado_biobase, redline y estufa deben tener Vector Stores distintos"


@pytest.mark.parametrize("clase_invalida", [
    None,
    "",
    "clase_que_no_existe",
    "Horno_Secado_Biobase",  # typo de mayúsculas: no debe coincidir por accidente
    "horno_secado",  # clase del modelo anterior (7 clases), ya no existe en el de 17
    "bod_sensor",  # idem
])
def test_clase_desconocida_o_invalida_devuelve_none_sin_lanzar_excepcion(clase_invalida):
    assert resolver_vector_store(clase_invalida) is None
