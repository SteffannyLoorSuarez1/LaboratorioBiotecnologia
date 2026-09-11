package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.animation.ValueAnimator;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;

/**
 * Controla toda la animación nativa (alpha/scale/rotation vía {@link ValueAnimator}, más el
 * parpadeo/guiño por intercambio de drawable) del avatar de NexoBio en el header del chat.
 * <p>
 * Deliberadamente NO usa Lottie ni {@code AnimatedVectorDrawable}: los estados requeridos
 * (reposo con parpadeo, escuchando, pensando, hablando, error) son pulsos/balanceos/parpadeos
 * simples que un {@link ValueAnimator} + dos drawables estáticos resuelven igual de bien, sin
 * dependencia adicional, sin asset .json, con impacto ~0 en APK/RAM/CPU y sin riesgo de
 * inestabilidad — ver el informe de la sesión para la comparación completa. Si más adelante un
 * diseño más elaborado lo justifica, este es el único punto que habría que tocar.
 * <p>
 * Solo puede haber animaciones de UN estado corriendo a la vez (se cancelan las anteriores antes
 * de iniciar las siguientes), y {@link #detener()} debe llamarse desde {@code onPause()}/
 * {@code onStop()}/{@code onDestroy()} de la Activity para no dejar nada corriendo en background
 * (incluido el parpadeo, programado con {@link Handler}).
 */
public final class NexoBioAnimador {

    private static final long DURACION_PULSO_MS = 550;
    private static final long DURACION_ANILLO_MS = 1000;
    private static final long DURACION_WOBBLE_MS = 460;
    private static final long DURACION_ERROR_MS = 700;

    /** Cada cuánto ocurre un gesto (parpadeo o, mucho más raro, guiño): más frecuente que el
     * parpadeo original (era 2600-5200ms) para que se sienta más viva, pero con suficiente
     * variación aleatoria para no parecer mecánica. */
    private static final long GESTO_MIN_MS = 1800;
    private static final long GESTO_MAX_MS = 3600;
    private static final long PARPADEO_DURACION_MS = 130;
    private static final long GUINO_DURACION_MS = 220;

    /** Probabilidad de que un ciclo de gesto sea un guiño en vez de un parpadeo normal. Con
     * ~12%, un guiño ocurre en promedio 1 de cada ~8 ciclos (con GESTO_MIN/MAX_MS de arriba,
     * aproximadamente cada 20-25s en promedio) — "mucho menos frecuente que el parpadeo" sin
     * necesitar un segundo Handler/temporizador corriendo en paralelo (evita que un parpadeo y
     * un guiño puedan pisarse entre sí). */
    private static final double PROBABILIDAD_GUINO = 0.12;

    /** Intervalo de alternancia boca cerrada/abierta mientras TTS habla (estado HABLANDO). No
     * intenta sincronía fonética: es solo un ciclo simple y ligero, ver {@link #alternarBoca()}. */
    private static final long BOCA_ALTERNANCIA_MS = 220;

    /** Duración de un medio-ciclo de la respiración sutil (ver {@link #respiracionSutil}): lo
     * bastante lenta para no competir visualmente con el parpadeo ni con las animaciones de
     * estado. */
    private static final long DURACION_RESPIRACION_MS = 2600;

    /** Amplitud horizontal (dp) del balanceo lateral MUY leve que acompaña a la respiración
     * sutil: da sensación de "cuerpo vivo" sin ser un movimiento brusco ni gastar batería extra
     * (reutiliza el mismo {@link ValueAnimator} de la respiración, ver {@link #iniciarRespiracion}). */
    private static final float AMPLITUD_BALANCEO_DP = 1.4f;

    /** Duración de un semiciclo del balanceo de la mano de saludo (ver {@link #iniciarSaludoMano}). */
    private static final long DURACION_SALUDO_MANO_MS = 320;

