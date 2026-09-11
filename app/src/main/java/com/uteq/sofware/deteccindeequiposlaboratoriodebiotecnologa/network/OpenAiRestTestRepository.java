package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;
import android.util.Log;

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

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;

/**
 * CLASE TEMPORAL DE PRUEBA AISLADA — no forma parte del flujo principal de BioTec.
 * <p>
 * Objetivo único: comprobar, mediante una llamada REST directa (sin el SDK de Python de
 * referencia), si la OpenAI API Key guardada localmente puede consultar UN Vector Store
 * concreto a través de la Responses API + herramienta {@code file_search}.
 * <p>
 * No sustituye a {@code ChatRepository} (que habla con el backend FastAPI) ni cambia nada
 * de la arquitectura actual. No está referenciada desde ninguna Activity: se invoca solo
 * desde el test instrumentado {@code OpenAiRestTestRepositoryTest}.
 * <p>
 * Reutiliza la API Key guardada por el usuario en {@code ConfiguracionActivity} a través de
 * {@link SecureConfigManager}; nunca la escribe en logs ni la hardcodea. Reutiliza también la
 * cola Volley singleton de {@link ApiClient} (su {@code BASE_URL} no aplica aquí: esta clase
 * pasa la URL completa de OpenAI a la petición).
 */
public final class OpenAiRestTestRepository {

    private static final String TAG = "OpenAiRestTest";

    private static final String ENDPOINT_RESPONSES = "https://api.openai.com/v1/responses";
    private static final String MODEL_DE_PRUEBA = "gpt-5.6-luna";
    private static final String VECTOR_STORE_ID_PRUEBA = "vs_6aa2afc8c3f88191a9c07603ba6791ea";
    private static final String PROMPT_DESARROLLADOR =
            "Compórtate como una laboratorista. Responde únicamente usando la información "
                    + "recuperada del manual mediante file_search. Si la información no está "
                    + "disponible, responde exactamente: No tengo suficiente información, por "
                    + "favor pregúntele al laboratorista encargado.";
    private static final String PREGUNTA_POR_DEFECTO = "¿Cuál es la función de este equipo?";

    /** Las respuestas con file_search pueden tardar; se da margen amplio como en ChatRepository. */
    private static final int TIMEOUT_MS = 60000;

    public interface Callback {
        void onResultado(Resultado resultado);
    }

    private OpenAiRestTestRepository() {
    }

    public static void ejecutarPruebaVectorStore(Context context, Callback callback) {
        ejecutarPruebaVectorStore(context, PREGUNTA_POR_DEFECTO, callback);
    }

    public static void ejecutarPruebaVectorStore(Context context, String pregunta, Callback callback) {
        Context appContext = context.getApplicationContext();
        SecureConfigManager secureConfigManager = new SecureConfigManager(appContext);

        if (!secureConfigManager.hasApiKey()) {
            Resultado sinClave = Resultado.sinApiKeyConfigurada();
            Log.w(TAG, "No hay API Key guardada en ConfiguracionActivity; prueba abortada sin llamar a OpenAI.");
            callback.onResultado(sinClave);
            return;
        }

        String apiKey = secureConfigManager.getApiKey();

        JSONObject cuerpo;
        try {
            cuerpo = construirCuerpoPeticion(pregunta);
        } catch (JSONException e) {
            callback.onResultado(Resultado.errorTransporte("No se pudo construir el JSON de la petición: " + e.getMessage()));
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                ENDPOINT_RESPONSES,
                cuerpo,
                response -> callback.onResultado(Resultado.desdeRespuestaExitosa(200, response)),
                (VolleyError error) -> callback.onResultado(interpretarError(error))) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + apiKey);
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(
                TIMEOUT_MS,
                0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        ApiClient.getInstancia(appContext).agregarPeticion(request);
    }

    private static JSONObject construirCuerpoPeticion(String pregunta) throws JSONException {
        JSONObject cuerpo = new JSONObject();
        cuerpo.put("model", MODEL_DE_PRUEBA);

        JSONArray input = new JSONArray();
        input.put(mensaje("developer", PROMPT_DESARROLLADOR));
        input.put(mensaje("user", pregunta));
        cuerpo.put("input", input);

        JSONObject herramientaFileSearch = new JSONObject();
        herramientaFileSearch.put("type", "file_search");
        JSONArray vectorStoreIds = new JSONArray();
        vectorStoreIds.put(VECTOR_STORE_ID_PRUEBA);
        herramientaFileSearch.put("vector_store_ids", vectorStoreIds);
        JSONArray tools = new JSONArray();
        tools.put(herramientaFileSearch);
        cuerpo.put("tools", tools);

        cuerpo.put("store", true);
        return cuerpo;
    }

