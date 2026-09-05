package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.configuracion;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util.InsetsUtil;

/**
 * Permite al usuario introducir su propia OpenAI API Key para el asistente inteligente.
 * <p>
 * La clave se guarda cifrada en el dispositivo mediante {@link SecureConfigManager} y nunca
 * se muestra completa, se registra en Logcat, ni se envía a ningún destino salvo el backend
 * propio de la aplicación (en el encabezado {@code X-OpenAI-API-Key} de cada consulta del
 * chat). Android nunca llama directamente a la API de OpenAI.
 */
public class ConfiguracionActivity extends AppCompatActivity {

    private SecureConfigManager secureConfigManager;

    private TextInputEditText editTextApiKey;
    private TextView textEstadoApiKey;
    private ImageView imageEstadoApiKey;
    private MaterialButton botonEliminarClave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configuracion);

        secureConfigManager = new SecureConfigManager(this);

        InsetsUtil.aplicarInsetsBarraSistema(findViewById(R.id.root), false, true, false, true);

        MaterialToolbar toolbar = findViewById(R.id.toolbarConfiguracion);
        toolbar.setNavigationOnClickListener(v -> finish());

        editTextApiKey = findViewById(R.id.editTextApiKey);
        textEstadoApiKey = findViewById(R.id.textEstadoApiKey);
        imageEstadoApiKey = findViewById(R.id.imageEstadoApiKey);
        botonEliminarClave = findViewById(R.id.botonEliminarClave);

        MaterialButton botonGuardar = findViewById(R.id.botonGuardarConfiguracion);
        botonGuardar.setOnClickListener(v -> guardarApiKey());
        botonEliminarClave.setOnClickListener(v -> eliminarApiKey());

        actualizarEstadoUI();
    }

    private void guardarApiKey() {
        CharSequence texto = editTextApiKey.getText();
        String apiKey = texto == null ? "" : texto.toString().trim();

        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, R.string.config_api_key_vacia, Toast.LENGTH_SHORT).show();
            return;
        }

        secureConfigManager.saveApiKey(apiKey);
        editTextApiKey.setText("");
        Toast.makeText(this, R.string.config_guardado_exitoso, Toast.LENGTH_SHORT).show();
        actualizarEstadoUI();
    }

    private void eliminarApiKey() {
        secureConfigManager.deleteApiKey();
        editTextApiKey.setText("");
        Toast.makeText(this, R.string.config_eliminado_exitoso, Toast.LENGTH_SHORT).show();
        actualizarEstadoUI();
    }

    private void actualizarEstadoUI() {
        boolean configurada = secureConfigManager.hasApiKey();

        if (configurada) {
            String enmascarada = enmascarar(secureConfigManager.getApiKey());
            textEstadoApiKey.setText(getString(R.string.config_estado_configurada) + " (" + enmascarada + ")");
            imageEstadoApiKey.setImageResource(R.drawable.ic_check_circle);
            imageEstadoApiKey.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.lab_primary, getTheme())));
            botonEliminarClave.setVisibility(View.VISIBLE);
        } else {
            textEstadoApiKey.setText(R.string.config_estado_no_configurada);
            imageEstadoApiKey.setImageResource(R.drawable.ic_warning);
            imageEstadoApiKey.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.lab_warning, getTheme())));
            botonEliminarClave.setVisibility(View.GONE);
        }
    }

    /**
     * Nunca se debe mostrar (ni registrar) la clave completa. Como máximo se muestra el
     * prefijo y los últimos 4 caracteres, por ejemplo: {@code sk-••••••••••••••••AB12}.
     */
    private String enmascarar(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "••••••••";
        }
        String inicio = apiKey.substring(0, Math.min(3, apiKey.length()));
        String fin = apiKey.substring(apiKey.length() - 4);
        return inicio + "••••••••••••••••" + fin;
    }
}
