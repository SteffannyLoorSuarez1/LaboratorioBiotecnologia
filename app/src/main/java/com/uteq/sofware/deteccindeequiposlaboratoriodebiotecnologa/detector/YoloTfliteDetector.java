package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;

/**
 * Implementación real de {@link DetectorService} basada en el modelo YOLO11n entrenado y
 * exportado a LiteRT/TFLite (ver {@code ml/README.md} y {@code docs/YOLO_SETUP.md}).
 * <p>
 * Contrato de tensores MEDIDO (no supuesto) sobre el {@code best.tflite} real, inspeccionando
 * {@code interpreter.get_input_details()}/{@code get_output_details()} en Google Colab tras
 * exportar con {@code ml/scripts/export_tflite.py}:
 * <ul>
 *     <li>Entrada: {@code [1, 3, 640, 640]} float32, NCHW, sin cuantizar, valores 0..1.</li>
 *     <li>Salida: {@code [1, 11, 8400]} float32, sin cuantizar. Canal 0-3 = caja
 *         (x_center, y_center, width, height) normalizada [0,1] respecto al lienzo 640x640
 *         (no respecto a la imagen original: hay que deshacer el letterbox). Canales 4-10 =
 *         score por clase (7 clases), ya con sigmoid aplicado — NO hay canal de objectness
 *         separado. Sin NMS embebido: hay que aplicarlo aquí.</li>
 * </ul>
 * Esta clase valida ese contrato contra el modelo real cargado en el constructor (no asume que
 * seguirá siendo así para siempre: si cambia, {@link #obtenerErrorCarga()} lo reporta en vez de
 * fallar en silencio o desviar coordenadas).
 */
public class YoloTfliteDetector implements DetectorService {

    private static final String TAG = "YoloTfliteDetector";
    private static final String MODEL_FILE = "best.tflite";
    private static final String LABELS_FILE = "labels.txt";

    private static final int INPUT_SIZE = 640;
    private static final int PAD_VALUE = 114; // mismo valor de relleno que ultralytics.data.augment.LetterBox
    private static final int NUM_THREADS = 4;

    /** TEMPORAL: logs de diagnostico de la cadena de coordenadas (ver LABELING/registro de
     * depuracion pedido). Poner en false (o borrar los bloques `if (DEBUG_LOGS)`) una vez
     * confirmado en dispositivo fisico que las cajas quedan correctamente alineadas. */
    private static final boolean DEBUG_LOGS = false;
    private static final int DEBUG_LOG_MAX_CANDIDATOS = 5;

    private Interpreter interpreter;
    private List<String> classNames;
    private final boolean modeloDisponible;
    private final String errorCarga;

    /** CAUSA RAIZ REAL de los cierres nativos (SIGSEGV dentro de libLiteRt.so) observados al
     * salir de esta pantalla justo tras seleccionar un equipo, confirmados en Logcat de
     * dispositivo físico (tombstone: "pool-4-thread-1 ... libLiteRt.so"): {@link #detectar} solo
     * comprobaba {@code interpreter == null} al PRINCIPIO del método, pero entre esa comprobación
     * y {@code interpreter.run(...)} no había ninguna exclusión mutua con {@link #liberar()}. El
     * intento anterior de evitar esto (ver {@code DeteccionActivity.onDestroy()}: apagar el
     * executor y esperar hasta 500ms antes de liberar) reduce la probabilidad pero NO la elimina:
     * si una inferencia tarda más de esos 500ms (frecuente en SoCs de gama media/baja, como el
     * que reportó el crash), {@code awaitTermination} devuelve {@code false} sin cancelar nada y
     * el código de todas formas cierra el intérprete mientras el hilo de análisis sigue dentro de
     * {@code Interpreter.run()} — un cierre/uso nativo concurrente clásico. Este lock hace que
     * {@link #liberar()} NUNCA pueda cerrar el intérprete mientras {@link #detectar} lo está
     * usando (y viceversa), sin importar cuánto tarde la inferencia: es la garantía real, la
     * espera con tope de tiempo en {@code DeteccionActivity} queda solo como optimización para
     * evitar tener que esperar aquí en el caso común. */
    private final Object interpreterLock = new Object();

