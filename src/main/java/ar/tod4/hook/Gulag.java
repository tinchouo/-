package ar.tod4.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

import ar.tod4.util.Ancho;

/**
 * El gulag se maneja con un script (gulag.sk). El plugin solo lee sus variables para:
 *  - la sidebar del gulag (jugadores y espectadores),
 *  - el color del brillo del que tiene la llave (amarillo inmune, blanco despues),
 *  - el mensaje de muerte "X murio".
 * Variables (en minuscula): {gulag::fase} = preparado | jugando | fin, {gulag::lista::<uuid>}, {gulag::salvado::<uuid>},
 * {gulag::texto}, {gulag::textoesp}, {gulag::n::juego}, {gulag::n::salvos}, {gulag::n::llaves},
 * {gulag::llave::<uuid>} = inmune | si, {gulag::muere::<uuid>}.
 */
public final class Gulag {
    private Gulag() {}

    public static boolean activo() {
        Object f = SkriptVars.get("gulag::fase");
        if (f == null) return false;
        String s = String.valueOf(f);
        return s.equals("preparado") || s.equals("jugando") || s.equals("fin");
    }

    public static boolean participa(UUID u) { return SkriptVars.get("gulag::lista::" + u) != null; }

    public static boolean salvado(UUID u) { return SkriptVars.get("gulag::salvado::" + u) != null; }

    /** "inmune", "si" o null. */
    public static String llave(UUID u) {
        Object o = SkriptVars.get("gulag::llave::" + u);
        return o == null ? null : String.valueOf(o);
    }

    public static boolean muereEnGulag(UUID u) { return SkriptVars.get("gulag::muere::" + u) != null; }

    private static String texto(String var, String def) {
        Object o = SkriptVars.get(var);
        return o == null || String.valueOf(o).isBlank() ? def : String.valueOf(o);
    }

    private static int numero(String var) {
        Object o = SkriptVars.get(var);
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** La sidebar completa del gulag (en azul). */
    public static List<String> sidebar(Player p, String sep, int ancho) {
        List<String> out = new ArrayList<>();
        out.add(sep);
        if (participa(p.getUniqueId())) {
            out.add("&9■ &9&lGULAG");
            List<String> t = Ancho.cortar("&b" + texto("gulag::texto", "Agarra una llave y escapa por la puerta abierta."), "&9  ", "&9  ", ancho);
            out.addAll(t.size() > 6 ? t.subList(0, 6) : t);
            out.add(sep);
            out.add(salvado(p.getUniqueId()) ? "&a✔ &aEstás a salvo" : "&c✖ &cNo estás a salvo");
        } else {
            out.add("&9■ &9&lGULAG EN CURSO");
            List<String> t = Ancho.cortar("&b" + texto("gulag::textoesp", "Mira quién logra escapar."), "&9  ", "&9  ", ancho);
            out.addAll(t.size() > 5 ? t.subList(0, 5) : t);
            out.add(sep);
            out.add("&a✔ &fA salvo: &a" + numero("gulag::n::salvos"));
            out.add("&c✖ &fEn juego: &c" + numero("gulag::n::juego"));
            out.add("&e✦ &fLlaves en el mapa: &e" + numero("gulag::n::llaves"));
        }
        out.add(sep);
        return out;
    }
}
