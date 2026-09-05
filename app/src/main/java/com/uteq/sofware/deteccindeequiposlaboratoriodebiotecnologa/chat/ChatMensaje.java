package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.Fuente;

/**
 * Un mensaje dentro del historial de conversación del chat (de usuario o del asistente).
 * {@code Serializable} para poder guardarse en {@code ChatActivity.onSaveInstanceState} y
 * sobrevivir a una recreación de la Activity (rotación, o que el sistema mate el proceso en
 * segundo plano) sin perder la conversación ya escrita.
 */
public class ChatMensaje implements Serializable {

    private final String texto;
    private final boolean esUsuario;
    private final List<Fuente> fuentes;

    public static ChatMensaje deUsuario(String texto) {
        return new ChatMensaje(texto, true, Collections.emptyList());
    }

    public static ChatMensaje deAsistente(String texto, List<Fuente> fuentes) {
        return new ChatMensaje(texto, false, fuentes == null ? Collections.emptyList() : fuentes);
    }

    private ChatMensaje(String texto, boolean esUsuario, List<Fuente> fuentes) {
        this.texto = texto;
        this.esUsuario = esUsuario;
        this.fuentes = fuentes;
    }

    public String getTexto() {
        return texto;
    }

    public boolean esUsuario() {
        return esUsuario;
    }

    public List<Fuente> getFuentes() {
        return fuentes;
    }
}
