package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

/**
 * Estados visuales del mini avatar animado del asistente (NexoBio) en {@link ChatActivity}.
 * Cada estado se relaciona con una animación nativa muy ligera en {@link NexoBioAnimador}.
 */
public enum NexoBioEstado {
    /** Reposo: casi estático (sin animación continua, para no gastar batería/CPU en vano). */
    NORMAL,
    /** Saludo inicial al entrar a la conversación por voz: mano levantada saludando + boca
     * sincronizada con el TTS del mensaje de bienvenida. Solo lo usa
     * {@code VozAsistenteActivity} (ver {@link NexoBioAnimador#manoSaludo}); en el avatar
     * pequeño del chat, que no tiene mano de saludo, se comporta igual que HABLANDO. */
    SALUDANDO,
    /** Escuchando por el micrófono: pequeño pulso. */
    ESCUCHANDO,
    /** Esperando la respuesta del backend (RAG/LLM): balanceo vertical suave. */
    PENSANDO,
    /** TextToSpeech reproduciendo una respuesta: pequeño pulso. */
    HABLANDO,
    /** Error de voz o del backend: indicador suave y breve, nunca alarmante (ver
     * {@link NexoBioAnimador}: un tinte breve, no rojo, no parpadeo agresivo). Vuelve solo a
     * NORMAL después de un instante. */
    ERROR
}
