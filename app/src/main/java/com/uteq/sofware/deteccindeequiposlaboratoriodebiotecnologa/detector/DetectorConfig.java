package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector;

/**
 * Configuración única del detector YOLO11n. Ningún otro archivo debe declarar su propio
 * valor de threshold de confianza "quemado"; todos deben referenciar esta clase.
 * <p>
 * Equivalente, del lado del pipeline de entrenamiento, a {@code ml/detector_config.json}. Si
 * se cambia este valor, actualizar también ese archivo (y viceversa) para que ambos coincidan.
 */
public final class DetectorConfig {

    /** Confianza mínima para mostrar una detección (0.0–1.0). */
    public static final float CONFIDENCE_THRESHOLD = 0.5f;

    /** IoU usado en la supresión de no-máximos (NMS) del post-procesamiento. */
    public static final float IOU_THRESHOLD = 0.45f;

    private DetectorConfig() {
    }
}
