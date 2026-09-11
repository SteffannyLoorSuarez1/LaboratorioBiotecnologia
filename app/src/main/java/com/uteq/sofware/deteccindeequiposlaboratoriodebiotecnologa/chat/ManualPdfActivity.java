package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.ManualRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visor local del PDF original del equipo (ver {@link ManualRepository}): el manual viaja
 * empaquetado en {@code assets/manuales/}, así que esta pantalla nunca usa Internet, el backend
 * FastAPI ni la clave de OpenAI. Si la clase detectada no tiene un manual empaquetado todavía,
 * se muestra un aviso claro ({@code R.string.bio_pdf_sin_manual}) en vez de intentar descargar
 * nada.
 */
public class ManualPdfActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView estado;
    private ImageView pagina;
    private MaterialButton anterior, siguiente, reintentar;
    private MaterialToolbar toolbar;
    private File archivo;
    private int indice = 0, total = 0;
    private Bitmap bitmap;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) indice = saved.getInt("pagina", 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        InsetsUtil.aplicarInsetsBarraSistema(root, false, true, false, true);
        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.bio_ver_pdf);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        estado = new TextView(this);
        estado.setPadding(16, 12, 16, 12);
        root.addView(estado);
        reintentar = new MaterialButton(this);
        reintentar.setText(R.string.voz_reintentar);
        reintentar.setVisibility(View.GONE);
        reintentar.setOnClickListener(v -> cargar());
        root.addView(reintentar);
        ScrollView scroll = new ScrollView(this);
        pagina = new ImageView(this);
        pagina.setAdjustViewBounds(true);
        pagina.setContentDescription(getString(R.string.bio_ver_pdf));
        scroll.addView(pagina, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controles = new LinearLayout(this);
        anterior = new MaterialButton(this);
        anterior.setText(R.string.bio_pdf_anterior);
        siguiente = new MaterialButton(this);
        siguiente.setText(R.string.bio_pdf_siguiente);
        controles.addView(anterior, new LinearLayout.LayoutParams(0, -2, 1));
        controles.addView(siguiente, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controles);
        anterior.setOnClickListener(v -> { indice--; renderizar(); });
        siguiente.setOnClickListener(v -> { indice++; renderizar(); });
        cargar();
    }
    private void ocupado() {
        anterior.setEnabled(false); siguiente.setEnabled(false);
        reintentar.setVisibility(View.GONE);
        estado.setText(R.string.bio_pdf_cargando);
    }
    private void cargar() {
        ocupado();
        String claseDetector = getIntent().getStringExtra(ChatActivity.EXTRA_CLASE_DETECTOR);
        worker.execute(() -> {
            ManualRepository repo = new ManualRepository(this);
            if (!repo.tieneManual(claseDetector)) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    anterior.setEnabled(false);
                    siguiente.setEnabled(false);
                    estado.setText(R.string.bio_pdf_sin_manual);
                });
                return;
            }
            try {
                File resultado = repo.obtenerManualLocal(claseDetector);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    archivo = resultado;
                    renderizar();
                });
            } catch (Exception e) {
                error();
            }
        });
    }
    private void renderizar() {
        ocupado();
        worker.execute(() -> {
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                int count = renderer.getPageCount();
                int actual = Math.max(0, Math.min(indice, count - 1));
                Bitmap rendered;
                try (PdfRenderer.Page page = renderer.openPage(actual)) {
                    // Resolución suficiente para lectura sin reservar un bitmap del documento entero.
                    float factor = Math.min(1600f / page.getWidth(), 2400f / page.getHeight());
                    rendered = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * factor)),
                            Math.max(1, Math.round(page.getHeight() * factor)), Bitmap.Config.ARGB_8888);
                    rendered.eraseColor(Color.WHITE);
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { rendered.recycle(); return; }
                    Bitmap viejo = bitmap;
                    bitmap = rendered;
                    pagina.setImageBitmap(bitmap);
                    if (viejo != null) viejo.recycle();
                    indice = actual; total = count;
                    estado.setText(getString(R.string.bio_pdf_pagina, indice + 1, total));
                    anterior.setEnabled(indice > 0);
                    siguiente.setEnabled(indice + 1 < total);
                });
            } catch (Exception e) { error(); }
        });
    }
    private void error() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            estado.setText(R.string.bio_pdf_error);
            reintentar.setVisibility(View.VISIBLE);
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("pagina", indice);
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        pagina.setImageDrawable(null);
        if (bitmap != null) bitmap.recycle();
        super.onDestroy();
    }
}
