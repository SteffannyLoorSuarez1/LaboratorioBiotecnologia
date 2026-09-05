package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el Markdown moderado que puede devolver el backend/LLM en dos representaciones:
 * <ul>
 *     <li>{@link #toDisplaySpannable(String)}: un {@code CharSequence} con estilos reales de
 *     Android (negrita, títulos, viñetas) para mostrar en el chat, sin símbolos {@code **}/
 *     {@code #} crudos.</li>
 *     <li>{@link #toSpeechText(String)}: texto plano limpio para {@code TextToSpeech}, sin
 *     sintaxis Markdown, para que no se lea "asterisco"/"almohadilla".</li>
 * </ul>
 * Cubre solo el subconjunto que este proyecto necesita (títulos, negrita, cursiva, listas,
 * código inline, enlaces) — deliberadamente NO es un parser Markdown completo (no maneja tablas,
 * bloques de código de varias líneas, HTML embebido, etc.) porque el backend solo usa formato
 * moderado (ver prompt del asistente) y una librería completa sería peso innecesario.
 */
final class MarkdownFormatter {

    private static final Pattern HEADER = Pattern.compile("^#{1,6}\\s*(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[\\-*•]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d+)[.)]\\s+(.*)$");

    /** Grupos: 1=**negrita**, 2=__negrita__, 3=`código`, 4/5=[texto](url), 6=*cursiva*, 7=_cursiva_. */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"
                    + "|__(.+?)__"
                    + "|`([^`]+)`"
                    + "|\\[([^\\]]+)\\]\\(([^)]+)\\)"
                    + "|\\*(.+?)\\*"
                    + "|_(.+?)_");

    private MarkdownFormatter() {
    }

    static CharSequence toDisplaySpannable(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lineas = raw.replace("\r\n", "\n").split("\n", -1);
        boolean esPrimeraLineaEscrita = true;

        for (String lineaOriginal : lineas) {
            if (!esPrimeraLineaEscrita) {
                builder.append('\n');
            }
            esPrimeraLineaEscrita = false;

            String linea = lineaOriginal.trim();
            if (linea.isEmpty()) {
                continue; // línea en blanco: solo aporta el salto de párrafo de arriba
            }

            Matcher mHeader = HEADER.matcher(linea);
            Matcher mBullet = BULLET.matcher(linea);
            Matcher mOrdered = ORDERED.matcher(linea);

            if (mHeader.matches()) {
                int inicio = builder.length();
                appendInlineStyled(builder, mHeader.group(1).trim());
                builder.setSpan(new StyleSpan(Typeface.BOLD), inicio, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new RelativeSizeSpan(1.08f), inicio, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (mBullet.matches()) {
                builder.append("• ");
                appendInlineStyled(builder, mBullet.group(1).trim());
            } else if (mOrdered.matches()) {
                builder.append(mOrdered.group(1)).append(". ");
                appendInlineStyled(builder, mOrdered.group(2).trim());
            } else {
                appendInlineStyled(builder, linea);
            }
        }
        return builder;
    }

    static String toSpeechText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] lineas = raw.replace("\r\n", "\n").split("\n", -1);
        StringBuilder resultado = new StringBuilder();

        for (String lineaOriginal : lineas) {
            String linea = lineaOriginal.trim();
            if (linea.isEmpty()) {
                continue;
            }

            Matcher mHeader = HEADER.matcher(linea);
            Matcher mBullet = BULLET.matcher(linea);
            Matcher mOrdered = ORDERED.matcher(linea);

            String contenido;
            if (mHeader.matches()) {
                contenido = mHeader.group(1).trim();
            } else if (mBullet.matches()) {
                contenido = mBullet.group(1).trim();
            } else if (mOrdered.matches()) {
                contenido = mOrdered.group(2).trim();
            } else {
                contenido = linea;
            }

            contenido = limpiarInlineParaVoz(contenido).trim();
            if (contenido.isEmpty()) {
                continue;
            }

            if (resultado.length() > 0) {
                char ultimo = resultado.charAt(resultado.length() - 1);
                resultado.append(esFinDeFrase(ultimo) ? ' ' : '.').append(' ');
            }
            resultado.append(contenido);
        }
        return resultado.toString().replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static boolean esFinDeFrase(char c) {
        return c == '.' || c == '!' || c == '?' || c == ':';
    }

    /** Aplica los spans de negrita/cursiva/código a las coincidencias de {@link #INLINE} dentro
     * de una línea ya sin marcador de bloque (título/viñeta), preservando el resto del texto. */
    private static void appendInlineStyled(SpannableStringBuilder builder, String texto) {
        Matcher m = INLINE.matcher(texto);
        int ultimoFin = 0;
        while (m.find()) {
            builder.append(texto, ultimoFin, m.start());
            int inicioSpan = builder.length();
            if (m.group(1) != null) {
                builder.append(m.group(1));
                builder.setSpan(new StyleSpan(Typeface.BOLD), inicioSpan, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                builder.append(m.group(2));
                builder.setSpan(new StyleSpan(Typeface.BOLD), inicioSpan, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                builder.append(m.group(3));
                builder.setSpan(new TypefaceSpan("monospace"), inicioSpan, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                builder.append(m.group(4)); // texto del enlace; se descarta la URL (group 5) visualmente
            } else if (m.group(6) != null) {
                builder.append(m.group(6));
                builder.setSpan(new StyleSpan(Typeface.ITALIC), inicioSpan, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(7) != null) {
                builder.append(m.group(7));
                builder.setSpan(new StyleSpan(Typeface.ITALIC), inicioSpan, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            ultimoFin = m.end();
        }
        builder.append(texto, ultimoFin, texto.length());
    }

    /** Igual que {@link #appendInlineStyled}, pero para el texto plano de voz: sustituye cada
     * marcador por su contenido sin aplicar ningún span. */
    private static String limpiarInlineParaVoz(String texto) {
        Matcher m = INLINE.matcher(texto);
        StringBuilder resultado = new StringBuilder();
        int ultimoFin = 0;
        while (m.find()) {
            resultado.append(texto, ultimoFin, m.start());
            if (m.group(1) != null) {
                resultado.append(m.group(1));
            } else if (m.group(2) != null) {
                resultado.append(m.group(2));
            } else if (m.group(3) != null) {
                resultado.append(m.group(3));
            } else if (m.group(4) != null) {
                resultado.append(m.group(4));
            } else if (m.group(6) != null) {
                resultado.append(m.group(6));
            } else if (m.group(7) != null) {
                resultado.append(m.group(7));
            }
            ultimoFin = m.end();
        }
        resultado.append(texto, ultimoFin, texto.length());
        return resultado.toString();
    }
}
