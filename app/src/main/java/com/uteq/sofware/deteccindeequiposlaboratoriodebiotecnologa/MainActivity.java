package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas.AreaAdapter;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas.AreasActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas.EquiposActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera.DeteccionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat.ChatActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion.ConfiguracionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data.LabRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Pantalla de inicio: presenta el laboratorio y los tres accesos principales
 * (detección, áreas y asistente inteligente), además de un resumen de las 3 áreas fijas.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        findViewById(R.id.cardDetectar).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(MainActivity.this, DeteccionActivity.class));
            }
        });

        findViewById(R.id.cardAreas).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(MainActivity.this, AreasActivity.class));
            }
        });

        findViewById(R.id.cardChat).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            }
        });

        findViewById(R.id.botonConfiguracion).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(MainActivity.this, ConfiguracionActivity.class));
            }
        });

        RecyclerView recyclerAreasHome = findViewById(R.id.recyclerAreasHome);
        recyclerAreasHome.setLayoutManager(new LinearLayoutManager(this));
        recyclerAreasHome.setAdapter(new AreaAdapter(LabRepository.getInstancia().obtenerAreas(), area -> {
            if (!NavegacionUtil.puedeNavegar()) {
                return;
            }
            Intent intent = new Intent(MainActivity.this, EquiposActivity.class);
            intent.putExtra(AreasActivity.EXTRA_AREA_ID, area.getId());
            startActivity(intent);
        }));
    }
}
