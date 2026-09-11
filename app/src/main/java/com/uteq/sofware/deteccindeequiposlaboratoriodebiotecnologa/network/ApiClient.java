package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

/** Cliente REST de Bio. Conexión directa HTTPS a OpenAI. */
public final class ApiClient {
    public static final String BASE_URL = "https://api.openai.com/v1";

    private static ApiClient instancia;

    private final RequestQueue requestQueue;

    private ApiClient(Context context) {
        requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    public static synchronized ApiClient getInstancia(Context context) {
        if (instancia == null) {
            instancia = new ApiClient(context);
        }
        return instancia;
    }

    public <T> void agregarPeticion(Request<T> request) {
        requestQueue.add(request);
    }
}