    private static JSONObject mensaje(String rol, String texto) throws JSONException {
        JSONObject contenido = new JSONObject();
        contenido.put("type", "input_text");
        contenido.put("text", texto);

        JSONArray contenidos = new JSONArray();
        contenidos.put(contenido);

        JSONObject mensaje = new JSONObject();
        mensaje.put("role", rol);
        mensaje.put("content", contenidos);
        return mensaje;
    }

    /**
     * Traduce un VolleyError a un Resultado diagnóstico, distinguiendo 401/403/404/429/5xx.
     * Nunca incluye el encabezado Authorization; solo el cuerpo de error devuelto por OpenAI
     * (que jamás contiene la API Key enviada, únicamente la de nuestra petición).
     */
    private static Resultado interpretarError(VolleyError error) {
        if (error instanceof TimeoutError) {
            return Resultado.errorTransporte("Tiempo de espera agotado al contactar a OpenAI.");
        }

        NetworkResponse networkResponse = error.networkResponse;
        if (networkResponse == null) {
            return Resultado.errorTransporte("Error de red antes de recibir respuesta de OpenAI: "
                    + error.getClass().getSimpleName());
        }

        int statusCode = networkResponse.statusCode;
        String cuerpoError = "";
        if (networkResponse.data != null) {
            cuerpoError = new String(networkResponse.data, StandardCharsets.UTF_8);
            if (cuerpoError.length() > 500) {
                cuerpoError = cuerpoError.substring(0, 500) + "…";
            }
        }

        return Resultado.desdeErrorHttp(statusCode, cuerpoError);
    }

    /** Cita de archivo (file_citation) encontrada en las annotations del texto de salida. */
    public static final class Fuente {
        public final String nombreArchivo;
        public final String fileId;

        Fuente(String nombreArchivo, String fileId) {
            this.nombreArchivo = nombreArchivo;
            this.fileId = fileId;
        }

        @Override
        public String toString() {
            if (!nombreArchivo.isEmpty() && !fileId.isEmpty()) {
                return nombreArchivo + " (file_id=" + fileId + ")";
            }
            if (!nombreArchivo.isEmpty()) {
                return nombreArchivo;
            }
            if (!fileId.isEmpty()) {
                return "file_id=" + fileId;
            }
            return "(cita sin nombre ni file_id)";
        }
    }

    /** Resultado diagnóstico completo de la prueba, listo para imprimir en el formato pedido. */
    public static final class Resultado {
        public final boolean llamadaCompletada;
        public final int httpStatus;
        public final String apiKeyEstado; // "ACEPTADA" | "RECHAZADA" | "DESCONOCIDO"
        public final String vectorStoreAccesible; // "SI" | "NO" | "DESCONOCIDO"
        public final boolean fileSearchEjecutado;
        public final String textoRespuesta;
        public final List<Fuente> fuentes;
        public final String errorTipo; // null si no hubo error
        public final String errorMensaje; // null si no hubo error

        private Resultado(boolean llamadaCompletada, int httpStatus, String apiKeyEstado,
                           String vectorStoreAccesible, boolean fileSearchEjecutado,
                           String textoRespuesta, List<Fuente> fuentes,
                           String errorTipo, String errorMensaje) {
            this.llamadaCompletada = llamadaCompletada;
            this.httpStatus = httpStatus;
            this.apiKeyEstado = apiKeyEstado;
            this.vectorStoreAccesible = vectorStoreAccesible;
            this.fileSearchEjecutado = fileSearchEjecutado;
            this.textoRespuesta = textoRespuesta;
            this.fuentes = fuentes;
            this.errorTipo = errorTipo;
            this.errorMensaje = errorMensaje;
        }

        static Resultado sinApiKeyConfigurada() {
            return new Resultado(false, -1, "DESCONOCIDO", "DESCONOCIDO", false, null,
                    new ArrayList<>(), "SIN_API_KEY",
                    "No hay ninguna API Key guardada. Ve a la app > Configuración (ConfiguracionActivity) "
                            + "e introduce una API Key de prueba antes de correr este test.");
        }

        static Resultado errorTransporte(String detalle) {
            return new Resultado(false, -1, "DESCONOCIDO", "DESCONOCIDO", false, null,
                    new ArrayList<>(), "ERROR_TRANSPORTE", detalle);
        }

