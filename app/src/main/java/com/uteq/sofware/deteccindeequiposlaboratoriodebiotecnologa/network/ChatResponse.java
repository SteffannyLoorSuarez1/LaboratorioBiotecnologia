package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import java.util.Collections;
import java.util.List;

/**
 * Respuesta de OpenAI adaptada a la interfaz del asistente Bio.
 */
public class ChatResponse {

    private final String respuesta;
    private final boolean encontrado;
    private final List<Fuente> fuentes;

    public ChatResponse(String respuesta, boolean encontrado, List<Fuente> fuentes) {
        this.respuesta = respuesta;
        this.encontrado = encontrado;
        this.fuentes = fuentes == null ? Collections.emptyList() : fuentes;
    }

    public String getRespuesta() {
        return respuesta;
    }

    public boolean isEncontrado() {
        return encontrado;
    }

    public List<Fuente> getFuentes() {
        return fuentes;
    }
}
