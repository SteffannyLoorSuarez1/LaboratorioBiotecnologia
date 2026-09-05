package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat.ChatActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.AreaLaboratorio;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.Equipo;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Ficha técnica de un equipo. Los campos documentales (función, componentes, procedimiento,
 * EPP, riesgos, prácticas) provienen únicamente de {@link LabRepository}; cuando un equipo
 * no tiene documentación cargada se muestra el aviso estándar en lugar de inventar contenido.
 */
public class EquipoDetalleActivity extends AppCompatActivity {

    public static final String EXTRA_EQUIPO_ID = "extra_equipo_id";

    private Equipo equipo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equipo_detalle);

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        String equipoId = getIntent().getStringExtra(EXTRA_EQUIPO_ID);
        equipo = LabRepository.getInstancia().obtenerEquipoPorId(equipoId);

        MaterialToolbar toolbar = findViewById(R.id.toolbarEquipoDetalle);
        toolbar.setNavigationOnClickListener(v -> finish());

        if (equipo == null) {
            toolbar.setTitle(R.string.equipos_title);
            return;
        }

        AreaLaboratorio area = LabRepository.getInstancia().obtenerAreaPorId(equipo.getAreaId());

        toolbar.setTitle(equipo.getNombre());
        ((TextView) findViewById(R.id.textNombreEquipoDetalle)).setText(equipo.getNombre());
        ((TextView) findViewById(R.id.textAreaEquipoDetalle)).setText(area != null ? area.getNombre() : "");
        ((TextView) findViewById(R.id.textClaseDetector))
                .setText(getString(R.string.equipo_clase_detector) + ": " + equipo.getClaseDetector());

        findViewById(R.id.chipDatoDemoDetalle).setVisibility(equipo.esDatoDemo() ? View.VISIBLE : View.GONE);

        String pendiente = getString(R.string.equipo_info_pendiente);
        establecerTexto(R.id.textFuncion, equipo.getFuncion(), pendiente);
        establecerTexto(R.id.textComponentes, unir(equipo.getComponentesPrincipales()), pendiente);
        establecerTexto(R.id.textProcedimiento, equipo.getProcedimientoBasico(), pendiente);
        establecerTexto(R.id.textEpp, unir(equipo.getEpp()), pendiente);
        establecerTexto(R.id.textRiesgos, unir(equipo.getRiesgos()), pendiente);
        establecerTexto(R.id.textPracticas, unir(equipo.getPracticasAcademicas()), pendiente);

        MaterialButton botonPreguntar = findViewById(R.id.botonPreguntarAsistente);
        botonPreguntar.setOnClickListener(v -> {
            if (!NavegacionUtil.puedeNavegar()) {
                return;
            }
            Intent intent = new Intent(EquipoDetalleActivity.this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_EQUIPO_NOMBRE, equipo.getNombre());
            intent.putExtra(ChatActivity.EXTRA_AREA_NOMBRE, area != null ? area.getNombre() : "");
            intent.putExtra(ChatActivity.EXTRA_CLASE_DETECTOR, equipo.getClaseDetector());
            startActivity(intent);
        });
    }

    private void establecerTexto(int viewId, String valor, String textoPendiente) {
        TextView textView = findViewById(viewId);
        textView.setText((valor == null || valor.trim().isEmpty()) ? textoPendiente : valor);
    }

    private String unir(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return "• " + String.join("\n• ", items);
    }
}
