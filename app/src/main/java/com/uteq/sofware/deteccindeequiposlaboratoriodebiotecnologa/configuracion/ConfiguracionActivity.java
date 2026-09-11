package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import com.google.android.material.textfield.TextInputEditText;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.HealthRepository;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;

/** Estado de la conexión de Bio; utiliza la credencial incluida en la compilación. */
public class ConfiguracionActivity extends AppCompatActivity {
    private SecureConfigManager claves;
    private TextInputEditText entradaClave;
    private TextView origenClave;
    private View restaurar;
    private TextView estado;
    private MaterialButton comprobar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configuracion);
        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);
        ((MaterialToolbar) findViewById(R.id.toolbarConfiguracion)).setNavigationOnClickListener(v -> finish());
        estado = findViewById(R.id.textEstadoApiKey);
        comprobar = findViewById(R.id.botonGuardarConfiguracion);
        comprobar.setOnClickListener(v -> comprobarServicio());
        claves = new SecureConfigManager(this);
        entradaClave = findViewById(R.id.editTextApiKey);
        origenClave = findViewById(R.id.textOrigenClave);
        restaurar = findViewById(R.id.botonRestaurarClave);
        findViewById(R.id.botonGuardarClave).setOnClickListener(v -> {
            String clave = entradaClave.getText() == null ? "" : entradaClave.getText().toString().trim();
            if (clave.isEmpty() || clave.chars().anyMatch(Character::isWhitespace)) {
                entradaClave.setError(getString(R.string.config_api_key_vacia));
                return;
            }
            claves.saveApiKey(clave);
            entradaClave.setText("");
            actualizarOrigen();
            Toast.makeText(this, R.string.config_guardado_exitoso, Toast.LENGTH_SHORT).show();
            comprobarServicio();
        });
        restaurar.setOnClickListener(v -> {
            claves.deleteApiKey();
            entradaClave.setText("");
            actualizarOrigen();
            comprobarServicio();
        });
        actualizarOrigen();
        comprobarServicio();
    }

    private void actualizarOrigen() {
        origenClave.setText(claves.hasApiKey() ? R.string.bio_clave_personal : R.string.bio_clave_original);
        restaurar.setVisibility(claves.hasApiKey() ? View.VISIBLE : View.GONE);
    }

    private void comprobarServicio() {
        comprobar.setEnabled(false);
        estado.setText(R.string.bio_comprobando);
        new HealthRepository(this).consultarEstado(new HealthRepository.Callback() {
            @Override public void onResultado(boolean disponible) {
                mostrarEstado(disponible ? R.string.bio_disponible : R.string.bio_no_configurado);
            }
            @Override public void onError() {
                mostrarEstado(R.string.chat_error_red);
            }
        });
    }

    private void mostrarEstado(int mensaje) {
        if (isFinishing() || isDestroyed()) return;
        estado.setText(mensaje);
        comprobar.setEnabled(true);
    }
}
