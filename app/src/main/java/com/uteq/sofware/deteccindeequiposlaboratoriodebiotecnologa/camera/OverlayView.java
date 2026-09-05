package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;

/**
 * Capa transparente que se dibuja encima de {@code PreviewView} para pintar las cajas
 * delimitadoras (bounding boxes) devueltas por un {@code DetectorService} — TODAS las
 * detecciones válidas del fotograma, no solo la de mayor confianza (ver
 * {@link #setResultados}/{@code onDraw}: el bucle recorre la lista completa).
 * <p>
 * Las detecciones se reciben junto con el ancho/alto de la imagen que las generó
 * ({@code sourceWidth}/{@code sourceHeight}) — el fotograma ya rotado a orientación vertical
 * por {@code ImageProxyUtils}, la misma orientación en la que se muestra {@code PreviewView}.
 * <p>
 * {@code PreviewView} en este layout ({@code activity_deteccion.xml}) no declara
 * {@code app:scaleType}, por lo que usa el valor por defecto de CameraX,
 * {@code ScaleType.FILL_CENTER} ("cover": escala uniforme hasta cubrir toda la vista,
 * recortando el sobrante en un eje — nunca deforma la imagen). Esta vista replica exactamente
 * ese mismo cálculo de escala + recorte centrado para que las cajas coincidan visualmente con
 * lo que el usuario ve. Si se cambia el {@code scaleType} de {@code previewView} en el XML,
 * este cálculo debe actualizarse igual.
 * <p>
 * Selección táctil (ver {@link #habilitarSeleccionTactil}): DESACTIVADA por defecto (modo
 * detección normal, sin cambios de comportamiento). Solo {@code DeteccionActivity} en modo
 * selección desde el chat la activa; el hit-test compara el toque contra las MISMAS coordenadas
 * de pantalla que {@code onDraw} usa para dibujar (nunca contra las coordenadas 640x640 del
 * modelo), cacheadas por fotograma en {@link #cajasEnPantalla}.
 */
public class OverlayView extends View {

    /** TEMPORAL: logs de diagnostico de la cadena de coordenadas. Ver YoloTfliteDetector.DEBUG_LOGS. */
    private static final boolean DEBUG_LOGS = true;

    /** Tamaño de texto de la etiqueta en sp (no px crudos): escala con la densidad de pantalla y
     * la preferencia de tamaño de fuente del sistema, igual que un TextView normal. */
    private static final float ETIQUETA_TEXTO_SP = 14f;
    private static final float ETIQUETA_PADDING_DP = 8f;
    private static final float ETIQUETA_MARGEN_DP = 6f;
    private static final float ETIQUETA_RADIO_DP = 6f;
    private static final float MARGEN_TACTIL_DP = 16f;
    private static final int MAX_INTENTOS_ANTISOLAPE = 4;
    private static final long DURACION_RESALTADO_MS = 350;

    /** Notifica que el usuario tocó una detección válida (ver {@link #habilitarSeleccionTactil}). */
    public interface OnDeteccionTocadaListener {
        void onDeteccionTocada(@NonNull DetectionResult deteccion);
    }

    private final Paint boxPaint = new Paint();
    private final Paint boxPaintResaltado = new Paint();
    private final Paint textBackgroundPaint = new Paint();
    private final Paint textPaint = new Paint();

    private float etiquetaPaddingPx;
    private float etiquetaMargenPx;
    private float etiquetaRadioPx;
    private float margenTactilPx;

    private List<DetectionResult> resultados = Collections.emptyList();
    private int sourceWidth = 0;
    private int sourceHeight = 0;

    /** Caja de una detección en coordenadas YA transformadas a pantalla (las mismas que dibuja
     * onDraw), reconstruida en cada fotograma — es contra esto, y NO contra las coordenadas
     * 640x640 del modelo, que se compara un toque en {@link #resolverDeteccionTocada}. */
    private static final class CajaDetectada {
        final DetectionResult deteccion;
        final RectF caja;

