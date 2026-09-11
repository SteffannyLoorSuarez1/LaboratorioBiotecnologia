package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;

/**
 * Envoltorio reutilizable sobre {@link SpeechRecognizer} para convertir preguntas habladas
 * en texto (flujo: pulsar micrófono → hablar → texto en la caja de pregunta).
 * <p>
 * Solo se entrega un resultado a través de {@link ResultadoListener#onResultado(String)}
 * cuando {@link #onResults} produce un texto final, no vacío. Los resultados parciales
 * ({@link RecognitionListener#onPartialResults}) no se usan: este flujo exige que el usuario
 * vea y pueda corregir el texto reconocido antes de enviarlo, así que no tiene sentido
 * mostrarle hipótesis intermedias.
 */
public class SpeechRecognitionManager {

    public interface ResultadoListener {
        void onResultado(String texto);
        void onError(String mensajeError);
        default void onEscuchando() {}
        /** El usuario terminó de hablar y el reconocedor está procesando el audio (entre
         * {@code onEndOfSpeech} y que llegue {@link #onResultado} o {@link #onError}). */
        default void onProcesando() {}
        /** Nivel de audio del micrófono mientras se escucha (ver
         * {@code RecognitionListener#onRmsChanged}), para una visualización tipo "onda de voz"
         * (ver {@code VozAsistenteActivity}/{@code OndaAudioView}). Llega en el hilo de UI, con
         * la misma frecuencia con la que Android lo reporta (típicamente cada 100-200ms)
         * mientras el estado sea ESCUCHANDO; opcional, sin implementación por defecto. */
        default void onNivelAudio(float rmsdB) {}
    }

    private static final int MAX_RESULTADOS = 3;

    private final Context context;
    private final SpeechRecognizer speechRecognizer;
    private ResultadoListener resultadoListener;
    /** Evita iniciar una segunda sesión de escucha mientras ya hay una en curso: SpeechRecognizer
     * no soporta llamadas concurrentes a startListening() (lanzaría resultados cruzados o
     * ERROR_RECOGNIZER_BUSY de forma confusa para el usuario). */
    private final AtomicBoolean escuchando = new AtomicBoolean(false);

    public SpeechRecognitionManager(Context context) {
        this.context = context.getApplicationContext();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this.context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (resultadoListener != null) {
                    resultadoListener.onEscuchando();
                }
            }

            @Override
            public void onResults(Bundle results) {
                escuchando.set(false);
                String texto = seleccionarMejorResultado(results);
                if (resultadoListener == null) {
                    return;
                }
                if (texto == null || texto.trim().isEmpty()) {
                    resultadoListener.onError(context.getString(R.string.chat_voz_no_entendido));
                    return;
                }
                resultadoListener.onResultado(texto.trim());
            }

            @Override
            public void onError(int error) {
                escuchando.set(false);
                if (resultadoListener != null) {
                    resultadoListener.onError(traducirError(error));
                }
            }

            @Override
            public void onEndOfSpeech() {
                if (resultadoListener != null) {
                    resultadoListener.onProcesando();
                }
            }

            @Override public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {
                // RecognitionListener siempre entrega sus callbacks en el hilo de UI (documentado
                // en SpeechRecognizer): no hace falta runOnUiThread aquí, a diferencia de
                // UtteranceProgressListener de TextToSpeech (ese sí llega en un hilo del motor).
                if (resultadoListener != null) {
                    resultadoListener.onNivelAudio(rmsdB);
                }
            }

            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    public void setResultadoListener(ResultadoListener resultadoListener) {
        this.resultadoListener = resultadoListener;
    }

    public static boolean hayReconocimientoDisponible(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public boolean estaEscuchando() {
        return escuchando.get();
    }

    /** No hace nada si ya hay una sesión de escucha en curso: evita instancias/llamadas
     * concurrentes a {@code startListening()}. */
    public void escuchar() {
        if (!escuchando.compareAndSet(false, true)) {
            return;
        }
        String idioma = determinarIdiomaReconocimiento();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, idioma);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTADOS);
        speechRecognizer.startListening(intent);
    }

    /**
     * El proyecto está pensado para la UTEQ (Ecuador), por lo que "es-EC" es el idioma por
     * defecto. Si el dispositivo ya está configurado en algún español regional distinto
     * (por ejemplo "es-MX" o "es-ES"), se usa ese en su lugar: suele coincidir mejor con el
     * paquete de idioma/voz realmente instalado en ese teléfono.
     */
    private String determinarIdiomaReconocimiento() {
        Locale predeterminado = Locale.getDefault();
        if ("es".equals(predeterminado.getLanguage()) && !predeterminado.getCountry().isEmpty()) {
            return predeterminado.toLanguageTag();
        }
        return "es-EC";
    }

    /**
     * Elige la alternativa reconocida de mayor confianza cuando esa información está
     * disponible ({@link SpeechRecognizer#CONFIDENCE_SCORES}); si no lo está, usa la primera
     * (las alternativas ya vienen ordenadas por el reconocedor).
     */
    private String seleccionarMejorResultado(Bundle results) {
        ArrayList<String> coincidencias = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (coincidencias == null || coincidencias.isEmpty()) {
            return null;
        }

        float[] confianzas = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        if (confianzas == null || confianzas.length != coincidencias.size()) {
            return coincidencias.get(0);
        }

        int mejorIndice = 0;
        for (int i = 1; i < confianzas.length; i++) {
            if (confianzas[i] > confianzas[mejorIndice]) {
                mejorIndice = i;
            }
        }
        return coincidencias.get(mejorIndice);
    }

    public void detener() {
        speechRecognizer.stopListening();
    }

    /** Aborta la escucha en curso sin esperar un resultado (por ejemplo, si el TTS necesita
     * hablar mientras el usuario seguía con el micrófono activo). No dispara
     * {@link ResultadoListener#onResultado} ni {@link ResultadoListener#onError}. */
    public void cancelar() {
        if (escuchando.compareAndSet(true, false)) {
            speechRecognizer.cancel();
        }
    }

    public void liberar() {
        escuchando.set(false);
        speechRecognizer.destroy();
    }

    private String traducirError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return context.getString(R.string.chat_voz_no_entendido);
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return context.getString(R.string.chat_voz_sin_voz);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return context.getString(R.string.chat_permiso_audio_requerido);
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return context.getString(R.string.chat_voz_error_red);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return context.getString(R.string.chat_voz_ocupado);
            case SpeechRecognizer.ERROR_AUDIO:
                return context.getString(R.string.chat_voz_error_audio);
            case SpeechRecognizer.ERROR_CLIENT:
            default:
                return context.getString(R.string.chat_voz_error_generico);
        }
    }
}
