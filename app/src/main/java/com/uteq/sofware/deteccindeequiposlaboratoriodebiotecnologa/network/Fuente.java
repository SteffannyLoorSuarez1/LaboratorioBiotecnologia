package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import java.io.Serializable;

/**
 * Fuente documental citada por OpenAI en una respuesta del asistente (RAG).
 * {@code Serializable} para poder guardarse en el {@code Bundle} de
 * {@code ChatActivity.onSaveInstanceState} (ver {@code ChatMensaje}).
 */
public class Fuente implements Serializable {

    private final String archivo;
    private final String referencia;

    public Fuente(String archivo, String referencia) {
        this.archivo = archivo;
        this.referencia = referencia;
    }

    public String getArchivo() {
        return archivo;
    }

    public String getReferencia() {
        return referencia;
    }
}
