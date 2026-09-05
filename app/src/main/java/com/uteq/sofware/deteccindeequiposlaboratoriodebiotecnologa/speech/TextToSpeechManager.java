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
    private boolean listo = false;
    private EstadoListener estadoListener;

    public TextToSpeechManager(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int resultado = textToSpeech.setLanguage(Locale.forLanguageTag("es-ES"));
                if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Español no disponible en este dispositivo para TextToSpeech.");
                }
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
                if (estadoListener != null) {
                    estadoListener.onInicioHabla();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                if (estadoListener != null) {
                    estadoListener.onFinHabla();
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (estadoListener != null) {
                    estadoListener.onErrorHabla();
                }
            }
        });
    }

    public void setEstadoListener(EstadoListener estadoListener) {
        this.estadoListener = estadoListener;
    }

    public void speak(String texto) {
        if (!listo || texto == null || texto.trim().isEmpty()) {
            return;
        }
        textToSpeech.speak(texto, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
    }

    /** Usado para evitar interferencia con {@code SpeechRecognizer}: si el asistente está
     * hablando, no tiene sentido empezar a escuchar (el micrófono captaría la propia voz del
     * TTS). Ver {@code ChatActivity.iniciarEscucha()}. */
    public boolean estaHablando() {
        return listo && textToSpeech != null && textToSpeech.isSpeaking();
    }

    public void detener() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    public void liberar() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}
