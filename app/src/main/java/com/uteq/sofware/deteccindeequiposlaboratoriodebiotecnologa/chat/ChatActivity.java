package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera.DeteccionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion.ConfiguracionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatRequest;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ChatResponse;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.HealthRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech.SpeechRecognitionManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.speech.TextToSpeechManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Chat del asistente inteligente ("BioTec"). Envía únicamente equipo/área/pregunta al backend
 * (POST /api/chat); toda la recuperación de documentos (RAG) y la consulta al LLM ocurren en el
 * servidor, nunca en el dispositivo. Si el usuario configuró una API Key propia
 * (ver {@link ConfiguracionActivity}), esta se adjunta como encabezado HTTP; en caso
 * contrario, el backend usa la suya propia si la tiene configurada.
 * <p>
 * El equipo en contexto puede llegar de dos formas: como {@code Intent} extra al abrir esta
 * Activity (desde {@code EquipoDetalleActivity}), o eligiéndolo en vivo desde el botón de cámara
 * de este chat (ver {@link #lanzadorCamara}, que reabre {@link DeteccionActivity} en modo
 * selección y conserva la conversación existente al volver).
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_EQUIPO_NOMBRE = "extra_equipo_nombre";
    public static final String EXTRA_AREA_NOMBRE = "extra_area_nombre";
    /** Clase estable del detector (ver {@code Equipo.getClaseDetector()} / {@code labels.txt}),
     * NO el nombre bonito del equipo: es el identificador que el backend usa para restringir
     * file_search al manual correspondiente (ver {@code equipo_manual_map.py}). La pasa
     * {@code EquipoDetalleActivity} al entrar directo desde la ficha de un equipo; para la
     * selección en vivo desde la cámara, ver {@link #lanzadorCamara} /
     * {@code DeteccionActivity.EXTRA_RESULTADO_CLASE_DETECTOR}. */
    public static final String EXTRA_CLASE_DETECTOR = "extra_clase_detector";

    private static final String KEY_EQUIPO_NOMBRE = "chat_equipo_nombre";
    private static final String KEY_AREA_NOMBRE = "chat_area_nombre";
    private static final String KEY_CLASE_DETECTOR = "chat_clase_detector";
    private static final String KEY_MENSAJES = "chat_mensajes";

    /** TEMPORAL: evidencia de lifecycle para el bug "seleccionar equipo vuelve a la pantalla
     * principal" (ver informe de la sesión). Si Android mata y recrea esta Activity mientras
     * DeteccionActivity estaba en primer plano (proceso muerto en segundo plano, frecuente en
     * teléfonos modestos con CameraX+TFLite ocupando memoria), aparecerá un onCreate() con
     * hash de instancia DISTINTO al que abrió la cámara — eso confirma la causa. Si el hash es
     * el MISMO, esta Activity nunca murió y el problema está en otro lado. Quitar estos logs (o
     * poner DEBUG_LIFECYCLE_LOGS en false) una vez confirmado en dispositivo físico. */
    private static final boolean DEBUG_LIFECYCLE_LOGS = true;
    private static final String TAG_LIFECYCLE = "ChatActivity#lifecycle";

    private ChatAdapter chatAdapter;
    private RecyclerView recyclerChat;
    private View cardEquipoSeleccionado;
    private TextView textNombreEquipoContexto;
    private TextView textEstadoAsistente;
    private View panelSinApiKey;
    private EditText editTextPregunta;
    private ImageButton botonEnviar;
    private ImageButton botonMicrofono;
    private ImageButton botonCamara;
    private ImageView imageNexoBioAvatar;

    private String equipoNombre;
    private String areaNombre;
    private String claseDetectorEquipo;

    private ChatRepository chatRepository;
    private HealthRepository healthRepository;
    private SecureConfigManager secureConfigManager;
    private TextToSpeechManager textToSpeechManager;
    private SpeechRecognitionManager speechRecognitionManager;
    private NexoBioAnimador nexoBioAnimador;

    private final ActivityResultLauncher<String> solicitarPermisoAudio =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), concedido -> {
                if (concedido) {
                    iniciarEscucha();
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, R.string.chat_permiso_audio_requerido, Toast.LENGTH_SHORT).show();
                } else {
                    mostrarAvisoPermisoDenegadoPermanente();
                }
            });

    /** Recibe el resultado de {@link DeteccionActivity} en modo selección (ver
     * {@link #abrirCamaraParaSeleccion()}). Si el usuario confirmó una detección, actualiza el
     * equipo en contexto SIN perder el historial de la conversación ya escrito; si canceló
     * (botón atrás), no cambia nada. */
    private final ActivityResultLauncher<Intent> lanzadorCamara =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), resultado -> {
                if (resultado.getResultCode() != Activity.RESULT_OK || resultado.getData() == null) {
                    return;
                }
                Intent data = resultado.getData();
                String nombre = data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_EQUIPO_NOMBRE);
                String area = data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_EQUIPO_AREA);
                String claseDetector = data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_CLASE_DETECTOR);
                if (!TextUtils.isEmpty(nombre)) {
                    actualizarContextoEquipo(nombre, area, claseDetector);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onCreate() instancia=" + System.identityHashCode(this)
                    + " savedInstanceState=" + (savedInstanceState != null ? "NO nulo (recreada)" : "nulo (instancia nueva)"));
        }
        setContentView(R.layout.activity_chat);

        equipoNombre = getIntent().getStringExtra(EXTRA_EQUIPO_NOMBRE);
        areaNombre = getIntent().getStringExtra(EXTRA_AREA_NOMBRE);
        claseDetectorEquipo = getIntent().getStringExtra(EXTRA_CLASE_DETECTOR);
        if (savedInstanceState != null) {
            // Esta Activity fue recreada (rotación, o el sistema mató el proceso en segundo
            // plano): el equipo/área guardados pueden ser más recientes que los del Intent
            // original (por ejemplo, si el usuario ya había cambiado de equipo con la cámara
            // antes de que ocurriera la recreación).
            equipoNombre = savedInstanceState.getString(KEY_EQUIPO_NOMBRE, equipoNombre);
            areaNombre = savedInstanceState.getString(KEY_AREA_NOMBRE, areaNombre);
            claseDetectorEquipo = savedInstanceState.getString(KEY_CLASE_DETECTOR, claseDetectorEquipo);
        }

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, false);

        MaterialToolbar toolbar = findViewById(R.id.toolbarChat);
        toolbar.setNavigationOnClickListener(v -> finish());

        imageNexoBioAvatar = findViewById(R.id.imageNexoBioAvatar);
        View viewPulsoNexoBio = findViewById(R.id.viewPulsoNexoBio);
        nexoBioAnimador = new NexoBioAnimador(imageNexoBioAvatar, viewPulsoNexoBio);

        cardEquipoSeleccionado = findViewById(R.id.cardEquipoSeleccionado);
        textNombreEquipoContexto = findViewById(R.id.textNombreEquipoContexto);
        actualizarContextoEquipo(equipoNombre, areaNombre, claseDetectorEquipo);

        MaterialButton botonCambiarEquipo = findViewById(R.id.botonCambiarEquipo);
        botonCambiarEquipo.setOnClickListener(v -> abrirCamaraParaSeleccion());

        recyclerChat = findViewById(R.id.recyclerChat);
        recyclerChat.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter(texto -> {
            if (textToSpeechManager != null) {
                textToSpeechManager.speak(MarkdownFormatter.toSpeechText(texto));
            }
        });
        recyclerChat.setAdapter(chatAdapter);
        if (savedInstanceState != null) {
            List<ChatMensaje> mensajesGuardados = leerMensajesGuardados(savedInstanceState);
            if (mensajesGuardados != null) {
                chatAdapter.restaurarMensajes(mensajesGuardados);
                recyclerChat.scrollToPosition(Math.max(0, chatAdapter.getCantidadMensajes() - 1));
            }
        }

        textEstadoAsistente = findViewById(R.id.textEstadoAsistente);
        panelSinApiKey = findViewById(R.id.panelSinApiKey);
        editTextPregunta = findViewById(R.id.editTextPregunta);

        botonEnviar = findViewById(R.id.botonEnviar);
        botonEnviar.setOnClickListener(v -> enviarPregunta());

        botonCamara = findViewById(R.id.botonCamara);
        botonCamara.setOnClickListener(v -> abrirCamaraParaSeleccion());

        botonMicrofono = findViewById(R.id.botonMicrofono);
        botonMicrofono.setOnClickListener(v -> {
            if (speechRecognitionManager.estaEscuchando()) {
                speechRecognitionManager.detener();
            } else {
                solicitarPermisoYEscuchar();
            }
        });

        // El panel de entrada está pegado al borde inferior: usa el mayor entre la barra de
        // navegación y el teclado (IME) para no quedar oculto tras ninguno de los dos.
        InsetsUtil.aplicarInsetInferiorConTeclado(findViewById(R.id.panelEntrada));

        MaterialButton botonIrAConfiguracion = findViewById(R.id.botonIrAConfiguracion);
        botonIrAConfiguracion.setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(ChatActivity.this, ConfiguracionActivity.class));
            }
        });

        chatRepository = new ChatRepository(this);
        healthRepository = new HealthRepository(this);
        secureConfigManager = new SecureConfigManager(this);
        textToSpeechManager = new TextToSpeechManager(this);
        speechRecognitionManager = new SpeechRecognitionManager(this);

        textToSpeechManager.setEstadoListener(new TextToSpeechManager.EstadoListener() {
            // onStart/onDone/onError de TextToSpeech llegan en un hilo interno del TTS, nunca en
            // el hilo de UI: hay que saltar a runOnUiThread antes de tocar el avatar animado.
            @Override
            public void onInicioHabla() {
                runOnUiThread(() -> nexoBioAnimador.setEstado(NexoBioEstado.HABLANDO));
            }

            @Override
            public void onFinHabla() {
                runOnUiThread(() -> nexoBioAnimador.setEstado(NexoBioEstado.NORMAL));
            }

            @Override
            public void onErrorHabla() {
                runOnUiThread(() -> nexoBioAnimador.setEstado(NexoBioEstado.NORMAL));
            }
        });

        speechRecognitionManager.setResultadoListener(new SpeechRecognitionManager.ResultadoListener() {
            @Override
            public void onEscuchando() {
                botonMicrofono.setActivated(true);
                mostrarEstado(getString(R.string.chat_escuchando));
                nexoBioAnimador.setEstado(NexoBioEstado.ESCUCHANDO);
            }

            @Override
            public void onProcesando() {
                mostrarEstado(getString(R.string.chat_procesando));
                nexoBioAnimador.setEstado(NexoBioEstado.PENSANDO);
            }

            @Override
            public void onResultado(String texto) {
                botonMicrofono.setActivated(false);
                ocultarEstado();
                nexoBioAnimador.setEstado(NexoBioEstado.NORMAL);
                editTextPregunta.setText(texto);
                editTextPregunta.setSelection(texto.length());
            }

            @Override
            public void onError(String mensajeError) {
                botonMicrofono.setActivated(false);
                ocultarEstado();
                nexoBioAnimador.setEstado(NexoBioEstado.ERROR);
                Toast.makeText(ChatActivity.this, mensajeError, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onStart() instancia=" + System.identityHashCode(this));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onResume() instancia=" + System.identityHashCode(this)
                    + " equipoNombre=" + equipoNombre + " mensajes=" + chatAdapter.getCantidadMensajes());
        }
        verificarDisponibilidadAsistente();
        // El parpadeo ocasional de NexoBio solo debe correr con la pantalla visible.
        if (nexoBioAnimador != null) {
            nexoBioAnimador.iniciarParpadeoAleatorio();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onPause() instancia=" + System.identityHashCode(this));
        }
        // Si BioTec estaba hablando y la pantalla deja de estar visible (p. ej. el usuario abrió
        // la cámara para cambiar de equipo), detener el audio también: si no, seguiría sonando
        // en segundo plano mientras la animación ya volvió a NORMAL, quedando desincronizados.
        if (textToSpeechManager != null) {
            textToSpeechManager.detener();
        }
        // Ninguna animación (parpadeo incluido) debe seguir corriendo si la pantalla no está
        // visible (lifecycle) — evita ValueAnimator/Handler en background sin motivo.
        if (nexoBioAnimador != null) {
            nexoBioAnimador.detener();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onStop() instancia=" + System.identityHashCode(this));
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_EQUIPO_NOMBRE, equipoNombre);
        outState.putString(KEY_AREA_NOMBRE, areaNombre);
        outState.putString(KEY_CLASE_DETECTOR, claseDetectorEquipo);
        outState.putSerializable(KEY_MENSAJES, new ArrayList<>(chatAdapter.getMensajes()));
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onSaveInstanceState() instancia=" + System.identityHashCode(this)
                    + " equipoNombre=" + equipoNombre + " mensajes=" + chatAdapter.getCantidadMensajes());
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private List<ChatMensaje> leerMensajesGuardados(Bundle savedInstanceState) {
        // minSdk 31: el overlaod tipado getSerializable(String, Class) requiere API 33+, así
        // que se usa el genérico (deprecado pero funcional) para mantener compatibilidad.
        return (List<ChatMensaje>) savedInstanceState.getSerializable(KEY_MENSAJES);
    }

    /** Actualiza el equipo/área en contexto del chat y la tarjeta compacta que lo muestra (ver
     * {@code activity_chat.xml}, {@code cardEquipoSeleccionado}). Se usa tanto al abrir el chat
     * con un equipo ya elegido ({@code EquipoDetalleActivity}) como al volver de
     * {@link #abrirCamaraParaSeleccion()} con una detección confirmada.
     * <p>
     * {@code claseDetector} es la clase estable del detector (ver {@code Equipo.claseDetector}),
     * NO el nombre bonito de {@code nombre}: es lo que el backend usa para restringir
     * file_search al manual correspondiente (ver {@code EXTRA_CLASE_DETECTOR} y
     * {@code ChatRequest}). Si {@code nombre} viene vacío (equipo limpiado), también se limpia
     * la clase para no dejar una asociación desactualizada. */
    private void actualizarContextoEquipo(String nombre, String area, String claseDetector) {
        equipoNombre = nombre;
        areaNombre = area;
        boolean hayEquipo = !TextUtils.isEmpty(equipoNombre);
        claseDetectorEquipo = hayEquipo ? claseDetector : null;
        cardEquipoSeleccionado.setVisibility(hayEquipo ? View.VISIBLE : View.GONE);
        if (hayEquipo) {
            textNombreEquipoContexto.setText(equipoNombre);
        }
    }

    /** Abre {@link DeteccionActivity} en modo selección (Chat → Cámara → detectar → seleccionar
     * → volver al mismo chat). La conversación actual no se toca: solo se actualiza el equipo en
     * contexto si el usuario confirma una detección (ver {@link #lanzadorCamara}). */
    private void abrirCamaraParaSeleccion() {
        if (!NavegacionUtil.puedeNavegar()) {
            return;
        }
        Intent intent = new Intent(this, DeteccionActivity.class);
        intent.putExtra(DeteccionActivity.EXTRA_MODO_SELECCION, true);
        lanzadorCamara.launch(intent);
    }

    /**
     * Antes de habilitar el chat, comprueba si hay alguna forma de autenticarse ante OpenAI:
     * una API Key local (guardada por el usuario) o una ya configurada en el backend
     * ({@code /health} → {@code rag_configurado}). Si no hay red o el backend no responde,
     * no se bloquea el chat aquí: el error de conexión se mostrará al intentar enviar una
     * pregunta (ver {@link ChatRepository}).
     */
    private void verificarDisponibilidadAsistente() {
        if (secureConfigManager.hasApiKey()) {
            mostrarChatDisponible();
            return;
        }

        healthRepository.consultarEstado(new HealthRepository.Callback() {
            @Override
            public void onResultado(boolean ragConfigurado) {
                // La respuesta de red puede llegar después de que el usuario ya haya salido de
                // esta pantalla (Volley la entrega en el hilo principal igualmente, pero sin
                // este chequeo tocaría vistas de una Activity que ya terminó).
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (ragConfigurado) {
                    mostrarChatDisponible();
                } else {
                    mostrarChatNoDisponible();
                }
            }

            @Override
            public void onError() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mostrarChatDisponible();
            }
        });
    }

    private void mostrarChatDisponible() {
        panelSinApiKey.setVisibility(View.GONE);
        habilitarEntrada(true);
    }

    private void mostrarChatNoDisponible() {
        panelSinApiKey.setVisibility(View.VISIBLE);
        habilitarEntrada(false);
    }

    private void habilitarEntrada(boolean habilitada) {
        editTextPregunta.setEnabled(habilitada);
        botonEnviar.setEnabled(habilitada);
        botonMicrofono.setEnabled(habilitada);
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
        if (!SpeechRecognitionManager.hayReconocimientoDisponible(this)) {
            Toast.makeText(this, "El reconocimiento de voz no está disponible en este dispositivo.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Si el asistente estaba hablando, se detiene antes de escuchar: evita que el micrófono
        // capture la propia voz del TTS como si fuera la pregunta del usuario.
        if (textToSpeechManager != null && textToSpeechManager.estaHablando()) {
            textToSpeechManager.detener();
        }
        speechRecognitionManager.escuchar();
    }

    /** El permiso fue denegado de forma permanente ("No volver a preguntar"): guía al usuario a Ajustes. */
    private void mostrarAvisoPermisoDenegadoPermanente() {
        Snackbar.make(botonMicrofono, R.string.chat_permiso_audio_denegado_permanente, Snackbar.LENGTH_LONG)
                .setAction(R.string.chat_ajustes, v -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .show();
    }

    private void mostrarEstado(String texto) {
        textEstadoAsistente.setText(texto);
        textEstadoAsistente.setVisibility(View.VISIBLE);
    }

    private void ocultarEstado() {
        textEstadoAsistente.setVisibility(View.GONE);
    }

    private void enviarPregunta() {
        String pregunta = editTextPregunta.getText().toString().trim();
        if (pregunta.isEmpty()) {
            return;
        }

        chatAdapter.agregarMensaje(ChatMensaje.deUsuario(pregunta));
        recyclerChat.scrollToPosition(chatAdapter.getCantidadMensajes() - 1);
        editTextPregunta.setText("");
        mostrarEstado(getString(R.string.chat_consultando));
        nexoBioAnimador.setEstado(NexoBioEstado.PENSANDO);

        ChatRequest request = new ChatRequest(equipoNombre, areaNombre, claseDetectorEquipo, pregunta);
        chatRepository.enviarPregunta(request, new ChatRepository.Callback() {
            @Override
            public void onExito(ChatResponse respuesta) {
                // La respuesta del backend puede tardar hasta 60s (ver ChatRepository): si el
                // usuario ya salió del chat en ese tiempo, no toques RecyclerView/adapter de una
                // Activity que ya terminó.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                ocultarEstado();
                nexoBioAnimador.setEstado(NexoBioEstado.NORMAL);
                // El backend ya arma el texto completo para mostrar al usuario tanto si
                // encontrado=true como si es false (sin info en los documentos, sin API Key
                // configurada, sin Vector Store configurado son tres mensajes DISTINTOS y
                // igual de válidos para mostrar tal cual — nunca un JSON técnico ni vacío).
                // Sustituirlo por un texto genérico fijo perdía esa distinción.
                chatAdapter.agregarMensaje(ChatMensaje.deAsistente(respuesta.getRespuesta(), respuesta.getFuentes()));
                recyclerChat.scrollToPosition(chatAdapter.getCantidadMensajes() - 1);
            }

            @Override
            public void onError(String mensaje) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                ocultarEstado();
                nexoBioAnimador.setEstado(NexoBioEstado.ERROR);
                chatAdapter.agregarMensaje(ChatMensaje.deAsistente(mensaje, null));
                recyclerChat.scrollToPosition(chatAdapter.getCantidadMensajes() - 1);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onDestroy() instancia=" + System.identityHashCode(this)
                    + " isFinishing=" + isFinishing() + " isChangingConfigurations=" + isChangingConfigurations());
        }
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
}
