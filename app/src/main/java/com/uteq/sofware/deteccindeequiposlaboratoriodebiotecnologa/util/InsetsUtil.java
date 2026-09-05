package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.util;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Ayuda a que el contenido de cada pantalla respete la barra de estado y, sobre todo, la
 * barra de navegación del sistema.
 * <p>
 * Desde Android 15 (targetSdk 35+), el sistema fuerza el modo "edge to edge": el contenido
 * de la app se dibuja detrás de las barras de sistema aunque la Activity no llame a
 * {@code EdgeToEdge.enable(...)}. Si una vista no aplica los insets manualmente, sus
 * elementos inferiores (botones, banners, campos) pueden terminar pegados a, o parcialmente
 * detrás de, la barra de navegación en un teléfono real.
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /**
     * Añade el inset de las barras de sistema como padding adicional (se suma al padding ya
     * definido en el XML, no lo reemplaza) en los lados indicados.
     */
    public static void aplicarInsetsBarraSistema(View vista, boolean izquierda, boolean superior,
                                                  boolean derecha, boolean inferior) {
        int paddingIzqOriginal = vista.getPaddingLeft();
        int paddingSupOriginal = vista.getPaddingTop();
        int paddingDerOriginal = vista.getPaddingRight();
        int paddingInfOriginal = vista.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(vista, (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    paddingIzqOriginal + (izquierda ? barras.left : 0),
                    paddingSupOriginal + (superior ? barras.top : 0),
                    paddingDerOriginal + (derecha ? barras.right : 0),
                    paddingInfOriginal + (inferior ? barras.bottom : 0));
            return insets;
        });
    }

    /**
     * Para contenedores con un campo de texto pegado al borde inferior (por ejemplo, la caja
     * de preguntas del chat): usa el mayor valor entre la barra de navegación y el teclado
     * (IME), para que el contenedor quede siempre por encima de lo que esté visible en cada
     * momento, sin quedar oculto tras la barra de navegación ni tras el teclado.
     */
    public static void aplicarInsetInferiorConTeclado(View vista) {
        int paddingInfOriginal = vista.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(vista, (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());
            int inferior = Math.max(barras.bottom, teclado.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), paddingInfOriginal + inferior);
            return insets;
        });
    }
}
