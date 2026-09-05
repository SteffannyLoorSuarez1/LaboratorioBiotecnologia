package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Convierte un {@link ImageProxy} de CameraX (formato YUV_420_888, el que entrega
 * {@code ImageAnalysis} por defecto) a un {@link Bitmap} RGB en posición vertical correcta,
 * respetando la rotación del sensor respecto a la orientación de pantalla.
 * <p>
 * El bitmap resultante queda en la MISMA orientación Y el MISMO recorte/campo de visión que ve
 * el usuario en {@code PreviewView}, por lo que sus dimensiones son directamente comparables a
 * las de la vista al mapear cajas en {@link OverlayView} — para esto es imprescindible que
 * {@code DeteccionActivity} enlace {@code Preview} e {@code ImageAnalysis} bajo el mismo
 * {@code ViewPort} (ver {@code UseCaseGroup} en {@code mostrarCamara()}): sin eso, cada caso de
 * uso puede pedirle a la cámara una resolución/aspect ratio distinta, y el recorte real que
 * CameraX aplica ({@link ImageProxy#getCropRect()}) puede no coincidir con lo que
 * {@code PreviewView} realmente muestra.
 */
final class ImageProxyUtils {

    private ImageProxyUtils() {
    }

    static Bitmap toBitmap(ImageProxy imageProxy) {
        Bitmap completo = yuv420888ToBitmap(imageProxy);
        Bitmap recortado = recortarACropRect(completo, imageProxy.getCropRect());

        int rotacion = imageProxy.getImageInfo().getRotationDegrees();
        if (rotacion == 0) {
            return recortado;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotacion);
        Bitmap rotado = Bitmap.createBitmap(recortado, 0, 0, recortado.getWidth(), recortado.getHeight(), matrix, true);
        if (rotado != recortado) {
            recortado.recycle();
        }
        return rotado;
    }

    /** El buffer YUV crudo puede ser más grande que el campo de visión que realmente comparten
     * Preview/ImageAnalysis bajo el {@code ViewPort} común (p. ej. el sensor entrega 4:3 pero el
     * ViewPort, calculado a partir del aspect ratio de la vista, pide un recorte 16:9 central).
     * {@code getCropRect()} es la región real que corresponde a lo mostrado; recortar aquí ANTES
     * de rotar es obligatorio para que las coordenadas de la detección coincidan con
     * {@code PreviewView} — recortar en el orden equivocado (o no recortar) es exactamente lo que
     * causaba cajas gigantes/desalineadas. */
    private static Bitmap recortarACropRect(Bitmap completo, Rect cropRect) {
        if (cropRect.left == 0 && cropRect.top == 0
                && cropRect.width() == completo.getWidth() && cropRect.height() == completo.getHeight()) {
            return completo; // el cropRect ya es el buffer entero, nada que recortar
        }
        Bitmap recortado = Bitmap.createBitmap(completo, cropRect.left, cropRect.top,
                cropRect.width(), cropRect.height());
        completo.recycle();
        return recortado;
    }

    /** YUV_420_888 (3 planos, con rowStride/pixelStride reales) -> NV21 -> JPEG -> Bitmap RGB.
     * Respeta rowStride/pixelStride de cada plano: una copia ingenua de los ByteBuffer sin
     * tenerlos en cuenta produce colores incorrectos en muchos dispositivos reales (planos
     * U/V semi-planares con pixelStride=2 son el caso mas comun). */
    private static Bitmap yuv420888ToBitmap(ImageProxy imageProxy) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        int pos = 0;

        ImageProxy.PlaneProxy planoY = imageProxy.getPlanes()[0];
        ByteBuffer bufferY = planoY.getBuffer();
        int rowStrideY = planoY.getRowStride();
        for (int fila = 0; fila < height; fila++) {
            bufferY.position(fila * rowStrideY);
            bufferY.get(nv21, pos, width);
            pos += width;
        }

        ImageProxy.PlaneProxy planoU = imageProxy.getPlanes()[1];
        ImageProxy.PlaneProxy planoV = imageProxy.getPlanes()[2];
        ByteBuffer bufferU = planoU.getBuffer();
        ByteBuffer bufferV = planoV.getBuffer();
        int rowStrideU = planoU.getRowStride();
        int pixelStrideU = planoU.getPixelStride();
        int rowStrideV = planoV.getRowStride();
        int pixelStrideV = planoV.getPixelStride();

        int chromaAlto = height / 2;
        int chromaAncho = width / 2;
        for (int fila = 0; fila < chromaAlto; fila++) {
            for (int col = 0; col < chromaAncho; col++) {
                int indiceV = fila * rowStrideV + col * pixelStrideV;
                int indiceU = fila * rowStrideU + col * pixelStrideU;
                nv21[pos++] = bufferV.get(indiceV); // NV21 = Y... V U V U ...
                nv21[pos++] = bufferU.get(indiceU);
            }
        }

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, salida);
        byte[] jpegBytes = salida.toByteArray();
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }
}