        CajaDetectada(DetectionResult deteccion, RectF caja) {
            this.deteccion = deteccion;
            this.caja = caja;
        }
    }

    private final List<CajaDetectada> cajasEnPantalla = new ArrayList<>();
    private final List<RectF> etiquetasColocadas = new ArrayList<>();

    private boolean seleccionTactilHabilitada = false;
    @Nullable
    private OnDeteccionTocadaListener listenerToque;

    @Nullable
    private DetectionResult deteccionResaltada;
    private long resaltadoHastaMs = 0L;

    public OverlayView(Context context) {
        super(context);
        inicializarPaints();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        inicializarPaints();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        inicializarPaints();
    }

    private void inicializarPaints() {
        int boxColor = getResources().getColor(R.color.lab_overlay_box, null);
        boxPaint.setColor(boxColor);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);

        int colorResaltado = getResources().getColor(R.color.lab_accent, null);
        boxPaintResaltado.setColor(colorResaltado);
        boxPaintResaltado.setStyle(Paint.Style.STROKE);
        boxPaintResaltado.setStrokeWidth(10f);

        textBackgroundPaint.setColor(getResources().getColor(R.color.lab_overlay_text_bg, null));
        textBackgroundPaint.setStyle(Paint.Style.FILL);
        textBackgroundPaint.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, ETIQUETA_TEXTO_SP, getResources().getDisplayMetrics()));

        etiquetaPaddingPx = dpAPx(ETIQUETA_PADDING_DP);
        etiquetaMargenPx = dpAPx(ETIQUETA_MARGEN_DP);
        etiquetaRadioPx = dpAPx(ETIQUETA_RADIO_DP);
        margenTactilPx = dpAPx(MARGEN_TACTIL_DP);
    }

    private float dpAPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    /**
     * Activa la selección táctil (usada SOLO por {@code DeteccionActivity} en modo selección
     * desde el chat). En modo detección normal esta vista nunca la activa, así que
     * {@link #onTouchEvent} sigue devolviendo el comportamiento por defecto y no cambia nada del
     * uso normal de la cámara.
     */
    public void habilitarSeleccionTactil(@NonNull OnDeteccionTocadaListener listener) {
        this.listenerToque = listener;
        this.seleccionTactilHabilitada = true;
    }

    /**
     * Resalta brevemente el bounding box de una detección (feedback visual inmediato al
     * seleccionarla) sin mover ni alterar sus coordenadas reales.
     */
    public void resaltarDeteccion(@NonNull DetectionResult deteccion, long duracionMs) {
        this.deteccionResaltada = deteccion;
        this.resaltadoHastaMs = System.currentTimeMillis() + duracionMs;
        postInvalidate();
        postInvalidateDelayed(duracionMs + 50);
    }

    /**
     * Actualiza las detecciones a dibujar. Recibe la LISTA COMPLETA de detecciones válidas
     * (post-threshold, post-NMS) — ver {@code YoloTfliteDetector.detectar()}; esta vista no
     * recorta ni selecciona un subconjunto, dibuja cada una con su propia caja/etiqueta.
     *
     * @param resultados   lista de detecciones (puede estar vacía).
     * @param sourceWidth  ancho de la imagen usada por el detector para generar las coordenadas.
     * @param sourceHeight alto de la imagen usada por el detector para generar las coordenadas.
     */
    public void setResultados(@NonNull List<DetectionResult> resultados, int sourceWidth, int sourceHeight) {
        this.resultados = resultados;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        postInvalidate();
    }

    public void limpiar() {
        this.resultados = Collections.emptyList();
        postInvalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        cajasEnPantalla.clear();
        etiquetasColocadas.clear();
        if (resultados.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        // FILL_CENTER / "cover": una sola escala uniforme (la mayor de las dos) para que el
        // contenido cubra toda la vista, más un desplazamiento centrado que recorta el sobrante.
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float scale = Math.max(viewWidth / sourceWidth, viewHeight / sourceHeight);
        float contenidoAncho = sourceWidth * scale;
        float contenidoAlto = sourceHeight * scale;
        float offsetX = (viewWidth - contenidoAncho) / 2f;
        float offsetY = (viewHeight - contenidoAlto) / 2f;

        if (DEBUG_LOGS) {
            android.util.Log.d("OverlayView", "[DEBUG] view=" + viewWidth + "x" + viewHeight
                    + " source=" + sourceWidth + "x" + sourceHeight + " scale=" + scale
                    + " offset=(" + offsetX + "," + offsetY + ")  detecciones=" + resultados.size());
        }

        boolean resaltadoVigente = deteccionResaltada != null && System.currentTimeMillis() < resaltadoHastaMs;

        // Recorre TODAS las detecciones (no solo la de mayor confianza): cada una dibuja su
        // propio box + su propia etiqueta, de forma completamente independiente.
        for (DetectionResult resultado : resultados) {
            RectF box = new RectF(
                    resultado.getLeft() * scale + offsetX,
                    resultado.getTop() * scale + offsetY,
                    resultado.getRight() * scale + offsetX,
                    resultado.getBottom() * scale + offsetY);
            if (DEBUG_LOGS) {
                android.util.Log.d("OverlayView", "[DEBUG] caja final en pantalla: clase="
                        + resultado.getClassName() + " conf=" + resultado.getConfidence() + " box=" + box);
            }

            // Cache para hit-test: misma caja, mismas coordenadas de pantalla que se dibujan.
            cajasEnPantalla.add(new CajaDetectada(resultado, box));

            Paint paintCaja = (resaltadoVigente && resultado == deteccionResaltada) ? boxPaintResaltado : boxPaint;
            canvas.drawRect(box, paintCaja);

            // Nombre CORTO específico para esta etiqueta (ver Equipo.getNombreCorto() /
            // LabRepository.obtenerNombreCorto): equipos con nombre oficial largo (p. ej.
            // "Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP") no
            // deben cruzar toda la pantalla en una sola línea. El nombre completo se sigue
            // usando en chat/ficha técnica/contexto del backend — esto solo acorta el overlay.
            // LabRepository.obtenerNombreCorto ya resuelve por mapa (O(1)), no por lista lineal.
            String etiqueta = String.format(Locale.getDefault(), "%s · %.0f%%",
                    LabRepository.getInstancia().obtenerNombreCorto(resultado.getClassName()),
                    resultado.getConfidence() * 100f);
            dibujarEtiqueta(canvas, box, etiqueta, viewWidth, viewHeight);
        }
    }

    /**
     * Dibuja la etiqueta (nombre + confianza) de una detección con fondo sólido para contraste,
     * SIEMPRE dentro de los límites de la vista: nunca con X negativa, nunca recortada por el
     * borde derecho, nunca fuera por arriba/abajo. Preferencia de posición: justo encima de la
     * esquina superior izquierda del bounding box; si no hay espacio arriba, se coloca dentro de
     * la parte superior del box en su lugar. Si choca con la etiqueta de OTRA detección ya
     * dibujada en este mismo fotograma, se desplaza hacia abajo (estrategia ligera anti-solape;
     * nunca mueve el bounding box real, solo la etiqueta). No participa en absoluto en el
     * cálculo de coordenadas del bounding box (parámetro {@code box} de solo lectura aquí).
     */
    private void dibujarEtiqueta(Canvas canvas, RectF box, String etiqueta, float viewWidth, float viewHeight) {
        float textoAncho = textPaint.measureText(etiqueta);
        float textoAlto = textPaint.descent() - textPaint.ascent();
        float anchoEtiqueta = textoAncho + etiquetaPaddingPx * 2f;
        float altoEtiqueta = textoAlto + etiquetaPaddingPx * 2f;

        // Posición preferida: encima de la esquina superior izquierda del box.
        float left = box.left;
        float top = box.top - altoEtiqueta - etiquetaMargenPx;

        // Sin espacio arriba (box pegado al borde superior de la pantalla): colocarla DENTRO
        // de la parte superior del box en su lugar, nunca fuera de la vista por arriba.
        if (top < 0) {
            top = Math.min(box.top + etiquetaMargenPx, viewHeight - altoEtiqueta);
        }

        // Clamp horizontal: nunca X negativa, nunca se sale por el borde derecho.
        left = Math.max(0, Math.min(left, viewWidth - anchoEtiqueta));
        // Si la etiqueta es más ancha que la vista (nombre larguísimo en pantalla muy angosta),
        // prioriza no salirse por la izquierda sobre no salirse por la derecha.
        if (anchoEtiqueta > viewWidth) {
            left = 0;
        }
        // Clamp vertical final (por si el box ocupa casi toda la altura de la vista).
        top = Math.max(0, Math.min(top, viewHeight - altoEtiqueta));

        RectF etiquetaRect = new RectF(left, top, left + anchoEtiqueta, top + altoEtiqueta);

        // Anti-solape ligero: si dos o más equipos están muy juntos, sus etiquetas pueden
        // superponerse; se van bajando en pasos de una altura de etiqueta hasta encontrar
        // hueco, con un tope de intentos para no complicar el caso de muchas detecciones juntas.
        int intentos = 0;
        while (intentos < MAX_INTENTOS_ANTISOLAPE && seSuperponeConAlguna(etiquetaRect)) {
            float nuevoTop = etiquetaRect.top + altoEtiqueta + etiquetaMargenPx;
            if (nuevoTop + altoEtiqueta > viewHeight) {
                break; // sin espacio hacia abajo: mejor una superposición leve que salirse de la vista
            }
            etiquetaRect.offsetTo(etiquetaRect.left, nuevoTop);
            intentos++;
        }
        etiquetasColocadas.add(new RectF(etiquetaRect));

        canvas.drawRoundRect(etiquetaRect, etiquetaRadioPx, etiquetaRadioPx, textBackgroundPaint);
        float baselineY = etiquetaRect.top + etiquetaPaddingPx - textPaint.ascent();
        canvas.drawText(etiqueta, etiquetaRect.left + etiquetaPaddingPx, baselineY, textPaint);
    }

    private boolean seSuperponeConAlguna(RectF rect) {
        for (RectF colocada : etiquetasColocadas) {
            if (RectF.intersects(rect, colocada)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!seleccionTactilHabilitada || listenerToque == null) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            DetectionResult tocada = resolverDeteccionTocada(event.getX(), event.getY());
            if (tocada != null) {
                listenerToque.onDeteccionTocada(tocada);
            }
        }
        return true; // consume el evento para recibir el ACTION_UP correspondiente al ACTION_DOWN
    }

    /**
     * Compara el toque contra las coordenadas de pantalla YA transformadas (las mismas de
     * {@link #cajasEnPantalla}, construidas en el último {@code onDraw}) — nunca contra
     * coordenadas 640x640 del modelo. Si varias cajas contienen el punto (equipos superpuestos),
     * se elige la de MENOR ÁREA (más específica/en primer plano), no simplemente la primera de
     * la lista. Si ninguna caja contiene el punto exactamente, se reintenta con un margen táctil
     * alrededor de cada una (facilita seleccionar equipos pequeños) aplicando la misma regla.
     */
    @Nullable
    private DetectionResult resolverDeteccionTocada(float x, float y) {
        CajaDetectada mejor = null;
        float mejorArea = Float.MAX_VALUE;
        for (CajaDetectada c : cajasEnPantalla) {
            if (c.caja.contains(x, y)) {
                float area = c.caja.width() * c.caja.height();
                if (area < mejorArea) {
                    mejorArea = area;
                    mejor = c;
                }
            }
        }
        if (mejor != null) {
            return mejor.deteccion;
        }

        for (CajaDetectada c : cajasEnPantalla) {
            RectF conMargen = new RectF(c.caja);
            conMargen.inset(-margenTactilPx, -margenTactilPx);
            if (conMargen.contains(x, y)) {
                float area = c.caja.width() * c.caja.height();
                if (area < mejorArea) {
                    mejorArea = area;
                    mejor = c;
                }
            }
        }
        return mejor != null ? mejor.deteccion : null;
    }
}
