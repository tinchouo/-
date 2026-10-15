package ar.tod4.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Ancho en pixeles de un texto con la fuente de Minecraft (para que la sidebar no se estire).
 * Cada letra mide su ancho + 1 de separacion; en negrita, +1 mas.
 */
public final class Ancho {
    private Ancho() {}

    private static int letra(char c) {
        switch (c) {
            case ' ': return 4;
            case '!': case '.': case ',': case ':': case ';': case '|': case 'i': case '\'': case '¡': return 2;
            case 'l': case '`': return 3;
            case 'I': case 't': case '[': case ']': case '"': case '(': case ')': case '{': case '}': case '*': case 'í': return 4;
            case 'f': case 'k': case '<': case '>': return 5;
            case '@': case '~': return 7;
            default:
                if (c < 128 || "áéóúñÁÉÓÚÑü¿".indexOf(c) >= 0) return 6;
                return 9; // simbolos (✔ ■ ☠ ...) usan la fuente unifont, mas anchos
        }
    }

    /** Ancho de un texto con codigos & (los codigos no ocupan lugar). */
    public static int de(String s) {
        s = Txt.norm(s);
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(i + 1));
                if (k == '#' && i + 7 < s.length()) { i += 7; bold = false; continue; }
                if ("0123456789abcdefr".indexOf(k) >= 0) { bold = false; i++; continue; }
                if (k == 'l') { bold = true; i++; continue; }
                if ("kmno".indexOf(k) >= 0) { i++; continue; }
            }
            if (Character.isHighSurrogate(c)) { w += 9; i++; continue; }
            w += letra(c) + (bold ? 1 : 0);
        }
        return w;
    }

    /** Ultimos codigos de color/formato activos al final del texto (para seguir el color en el renglon siguiente). */
    static String colorFinal(String s) {
        s = Txt.norm(s);
        String col = "", fmt = "";
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) != '&') continue;
            char k = Character.toLowerCase(s.charAt(i + 1));
            if (k == '#' && i + 7 < s.length()) { col = s.substring(i, i + 8); fmt = ""; i += 7; }
            else if ("0123456789abcdefr".indexOf(k) >= 0) { col = s.substring(i, i + 2); fmt = ""; i++; }
            else if ("klmno".indexOf(k) >= 0) { fmt += s.substring(i, i + 2); i++; }
        }
        return col + fmt;
    }

    /**
     * Corta un texto en renglones que no pasen de {@code max} pixeles.
     * El primer renglon empieza con {@code pre1} y los siguientes con {@code pre2}; el color sigue.
     */
    public static List<String> cortar(String texto, String pre1, String pre2, int max) {
        List<String> out = new ArrayList<>();
        String[] palabras = texto.split(" ");
        StringBuilder linea = new StringBuilder(pre1);
        boolean vacia = true;
        for (String w : palabras) {
            String prueba = linea + (vacia ? "" : " ") + w;
            if (!vacia && de(prueba) > max) {
                out.add(linea.toString());
                linea = new StringBuilder(pre2 + colorFinal(linea.toString()) + w);
            } else {
                linea = new StringBuilder(prueba);
            }
            vacia = false;
        }
        out.add(linea.toString());
        return out;
    }
}
