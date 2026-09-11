package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;

/**
 * Envoltorio reutilizable sobre {@link TextToSpeech} para leer en voz alta las respuestas
 * del asistente, en español.
 */
public class TextToSpeechManager {

    public interface EstadoListener {
        default void onListo() {}
        default void onInicioHabla() {}
        default void onFinHabla() {}
        default void onErrorHabla() {}
    }

    private static final String TAG = "TextToSpeechManager";

    private TextToSpeech textToSpeech;
    private volatile boolean listo = false;
    private volatile String locucionActual;
    private EstadoListener estadoListener;

    public TextToSpeechManager(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int resultado = textToSpeech.setLanguage(Locale.forLanguageTag("es-ES"));
                if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Español no disponible en este dispositivo para TextToSpeech.");
                }
                // Pitch ligeramente más alto que el neutro (1.0): da un tono más cálido/animado,
                // apropiado para un asistente amigable, sin llegar a sonar artificial ni afectar
                // la inteligibilidad. Velocidad normal (no se toca): apurar la voz sí perjudica
                // que se entienda bien.
                //
                // NO se cambia la voz (setVoice) a propósito: se probó elegir la de mayor
                // "calidad" reportada entre las instaladas (ver commit/sesión anterior) y varias
                // voces alternativas del motor de Google (aunque reportadas como es-ES/es-US)
                // resultaron pronunciar términos técnicos/nombres propios (p. ej. "Biobase",
                // abreviaturas como "20V") con reglas más propias del inglés — un problema real
                // de esa voz concreta, no de esta app. Con la voz que deja setLanguage(...) por
                // defecto (la que el sistema ya trae configurada para español) esos casos sonaban
                // mejor, así que se revirtió: mejor una voz consistente que una "mejor" en papel
                // pero que pronuncia mal justo los términos que más aparecen en este proyecto.
                textToSpeech.setPitch(1.05f);
                listo = true;
                if (estadoListener != null) {
                    estadoListener.onListo();
                }
            } else {
                Log.e(TAG, "No se pudo inicializar TextToSpeech (status=" + status + ")");
            }
        });

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (utteranceId.equals(locucionActual) && estadoListener != null) {
                    estadoListener.onInicioHabla();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId.equals(locucionActual)) {
                    locucionActual = null;
                    if (estadoListener != null) estadoListener.onFinHabla();
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (utteranceId.equals(locucionActual)) {
                    locucionActual = null;
                    if (estadoListener != null) estadoListener.onErrorHabla();
                }
            }
        });
    }

    public void setEstadoListener(EstadoListener estadoListener) {
        this.estadoListener = estadoListener;
        if (listo && estadoListener != null) estadoListener.onListo();
    }

    public void speak(String texto) {
        if (!listo || texto == null || texto.trim().isEmpty()) {
            return;
        }
        locucionActual = UUID.randomUUID().toString();
        if (textToSpeech.speak(texto, TextToSpeech.QUEUE_FLUSH, null, locucionActual) == TextToSpeech.ERROR) {
            locucionActual = null;
            if (estadoListener != null) estadoListener.onErrorHabla();
        }
    }

    /** Usado para evitar interferencia con {@code SpeechRecognizer}: si el asistente está
     * hablando, no tiene sentido empezar a escuchar (el micrófono captaría la propia voz del
     * TTS). Ver {@code ChatActivity.iniciarEscucha()}. */
    public boolean estaHablando() {
        return listo && textToSpeech != null && textToSpeech.isSpeaking();
    }

    public boolean estaListo() { return listo; }

    public void detener() {
        locucionActual = null;
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    public void liberar() {
        locucionActual = null;
        listo = false;
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}
