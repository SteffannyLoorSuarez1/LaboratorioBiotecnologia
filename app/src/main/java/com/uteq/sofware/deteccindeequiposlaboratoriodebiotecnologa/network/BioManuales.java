package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Manual dedicado de cada equipo. Mantener sincronizado con equipo_manual_map.py. */
final class BioManuales {
    private BioManuales() {}
    static final String SIN_INFORMACION = "No tengo suficiente información, por favor pregúntele al laboratorista encargado.";
    static final String INSTRUCCIONES = "Eres el asistente del Laboratorio de Biotecnología de la UTEQ.\n\nResponde únicamente con información sustentada en los documentos recuperados.\nNo uses conocimiento general para completar información técnica faltante.\n\nPuedes responder acerca de:\n- función\n- componentes\n- operación\n- seguridad\n- EPP\n- riesgos\n- mantenimiento\n- prácticas académicas\n\nSi los documentos recuperados no contienen información suficiente, responde EXACTAMENTE:\n\"No tengo suficiente información, por favor pregúntele al laboratorista encargado.\"";
    static final Map<String, String> STORES;
    static {
        Map<String, String> stores = new HashMap<>();
        stores.put("autoclave_vapor_mesa_gemmy_sturdy", "vs_6aa2af15cde08191830970b3607acc12");
        stores.put("cabina_flujo_laminar_mini_c4", "vs_6aa2af2949708191b9b1bb13bfb4d08b");
        stores.put("centrifuga_laboratorio_ohaus", "vs_6aa2af345c08819194f81ee566f43f4e");
        stores.put("medidor_mesa_electroquimica_ohaus", "vs_6aa2af43ca308191b57b1a0ebc5d0685");
        stores.put("termociclador_miniamp_plus", "vs_6aa2af52a49881918d0391c6c3ba67f5");
        stores.put("bano_maria_memmert", "vs_6aa2af5afc5c8191889a54d68ce7948a");
        stores.put("cubeta_electroforesis_horizontal_gel", "vs_6aa2af6813588191b7a3645b0ad5ddb9");
        stores.put("espectrofotometro_visible_digital_unico", "vs_6aa2af7224208191bd95a834ae0c70cf");
        stores.put("horno_secado_conveccion_forzada_redline", "vs_6aa2af7bf3bc81919401dd2973930511");
        stores.put("medidor_demanda_bioquimica_oxigeno", "vs_6aa2af8464e48191bf2b516422716e24");
        stores.put("balanza_analitica_ohaus", "vs_6aa2af8f5b08819184a82939f9595fe4");
        stores.put("camara_incubacion_uv_prc_workstation", "vs_6aa2af9ba7288191ab1cadac5069b9e2");
        stores.put("estufa_laboratorio_doble_puerta", "vs_6aa2afa52ebc8191ad4da1aa876f512b");
        stores.put("horno_secado_biobase", "vs_6aa2afafd1c08191b0618fada76b4763");
        stores.put("incubador_agitacion_orbital_incu_shaker", "vs_6aa2afb7c12881918da57c95c7ae2215");
        stores.put("microscopio_boeco", "vs_6aa2afc024c48191be6541083305a21a");
        stores.put("camara_seguridad_biologica_biobase", "vs_6aa2afc8c3f88191a9c07603ba6791ea");
        STORES = Collections.unmodifiableMap(stores);
    }
}
