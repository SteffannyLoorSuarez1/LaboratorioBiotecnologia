package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;

/**
 * Consulta GET /health del backend, usada para saber (antes de habilitar el chat) si el
 * servicio de asistente inteligente ya tiene configurado el RAG (OPENAI_API_KEY +
 * OPENAI_VECTOR_STORE_ID) del lado del servidor.
 */
public class HealthRepository {

    public interface Callback {
        void onResultado(boolean ragConfigurado);
        void onError();
    }

    private static final String ENDPOINT_HEALTH = ApiClient.BASE_URL + "/health";
    private static final int TIMEOUT_MS = 8000;

    private final Context context;

    public HealthRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public void consultarEstado(Callback callback) {
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                ENDPOINT_HEALTH,
                null,
                response -> callback.onResultado(response.optBoolean("rag_configurado", false)),
                error -> callback.onError());

        request.setRetryPolicy(new DefaultRetryPolicy(
                TIMEOUT_MS,
                0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        ApiClient.getInstancia(context).agregarPeticion(request);
    }
}
