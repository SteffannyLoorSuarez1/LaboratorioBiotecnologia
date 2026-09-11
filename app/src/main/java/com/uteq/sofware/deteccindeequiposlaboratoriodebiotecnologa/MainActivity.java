package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.camera.DeteccionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat.ChatActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat.VozAsistenteActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion.ConfiguracionActivity;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.NavegacionUtil;

/**
 * Pantalla de inicio: presenta el laboratorio y el acceso principal al asistente inteligente. La
 * tarjeta/sección "Áreas del laboratorio" (antes abría {@code AreasActivity}) se quitó del menú a
 * pedido del usuario. El acceso directo "Detectar equipos" (cámara en vivo sin seleccionar
 * equipo) también se había quitado del menú antes; {@link DeteccionActivity} se sigue usando
 * igual, pero solo en modo selección, desde "Asistente inteligente" (ver
 * {@link #lanzadorSeleccionEquipoParaVoz}) y desde el chat
 * ({@code ChatActivity.abrirCamaraParaSeleccion()}).
 * <p>
 * "Asistente inteligente" ya NO abre el chat escrito directamente: primero abre
 * {@link DeteccionActivity} en modo selección (misma pantalla/mecanismo que ya usaba
 * {@code ChatActivity} para "Cambiar equipo") para que el usuario apunte la cámara y elija el
 * equipo del que quiere hablar; con ese equipo confirmado se abre
 * {@link VozAsistenteActivity} (conversación por voz), no el chat. El chat escrito sigue
 * intacto y accesible desde dentro de esa pantalla de voz ("Usar chat escrito") y desde
 * {@code EquipoDetalleActivity} ("Preguntar al asistente"), ninguno de los cuales cambió.
 */
public class MainActivity extends AppCompatActivity {

    /** Recibe el resultado de {@link DeteccionActivity} en modo selección lanzada desde la
     * tarjeta "Asistente inteligente" (ver {@link #onCreate}). Si el usuario cancela (botón
     * atrás sin tocar ninguna detección), no pasa nada y el usuario se queda en Inicio. */
    private final ActivityResultLauncher<Intent> lanzadorSeleccionEquipoParaVoz =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), resultado -> {
                if (resultado.getResultCode() != Activity.RESULT_OK || resultado.getData() == null) {
                    return;
                }
                Intent data = resultado.getData();
                Intent intent = new Intent(MainActivity.this, VozAsistenteActivity.class);
                intent.putExtra(ChatActivity.EXTRA_EQUIPO_NOMBRE,
                        data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_EQUIPO_NOMBRE));
                intent.putExtra(ChatActivity.EXTRA_AREA_NOMBRE,
                        data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_EQUIPO_AREA));
                intent.putExtra(ChatActivity.EXTRA_CLASE_DETECTOR,
                        data.getStringExtra(DeteccionActivity.EXTRA_RESULTADO_CLASE_DETECTOR));
                startActivity(intent);
            });

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

        findViewById(R.id.cardChat).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                Intent intent = new Intent(MainActivity.this, DeteccionActivity.class);
                intent.putExtra(DeteccionActivity.EXTRA_MODO_SELECCION, true);
                lanzadorSeleccionEquipoParaVoz.launch(intent);
            }
        });

        findViewById(R.id.botonConfiguracion).setOnClickListener(v -> {
            if (NavegacionUtil.puedeNavegar()) {
                startActivity(new Intent(MainActivity.this, ConfiguracionActivity.class));
            }
        });
    }
}
