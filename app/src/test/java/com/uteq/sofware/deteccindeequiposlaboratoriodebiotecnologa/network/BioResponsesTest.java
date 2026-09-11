package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BioResponsesTest {
    private JSONObject response(JSONArray content) throws JSONException {
        return new JSONObject().put("status", "completed").put("output", new JSONArray()
                .put(new JSONObject().put("type", "file_search_call"))
                .put(new JSONObject().put("type", "message").put("content", content)));
    }

    @Test public void consultaSoloElManualSeleccionadoSinEnviarClaveEnJson() throws Exception {
        String store = BioManuales.STORES.get("horno_secado_biobase");
        JSONObject request = BioResponses.crearPeticion(new ChatRequest("Horno", "Lab",
                "horno_secado_biobase", "¿Para qué sirve?"), store);
        JSONArray tools = request.getJSONArray("tools");
        assertEquals(1, tools.length());
        assertEquals(1, tools.getJSONObject(0).getJSONArray("vector_store_ids").length());
        assertEquals(store, tools.getJSONObject(0).getJSONArray("vector_store_ids").getString(0));
        assertFalse(request.has("api_key"));
        assertFalse(request.getBoolean("store"));
        assertEquals(17, BioManuales.STORES.size());
        assertNull(BioManuales.STORES.get("equipo_inexistente"));
    }

    @Test public void extraeTextoRestYCitasSinDuplicarlas() throws Exception {
        JSONObject citation = new JSONObject().put("type", "file_citation").put("filename", "manual.pdf");
        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "output_text").put("text", "Primera parte")
                        .put("annotations", new JSONArray().put(citation).put(citation)))
                .put(new JSONObject().put("type", "output_text").put("text", "Segunda parte"));
        ChatResponse result = BioResponses.parsear(response(content));
        assertEquals("Primera parte\nSegunda parte", result.getRespuesta());
        assertEquals(1, result.getFuentes().size());
        assertEquals("manual.pdf", result.getFuentes().get(0).getArchivo());
        assertTrue(result.isEncontrado());
    }

    @Test public void conservaRespuestaSinInformacion() throws Exception {
        ChatResponse result = BioResponses.parsear(response(new JSONArray().put(new JSONObject()
                .put("type", "output_text").put("text", BioManuales.SIN_INFORMACION))));
        assertFalse(result.isEncontrado());
        assertTrue(result.getFuentes().isEmpty());
    }

    @Test(expected = JSONException.class) public void rechazaRespuestaIncompleta() throws Exception {
        BioResponses.parsear(response(new JSONArray()).put("status", "incomplete"));
    }

    @Test(expected = JSONException.class) public void rechazaRespuestaSinTexto() throws Exception {
        BioResponses.parsear(response(new JSONArray()));
    }

    @Test public void conservaRechazoDelProveedor() throws Exception {
        ChatResponse result = BioResponses.parsear(response(new JSONArray().put(new JSONObject()
                .put("type", "refusal").put("refusal", "No puedo ayudar con esa solicitud."))));
        assertEquals("No puedo ayudar con esa solicitud.", result.getRespuesta());
    }
}
