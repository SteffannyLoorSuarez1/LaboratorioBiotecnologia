package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Evita doble navegación por doble tap accidental (por ejemplo, dos {@code DeteccionActivity} o
 * dos {@code ChatActivity} abriéndose a la vez desde el mismo click, con el back stack raro que
 * eso produce). Un único cronómetro global es suficiente para este caso de uso: no hace falta
 * estado por Activity ni por vista.
 */
public final class NavegacionUtil {

    private static final long VENTANA_DEBOUNCE_MS = 600;
    private static final AtomicLong ultimaNavegacionMs = new AtomicLong(0);

    private NavegacionUtil() {
    }

    /**
     * Devuelve {@code true} la primera vez que se llama dentro de una ventana de
     * {@value #VENTANA_DEBOUNCE_MS} ms; llamadas repetidas dentro de esa ventana devuelven
     * {@code false} (se ignoran). Pensado para envolver el {@code startActivity(...)}/
     * {@code launch(...)} dentro de un listener de click de navegación.
     */
    public static boolean puedeNavegar() {
        long ahora = System.currentTimeMillis();
        long anterior = ultimaNavegacionMs.get();
        if (ahora - anterior < VENTANA_DEBOUNCE_MS) {
            return false;
        }
        return ultimaNavegacionMs.compareAndSet(anterior, ahora);
    }
}
