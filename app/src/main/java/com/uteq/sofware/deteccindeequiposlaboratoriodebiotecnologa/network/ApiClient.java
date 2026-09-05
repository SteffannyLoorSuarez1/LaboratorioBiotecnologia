package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

/**
 * Cliente HTTP central de la aplicación, basado en Volley. Todas las peticiones de red deben
 * pasar por aquí (o por una clase de repositorio como {@link ChatRepository}) en lugar de
 * crearse directamente dentro de las Activities.
 * <p>
 * IMPORTANTE — CONFIGURACIÓN DE RED PARA PRUEBAS EN UN TELÉFONO REAL:
 * <p>
 * {@link #BASE_URL} debe apuntar a la IP de la máquina donde corre el backend FastAPI dentro
 * de la misma red local, por ejemplo {@code http://192.168.1.50:8000}. NUNCA uses
 * {@code http://localhost:8000} ni {@code http://127.0.0.1:8000} desde un teléfono real: esas
 * direcciones apuntan al propio teléfono, no a tu computador.
 * <p>
 * Para obtener la IP de tu computador en la misma red Wi-Fi:
 * <ul>
 *     <li>Windows: {@code ipconfig} (buscar "Dirección IPv4").</li>
 *     <li>Linux/Mac: {@code ifconfig} o {@code ip addr}.</li>
 * </ul>
 * Cambia el valor de {@link #BASE_URL} más abajo antes de probar en un dispositivo físico.
 */
public final class ApiClient {

    /**
     * Cambiar aquí la IP/puerto del backend antes de probar en un teléfono real.
     * Valor actual: IPv4 del adaptador Wi-Fi "WIFI_UTEQ" de la máquina de desarrollo, verificada
     * con {@code Get-NetIPConfiguration} el 2026-09-04 (la anterior, 192.168.0.109, era de una
     * red distinta y ya no corresponde a ningún adaptador activo — esa desincronización fue la
     * causa real de "No se pudo conectar con el servidor" en el teléfono físico). Debe
     * actualizarse cada vez que cambie de red o de equipo: las redes por DHCP (como esta, de la
     * universidad) pueden reasignar la IP en cualquier momento.
     */
    public static final String BASE_URL = "http://10.2.15.218:8000";

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