    private final ImageView avatar;
    @Nullable
    private final View anilloPulso;
    @DrawableRes
    private final int drawableNormal;
    @DrawableRes
    private final int drawableBlink;
    @DrawableRes
    private final int drawableWink;
    @DrawableRes
    private final int drawableTalk;
    /** Si está activa, añade una respiración corporal muy sutil (escala vertical + inclinación +
     * balanceo lateral leves) mientras el estado es NORMAL — pensada para el avatar grande de
     * cuerpo entero de {@code VozAsistenteActivity}; desactivada por defecto para no alterar el
     * avatar pequeño ya existente en el header del chat. */
    private final boolean respiracionSutil;
    /** Vista opcional de la mano de saludo (ver {@code ic_biotec_hand_wave.xml}), solo usada en
     * el estado SALUDANDO. {@code null} en el avatar pequeño del chat, que no tiene esta vista
     * (ver constructor de 2 argumentos): en ese caso SALUDANDO se ve igual que HABLANDO. */
    @Nullable
    private final View manoSaludo;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable gestoRunnable = this::ejecutarCicloGesto;
    private final Runnable bocaRunnable = this::alternarBoca;

    private final List<ValueAnimator> animadoresActivos = new ArrayList<>();
    @Nullable
    private ValueAnimator animadorRespiracion;
    private NexoBioEstado estadoActual = NexoBioEstado.NORMAL;
    private boolean gestosActivos = false;
    private boolean bocaAbierta = false;

    /** Constructor original: mismos drawables de siempre (avatar pequeño del header del chat,
     * ver {@code ic_nexobio_robot*.xml}), sin respiración corporal continua.
     * @param anilloPulso vista opcional (círculo con borde, ver {@code bg_nexobio_pulso.xml})
     *                     alrededor del avatar, usada como "ping" suave en ESCUCHANDO/HABLANDO.
     *                     Puede ser {@code null} (por ejemplo, para un avatar pequeño de mensaje
     *                     que no necesita esta capa extra). */
    public NexoBioAnimador(ImageView avatar, @Nullable View anilloPulso) {
        this(avatar, anilloPulso, R.drawable.ic_nexobio_robot, R.drawable.ic_nexobio_robot_blink,
                R.drawable.ic_nexobio_robot_wink, R.drawable.ic_nexobio_robot_talk, false, null);
    }

    /** Variante con drawables propios (por ejemplo, el robot de cuerpo entero
     * {@code ic_biotec_full*.xml} de {@code VozAsistenteActivity}) y respiración corporal
     * opcional, sin mano de saludo — misma lógica de parpadeo/guiño/pulso/boca que el avatar del
     * chat, solo cambia el arte y, opcionalmente, si respira en reposo. Conservado para no
     * obligar a todo llamador a pasar una vista de mano. */
    public NexoBioAnimador(ImageView avatar, @Nullable View anilloPulso,
            @DrawableRes int drawableNormal, @DrawableRes int drawableBlink,
            @DrawableRes int drawableWink, @DrawableRes int drawableTalk,
            boolean respiracionSutil) {
        this(avatar, anilloPulso, drawableNormal, drawableBlink, drawableWink, drawableTalk,
                respiracionSutil, null);
    }

    /** Variante completa, con mano de saludo opcional (ver {@link #manoSaludo}) para el estado
     * SALUDANDO — usada por {@code VozAsistenteActivity}. */
    public NexoBioAnimador(ImageView avatar, @Nullable View anilloPulso,
            @DrawableRes int drawableNormal, @DrawableRes int drawableBlink,
            @DrawableRes int drawableWink, @DrawableRes int drawableTalk,
            boolean respiracionSutil, @Nullable View manoSaludo) {
        this.avatar = avatar;
        this.anilloPulso = anilloPulso;
        this.drawableNormal = drawableNormal;
        this.drawableBlink = drawableBlink;
        this.drawableWink = drawableWink;
        this.drawableTalk = drawableTalk;
        this.respiracionSutil = respiracionSutil;
        this.manoSaludo = manoSaludo;
    }

