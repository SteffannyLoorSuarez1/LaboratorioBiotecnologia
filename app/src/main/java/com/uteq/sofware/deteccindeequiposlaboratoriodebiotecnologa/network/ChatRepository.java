package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.BuildConfig;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;

/**
 * Capa de acceso a la API de chat del backend (POST /api/chat). Las Activities no deben
 * construir peticiones de red directamente; deben usar esta clase.
 * <p>
 * Si el usuario configuró su propia OpenAI API Key en {@link SecureConfigManager}, se envía
 * al backend en el encabezado {@code X-OpenAI-API-Key} (nunca dentro del cuerpo JSON, para no
 * mezclar la credencial con el modelo de {@link ChatRequest}). Si no hay clave local, el
 * backend usará la suya propia (variable de entorno {@code OPENAI_API_KEY}) si existe.
 */
public class ChatRepository {

    public interface Callback {
        void onExito(ChatResponse respuesta);
        void onError(String mensaje);
    }

    private static final String TAG = "ChatRepository";
    private static final String ENDPOINT_CHAT = ApiClient.BASE_URL + "/api/chat";
    private static final String HEADER_API_KEY = "X-OpenAI-API-Key";

    /**
     * Las respuestas del LLM/RAG (backend → OpenAI → file_search → Vector Store → respuesta)
     * pueden tardar bastante más que una petición HTTP típica; 30s resultaba insuficiente en
     * pruebas reales, por lo que se amplió a 60s.
     */
    private static final int TIMEOUT_MS = 60000;

    private final Context context;
    private final SecureConfigManager secureConfigManager;

    public ChatRepository(Context context) {
        this.context = context.getApplicationContext();
        this.secureConfigManager = new SecureConfigManager(this.context);
    }

    public void enviarPregunta(ChatRequest chatRequest, Callback callback) {
        JSONObject cuerpo;
        try {
            cuerpo = new JSONObject();
            cuerpo.put("equipo", chatRequest.getEquipo());
            cuerpo.put("area", chatRequest.getArea());
            cuerpo.put("clase_detector", chatRequest.getClaseDetector());
            cuerpo.put("pregunta", chatRequest.getPregunta());
        } catch (JSONException e) {
            callback.onError(context.getString(R.string.chat_error_generico));
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                ENDPOINT_CHAT,
                cuerpo,
                response -> callback.onExito(parsearRespuesta(response)),
                (VolleyError error) -> {
                    registrarErrorEnDebug(error);
                    callback.onError(interpretarError(error));
                }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                if (secureConfigManager.hasApiKey()) {
                    headers.put(HEADER_API_KEY, secureConfigManager.getApiKey());
                }
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(
                TIMEOUT_MS,
                0, // sin reintentos automáticos: evita duplicar consultas costosas al LLM
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        ApiClient.getInstancia(context).agregarPeticion(request);
    }

    private ChatResponse parsearRespuesta(JSONObject json) {
        String respuesta = json.optString("respuesta", "");
        boolean encontrado = json.optBoolean("encontrado", false);
        List<Fuente> fuentes = new ArrayList<>();

        JSONArray arregloFuentes = json.optJSONArray("fuentes");
        if (arregloFuentes != null) {
            for (int i = 0; i < arregloFuentes.length(); i++) {
                JSONObject fuenteJson = arregloFuentes.optJSONObject(i);
                if (fuenteJson != null) {
                    fuentes.add(new Fuente(
                            fuenteJson.optString("archivo", ""),
                            fuenteJson.optString("referencia", "")));
                }
            }
        }

        return new ChatResponse(respuesta, encontrado, fuentes);
    }

    /**
     * Traduce el error de Volley a un mensaje comprensible para el usuario. Nunca se muestra
     * el cuerpo HTTP crudo, un stack trace, JSON técnico ni la API Key.
     */
    private String interpretarError(VolleyError error) {
        if (error instanceof TimeoutError) {
            return context.getString(R.string.chat_error_timeout);
        }
        if (error.networkResponse == null) {
            return context.getString(R.string.chat_error_red);
        }

        int statusCode = error.networkResponse.statusCode;
        if (statusCode == 401 || statusCode == 403) {
            return context.getString(R.string.chat_error_autenticacion);
        }
        if (statusCode == 429) {
            return context.getString(R.string.chat_error_cuota);
        }
        if (statusCode >= 500) {
            return context.getString(R.string.chat_error_servidor);
        }
        return context.getString(R.string.chat_error_generico);
    }

    /**
     * Registra detalles del error SOLO en builds de depuración, para facilitar el diagnóstico.
     * Nunca registra la API Key ni el encabezado {@code X-OpenAI-API-Key}; el cuerpo de error
     * mostrado proviene únicamente de nuestro propio backend (nunca incluye la clave).
     */
    private void registrarErrorEnDebug(VolleyError error) {
        if (!BuildConfig.DEBUG) {
            return;
        }

        NetworkResponse networkResponse = error.networkResponse;
        int statusCode = networkResponse != null ? networkResponse.statusCode : -1;
        String cuerpo = "";
        if (networkResponse != null && networkResponse.data != null) {
            cuerpo = new String(networkResponse.data, StandardCharsets.UTF_8);
            if (cuerpo.length() > 300) {
                cuerpo = cuerpo.substring(0, 300) + "…";
            }
        }

        Log.d(TAG, "Fallo en POST " + ENDPOINT_CHAT
                + " | tipo=" + error.getClass().getSimpleName()
                + " | httpStatus=" + statusCode
                + " | cuerpo=" + cuerpo);
    }
}
