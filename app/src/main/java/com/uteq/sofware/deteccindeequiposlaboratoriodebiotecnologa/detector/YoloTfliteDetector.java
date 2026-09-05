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
import java.util.HashMap;
import java.util.List;
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
    private static final boolean DEBUG_LOGS = true;
    private static final int DEBUG_LOG_MAX_CANDIDATOS = 5;

    private Interpreter interpreter;
    private List<String> classNames;
    private final boolean modeloDisponible;
    private final String errorCarga;

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
        // interpreter==null cubre la ventana entre liberar() (llamado desde onDestroy de la
        // Activity) y que este hilo de análisis termine: modeloDisponible es final y no se
        // actualiza en liberar(), así que por sí solo no basta para detectar ese caso (ver
        // DeteccionActivity.onDestroy() para la corrección de fondo del orden de apagado).
        if (!modeloDisponible || frame == null || interpreter == null) {
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

            List<DetectionResult> candidatos = decodificarSalida(salida, lb, frame.getWidth(), frame.getHeight());
            List<DetectionResult> resultado = nmsPorClase(candidatos);

            if (DEBUG_LOGS) {
                Log.d(TAG, "[DEBUG] candidatos antes de NMS=" + candidatos.size()
                        + "  detecciones finales=" + resultado.size());
                for (DetectionResult d : resultado) {
                    Log.d(TAG, "[DEBUG] final: clase=" + d.getClassName() + " conf=" + d.getConfidence()
                            + " box=[" + d.getLeft() + "," + d.getTop() + "," + d.getRight() + "," + d.getBottom() + "]");
                }
            }
            return resultado;
        } catch (Exception e) {
            Log.e(TAG, "Error durante la inferencia sobre un fotograma", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void liberar() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
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

    private List<DetectionResult> decodificarSalida(float[][][] salida, LetterboxResult lb,
                                                      int origW, int origH) {
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
            if (mejorScore < DetectorConfig.CONFIDENCE_THRESHOLD) {
                continue;
            }

            float rawX = out[0][a], rawY = out[1][a], rawW = out[2][a], rawH = out[3][a];

            // xywh normalizado [0,1] respecto al lienzo 640x640 (con letterbox) -> pixeles 640x640
            float cx = rawX * INPUT_SIZE;
            float cy = rawY * INPUT_SIZE;
            float w = rawW * INPUT_SIZE;
            float h = rawH * INPUT_SIZE;

            float x1 = cx - w / 2f;
            float y1 = cy - h / 2f;
            float x2 = cx + w / 2f;
            float y2 = cy + h / 2f;
            if (DEBUG_LOGS && loggeados < DEBUG_LOG_MAX_CANDIDATOS) {
                Log.d(TAG, "[DEBUG] candidato ancla=" + a + " clase=" + classNames.get(mejorClase)
                        + " score=" + mejorScore + "  raw xywh=(" + rawX + "," + rawY + "," + rawW + "," + rawH + ")"
                        + "  caja en lienzo 640=[" + x1 + "," + y1 + "," + x2 + "," + y2 + "]");
            }

            // deshacer letterbox: quitar el relleno y des-escalar a la imagen original
            x1 = (x1 - lb.padLeft) / lb.escala;
            y1 = (y1 - lb.padTop) / lb.escala;
            x2 = (x2 - lb.padLeft) / lb.escala;
            y2 = (y2 - lb.padTop) / lb.escala;

            x1 = clamp(x1, 0, origW);
            y1 = clamp(y1, 0, origH);
            x2 = clamp(x2, 0, origW);
            y2 = clamp(y2, 0, origH);

            if (DEBUG_LOGS && loggeados < DEBUG_LOG_MAX_CANDIDATOS) {
                Log.d(TAG, "[DEBUG] candidato ancla=" + a + " caja sin letterbox (imagen "
                        + origW + "x" + origH + ")=[" + x1 + "," + y1 + "," + x2 + "," + y2 + "]");
                loggeados++;
            }

            if (x2 <= x1 || y2 <= y1) {
                continue; // caja degenerada tras el recorte, descartar
            }

            candidatos.add(new DetectionResult(mejorClase, classNames.get(mejorClase), mejorScore, x1, y1, x2, y2));
        }
        return candidatos;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** NMS independiente por clase (no agnostico), con DetectorConfig.IOU_THRESHOLD — igual que
     * el comportamiento por defecto de Ultralytics (agnostic_nms=False). */
    private static List<DetectionResult> nmsPorClase(List<DetectionResult> candidatos) {
        Map<Integer, List<DetectionResult>> porClase = new HashMap<>();
        for (DetectionResult d : candidatos) {
            porClase.computeIfAbsent(d.getClassId(), k -> new ArrayList<>()).add(d);
        }

        List<DetectionResult> resultado = new ArrayList<>();
        for (List<DetectionResult> lista : porClase.values()) {
            lista.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
            boolean[] suprimido = new boolean[lista.size()];
            for (int i = 0; i < lista.size(); i++) {
                if (suprimido[i]) {
                    continue;
                }
                DetectionResult actual = lista.get(i);
                resultado.add(actual);
                for (int j = i + 1; j < lista.size(); j++) {
                    if (!suprimido[j] && iou(actual, lista.get(j)) > DetectorConfig.IOU_THRESHOLD) {
                        suprimido[j] = true;
                    }
                }
            }
        }
        return resultado;
    }

    private static float iou(DetectionResult a, DetectionResult b) {
        float interLeft = Math.max(a.getLeft(), b.getLeft());
        float interTop = Math.max(a.getTop(), b.getTop());
        float interRight = Math.min(a.getRight(), b.getRight());
        float interBottom = Math.min(a.getBottom(), b.getBottom());
        float interArea = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);

        float areaA = (a.getRight() - a.getLeft()) * (a.getBottom() - a.getTop());
        float areaB = (b.getRight() - b.getLeft()) * (b.getBottom() - b.getTop());
        float union = areaA + areaB - interArea;
        return union <= 0 ? 0 : interArea / union;
    }
}
