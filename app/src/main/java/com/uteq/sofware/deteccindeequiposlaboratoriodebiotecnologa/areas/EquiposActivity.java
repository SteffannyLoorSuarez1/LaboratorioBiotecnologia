package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.AreaLaboratorio;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.Equipo;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Lista los equipos asociados a un área. La lista es dinámica: si el área todavía no tiene
 * equipos registrados (o son solo datos de demostración) se refleja tal cual, sin necesidad
 * de cambios en esta pantalla cuando se agreguen las clases reales del modelo.
 */
public class EquiposActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equipos);

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        String areaId = getIntent().getStringExtra(AreasActivity.EXTRA_AREA_ID);
        AreaLaboratorio area = LabRepository.getInstancia().obtenerAreaPorId(areaId);

        MaterialToolbar toolbar = findViewById(R.id.toolbarEquipos);
        toolbar.setTitle(area != null ? area.getNombre() : getString(R.string.equipos_title));
        toolbar.setNavigationOnClickListener(v -> finish());

        List<Equipo> equipos = LabRepository.getInstancia().obtenerEquiposPorArea(areaId);

        RecyclerView recyclerView = findViewById(R.id.recyclerEquipos);
        TextView textVacio = findViewById(R.id.textEquiposVacio);

        if (equipos.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            textVacio.setVisibility(View.VISIBLE);
            return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new EquipoAdapter(equipos, equipo -> {
            if (!NavegacionUtil.puedeNavegar()) {
                return;
            }
            Intent intent = new Intent(EquiposActivity.this, EquipoDetalleActivity.class);
            intent.putExtra(EquipoDetalleActivity.EXTRA_EQUIPO_ID, equipo.getId());
            startActivity(intent);
        }));
    }
}
