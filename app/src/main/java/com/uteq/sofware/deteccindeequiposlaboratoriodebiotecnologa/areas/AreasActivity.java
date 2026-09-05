package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Muestra las 3 áreas fijas del laboratorio. Al seleccionar una, navega a
 * {@link EquiposActivity} con los equipos asociados (si existen).
 */
public class AreasActivity extends AppCompatActivity {

    public static final String EXTRA_AREA_ID = "extra_area_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_areas);

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        MaterialToolbar toolbar = findViewById(R.id.toolbarAreas);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerAreas);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new AreaAdapter(LabRepository.getInstancia().obtenerAreas(), area -> {
            if (!NavegacionUtil.puedeNavegar()) {
                return;
            }
            Intent intent = new Intent(AreasActivity.this, EquiposActivity.class);
            intent.putExtra(EXTRA_AREA_ID, area.getId());
            startActivity(intent);
        }));
    }
}
