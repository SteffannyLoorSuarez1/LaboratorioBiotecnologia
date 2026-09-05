package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector.DetectorService;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector.YoloTfliteDetector;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.AreaLaboratorio;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.Equipo;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;

/**
 * Pantalla de detección: muestra la cámara real del dispositivo mediante CameraX ({@code Preview}
 * + {@code ImageAnalysis}) y superpone {@link OverlayView} con las cajas devueltas por
 * {@link DetectorService} (implementado por {@link YoloTfliteDetector}) en tiempo real.
 * <p>
 * Cada fotograma de {@code ImageAnalysis} se analiza en {@link #executorAnalisis} (nunca en el
 * hilo de UI), con {@code STRATEGY_KEEP_ONLY_LATEST} y {@link #detectando} garantizando que nunca
 * hay más de una inferencia corriendo a la vez. Si {@code best.tflite}/{@code labels.txt} no
 * están instalados, o si el modelo existe pero falla al cargar, esta pantalla NO simula
 * detecciones: solo muestra la vista previa de la cámara y un aviso real (ver
 * {@link #actualizarBannerModelo()}).
 */
public class DeteccionActivity extends AppCompatActivity {

    /** Si se pasa {@code true}, activa el modo selección (ver {@link #modoSeleccion}): usado
     * cuando {@code ChatActivity} abre esta pantalla desde el botón de cámara del chat. Cuando
     * se abre normalmente (Inicio, tarjeta "Detectar equipos"), esta extra no se envía y el
     * comportamiento es exactamente el mismo de siempre (solo visor en vivo). */
    public static final String EXTRA_MODO_SELECCION = "extra_modo_seleccion";

    /** Extras del resultado devuelto vía Activity Result cuando el usuario confirma una
     * selección en modo selección (ver {@link #confirmarSeleccion()}). */
    public static final String EXTRA_RESULTADO_EQUIPO_NOMBRE = "extra_resultado_equipo_nombre";
    public static final String EXTRA_RESULTADO_EQUIPO_AREA = "extra_resultado_equipo_area";
    public static final String EXTRA_RESULTADO_CLASE_DETECTOR = "extra_resultado_clase_detector";

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView bannerModeloPendiente;
    private android.widget.LinearLayout panelPermiso;
    private TextView textMensajePermiso;
    private MaterialButton botonConcederPermiso;

    private DetectorService detectorService;
    private ExecutorService executorAnalisis;
    private final AtomicBoolean detectando = new AtomicBoolean(false);

    private boolean modoSeleccion;

