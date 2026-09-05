package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

/**
 * Cuerpo de la petición POST /api/chat. Android solo envía identificadores y la pregunta del
 * usuario; el backend es responsable de recuperar los fragmentos documentales relevantes
 * (RAG) y de consultar al LLM.
 */
public class ChatRequest {

    private final String equipo;
    private final String area;
    private final String claseDetector;
    private final String pregunta;

    /** @param claseDetector clase estable del detector (ver {@code Equipo.getClaseDetector()} /
     *                       {@code labels.txt}), NO el nombre bonito de {@code equipo}: el
     *                       backend la usa para restringir file_search al manual correspondiente
     *                       de ese equipo. Puede venir vacía/null si no hay equipo seleccionado
     *                       o si el equipo todavía no tiene clase asociada. */
    public ChatRequest(String equipo, String area, String claseDetector, String pregunta) {
        this.equipo = equipo == null ? "" : equipo;
        this.area = area == null ? "" : area;
        this.claseDetector = claseDetector == null ? "" : claseDetector;
        this.pregunta = pregunta;
    }

    public String getEquipo() {
        return equipo;
    }

    public String getArea() {
        return area;
    }

    public String getClaseDetector() {
        return claseDetector;
    }

    public String getPregunta() {
        return pregunta;
    }
}
