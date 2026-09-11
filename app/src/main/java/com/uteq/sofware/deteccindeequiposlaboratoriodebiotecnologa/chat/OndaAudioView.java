package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Random;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;

/**
 * Ecualizador simple de barras verticales que reacciona en vivo al volumen de la voz mientras
 * {@code SpeechRecognizer} está escuchando (estado ESCUCHANDO de {@link VozAsistenteActivity}):
 * mientras más alto habla el usuario, más altas se ven las barras.
 * <p>
 * Deliberadamente un {@link View} propio con {@code onDraw} simple (mismo criterio que
 * {@link NexoBioAnimador}: nada de Lottie ni librerías externas de audio) en vez de, por
 * ejemplo, un {@code AudioRecord}/analizador FFT independiente: la fuente del nivel de audio ya
 * la entrega gratis {@code SpeechRecognizer} vía {@code RecognitionListener.onRmsChanged(float)}
 * (ver {@link SpeechRecognitionManager}), así que no hace falta grabar ni procesar audio por
 * cuenta propia — solo pintar el valor que Android ya calculó.
 */
public final class OndaAudioView extends View {

    private static final int NUM_BARRAS = 5;
    /** Fracción de alto que muestran las barras en completo silencio (para que nunca
     * "desaparezcan" del todo y se note que el componente está activo/escuchando). */
    private static final float ALTURA_MINIMA_FRACCION = 0.16f;
    /** Suavizado exponencial entre el nivel mostrado y el nuevo valor de onRmsChanged: evita que
     * las barras salten bruscamente entre callbacks (llegan cada ~100-200ms). Más cerca de 1 =
     * más reactivo; más cerca de 0 = más suave. */
    private static final float FACTOR_SUAVIZADO = 0.45f;
    /** Rango aproximado (no calibrado entre dispositivos) en el que Android reporta
     * {@code onRmsChanged}: valores bajos/negativos en silencio, hasta ~10 hablando fuerte. */
    private static final float RMS_MINIMO = -2f;
    private static final float RMS_MAXIMO = 10f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Multiplicador fijo por barra (generado una sola vez): da variedad "de ecualizador" en vez
     * de que las 5 barras se muevan siempre idénticas y perfectamente sincronizadas. */
    private final float[] factoresBarra = new float[NUM_BARRAS];
    private float nivel = 0f; // 0..1, ya suavizado

    public OndaAudioView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(ContextCompat.getColor(context, R.color.lab_primary));
        paint.setStyle(Paint.Style.FILL);
        Random random = new Random();
        for (int i = 0; i < NUM_BARRAS; i++) {
            factoresBarra[i] = 0.55f + random.nextFloat() * 0.45f; // 0.55..1.0
        }
    }

    /** Actualiza el nivel mostrado a partir de un nuevo valor de
     * {@code RecognitionListener#onRmsChanged(float)}. Seguro de llamar desde el hilo de UI
     * (que es desde donde {@link SpeechRecognitionManager} lo reenvía). */
    public void actualizarNivel(float rmsdB) {
        float normalizado = clamp((rmsdB - RMS_MINIMO) / (RMS_MAXIMO - RMS_MINIMO), 0f, 1f);
        nivel = nivel + (normalizado - nivel) * FACTOR_SUAVIZADO;
        invalidate();
    }

    /** Vuelve al reposo (silencio) — llamar al dejar de escuchar, para no dejar las barras a
     * media altura la próxima vez que este componente se muestre. */
    public void reiniciar() {
        nivel = 0f;
        invalidate();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int ancho = getWidth();
        int alto = getHeight();
        if (ancho <= 0 || alto <= 0) {
            return;
        }
        float espacio = ancho / (float) NUM_BARRAS;
        float anchoBarra = espacio * 0.45f;
        float radio = anchoBarra / 2f;
        for (int i = 0; i < NUM_BARRAS; i++) {
            float fraccion = Math.max(ALTURA_MINIMA_FRACCION, nivel * factoresBarra[i]);
            float alturaBarra = alto * fraccion;
            float centroX = espacio * i + espacio / 2f;
            float top = (alto - alturaBarra) / 2f;
            float bottom = top + alturaBarra;
            canvas.drawRoundRect(centroX - anchoBarra / 2f, top, centroX + anchoBarra / 2f, bottom, radio, radio, paint);
        }
    }
}
