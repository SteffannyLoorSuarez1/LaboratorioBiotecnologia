package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera.OverlayView;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ManualRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BioFeaturesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void claveOpcionalYRestauracionSinCambiarLaOriginal() {
        SecureConfigManager claves = new SecureConfigManager(context());
        String anterior = claves.getApiKey();
        try {
            claves.saveApiKey("sk-prueba-sin-red");
            assertTrue("Debe usar la clave personal", "sk-prueba-sin-red".equals(claves.getEffectiveApiKey()));
            claves.deleteApiKey();
            assertTrue("Debe restaurar la clave incluida", BuildConfig.OPENAI_API_KEY.equals(claves.getEffectiveApiKey()));
        } finally {
            if (anterior == null) claves.deleteApiKey(); else claves.saveApiKey(anterior);
        }
    }

    @Test public void overlayAjustaFotogramaCompletoYEtiquetaDentroDePantalla() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                OverlayView overlay = new OverlayView(context());
                overlay.layout(0, 0, 360, 640);
                overlay.setResultados(Collections.singletonList(new DetectionResult(0,
                        "Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP", .93f,
                        0, 0, 640, 480)), 640, 480);
                Bitmap bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888);
                overlay.draw(new Canvas(bitmap));
                Field cajas = OverlayView.class.getDeclaredField("cajasEnPantalla");
                cajas.setAccessible(true);
                Object primera = ((List<?>) cajas.get(overlay)).get(0);
                Field caja = primera.getClass().getDeclaredField("caja");
                caja.setAccessible(true);
                RectF rect = (RectF) caja.get(primera);
                assertEquals(360f, rect.width(), .1f);
                assertEquals(270f, rect.height(), .1f);
                assertEquals(185f, rect.top, .1f);
                Field etiquetas = OverlayView.class.getDeclaredField("etiquetasColocadas");
                etiquetas.setAccessible(true);
                for (Object item : (List<?>) etiquetas.get(overlay)) {
                    RectF label = (RectF) item;
                    assertTrue(label.left >= 0 && label.right <= 360);
                    assertTrue(label.top >= 0 && label.bottom <= 640);
                }
                try (FileOutputStream out = context().openFileOutput("overlay-verificado.png", Context.MODE_PRIVATE)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bitmap.recycle();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void abreManualLocalEmpaquetadoEnAssetsSinRed() throws Exception {
        // El manual viaja en assets/manuales/, sin depender de Internet/OpenAI/backend (ver
        // ManualRepository) — a diferencia de la versión anterior de esta prueba, no necesita
        // "manual_real" ni ninguna condición de red.
        ManualRepository repo = new ManualRepository(context());
        assertTrue("Debe existir un manual local empaquetado para este equipo",
                repo.tieneManual("horno_secado_biobase"));
        File pdf = repo.obtenerManualLocal("horno_secado_biobase");
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            assertTrue(renderer.getPageCount() > 0);
        }
    }

    @Test public void claseSinManualEmpaquetadoNoTieneManual() {
        // cabina_flujo_laminar_mini_c4 es una de las clases sin PDF todavía disponible: debe
        // reportarse como tal (y ManualPdfActivity mostrar bio_pdf_sin_manual) en vez de intentar
        // ninguna descarga.
        ManualRepository repo = new ManualRepository(context());
        assertFalse(repo.tieneManual("cabina_flujo_laminar_mini_c4"));
    }
}