    /** Arranca el ciclo de gestos aleatorios — parpadeo frecuente y, ocasionalmente, un guiño
     * (solo visibles mientras el estado sea NORMAL). Llamar una vez, típicamente desde
     * {@code onResume()} de la Activity; {@link #detener()} lo detiene. Volver a llamarlo tras
     * {@link #detener()} lo reinicia sin problema. */
    public void iniciarParpadeoAleatorio() {
        if (gestosActivos) {
            return;
        }
        gestosActivos = true;
        programarSiguienteGesto();
        if (respiracionSutil && estadoActual == NexoBioEstado.NORMAL) {
            iniciarRespiracion();
        }
    }

    /** Escala vertical (±1.5%) + inclinación leve (±1.5°) + balanceo lateral leve
     * (±{@value #AMPLITUD_BALANCEO_DP}dp) en bucle, muy lento: da sensación de "cuerpo vivo" en
     * reposo (respira y se balancea apenas) sin competir con el parpadeo ni parecer un temblor.
     * Un único {@link ValueAnimator} para las tres propiedades — no cuesta batería/CPU extra
     * frente a la respiración original. Solo corre mientras el estado sea NORMAL (ver
     * {@link #setEstado}, que la cancela al salir de NORMAL y la retoma al volver). */
    private void iniciarRespiracion() {
        if (animadorRespiracion != null) {
            return;
        }
        float amplitudBalanceoPx = AMPLITUD_BALANCEO_DP * avatar.getResources().getDisplayMetrics().density;
        ValueAnimator animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(DURACION_RESPIRACION_MS);
        animador.setRepeatMode(ValueAnimator.REVERSE);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            avatar.setScaleY(1f + 0.015f * t);
            avatar.setRotation(-1.5f + 3f * t);
            avatar.setTranslationX(-amplitudBalanceoPx + 2f * amplitudBalanceoPx * t);
        });
        animador.start();
        animadorRespiracion = animador;
    }

    private void detenerRespiracion() {
        if (animadorRespiracion != null) {
            animadorRespiracion.cancel();
            animadorRespiracion = null;
        }
        avatar.setTranslationX(0f);
    }

    private void programarSiguienteGesto() {
        long siguiente = GESTO_MIN_MS + (long) (Math.random() * (GESTO_MAX_MS - GESTO_MIN_MS));
        handler.postDelayed(gestoRunnable, siguiente);
    }

    /** Un solo ciclo: si el estado sigue siendo NORMAL, decide al azar entre parpadeo (lo
     * habitual) o guiño (ocasional, ver {@link #PROBABILIDAD_GUINO}), lo muestra brevemente y
     * vuelve a la sonrisa normal. Si el estado cambió mientras tanto (ESCUCHANDO/PENSANDO/
     * HABLANDO/ERROR), este ciclo no dibuja nada — pero el temporizador sigue vivo para cuando
     * vuelva a NORMAL. */
    private void ejecutarCicloGesto() {
        if (estadoActual == NexoBioEstado.NORMAL) {
            boolean esGuino = Math.random() < PROBABILIDAD_GUINO;
            int drawableGesto = esGuino ? drawableWink : drawableBlink;
            long duracion = esGuino ? GUINO_DURACION_MS : PARPADEO_DURACION_MS;
            avatar.setImageResource(drawableGesto);
            handler.postDelayed(() -> {
                if (estadoActual == NexoBioEstado.NORMAL) {
                    avatar.setImageResource(drawableNormal);
                }
            }, duracion);
        }
        if (gestosActivos) {
            programarSiguienteGesto();
        }
    }

    public void setEstado(NexoBioEstado estado) {
        if (estado == estadoActual) {
            return;
        }
        estadoActual = estado;
        detenerAnimacionesDeEstado();

        switch (estado) {
            case ESCUCHANDO:
                iniciarAnimador(crearPulsoAvatar());
                if (anilloPulso != null) {
                    iniciarAnimador(crearPulsoAnillo());
                }
                break;
            case SALUDANDO:
                // Saludo inicial: solo mano levantada (si esta instancia tiene esa vista, ver
                // manoSaludo) + boca sincronizada con el TTS del mensaje de bienvenida (ver
                // VozAsistenteActivity.reproducirSaludoInicial()). A propósito SIN el pulso de
                // avatar que sí usa HABLANDO: con la mano ya moviéndose de forma independiente,
                // sumar el pulso del cuerpo competiría visualmente en vez de sumar (ver punto 3
                // del pedido: "no quiero que dos animaciones se peleen entre sí").
                iniciarSaludoMano();
                bocaAbierta = false;
                handler.post(bocaRunnable);
                break;
            case HABLANDO:
                iniciarAnimador(crearPulsoAvatar());
                if (anilloPulso != null) {
                    iniciarAnimador(crearPulsoAnillo());
                }
                // Da la sensación de que BioTec está pronunciando la respuesta: alterna
                // boca cerrada/abierta mientras el estado siga siendo HABLANDO (ver
                // alternarBoca(), que se auto-cancela apenas el estado cambia).
                bocaAbierta = false;
                handler.post(bocaRunnable);
                break;
            case PENSANDO:
                iniciarAnimador(crearWobble());
                break;
            case ERROR:
                aplicarIndicadorError();
                break;
            case NORMAL:
            default:
                // reposo: sin animación de estado (además del parpadeo ocasional ya programado
                // aparte); avatar ya restaurado por detenerAnimacionesDeEstado(). Si esta
                // instancia usa respiración sutil, retomarla aquí (detenerAnimacionesDeEstado ya
                // la había cancelado al entrar a este método).
                if (respiracionSutil && gestosActivos) {
                    iniciarRespiracion();
                }
                break;
        }
    }

    private void iniciarAnimador(ValueAnimator animador) {
        animadoresActivos.add(animador);
        animador.start();
    }

    private ValueAnimator crearPulsoAvatar() {
        ValueAnimator animador = ValueAnimator.ofFloat(1f, 1.1f);
        animador.setDuration(DURACION_PULSO_MS);
        animador.setRepeatMode(ValueAnimator.REVERSE);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.setInterpolator(new LinearInterpolator());
        animador.addUpdateListener(a -> {
            float valor = (float) a.getAnimatedValue();
            avatar.setScaleX(valor);
            avatar.setScaleY(valor);
        });
        return animador;
    }

    /** "Ping" de sonar: el anillo crece y se desvanece en bucle alrededor del avatar. */
    private ValueAnimator crearPulsoAnillo() {
        anilloPulso.setScaleX(1f);
        anilloPulso.setScaleY(1f);
        ValueAnimator animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(DURACION_ANILLO_MS);
        animador.setRepeatMode(ValueAnimator.RESTART);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float escala = 1f + 0.35f * t;
            anilloPulso.setScaleX(escala);
            anilloPulso.setScaleY(escala);
            anilloPulso.setAlpha((1f - t) * 0.6f);
        });
        return animador;
    }

    /** Balanceo/inclinación muy leve mientras se espera la respuesta del backend: rotación
     * pequeña (±4°), no un spinner ni un movimiento grande. */
    private ValueAnimator crearWobble() {
        ValueAnimator animador = ValueAnimator.ofFloat(-4f, 4f);
        animador.setDuration(DURACION_WOBBLE_MS);
        animador.setRepeatMode(ValueAnimator.REVERSE);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.addUpdateListener(a -> avatar.setRotation((float) a.getAnimatedValue()));
        return animador;
    }

    /** Indicador de error suave y breve: un tinte cálido (no rojo) durante
     * {@value #DURACION_ERROR_MS} ms sobre el avatar, sin parpadeo agresivo ni forma de alerta.
     * Vuelve solo a NORMAL al terminar. */
    private void aplicarIndicadorError() {
        avatar.setColorFilter(
                ContextCompat.getColor(avatar.getContext(), R.color.lab_accent), PorterDuff.Mode.SRC_ATOP);
        handler.postDelayed(() -> {
            avatar.clearColorFilter();
            if (estadoActual == NexoBioEstado.ERROR) {
                estadoActual = NexoBioEstado.NORMAL;
            }
        }, DURACION_ERROR_MS);
    }

    /** Alterna boca cerrada/abierta mientras {@code estadoActual} siga siendo HABLANDO o
     * SALUDANDO; se detiene sola en cuanto deja de serlo (llamada desde TTS onDone/onError vía
     * {@code setEstado}, que ya canceló este runnable en {@link #detenerAnimacionesDeEstado()}
     * antes de aplicar el nuevo estado — esta comprobación es una segunda red de seguridad). */
    private void alternarBoca() {
        if (estadoActual != NexoBioEstado.HABLANDO && estadoActual != NexoBioEstado.SALUDANDO) {
            return;
        }
        bocaAbierta = !bocaAbierta;
        avatar.setImageResource(bocaAbierta ? drawableTalk : drawableNormal);
        handler.postDelayed(bocaRunnable, BOCA_ALTERNANCIA_MS);
    }

    /** Muestra la mano de saludo (si esta instancia la tiene, ver {@link #manoSaludo}) y la hace
     * oscilar levemente en bucle, pivotada en su extremo inferior (la "muñeca"), como un saludo
     * natural. Usa {@link RotateAnimation} clásico (no {@link ValueAnimator}) porque soporta
     * pivote relativo en porcentaje directamente: evita tener que calcular a mano el pivote en
     * píxeles y el riesgo de hacerlo antes de que la vista tenga tamaño medido (recién pasa de
     * GONE a VISIBLE aquí mismo). No hace nada si esta instancia no tiene mano de saludo (avatar
     * pequeño del chat). */
    private void iniciarSaludoMano() {
        if (manoSaludo == null) {
            return;
        }
        manoSaludo.setVisibility(View.VISIBLE);
        RotateAnimation rotacion = new RotateAnimation(-12f, 28f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 1f);
        rotacion.setDuration(DURACION_SALUDO_MANO_MS);
        rotacion.setRepeatMode(Animation.REVERSE);
        rotacion.setRepeatCount(Animation.INFINITE);
        rotacion.setInterpolator(new AccelerateDecelerateInterpolator());
        manoSaludo.startAnimation(rotacion);
    }

    private void detenerSaludoMano() {
        if (manoSaludo == null) {
            return;
        }
        manoSaludo.clearAnimation();
        manoSaludo.setVisibility(View.GONE);
    }

    private void detenerAnimacionesDeEstado() {
        detenerRespiracion();
        detenerSaludoMano();
        for (ValueAnimator animador : animadoresActivos) {
            animador.cancel();
        }
        animadoresActivos.clear();
        avatar.clearColorFilter();
        avatar.setScaleX(1f);
        avatar.setScaleY(1f);
        avatar.setRotation(0f);
        if (anilloPulso != null) {
            anilloPulso.setAlpha(0f);
            anilloPulso.setScaleX(1f);
            anilloPulso.setScaleY(1f);
        }
        // Corta cualquier ciclo de boca hablando pendiente (p. ej. al pasar de HABLANDO/SALUDANDO
        // a NORMAL/ERROR) y deja el rostro en boca cerrada.
        handler.removeCallbacks(bocaRunnable);
        bocaAbierta = false;
        avatar.setImageResource(drawableNormal);
    }

    /** Cancela toda animación en curso (incluido el parpadeo) y vuelve el avatar a su estado de
     * reposo. Llamar desde {@code onPause()}/{@code onStop()} y {@code onDestroy()} de la
     * Activity (lifecycle) para no dejar nada corriendo en background. */
    public void detener() {
        gestosActivos = false;
        handler.removeCallbacksAndMessages(null);
        detenerAnimacionesDeEstado();
        avatar.setImageResource(drawableNormal);
        estadoActual = NexoBioEstado.NORMAL;
    }
}
