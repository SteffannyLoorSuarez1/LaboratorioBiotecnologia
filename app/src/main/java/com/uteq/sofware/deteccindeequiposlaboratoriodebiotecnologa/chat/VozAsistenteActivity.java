package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatRequest;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatResponse;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.Fuente;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech.SpeechRecognitionManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech.TextToSpeechManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Conversación por voz con BioTec: "Asistente inteligente" → cámara → seleccionar equipo →
 * esta pantalla (ver {@code MainActivity}/{@code DeteccionActivity.EXTRA_MODO_SELECCION}),
 * contextualizada al equipo elegido. Reutiliza EXACTAMENTE la misma infraestructura que el chat
 * escrito ({@link ChatRepository}/{@link ChatRequest}, {@link TextToSpeechManager},
 * {@link SpeechRecognitionManager}, {@link NexoBioAnimador}, {@link MarkdownFormatter}): la
 * diferencia real es la interfaz (un robot de cuerpo entero en vez de una lista de mensajes) y
 * que aquí la respuesta se reproduce por voz automáticamente, sin que el usuario tenga que tocar
 * "escuchar" (a diferencia del chat escrito, que conserva ese control manual).
 * <p>
 * Historial voz↔chat: esta pantalla solo muestra el último intercambio (pregunta reconocida +
 * respuesta), no una lista desplazable como el chat. Al pasar a {@link ChatActivity} con el
 * botón "Usar chat escrito" SÍ se traslada el historial completo ya conversado por voz (ver
 * {@link #historialVoz} / {@code ChatActivity.EXTRA_MENSAJES_PREVIOS}). La dirección opuesta NO
 * está cubierta: esta Activity nunca se cierra al abrir el chat (queda debajo en el back stack),
 * así que si el usuario escribe más preguntas allá y vuelve aquí con "atrás", esta pantalla
 * sigue mostrando solo su propio último intercambio de voz, no lo escrito después en el chat.
 * Sincronizar eso en ambos sentidos requeriría un almacén de conversación compartido entre
 * Activities (por ejemplo un ViewModel de Application) en lugar del historial en memoria de cada
 * una — cambio de arquitectura mayor, fuera de alcance de esta tarea.
 */
public class VozAsistenteActivity extends AppCompatActivity {

    private static final String KEY_SALUDO_REPRODUCIDO = "voz_saludo_reproducido";

    private TextView textEstadoVoz;
    private MaterialButton botonReintentar;
    private ScrollView scrollRespuesta;
    private TextView textRespuestaVoz;
    private View contenedorFuentesVoz;
    private TextView textFuentesVoz;
    private ImageButton botonMicrofonoVoz;
    private OndaAudioView ondaAudioVoz;
    private View viewPulsoMicVoz;
    /** Animador del anillo de pulso alrededor del micrófono (ver {@link #iniciarPulsoMic()}).
     * Independiente de {@link NexoBioAnimador} a propósito: su propio {@code anilloPulso}
     * también pulsa en HABLANDO, y este anillo debe pulsar SOLO mientras se escucha. */
    @Nullable
    private ValueAnimator animadorPulsoMic;

    private String equipoNombre;
    private String areaNombre;
    private String claseDetectorEquipo;
    private boolean hayEquipo;

    private ChatRepository chatRepository;
    private TextToSpeechManager textToSpeechManager;
    private SpeechRecognitionManager speechRecognitionManager;
    private NexoBioAnimador nexoBioAnimador;

    /** {@code true} en cuanto se dispara el saludo inicial (ver {@link #reproducirSaludoInicial()})
     * — se guarda en {@code onSaveInstanceState} para que una recreación de esta Activity
     * (rotación/cambio de configuración, o que el sistema mate el proceso mientras estaba en
     * segundo plano bajo {@link ChatActivity}) NO repita el saludo: solo debe sonar una vez por
     * conversación de voz real, no una vez por cada {@code onCreate()}. */
    private boolean saludoReproducido = false;
    /** {@code true} mientras el habla en curso del TTS es el saludo inicial (no una respuesta a
     * una pregunta): permite que {@code onInicioHabla()} anime SALUDANDO (mano + boca) en vez de
     * HABLANDO (solo boca) sin necesitar dos listeners de TTS distintos. */
    private boolean hablandoSaludo = false;
    private boolean pantallaActiva;
    private boolean microfonoPendiente;

    @Nullable
    private String ultimaPreguntaFallida;
    private final List<ChatMensaje> historialVoz = new ArrayList<>();

    private final ActivityResultLauncher<String> solicitarPermisoAudio =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), concedido -> {
                if (concedido) {
                    microfonoPendiente = true;
                    intentarMicrofonoPendiente();
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, R.string.chat_permiso_audio_requerido, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.chat_permiso_audio_denegado_permanente, Toast.LENGTH_LONG).show();
                }
            });

    /** Título de un item del menú de acción en blanco: el tema de este toolbar no aplica
     * automáticamente navigationIconTint/titleTextColor a los items de acción, así que sin esto
     * "Ver PDF" se vería con el color de texto por defecto del tema (oscuro, casi invisible
     * sobre el verde de la barra). */
    private static CharSequence blanco(String texto) {
        SpannableString resultado = new SpannableString(texto);
        resultado.setSpan(new ForegroundColorSpan(android.graphics.Color.WHITE), 0, texto.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return resultado;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voz_asistente);

        equipoNombre = getIntent().getStringExtra(ChatActivity.EXTRA_EQUIPO_NOMBRE);
        areaNombre = getIntent().getStringExtra(ChatActivity.EXTRA_AREA_NOMBRE);
        claseDetectorEquipo = getIntent().getStringExtra(ChatActivity.EXTRA_CLASE_DETECTOR);
        hayEquipo = !TextUtils.isEmpty(equipoNombre);
        if (savedInstanceState != null) {
            saludoReproducido = savedInstanceState.getBoolean(KEY_SALUDO_REPRODUCIDO, false);
        }

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        MaterialToolbar toolbar = findViewById(R.id.toolbarVoz);
        toolbar.setNavigationOnClickListener(v -> finish());
        // Icono de "tres puntos" (menú de Configuración, ver más abajo) pintado en blanco: sin
        // esto seguía el color por defecto del tema (oscuro), casi invisible sobre el verde de
        // la barra — el motivo por el que costaba encontrarlo.
        toolbar.setOverflowIcon(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_more_vert_white));
        // "Ver PDF" queda SIEMPRE visible en la barra (con texto, no escondido en el menú de
        // tres puntos): antes estaba oculto ahí y era difícil de encontrar. El texto se pinta
        // en blanco a mano (SpannableString) porque el color de texto de los items de acción no
        // sigue automáticamente a navigationIconTint/titleTextColor en este tema.
        android.view.MenuItem itemVerPdf = toolbar.getMenu().add(0, 7001, 0, blanco(getString(R.string.bio_ver_pdf_boton)));
        itemVerPdf.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS | android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        toolbar.getMenu().add(0, 7002, 1, R.string.config_title).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 7001) {
                Intent pdf = new Intent(this, ManualPdfActivity.class);
                pdf.putExtra(ChatActivity.EXTRA_CLASE_DETECTOR, claseDetectorEquipo);
                startActivity(pdf);
                return true;
            }
            if (item.getItemId() == 7002) {
                startActivity(new Intent(this, com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion.ConfiguracionActivity.class));
                return true;
            }
            return false;
        });

        ImageView imageBioTec = findViewById(R.id.imageBioTecCompleto);
        ImageView imageManoSaludo = findViewById(R.id.imageBioTecManoSaludo);
        // respiracionSutil=true: única diferencia de configuración frente al avatar pequeño del
        // chat (ver NexoBioAnimador) — misma lógica de parpadeo/guiño/pulso/boca de siempre.
        // imageManoSaludo habilita el estado SALUDANDO (mano levantada) solo en esta pantalla.
        nexoBioAnimador = new NexoBioAnimador(imageBioTec, null,
                R.drawable.ic_biotec_full, R.drawable.ic_biotec_full_blink,
                R.drawable.ic_biotec_full_wink, R.drawable.ic_biotec_full_talk, true,
                imageManoSaludo);

        TextView textConsultandoSobre = findViewById(R.id.textConsultandoSobre);
        if (hayEquipo) {
            textConsultandoSobre.setText(getString(R.string.voz_consultando_sobre, equipoNombre));
            textConsultandoSobre.setVisibility(View.VISIBLE);
        } else {
            textConsultandoSobre.setVisibility(View.GONE);
        }

        textEstadoVoz = findViewById(R.id.textEstadoVoz);
        botonReintentar = findViewById(R.id.botonReintentar);
        scrollRespuesta = findViewById(R.id.scrollRespuesta);
        textRespuestaVoz = findViewById(R.id.textRespuestaVoz);
        contenedorFuentesVoz = findViewById(R.id.contenedorFuentesVoz);
        textFuentesVoz = findViewById(R.id.textFuentesVoz);
        ondaAudioVoz = findViewById(R.id.ondaAudioVoz);
        viewPulsoMicVoz = findViewById(R.id.viewPulsoMicVoz);

        mostrarEstadoIdle();

        botonReintentar.setOnClickListener(v -> {
            if (ultimaPreguntaFallida != null) {
                enviarPregunta(ultimaPreguntaFallida);
            }
        });

        botonMicrofonoVoz = findViewById(R.id.botonMicrofonoVoz);
        botonMicrofonoVoz.setOnClickListener(v -> {
            if (speechRecognitionManager.estaEscuchando()) {
                speechRecognitionManager.detener();
            } else {
                solicitarPermisoYEscuchar();
            }
        });

        // Construidos aquí, apenas nexoBioAnimador y botonMicrofonoVoz ya existen (ambos los
        // necesita el listener de abajo, vía cambiarEstadoRobot) — no antes ni después, para que
        // TextToSpeech empiece a enlazarse con el servicio del sistema (bindService + protocolo
        // interno, ver TextToSpeechManager) lo antes posible dentro de este onCreate() y el
        // saludo inicial arranque en cuanto Android avise que está listo. El resto del retraso
        // percibido hasta ese aviso es inherente a Android (enlazar con el motor TTS del sistema
        // es async y no está bajo control de esta app) — ver reproducirSaludoInicial()/onListo().
        chatRepository = new ChatRepository(this);
        textToSpeechManager = new TextToSpeechManager(this);
        speechRecognitionManager = new SpeechRecognitionManager(this);

        textToSpeechManager.setEstadoListener(new TextToSpeechManager.EstadoListener() {
            // onListo/onStart/onDone/onError de TextToSpeech llegan en un hilo interno del TTS
            // (no necesariamente el de UI): hay que saltar a runOnUiThread antes de tocar
            // vistas/animación (igual que en ChatActivity), y comprobar que la Activity siga viva
            // — si el usuario ya salió de esta pantalla cuando llega el callback, no hay nada
            // que animar/hablar.
            @Override
            public void onListo() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    reproducirSaludoInicial();
                });
            }

            @Override
            public void onInicioHabla() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    cambiarEstadoRobot(hablandoSaludo ? NexoBioEstado.SALUDANDO : NexoBioEstado.HABLANDO);
                });
            }

            @Override
            public void onFinHabla() {
                runOnUiThread(() -> {
                    boolean terminoSaludo = hablandoSaludo;
                    hablandoSaludo = false;
                    if (isFinishing() || isDestroyed() || !pantallaActiva) return;
                    cambiarEstadoRobot(NexoBioEstado.NORMAL);
                    if (terminoSaludo) {
                        microfonoPendiente = true;
                        intentarMicrofonoPendiente();
                    }
                });
            }

            @Override
            public void onErrorHabla() {
                runOnUiThread(() -> {
                    hablandoSaludo = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    cambiarEstadoRobot(NexoBioEstado.NORMAL);
                });
            }
        });

        speechRecognitionManager.setResultadoListener(new SpeechRecognitionManager.ResultadoListener() {
            @Override
            public void onEscuchando() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                botonMicrofonoVoz.setActivated(true);
                mostrarEstado(getString(R.string.bio_habla_ahora));
                cambiarEstadoRobot(NexoBioEstado.ESCUCHANDO);
                ondaAudioVoz.reiniciar();
                ondaAudioVoz.setVisibility(View.VISIBLE);
                iniciarPulsoMic();
            }

            @Override
            public void onProcesando() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mostrarEstado(getString(R.string.chat_procesando));
                cambiarEstadoRobot(NexoBioEstado.PENSANDO);
                ondaAudioVoz.setVisibility(View.GONE);
                detenerPulsoMic();
            }

            @Override
            public void onResultado(String texto) {
                botonMicrofonoVoz.setActivated(false);
                ondaAudioVoz.setVisibility(View.GONE);
                detenerPulsoMic();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                enviarPregunta(texto);
            }

            @Override
            public void onError(String mensajeError) {
                botonMicrofonoVoz.setActivated(false);
                ondaAudioVoz.setVisibility(View.GONE);
                detenerPulsoMic();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                cambiarEstadoRobot(NexoBioEstado.ERROR);
                mostrarEstado(mensajeError);
                Toast.makeText(VozAsistenteActivity.this, mensajeError, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNivelAudio(float rmsdB) {
                // Llega en el hilo de UI (ver SpeechRecognitionManager#onRmsChanged): no hace
                // falta runOnUiThread. Si la vista ya no está visible (p. ej. llegó un evento
                // tardío justo después de onProcesando/onError) no pasa nada: invalidate() sobre
                // una vista GONE simplemente no dibuja nada.
                ondaAudioVoz.actualizarNivel(rmsdB);
            }
        });

        ImageButton botonSilenciar = findViewById(R.id.botonSilenciar);
        botonSilenciar.setOnClickListener(v -> {
            hablandoSaludo = false;
            textToSpeechManager.detener();
            cambiarEstadoRobot(NexoBioEstado.NORMAL);
        });

        MaterialButton botonChatear = findViewById(R.id.botonChatear);
        botonChatear.setOnClickListener(v -> abrirChatEscrito());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_SALUDO_REPRODUCIDO, saludoReproducido);
    }

    /** Habilita/deshabilita el botón de micrófono según el estado del robot: debe quedar
     * deshabilitado mientras BioTec está pensando/hablando/saludando (para no disparar una
     * segunda escucha superpuesta a una respuesta que todavía puede llegar, ver
     * {@link #enviarPregunta}) mientras permanece habilitado en ESCUCHANDO (el usuario necesita
     * poder volver a tocarlo para detener la escucha) y en NORMAL/ERROR. Centralizar esto aquí
     * evita tener que repetir la misma condición en cada sitio que cambia el estado del robot. */
    private void cambiarEstadoRobot(NexoBioEstado estado) {
        nexoBioAnimador.setEstado(estado);
        boolean micDisponible = estado != NexoBioEstado.PENSANDO
                && estado != NexoBioEstado.HABLANDO
                && estado != NexoBioEstado.SALUDANDO;
        botonMicrofonoVoz.setEnabled(micDisponible);
        // bg_mic_button_voz.xml no define un estado "deshabilitado" distinto (solo
        // activado/reposo): sin esto, un botón deshabilitado se vería idéntico a uno pulsable.
        botonMicrofonoVoz.setAlpha(micDisponible ? 1f : 0.5f);
    }

    /** Muestra el anillo de pulso alrededor del micrófono (ver {@code viewPulsoMicVoz} en
     * {@code activity_voz_asistente.xml}) en bucle mientras se escucha, como refuerzo visual
     * junto a {@link #ondaAudioVoz}. Mismo patrón simple de {@code ValueAnimator} que ya usa
     * {@code NexoBioAnimador} (sin librerías externas): escala 1→1.35 + desvanece alfa. */
    private void iniciarPulsoMic() {
        if (viewPulsoMicVoz == null || animadorPulsoMic != null) {
            return;
        }
        viewPulsoMicVoz.setScaleX(1f);
        viewPulsoMicVoz.setScaleY(1f);
        ValueAnimator animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(1000);
        animador.setRepeatMode(ValueAnimator.RESTART);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float escala = 1f + 0.35f * t;
            viewPulsoMicVoz.setScaleX(escala);
            viewPulsoMicVoz.setScaleY(escala);
            viewPulsoMicVoz.setAlpha((1f - t) * 0.7f);
        });
        animador.start();
        animadorPulsoMic = animador;
    }

    private void detenerPulsoMic() {
        if (animadorPulsoMic != null) {
            animadorPulsoMic.cancel();
            animadorPulsoMic = null;
        }
        if (viewPulsoMicVoz != null) {
            viewPulsoMicVoz.setAlpha(0f);
            viewPulsoMicVoz.setScaleX(1f);
            viewPulsoMicVoz.setScaleY(1f);
        }
    }

    /** Saludo inicial de BioTec (mano + TTS + boca sincronizada), ver estado SALUDANDO en
     * {@link NexoBioAnimador}. Solo se dispara la PRIMERA vez que esta Activity arranca una
     * conversación nueva (ver {@link #saludoReproducido}), y solo una vez que el TTS confirmó
     * estar listo (ver {@code onListo()} arriba) — evita la carrera de intentar hablar antes de
     * que {@link TextToSpeechManager} termine de inicializarse, en cuyo caso el saludo se
     * perdería en silencio. */
    private void reproducirSaludoInicial() {
        if (saludoReproducido || !pantallaActiva || !textToSpeechManager.estaListo()) {
            return;
        }
        saludoReproducido = true;
        hablandoSaludo = true;
        cambiarEstadoRobot(NexoBioEstado.SALUDANDO);
        textToSpeechManager.speak(getString(R.string.voz_saludo_inicial));
    }

    @Override
    protected void onResume() {
        super.onResume();
        pantallaActiva = true;
        if (textToSpeechManager != null) reproducirSaludoInicial();
        intentarMicrofonoPendiente();
        // El parpadeo/respiración de BioTec solo debe correr con la pantalla visible.
        if (nexoBioAnimador != null) {
            nexoBioAnimador.iniciarParpadeoAleatorio();
        }
    }

    @Override
    protected void onPause() {
        pantallaActiva = false;
        microfonoPendiente = false;
        super.onPause();
        // Igual que ChatActivity: si BioTec estaba hablando y la pantalla deja de estar visible
        // (p. ej. el usuario tocó "Usar chat escrito"), detener el audio para que no siga sonando
        // en segundo plano ya sin animación. También se cancela una escucha en curso (más
        // estricto que ChatActivity a propósito: aquí el resultado dispara una consulta de red
        // automática, así que no conviene que llegue mientras la pantalla no está visible).
        hablandoSaludo = false;
        if (textToSpeechManager != null) {
            textToSpeechManager.detener();
        }
        if (speechRecognitionManager != null) {
            // cancelar() no dispara onResultado/onError (ver SpeechRecognitionManager): sin
            // esto, la onda de voz podría quedar visible "congelada" en el último nivel si la
            // pantalla se pausó justo mientras el usuario estaba hablando.
            speechRecognitionManager.cancelar();
        }
        if (ondaAudioVoz != null) {
            ondaAudioVoz.setVisibility(View.GONE);
            ondaAudioVoz.reiniciar();
        }
        detenerPulsoMic();
        if (nexoBioAnimador != null) {
            nexoBioAnimador.detener();
        }
    }

    @Override
    protected void onDestroy() {
        if (nexoBioAnimador != null) {
            nexoBioAnimador.detener();
        }
        if (textToSpeechManager != null) {
            textToSpeechManager.liberar();
        }
        if (speechRecognitionManager != null) {
            speechRecognitionManager.liberar();
        }
        super.onDestroy();
    }

    private void mostrarEstadoIdle() {
        mostrarEstado(getString(hayEquipo ? R.string.voz_estado_idle : R.string.voz_estado_idle_general));
    }

    private void mostrarEstado(String texto) {
        botonReintentar.setVisibility(View.GONE);
        scrollRespuesta.setVisibility(View.GONE);
        textEstadoVoz.setText(texto);
        textEstadoVoz.setVisibility(View.VISIBLE);
    }

    private void intentarMicrofonoPendiente() {
        if (!microfonoPendiente || !pantallaActiva || isFinishing() || isDestroyed()) return;
        microfonoPendiente = false;
        solicitarPermisoYEscuchar();
    }

    private void solicitarPermisoYEscuchar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarEscucha();
        } else {
            solicitarPermisoAudio.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void iniciarEscucha() {
        if (!pantallaActiva || isFinishing() || isDestroyed()) return;
        if (!SpeechRecognitionManager.hayReconocimientoDisponible(this)) {
            Toast.makeText(this, R.string.voz_reconocimiento_no_disponible, Toast.LENGTH_SHORT).show();
            return;
        }
        // Si el asistente estaba hablando, se detiene antes de escuchar: evita que el micrófono
        // capture la propia voz del TTS como si fuera la pregunta del usuario (igual que en
        // ChatActivity.iniciarEscucha()).
        hablandoSaludo = false;
        if (textToSpeechManager != null && textToSpeechManager.estaHablando()) {
            textToSpeechManager.detener();
        }
        mostrarEstadoIdle(); // limpia una respuesta/error anterior antes de volver a escuchar
        speechRecognitionManager.escuchar();
    }

    /** Envía la pregunta (ya reconocida por voz) al mismo backend/RAG que el chat escrito. A
     * diferencia de {@code ChatActivity.enviarPregunta}, en cuanto llega la respuesta se
     * reproduce por voz automáticamente (ver {@link TextToSpeechManager#speak}), sin esperar a
     * que el usuario toque nada — ese es el único comportamiento propio del modo voz. */
    private void enviarPregunta(String pregunta) {
        hablandoSaludo = false;
        historialVoz.add(ChatMensaje.deUsuario(pregunta));
        mostrarEstado(getString(R.string.voz_estado_procesando));
        cambiarEstadoRobot(NexoBioEstado.PENSANDO);

        ChatRequest request = new ChatRequest(equipoNombre, areaNombre, claseDetectorEquipo, pregunta);
        chatRepository.enviarPregunta(request, new ChatRepository.Callback() {
            @Override
            public void onExito(ChatResponse respuesta) {
                // La respuesta puede tardar hasta 60s (ver ChatRepository): si el usuario ya
                // salió de esta pantalla en ese tiempo, no toques vistas de una Activity que ya
                // terminó.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                ultimaPreguntaFallida = null;
                historialVoz.add(ChatMensaje.deAsistente(respuesta.getRespuesta(), respuesta.getFuentes()));
                mostrarRespuesta(respuesta);
                // El micrófono deshabilitado durante PENSANDO (ver cambiarEstadoRobot) no impide
                // que el usuario lo haya vuelto a tocar justo antes de que esta respuesta
                // llegara si ya se había re-habilitado por algún otro camino: si por lo que sea
                // el reconocimiento sigue activo en este instante, se detiene primero para que
                // no capture la voz del propio TTS como si fuera una nueva pregunta.
                if (speechRecognitionManager.estaEscuchando()) {
                    speechRecognitionManager.cancelar();
                }
                textToSpeechManager.speak(MarkdownFormatter.toSpeechText(respuesta.getRespuesta()));
            }

            @Override
            public void onError(String mensaje) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                ultimaPreguntaFallida = pregunta;
                cambiarEstadoRobot(NexoBioEstado.ERROR);
                mostrarEstado(mensaje);
                botonReintentar.setVisibility(View.VISIBLE);
            }
        });
    }

    private void mostrarRespuesta(ChatResponse respuesta) {
        textEstadoVoz.setVisibility(View.GONE);
        botonReintentar.setVisibility(View.GONE);
        textRespuestaVoz.setText(MarkdownFormatter.toDisplaySpannable(respuesta.getRespuesta()));

        List<Fuente> fuentes = respuesta.getFuentes();
        if (fuentes == null || fuentes.isEmpty()) {
            contenedorFuentesVoz.setVisibility(View.GONE);
        } else {
            StringBuilder builder = new StringBuilder();
            for (Fuente fuente : fuentes) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("• ").append(fuente.getArchivo());
                if (fuente.getReferencia() != null && !fuente.getReferencia().isEmpty()) {
                    builder.append(" — ").append(fuente.getReferencia());
                }
            }
            textFuentesVoz.setText(builder.toString());
            contenedorFuentesVoz.setVisibility(View.VISIBLE);
        }
        scrollRespuesta.setVisibility(View.VISIBLE);
    }

    /** Abre el chat escrito existente (mismo {@link ChatActivity} de siempre) con el MISMO
     * equipo en contexto y el historial ya conversado por voz (ver {@link #historialVoz}). No
     * cierra esta pantalla: al volver con el botón atrás del chat, el usuario regresa aquí tal
     * como lo dejó (robot, último intercambio y equipo en contexto intactos). */
    private void abrirChatEscrito() {
        if (!NavegacionUtil.puedeNavegar()) {
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_EQUIPO_NOMBRE, equipoNombre);
        intent.putExtra(ChatActivity.EXTRA_AREA_NOMBRE, areaNombre);
        intent.putExtra(ChatActivity.EXTRA_CLASE_DETECTOR, claseDetectorEquipo);
        if (!historialVoz.isEmpty()) {
            intent.putExtra(ChatActivity.EXTRA_MENSAJES_PREVIOS, new ArrayList<>(historialVoz));
        }
        startActivity(intent);
    }
}
