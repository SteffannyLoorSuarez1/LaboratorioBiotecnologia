package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Manual PDF ORIGINAL de cada equipo, empaquetado localmente en {@code assets/manuales/} (ver
 * {@code app/src/main/assets/manuales/README.md} si existe, o directamente la carpeta). No
 * depende de Internet, del backend FastAPI, ni de la OpenAI Files API.
 * <p>
 * Se abandonó deliberadamente la descarga vía {@code GET /v1/files/{id}/content}: OpenAI
 * responde {@code 400 "Not allowed to download files of purpose: assistants"} para CUALQUIER
 * archivo que la app haya subido (se probó también re-subiéndolo con {@code purpose=user_data},
 * mismo resultado) — es una restricción de la plataforma, no un bug de esta app, así que no hay
 * forma de "arreglarlo" quedándose en esa API. El RAG (file_search sobre los Vector Stores de
 * {@code BioManuales.STORES}) no se toca: sigue usando OpenAI normalmente para responder
 * preguntas, esta clase solo resuelve el botón "Ver PDF del equipo".
 * <p>
 * Cada clase con manual disponible tiene exactamente un archivo en
 * {@code assets/manuales/<claseDetector>.pdf} (mismo nombre que {@code labels.txt}). Las clases
 * sin manual todavía disponible simplemente no tienen ese archivo — ver {@link #tieneManual}.
 */
public final class ManualRepository {

    private static final String CARPETA_ASSETS = "manuales";
    private static final String CARPETA_CACHE = "manuales";

    private final Context context;

    public ManualRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** {@code true} si hay un manual PDF local empaquetado para esta clase de detector. */
    public boolean tieneManual(String claseDetector) {
        if (claseDetector == null || claseDetector.isEmpty()) {
            return false;
        }
        try (InputStream in = context.getAssets().open(rutaAsset(claseDetector))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copia (si hace falta) el manual desde {@code assets/} a {@code cacheDir} y devuelve el
     * {@link File} local listo para {@link android.graphics.pdf.PdfRenderer}, que necesita un
     * descriptor de un archivo real y no puede abrir un asset directamente. Ejecutar fuera del
     * hilo de interfaz.
     */
    public File obtenerManualLocal(String claseDetector) throws IOException {
        String rutaAsset = rutaAsset(claseDetector);
        File carpeta = new File(context.getCacheDir(), CARPETA_CACHE);
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de caché de manuales");
        }
        File destino = new File(carpeta, claseDetector + ".pdf");
        if (destino.isFile() && destino.length() > 5) {
            return destino;
        }
        File temporal = File.createTempFile("manual-", ".part", carpeta);
        try (InputStream in = context.getAssets().open(rutaAsset);
             OutputStream out = new FileOutputStream(temporal)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
        if (!temporal.renameTo(destino)) {
            throw new IOException("No se pudo preparar el manual local");
        }
        return destino;
    }

    private static String rutaAsset(String claseDetector) {
        return CARPETA_ASSETS + "/" + claseDetector + ".pdf";
    }
}