        static Resultado desdeErrorHttp(int statusCode, String cuerpoError) {
            String apiKeyEstado = (statusCode == 401) ? "RECHAZADA" : "DESCONOCIDO";
            String vectorStoreAccesible = (statusCode == 404) ? "NO" : "DESCONOCIDO";
            String tipo;
            switch (statusCode) {
                case 401:
                    tipo = "401_API_KEY_INVALIDA";
                    break;
                case 403:
                    tipo = "403_ACCESO_DENEGADO";
                    break;
                case 404:
                    tipo = "404_RECURSO_NO_ENCONTRADO";
                    break;
                case 429:
                    tipo = "429_LIMITE_CUOTA";
                    break;
                default:
                    tipo = (statusCode >= 500) ? "5XX_ERROR_OPENAI" : ("ERROR_HTTP_" + statusCode);
            }
            return new Resultado(true, statusCode, apiKeyEstado, vectorStoreAccesible, false, null,
                    new ArrayList<>(), tipo, cuerpoError);
        }

        static Resultado desdeRespuestaExitosa(int statusCode, JSONObject response) {
            boolean fileSearchEjecutado = false;
            String vectorStoreAccesible = "DESCONOCIDO";
            StringBuilder texto = new StringBuilder();
            List<Fuente> fuentes = new ArrayList<>();

            JSONArray output = response.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String tipo = item.optString("type", "");

                    if ("file_search_call".equals(tipo)) {
                        fileSearchEjecutado = true;
                        String status = item.optString("status", "");
                        boolean tieneError = item.has("error") && !item.isNull("error");
                        if (tieneError || "failed".equalsIgnoreCase(status)) {
                            vectorStoreAccesible = "NO";
                        } else {
                            vectorStoreAccesible = "SI";
                        }
                    } else if ("message".equals(tipo)) {
                        JSONArray content = item.optJSONArray("content");
                        if (content != null) {
                            for (int j = 0; j < content.length(); j++) {
                                JSONObject contentItem = content.optJSONObject(j);
                                if (contentItem == null) {
                                    continue;
                                }
                                if ("output_text".equals(contentItem.optString("type", ""))) {
                                    texto.append(contentItem.optString("text", ""));
                                    JSONArray annotations = contentItem.optJSONArray("annotations");
                                    if (annotations != null) {
                                        for (int k = 0; k < annotations.length(); k++) {
                                            JSONObject annotation = annotations.optJSONObject(k);
                                            if (annotation == null) {
                                                continue;
                                            }
                                            if ("file_citation".equals(annotation.optString("type", ""))) {
                                                fuentes.add(new Fuente(
                                                        annotation.optString("filename", ""),
                                                        annotation.optString("file_id", "")));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return new Resultado(true, statusCode, "ACEPTADA", vectorStoreAccesible,
                    fileSearchEjecutado, texto.toString(), fuentes, null, null);
        }

        /** Formatea el bloque de resultado final exactamente como se pidió validar la prueba. */
        public String toResumenFinal() {
            StringBuilder sb = new StringBuilder();
            sb.append("REST /v1/responses: ").append(llamadaCompletada ? "OK" : "ERROR").append('\n');
            sb.append("API KEY: ").append(traducirEstadoApiKey()).append('\n');
            sb.append("VECTOR STORE: ").append(VECTOR_STORE_ID_PRUEBA).append('\n');
            sb.append("ACCESO AL VECTOR STORE: ").append(traducirSiNo(vectorStoreAccesible)).append('\n');
            sb.append("FILE SEARCH: ").append(fileSearchEjecutado ? "SÍ" : "NO").append('\n');
            sb.append("RESPUESTA DEL MANUAL: ")
                    .append((textoRespuesta == null || textoRespuesta.isEmpty()) ? "(sin texto)" : textoRespuesta)
                    .append('\n');
            sb.append("FUENTE: ").append(fuentes.isEmpty() ? "(ninguna citación devuelta)" : fuentesComoTexto());
            if (errorTipo != null) {
                sb.append('\n').append("ERROR: ").append(errorTipo);
                if (errorMensaje != null && !errorMensaje.isEmpty()) {
                    sb.append(" | ").append(errorMensaje);
                }
            }
            return sb.toString();
        }

        private String traducirEstadoApiKey() {
            if ("ACEPTADA".equals(apiKeyEstado)) {
                return "aceptada";
            }
            if ("RECHAZADA".equals(apiKeyEstado)) {
                return "rechazada";
            }
            return "desconocido (ver ERROR)";
        }

        private String traducirSiNo(String valor) {
            if ("SI".equals(valor)) {
                return "SÍ";
            }
            if ("NO".equals(valor)) {
                return "NO";
            }
            return "desconocido";
        }

        private String fuentesComoTexto() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fuentes.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(fuentes.get(i).toString());
            }
            return sb.toString();
        }
    }
}