    public YoloTfliteDetector(Context context) {
        boolean archivosPresentes = existeEnAssets(context, MODEL_FILE) && existeEnAssets(context, LABELS_FILE);
        if (!archivosPresentes) {
            this.modeloDisponible = false;
            this.errorCarga = null;
            Log.i(TAG, "Modelo de detección no encontrado en assets (" + MODEL_FILE + " / " + LABELS_FILE
                    + "). La detección estará deshabilitada hasta que se integre el modelo entrenado.");
            return;
        }

        boolean listo;
        String error = null;
        try {
            this.classNames = cargarLabels(context);
            MappedByteBuffer modelo = cargarModelo(context);
            Interpreter.Options opciones = new Interpreter.Options();
            opciones.setNumThreads(NUM_THREADS);
            this.interpreter = new Interpreter(modelo, opciones);
            validarContrato();
            listo = true;
            Log.i(TAG, "Modelo YOLO11n cargado correctamente (" + classNames.size() + " clases).");
        } catch (Exception e) {
            Log.e(TAG, "Error real cargando el modelo YOLO11n TFLite. NO se usaran detecciones falsas.", e);
            if (this.interpreter != null) {
                this.interpreter.close();
                this.interpreter = null;
            }
            listo = false;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        this.modeloDisponible = listo;
        this.errorCarga = error;
    }

    @Override
    public boolean estaListo() {
        return modeloDisponible;
    }

    @Override
    public String obtenerErrorCarga() {
        return errorCarga;
    }

    @Override
    public List<DetectionResult> detectar(Bitmap frame) {
        if (!modeloDisponible || frame == null) {
            return Collections.emptyList();
        }
        // TODO el uso del interprete (incluida la inferencia nativa) va dentro de este lock:
        // liberar() no puede cerrar el interprete mientras este bloque lo esta usando, sin
        // importar cuanto tarde la inferencia en este dispositivo (ver interpreterLock).
        synchronized (interpreterLock) {
            // interpreter==null cubre el caso de que liberar() (llamado desde onDestroy de la
            // Activity) ya haya cerrado el interprete antes de que este hilo de analisis llegara
            // a adquirir el lock: modeloDisponible es final y no se actualiza en liberar(), asi
            // que por si solo no basta para detectar ese caso.
            if (interpreter == null) {
                return Collections.emptyList();
            }
            try {
                LetterboxResult lb = letterbox(frame);
                float[][][][] entrada = bitmapToNchwTensor(lb.bitmap);
                lb.bitmap.recycle();

                float[][][] salida = new float[1][4 + classNames.size()][numAnclas()];
                interpreter.run(entrada, salida);

                if (DEBUG_LOGS) {
                    Log.d(TAG, "[DEBUG] bitmap analizado: " + frame.getWidth() + "x" + frame.getHeight()
                            + "  letterbox: escala=" + lb.escala + " padLeft=" + lb.padLeft + " padTop=" + lb.padTop);
                }

                Map<DetectionResult, float[][]> debugCadenaPorDeteccion =
                        DEBUG_LOGS ? new IdentityHashMap<>() : null;
                List<DetectionResult> candidatos = decodificarSalida(salida, lb, frame.getWidth(), frame.getHeight(),
                        debugCadenaPorDeteccion);
                List<DetectionResult> resultado = nmsPorClase(candidatos);

                if (DEBUG_LOGS) {
                    Log.d(TAG, "[DEBUG] candidatos antes de NMS=" + candidatos.size()
                            + "  detecciones finales=" + resultado.size());
                    for (DetectionResult d : resultado) {
                        // Cadena COMPLETA de la caja para esta deteccion final (sobrevivio al NMS):
                        // boxModelo640 (lectura cruda cx,cy,w,h ya en pixeles del lienzo 640x640,
                        // ANTES de invertir el letterbox) -> boxDespuesLetterbox (tras restar
                        // padding y dividir por escala, EXPRESADA EN COORDENADAS DE LA IMAGEN
                        // ORIGINAL, SIN recortar todavia -- si esta se sale mucho de
                        // [0,frameW]x[0,frameH], el clamp de boxImagen la va a "aplanar" contra el
                        // borde, y ESO es lo que se veria como caja gigante) -> boxImagen (post
                        // clamp, = las coordenadas que realmente guarda este DetectionResult).
                        float[][] cadena = debugCadenaPorDeteccion.get(d);
                        String box640Str = cadena != null ? aStr(cadena[0]) : "?";
                        String boxSinClampStr = cadena != null ? aStr(cadena[1]) : "?";
                        Log.d(TAG, "[DEBUG] FINAL class=" + d.getClassName() + " conf=" + d.getConfidence()
                                + " boxModelo640=" + box640Str
                                + " boxDespuesLetterbox(sinClamp)=" + boxSinClampStr
                                + " boxImagen(postClamp)=" + aStr(new float[]{d.getLeft(), d.getTop(), d.getRight(), d.getBottom()})
                                + " frameAnalizado=" + frame.getWidth() + "x" + frame.getHeight());
                    }
                }
                return resultado;
            } catch (Exception e) {
                Log.e(TAG, "Error durante la inferencia sobre un fotograma", e);
                return Collections.emptyList();
            }
        }
    }

    @Override
    public void liberar() {
        // Bloquea hasta que una inferencia en curso (si la hay) termine: ver interpreterLock.
        // Puede tardar lo que tarde esa inferencia en este dispositivo, pero es preferible a la
        // alternativa (cerrar el interprete nativo mientras otro hilo sigue dentro de el).
        synchronized (interpreterLock) {
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Carga y validacion
    // ------------------------------------------------------------------

    private int numAnclas() {
        return interpreter.getOutputTensor(0).shape()[2];
    }

    /** Lanza una excepcion (capturada en el constructor) si el modelo real no coincide con el
     * contrato [1,3,640,640] float32 de entrada / [1, 4+nc, N] float32 de salida. */
    private void validarContrato() {
        int[] shapeEntrada = interpreter.getInputTensor(0).shape();
        int[] esperadoEntrada = {1, 3, INPUT_SIZE, INPUT_SIZE};
        if (!Arrays.equals(shapeEntrada, esperadoEntrada)) {
            throw new IllegalStateException("Input shape inesperado: " + Arrays.toString(shapeEntrada)
                    + " (esperado " + Arrays.toString(esperadoEntrada) + ")");
        }
        if (interpreter.getInputTensor(0).dataType() != org.tensorflow.lite.DataType.FLOAT32) {
            throw new IllegalStateException("Input dtype inesperado: " + interpreter.getInputTensor(0).dataType()
                    + " (esperado FLOAT32)");
        }

        int[] shapeSalida = interpreter.getOutputTensor(0).shape();
        int esperadoCanales = 4 + classNames.size();
        if (shapeSalida.length != 3 || shapeSalida[0] != 1 || shapeSalida[1] != esperadoCanales) {
            throw new IllegalStateException("Output shape inesperado: " + Arrays.toString(shapeSalida)
                    + " (esperado [1, " + esperadoCanales + ", N])");
        }
        if (interpreter.getOutputTensor(0).dataType() != org.tensorflow.lite.DataType.FLOAT32) {
            throw new IllegalStateException("Output dtype inesperado: " + interpreter.getOutputTensor(0).dataType()
                    + " (esperado FLOAT32)");
        }
    }

    private static MappedByteBuffer cargarModelo(Context context) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel canal = in.getChannel();
            return canal.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private static List<String> cargarLabels(Context context) throws IOException {
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(LABELS_FILE), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                linea = linea.trim();
                if (!linea.isEmpty()) {
                    labels.add(linea);
                }
            }
        }
        if (labels.isEmpty()) {
            throw new IOException(LABELS_FILE + " esta vacio");
        }
        return labels;
    }

    private boolean existeEnAssets(Context context, String nombreArchivo) {
        try {
            for (String archivo : context.getAssets().list("")) {
                if (archivo.equals(nombreArchivo)) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "No se pudo listar assets/", e);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Preprocesamiento: letterbox 640x640 + tensor NCHW normalizado
    // ------------------------------------------------------------------

    /** Resultado de letterbox: imagen final 640x640 + los parametros necesarios para deshacerlo
     * sobre las cajas detectadas (mismo algoritmo que ultralytics.data.augment.LetterBox con sus
     * valores por defecto: auto=False, scale_fill=False, scaleup=True, center=True, stride=32,
     * padding_value=114 — usado tanto en entrenamiento como en export/predict). */
    private static final class LetterboxResult {
        Bitmap bitmap;
        float escala;
        int padLeft;
        int padTop;
    }

    private static LetterboxResult letterbox(Bitmap src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float r = Math.min((float) INPUT_SIZE / srcW, (float) INPUT_SIZE / srcH);
        int nuevoAnchoSinPad = Math.round(srcW * r);
        int nuevoAltoSinPad = Math.round(srcH * r);
        float dw = (INPUT_SIZE - nuevoAnchoSinPad) / 2f;
        float dh = (INPUT_SIZE - nuevoAltoSinPad) / 2f;
        int top = Math.round(dh - 0.1f);
        int left = Math.round(dw - 0.1f);

        Bitmap redimensionada = Bitmap.createScaledBitmap(src, nuevoAnchoSinPad, nuevoAltoSinPad, true);
        Bitmap conRelleno = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(conRelleno);
        canvas.drawColor(Color.rgb(PAD_VALUE, PAD_VALUE, PAD_VALUE));
        canvas.drawBitmap(redimensionada, left, top, null);
        if (redimensionada != src) {
            redimensionada.recycle();
        }

        LetterboxResult resultado = new LetterboxResult();
        resultado.bitmap = conRelleno;
        resultado.escala = r;
        resultado.padLeft = left;
        resultado.padTop = top;
        return resultado;
    }

    /** Bitmap 640x640 ARGB -> tensor float32 [1,3,640,640] NCHW normalizado 0..1, canal R,G,B
     * (Android decodifica JPEG/Bitmap en RGB directo; el modelo se entreno sobre fotos RGB, sin
     * conversion BGR de por medio). */
    private static float[][][][] bitmapToNchwTensor(Bitmap bitmap) {
        int size = INPUT_SIZE;
        float[][][][] tensor = new float[1][3][size][size];
        int[] pixeles = new int[size * size];
        bitmap.getPixels(pixeles, 0, size, 0, 0, size, size);
        for (int y = 0; y < size; y++) {
            int fila = y * size;
            for (int x = 0; x < size; x++) {
                int pixel = pixeles[fila + x];
                tensor[0][0][y][x] = ((pixel >> 16) & 0xFF) / 255f; // R
                tensor[0][1][y][x] = ((pixel >> 8) & 0xFF) / 255f;  // G
                tensor[0][2][y][x] = (pixel & 0xFF) / 255f;         // B
            }
        }
        return tensor;
    }

    // ------------------------------------------------------------------
    // Postprocesamiento: decodificar [1,11,8400] + deshacer letterbox + NMS por clase
    // ------------------------------------------------------------------

    /** TEMPORAL: formatea una caja [x1,y1,x2,y2] para los logs de depuracion de la cadena de
     * coordenadas (ver DEBUG_LOGS). Quitar junto con el resto del debug una vez confirmado en
     * dispositivo fisico que las cajas quedan correctamente ajustadas. */
    private static String aStr(float[] box) {
        return String.format(Locale.US, "[%.1f,%.1f,%.1f,%.1f] w=%.1f h=%.1f",
                box[0], box[1], box[2], box[3], box[2] - box[0], box[3] - box[1]);
    }

    private List<DetectionResult> decodificarSalida(float[][][] salida, LetterboxResult lb,
                                                      int origW, int origH,
                                                      Map<DetectionResult, float[][]> debugCadenaOut) {
        float[][] out = salida[0]; // [4+nc][N]
        int numAnclas = out[0].length;
        int numClases = classNames.size();
        List<DetectionResult> candidatos = new ArrayList<>();
        int loggeados = 0;

        for (int a = 0; a < numAnclas; a++) {
            int mejorClase = -1;
            float mejorScore = -1f;
            for (int c = 0; c < numClases; c++) {
                float score = out[4 + c][a];
                if (score > mejorScore) {
                    mejorScore = score;
                    mejorClase = c;
                }
            }
            // Estrictamente MAYOR (no >=): una confianza de exactamente 50.00% NO debe mostrarse,
            // solo 50.01% en adelante (ver DetectorConfig.CONFIDENCE_THRESHOLD). Único punto de
            // todo el pipeline que compara contra el umbral: NMS (nmsPorClase) ya no vuelve a
            // comparar contra CONFIDENCE_THRESHOLD, solo compara candidatos entre sí (IoU/
            // contención), y OverlayView dibuja tal cual la lista que recibe — así que filtrar
            // aquí basta para garantizar que nada <=50% llegue a NMS ni a pantalla.
            if (mejorScore <= DetectorConfig.CONFIDENCE_THRESHOLD) {
                continue;
            }

            float rawX = out[0][a], rawY = out[1][a], rawW = out[2][a], rawH = out[3][a];

            // xywh normalizado [0,1] respecto al lienzo 640x640 (con letterbox) -> pixeles 640x640
            float cx = rawX * INPUT_SIZE;
            float cy = rawY * INPUT_SIZE;
            float w = rawW * INPUT_SIZE;
            float h = rawH * INPUT_SIZE;

            float x1_640 = cx - w / 2f;
            float y1_640 = cy - h / 2f;
            float x2_640 = cx + w / 2f;
            float y2_640 = cy + h / 2f;
            if (DEBUG_LOGS && loggeados < DEBUG_LOG_MAX_CANDIDATOS) {
                Log.d(TAG, "[DEBUG] candidato ancla=" + a + " clase=" + classNames.get(mejorClase)
                        + " score=" + mejorScore + "  raw xywh=(" + rawX + "," + rawY + "," + rawW + "," + rawH + ")"
                        + "  caja en lienzo 640=[" + x1_640 + "," + y1_640 + "," + x2_640 + "," + y2_640 + "]");
            }

            // deshacer letterbox: quitar el relleno y des-escalar a la imagen original.
            // OJO: SIN recortar todavia -- si el modelo predijo una caja demasiado grande
            // para este ancla, aqui puede salirse mucho de [0,origW]x[0,origH]. Se guarda
            // este valor (boxSinClamp) ANTES del clamp para el log de depuracion: si en
            // dispositivo real se ve boxSinClamp muy fuera de rango mientras boxImagen
            // (post-clamp) queda pegada a los bordes, confirma que la "caja gigante" es un
            // recorte de una prediccion del modelo ya desproporcionada, no un error de
            // transformacion de coordenadas.
            float x1_sinClamp = (x1_640 - lb.padLeft) / lb.escala;
            float y1_sinClamp = (y1_640 - lb.padTop) / lb.escala;
            float x2_sinClamp = (x2_640 - lb.padLeft) / lb.escala;
            float y2_sinClamp = (y2_640 - lb.padTop) / lb.escala;

            float x1 = clamp(x1_sinClamp, 0, origW);
            float y1 = clamp(y1_sinClamp, 0, origH);
            float x2 = clamp(x2_sinClamp, 0, origW);
            float y2 = clamp(y2_sinClamp, 0, origH);

            if (DEBUG_LOGS && loggeados < DEBUG_LOG_MAX_CANDIDATOS) {
                Log.d(TAG, "[DEBUG] candidato ancla=" + a + " caja sin letterbox (imagen "
                        + origW + "x" + origH + ") sinClamp=[" + x1_sinClamp + "," + y1_sinClamp + ","
                        + x2_sinClamp + "," + y2_sinClamp + "]  postClamp=[" + x1 + "," + y1 + "," + x2 + "," + y2 + "]");
                loggeados++;
            }

            if (x2 <= x1 || y2 <= y1) {
                continue; // caja degenerada tras el recorte, descartar
            }

            DetectionResult candidato = new DetectionResult(mejorClase, classNames.get(mejorClase), mejorScore, x1, y1, x2, y2);
            candidatos.add(candidato);
            if (debugCadenaOut != null) {
                debugCadenaOut.put(candidato, new float[][]{
                        {x1_640, y1_640, x2_640, y2_640},
                        {x1_sinClamp, y1_sinClamp, x2_sinClamp, y2_sinClamp}
                });
            }
        }
        return candidatos;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** NMS por clase: conserva las cajas de mayor confianza; no supone que la menor sea mejor. */
    static List<DetectionResult> nmsPorClase(List<DetectionResult> candidatos) {
        List<DetectionResult> ordenados = new ArrayList<>(candidatos);
        ordenados.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
        List<DetectionResult> resultado = new ArrayList<>();
        for (DetectionResult candidato : ordenados) {
            boolean duplicado = false;
            for (DetectionResult existente : resultado) {
                if (existente.getClassId() == candidato.getClassId()
                        && iou(candidato, existente) > DetectorConfig.IOU_THRESHOLD) {
                    duplicado = true;
                    break;
                }
            }
            if (!duplicado) resultado.add(candidato);
        }
        return resultado;
    }

    private static float area(DetectionResult d) {
        return (d.getRight() - d.getLeft()) * (d.getBottom() - d.getTop());
    }

    private static float interseccion(DetectionResult a, DetectionResult b) {
        float interLeft = Math.max(a.getLeft(), b.getLeft());
        float interTop = Math.max(a.getTop(), b.getTop());
        float interRight = Math.min(a.getRight(), b.getRight());
        float interBottom = Math.min(a.getBottom(), b.getBottom());
        return Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
    }

    private static float iou(DetectionResult a, DetectionResult b) {
        float interArea = interseccion(a, b);
        float union = area(a) + area(b) - interArea;
        return union <= 0 ? 0 : interArea / union;
    }

}
