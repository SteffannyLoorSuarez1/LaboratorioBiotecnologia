package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.BuildConfig;
import java.util.Collections;
import java.util.Map;

/** Comprueba acceso a OpenAI sin generar respuestas ni depender del antiguo /health. */
public class HealthRepository {
    public interface Callback {
        void onResultado(boolean configurado);
        void onError();
    }
    private final Context context;
    public HealthRepository(Context context) { this.context = context.getApplicationContext(); }
    public void consultarEstado(Callback callback) {
        final String apiKey = new SecureConfigManager(context).getEffectiveApiKey();
        if (apiKey.isEmpty()) {
            callback.onResultado(false);
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET,
                ApiClient.BASE_URL + "/models/" + BuildConfig.OPENAI_MODEL, null,
                response -> callback.onResultado(true), error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 401)
                        callback.onResultado(false);
                    else callback.onError();
                }) {
            @Override public Map<String, String> getHeaders() {
                return Collections.singletonMap("Authorization", "Bearer " + apiKey);
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(15000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        ApiClient.getInstancia(context).agregarPeticion(request);
    }
}
