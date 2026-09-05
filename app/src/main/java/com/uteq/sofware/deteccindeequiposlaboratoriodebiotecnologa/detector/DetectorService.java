package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector;

import android.graphics.Bitmap;

import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;

/**
 * Abstracción del detector de equipos. Permite que las Activities (por ejemplo
 * {@code DeteccionActivity}) trabajen contra esta interfaz sin conocer la implementación
 * concreta, de modo que el futuro {@code YoloTfliteDetector} (basado en un modelo .tflite
 * entrenado con YOLO) pueda integrarse sin modificar la interfaz de usuario.
 */
public interface DetectorService {

    /**
     * Indica si el detector está listo para analizar imágenes (por ejemplo, si el modelo
     * .tflite fue encontrado y cargado correctamente).
     */
    boolean estaListo();

    /**
     * Devuelve un mensaje de error REAL si {@code best.tflite}/{@code labels.txt} existen pero
     * la carga del modelo falló (archivo corrupto, forma de tensor inesperada, operación no
     * soportada, etc.), o {@code null} si no hay error (modelo cargado correctamente, o
     * simplemente no instalado todavía — ese caso NO es un error, ver {@link #estaListo()}).
     * Las Activities deben distinguir "modelo no instalado" de "modelo instalado pero con un
     * error real" en vez de mostrar el mismo aviso genérico para ambos casos.
     */
    String obtenerErrorCarga();

    /**
     * Analiza un fotograma y devuelve las detecciones encontradas.
     * Si el detector no está listo debe devolver una lista vacía en lugar de lanzar
     * una excepción o simular resultados.
     */
    List<DetectionResult> detectar(Bitmap frame);

    /**
     * Libera los recursos usados por el detector (por ejemplo, el intérprete de TensorFlow Lite).
     */
    void liberar();
}
