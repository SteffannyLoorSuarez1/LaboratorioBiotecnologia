package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.BuildConfig;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Consulta directamente Responses API por HTTPS, tanto para chat escrito como por voz. */
public class ChatRepository {
    public interface Callback {
        void onExito(ChatResponse respuesta);
        void onError(String mensaje);
    }
    private final Context context;
    public ChatRepository(Context context) { this.context = context.getApplicationContext(); }

    public void enviarPregunta(ChatRequest pregunta, Callback callback) {
        final String apiKey = new SecureConfigManager(context).getEffectiveApiKey();
        String store = BioManuales.STORES.get(pregunta.getClaseDetector());
        if (store == null) {
            callback.onExito(BioResponses.sinInformacion());
            return;
        }
        final JSONObject cuerpo;
        try {
            cuerpo = BioResponses.crearPeticion(pregunta, store);
        } catch (JSONException e) {
            callback.onError(context.getString(R.string.chat_error_generico));
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST,
                ApiClient.BASE_URL + "/responses", cuerpo,
                response -> {
                    try {
                        callback.onExito(BioResponses.parsear(response));
                    } catch (JSONException e) {
                        callback.onError(context.getString(R.string.chat_error_generico));
                    }
                }, error -> callback.onError(interpretarError(error))) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + apiKey);
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(90000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        ApiClient.getInstancia(context).agregarPeticion(request);
    }

    // No registra cabeceras ni cuerpos de error del proveedor, que pueden contener credenciales.
    private String interpretarError(VolleyError error) {
        int mensaje = R.string.chat_error_generico;
        if (error instanceof TimeoutError) mensaje = R.string.chat_error_timeout;
        else if (error.networkResponse == null) mensaje = R.string.chat_error_red;
        else {
            int status = error.networkResponse.statusCode;
            if (status == 401 || status == 403) mensaje = R.string.chat_error_autenticacion;
            else if (status == 429) mensaje = R.string.chat_error_cuota;
            else if (status >= 500) mensaje = R.string.chat_error_servidor;
        }
        return context.getString(mensaje);
    }
}