    private final ActivityResultLauncher<String> solicitarPermisoCamara =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), concedido -> {
                if (concedido) {
                    mostrarCamara();
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    mostrarPanelPermiso(false);
                } else {
                    mostrarPanelPermiso(true);
                }
            });

    /** TEMPORAL: evidencia de lifecycle para correlacionar con {@code ChatActivity#lifecycle} en
     * Logcat durante Chat→Cámara→Seleccionar (ver informe de la sesión, bug "seleccionar equipo
     * vuelve a la pantalla principal"). Quitar (o poner en false) una vez confirmado en
     * dispositivo físico que el back stack se conserva correctamente. */
    private static final boolean DEBUG_LIFECYCLE_LOGS = true;
    private static final String TAG_LIFECYCLE = "DeteccionActivity#lifecycle";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onCreate() instancia=" + System.identityHashCode(this)
                    + " modoSeleccion=" + getIntent().getBooleanExtra(EXTRA_MODO_SELECCION, false));
        }
        setContentView(R.layout.activity_deteccion);

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        MaterialToolbar toolbar = findViewById(R.id.toolbarDeteccion);
        toolbar.setNavigationOnClickListener(v -> finish());

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        bannerModeloPendiente = findViewById(R.id.bannerModeloPendiente);
        panelPermiso = findViewById(R.id.panelPermiso);
        textMensajePermiso = findViewById(R.id.textMensajePermiso);

        botonConcederPermiso = findViewById(R.id.botonConcederPermiso);

        // Multidetección real: en modo selección, CUALQUIERA de las detecciones válidas
        // simultáneas se puede tocar (ver OverlayView.habilitarSeleccionTactil) — nunca se
        // preselecciona automáticamente "la de mayor confianza". En modo normal (fuera de este
        // if) OverlayView nunca activa el hit-test táctil, así que el visor de siempre no
        // cambia en nada.
        modoSeleccion = getIntent().getBooleanExtra(EXTRA_MODO_SELECCION, false);
        if (modoSeleccion) {
            findViewById(R.id.bannerModoSeleccion).setVisibility(android.view.View.VISIBLE);
            overlayView.habilitarSeleccionTactil(this::onDeteccionTocadaEnModoSeleccion);
        }

        executorAnalisis = Executors.newSingleThreadExecutor();

        detectorService = new YoloTfliteDetector(getApplicationContext());
        actualizarBannerModelo();

        if (tienePermisoCamara()) {
            mostrarCamara();
        } else {
            solicitarPermisoCamara.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onResume() instancia=" + System.identityHashCode(this));
        }
        // Cubre el caso de que el usuario haya concedido el permiso desde Ajustes y regrese.
        if (tienePermisoCamara() && panelPermiso.getVisibility() == android.view.View.VISIBLE) {
            mostrarCamara();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onPause() instancia=" + System.identityHashCode(this));
        }
    }

    /**
     * Distingue "modelo no instalado todavía" (aviso neutro, ya existía) de "modelo instalado
     * pero con un error real al cargarlo" (aviso de error, con el detalle de la excepción) — no
     * deben mostrar el mismo mensaje genérico ni caer silenciosamente uno en el otro.
     */
    private void actualizarBannerModelo() {
        if (detectorService.estaListo()) {
            bannerModeloPendiente.setVisibility(android.view.View.GONE);
            return;
        }
        bannerModeloPendiente.setVisibility(android.view.View.VISIBLE);
        String error = detectorService.obtenerErrorCarga();
        if (error != null) {
            bannerModeloPendiente.setText(getString(R.string.deteccion_modelo_error, error));
            bannerModeloPendiente.setBackgroundColor(ContextCompat.getColor(this, R.color.lab_error));
            bannerModeloPendiente.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            bannerModeloPendiente.setText(R.string.deteccion_modelo_pendiente);
            bannerModeloPendiente.setBackgroundColor(ContextCompat.getColor(this, R.color.lab_warning_bg));
            bannerModeloPendiente.setTextColor(ContextCompat.getColor(this, R.color.lab_warning));
        }
    }

    private boolean tienePermisoCamara() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param denegadoPermanente si es {@code true}, el usuario marcó "No volver a preguntar";
     *                           en ese caso se ofrece abrir los ajustes de la aplicación en
     *                           lugar de repetir la solicitud del sistema (que ya no se mostrará).
     */
    private void mostrarPanelPermiso(boolean denegadoPermanente) {
        panelPermiso.setVisibility(android.view.View.VISIBLE);

        if (denegadoPermanente) {
            textMensajePermiso.setText(R.string.deteccion_permiso_denegado_permanente);
            botonConcederPermiso.setText(R.string.deteccion_abrir_ajustes);
            botonConcederPermiso.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            });
        } else {
            textMensajePermiso.setText(R.string.deteccion_permiso_requerido);
            botonConcederPermiso.setText(R.string.deteccion_conceder_permiso);
            botonConcederPermiso.setOnClickListener(v ->
                    solicitarPermisoCamara.launch(Manifest.permission.CAMERA));
        }
    }

    private void mostrarCamara() {
        panelPermiso.setVisibility(android.view.View.GONE);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(executorAnalisis, this::analizarFotograma);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();

                // CRITICO para que las cajas coincidan con lo que se ve en pantalla: Preview e
                // ImageAnalysis son dos streams de cámara INDEPENDIENTES. Sin un ViewPort común,
                // CameraX puede elegir para cada uno una resolución/aspect ratio distinta (p. ej.
                // Preview 16:9 vs ImageAnalysis 4:3), y las cajas calculadas sobre el fotograma de
                // ImageAnalysis quedan desalineadas/gigantes al mapearlas sobre PreviewView. El
                // ViewPort de previewView obliga a ambos casos de uso a compartir el mismo campo
                // de visión real (ver ImageProxyUtils.toBitmap(), que además respeta
                // ImageProxy.getCropRect() para el recorte exacto).
                ViewPort viewPort = previewView.getViewPort();
                if (viewPort != null) {
                    UseCaseGroup useCaseGroup = new UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(imageAnalysis)
                            .setViewPort(viewPort)
                            .build();
                    cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup);
                } else {
                    // previewView todavia no tiene tamano asignado (layout no completado); caso
                    // raro pero posible en el primer arranque. Se enlaza sin ViewPort para no
                    // bloquear la camara -- puede desalinear las cajas hasta el proximo bind.
                    Log.w("DeteccionActivity", "previewView.getViewPort() devolvio null: "
                            + "vinculando Preview/ImageAnalysis sin ViewPort compartido.");
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e("DeteccionActivity", "No se pudo iniciar la cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Analiza un fotograma de {@code ImageAnalysis} (corre en {@code executorAnalisis}, nunca en
     * el hilo de UI). Con {@code STRATEGY_KEEP_ONLY_LATEST} CameraX ya descarta fotogramas viejos
     * si el analyzer está ocupado; {@link #detectando} es una segunda garantía explícita para
     * nunca tener más de una inferencia real corriendo a la vez. El {@link ImageProxy} SIEMPRE
     * se cierra (try/finally), se procese o no, para no bloquear el pipeline de la cámara.
     */
    /** TEMPORAL: logs de diagnostico de la cadena de coordenadas. Ver YoloTfliteDetector.DEBUG_LOGS. */
    private static final boolean DEBUG_LOGS = true;

    private void analizarFotograma(@NonNull ImageProxy imageProxy) {
        if (!detectorService.estaListo() || !detectando.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        try {
            int rotacion = imageProxy.getImageInfo().getRotationDegrees();
            if (DEBUG_LOGS) {
                Log.d("DeteccionActivity", "[DEBUG] ImageProxy=" + imageProxy.getWidth() + "x" + imageProxy.getHeight()
                        + " cropRect=" + imageProxy.getCropRect() + " rotationDegrees=" + rotacion
                        + "  previewView=" + previewView.getWidth() + "x" + previewView.getHeight());
            }

            Bitmap frame = ImageProxyUtils.toBitmap(imageProxy);
            List<DetectionResult> resultados = detectorService.detectar(frame);
            int ancho = frame.getWidth();
            int alto = frame.getHeight();
            if (DEBUG_LOGS) {
                Log.d("DeteccionActivity", "[DEBUG] frame analizado (post-crop/rotacion)=" + ancho + "x" + alto
                        + "  detecciones=" + resultados.size());
            }
            // Se pasa la LISTA COMPLETA de detecciones (post-threshold, post-NMS) a OverlayView:
            // esta Activity no elige "la mejor" en ningún punto, ni aquí ni en modo selección
            // (la selección real ocurre por toque, ver onDeteccionTocadaEnModoSeleccion).
            ContextCompat.getMainExecutor(this).execute(() -> overlayView.setResultados(resultados, ancho, alto));
        } catch (Exception e) {
            Log.e("DeteccionActivity", "Error analizando fotograma", e);
        } finally {
            imageProxy.close();
            detectando.set(false);
        }
    }

    /**
     * CAUSA RAIZ IDENTIFICADA de cierres intermitentes de la app al salir de esta pantalla (ver
     * informe de la sesión, "crashes/navegación"): el orden anterior llamaba primero a
     * {@code detectorService.liberar()} (cierra el {@code Interpreter} nativo de TFLite) y
     * DESPUÉS a {@code executorAnalisis.shutdown()}. {@code shutdown()} no cancela ni espera la
     * tarea que {@code executorAnalisis} pudiera estar ejecutando en ese instante — si
     * {@link #analizarFotograma} seguía dentro de {@code detectorService.detectar()} (es decir,
     * dentro de {@code Interpreter.run()}) en el momento exacto en que el hilo de UI cerraba el
     * interprete, se producía una carrera entre "cerrar" y "usar" el mismo objeto nativo de
     * TFLite. Eso no siempre lanza una excepción Java capturable: puede terminar en un crash
     * nativo del proceso, lo que encaja con el síntoma real reportado ("a veces se cierra, no
     * siempre, al regresar de cámara").
     * <p>
     * Corrección: apagar el executor primero y esperar (con tope de tiempo) a que la tarea en
     * curso termine ANTES de cerrar el interprete, garantizando que ningún hilo siga dentro de
     * {@code detectar()}/{@code Interpreter.run()} cuando {@code liberar()} se ejecuta.
     */
    @Override
    protected void onDestroy() {
        if (DEBUG_LIFECYCLE_LOGS) {
            Log.d(TAG_LIFECYCLE, "onDestroy() instancia=" + System.identityHashCode(this)
                    + " isFinishing=" + isFinishing());
        }
        if (executorAnalisis != null) {
            executorAnalisis.shutdown();
            try {
                executorAnalisis.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (detectorService != null) {
            detectorService.liberar();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Modo selección (Chat -> Cámara -> detectar -> TOCAR una de varias -> volver al chat)
    // ------------------------------------------------------------------

    /**
     * El usuario tocó una detección válida directamente sobre su bounding box (ver
     * {@link OverlayView#habilitarSeleccionTactil}) — puede haber otras detecciones simultáneas
     * en pantalla y esto NO las afecta ni las descarta, solo confirma la que realmente se tocó.
     * Dispara un resaltado breve del box + un aviso corto (no bloqueante) y confirma la
     * selección con un pequeño retraso para que el usuario alcance a percibir cuál eligió antes
     * de volver al chat.
     */
    private void onDeteccionTocadaEnModoSeleccion(@NonNull DetectionResult deteccion) {
        String nombre = LabRepository.getInstancia().obtenerNombreAmigable(deteccion.getClassName());
        overlayView.resaltarDeteccion(deteccion, 350);
        Toast.makeText(this, getString(R.string.deteccion_equipo_seleccionado_toast, nombre), Toast.LENGTH_SHORT).show();
        overlayView.postDelayed(() -> confirmarSeleccion(deteccion), 350);
    }

    /** Devuelve el equipo tocado a quien haya lanzado esta Activity (típicamente
     * {@code ChatActivity} vía {@code ActivityResultLauncher}) y cierra SOLO esta pantalla. */
    private void confirmarSeleccion(@NonNull DetectionResult deteccion) {
        if (isFinishing() || isDestroyed()) {
            // El usuario pudo pulsar "atrás" en los ~350ms del resaltado antes de que este
            // callback retrasado (ver onDeteccionTocadaEnModoSeleccion) llegara a ejecutarse.
            return;
        }
        Equipo equipo = LabRepository.getInstancia().obtenerEquipoPorClaseDetector(deteccion.getClassName());
        String nombreMostrado = equipo != null ? equipo.getNombre() : deteccion.getClassName();

        Intent resultado = new Intent();
        resultado.putExtra(EXTRA_RESULTADO_EQUIPO_NOMBRE, nombreMostrado);
        resultado.putExtra(EXTRA_RESULTADO_CLASE_DETECTOR, deteccion.getClassName());
        if (equipo != null) {
            AreaLaboratorio area = LabRepository.getInstancia().obtenerAreaPorId(equipo.getAreaId());
            resultado.putExtra(EXTRA_RESULTADO_EQUIPO_AREA, area != null ? area.getNombre() : "");
        }
        setResult(RESULT_OK, resultado);
        finish();
    }
}
