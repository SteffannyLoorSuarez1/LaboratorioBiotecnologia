package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.BuildConfig;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Traduce el contrato REST de Responses API al modelo que ya usa la interfaz de Bio. */
final class BioResponses {
    private BioResponses() {}

    static JSONObject crearPeticion(ChatRequest pregunta, String store) throws JSONException {
        JSONObject herramienta = new JSONObject();
        herramienta.put("type", "file_search");
        herramienta.put("vector_store_ids", new JSONArray().put(store));
        JSONObject cuerpo = new JSONObject();
        cuerpo.put("model", BuildConfig.OPENAI_MODEL);
        cuerpo.put("instructions", BioManuales.INSTRUCCIONES);
        cuerpo.put("input", "Área: " + pregunta.getArea() + ". Equipo: " + pregunta.getEquipo()
                + ".\nPregunta: " + pregunta.getPregunta());
        cuerpo.put("tools", new JSONArray().put(herramienta));
        cuerpo.put("store", false);
        return cuerpo;
    }

    static ChatResponse sinInformacion() {
        return new ChatResponse(BioManuales.SIN_INFORMACION, false, Collections.emptyList());
    }

    static ChatResponse parsear(JSONObject response) throws JSONException {
        if (!"completed".equals(response.optString("status")) || !response.isNull("error")) {
            throw new JSONException("Respuesta no completada");
        }
        // output_text es un helper del SDK; en REST hay que recorrer output[].content[].
        JSONArray output = response.getJSONArray("output");
        StringBuilder texto = new StringBuilder();
        List<Fuente> fuentes = new ArrayList<>();
        Set<String> archivos = new HashSet<>();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject bloque = content.optJSONObject(j);
                if (bloque == null) continue;
                if ("refusal".equals(bloque.optString("type"))) {
                    if (texto.length() > 0) texto.append("\n");
                    texto.append(bloque.optString("refusal"));
                    continue;
                }
                if (!"output_text".equals(bloque.optString("type"))) continue;
                if (texto.length() > 0) texto.append("\n");
                texto.append(bloque.optString("text"));
                JSONArray annotations = bloque.optJSONArray("annotations");
                if (annotations == null) continue;
                for (int k = 0; k < annotations.length(); k++) {
                    JSONObject citation = annotations.optJSONObject(k);
                    if (citation == null || !"file_citation".equals(citation.optString("type"))) continue;
                    String archivo = citation.optString("filename");
                    if (!archivo.isEmpty() && archivos.add(archivo)) fuentes.add(new Fuente(archivo, archivo));
                }
            }
        }
        String respuesta = texto.toString().trim();
        if (respuesta.isEmpty()) throw new JSONException("Respuesta sin texto");
        if (respuesta.equals(BioManuales.SIN_INFORMACION)) return sinInformacion();
        return new ChatResponse(respuesta, true, fuentes);
    }
}
