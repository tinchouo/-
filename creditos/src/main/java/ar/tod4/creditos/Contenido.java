package ar.tod4.creditos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Arma la lista de renglones de los creditos, en "pixeles de pantalla" (la pantalla mide 1280 x 720, como el video).
 * Cada renglon tiene su "y" (arriba), su alto, su centro en x y el tamano de letra (1 pixel de la letra de Minecraft
 * = "s" pixeles de pantalla).
 */
final class Contenido {

    static final int W = 1280, H = 720;
    static final TextColor ORO = TextColor.color(0xFFBE3C), ORO2 = TextColor.color(0xFFE18C), BL = TextColor.color(0xFFFFFF),
            GR = TextColor.color(0xBEBEBE), VIO = TextColor.color(0xAF96FF);
    static final int LOGO_H = 600;

    static final class Item {
        boolean logo;
        double y, h, x, s, ancho;
        Component texto;
        boolean seccionJugadores;
    }

    final List<Item> items = new ArrayList<>();
    double alto;           // donde termina todo
    double inicioJugadores = Double.MAX_VALUE;
    double finLogo;

    private double y;
    /** Ancho maximo de un renglon: lo que queda en el medio entre las capturas. */
    private final double maxAncho;

    /** Ancho de las capturas en pixeles de pantalla (0 si no hay capturas). */
    static int anchoCaptura(TOD4Creditos pl) {
        return pl.getConfig().getInt("capturas_ancho", 416);
    }

    Contenido(TOD4Creditos pl, Datos d, YamlConfiguration c, Map<String, String> ganadores, YamlConfiguration premios) {
        int cw = anchoCaptura(pl);
        maxAncho = cw <= 0 ? W - 80 : W - 2 * (28 + cw) - 32;
        List<Map<?, ?>> orden = c.getMapList("orden");
        Map<String, String> vals = d.valores();
        for (Map<?, ?> m : orden) {
            if (m.containsKey("espacio")) { y += num(m.get("espacio"), 0); continue; }
            if (m.containsKey("logo")) {
                Item it = new Item();
                it.logo = true; it.y = y; it.h = LOGO_H; it.x = W / 2.0; it.ancho = LOGO_H;
                items.add(it);
                y += LOGO_H;
                finLogo = y;
                continue;
            }
            if (m.containsKey("texto")) {
                linea(str(m.get("texto")), color(m.get("color"), BL), (int) num(m.get("tam"), 4), W / 2.0);
                continue;
            }
            if (m.containsKey("titulo")) { titulo(str(m.get("titulo"))); continue; }
            if (m.containsKey("persona")) {
                linea(str(m.get("persona")), BL, 5, W / 2.0);
                y += 12;
                Object rs = m.get("roles");
                if (rs instanceof List<?> l) for (Object r : l) {
                    String t = str(r);
                    if (t.startsWith("#")) { y += 10; linea(t.substring(1), ORO2, 3, W / 2.0); }
                    else linea(t, GR, 3, W / 2.0);
                    y += 6;
                }
                y += 70;
                continue;
            }
            if (m.containsKey("lista")) {
                Object l = m.get("lista");
                if (l instanceof List<?> ll) for (Object o : ll) { linea(str(o), BL, 3, W / 2.0); y += 14; }
                continue;
            }
            if (m.containsKey("numeros")) {
                titulo(c.getString("numeros.titulo", "NÚMEROS"));
                for (String l : c.getStringList("numeros.lineas")) {
                    for (Map.Entry<String, String> v : vals.entrySet()) l = l.replace("{" + v.getKey() + "}", v.getValue());
                    if (l.contains("???")) continue;
                    linea(l, BL, 4, W / 2.0);
                    y += 14;
                }
                continue;
            }
            if (m.containsKey("records")) {
                titulo(c.getString("records.titulo", "RÉCORDS"));
                for (Map<?, ?> r : c.getMapList("records.lista")) {
                    String tit = str(r.get("titulo"));
                    String nom, val = null;
                    if (r.containsKey("stat")) {
                        String[] rec = d.record(str(r.get("stat")), "menor".equalsIgnoreCase(str(r.get("orden"))), num(r.get("min_horas"), 0));
                        if (rec == null) continue;
                        nom = rec[0]; val = rec[1];
                    } else nom = str(r.get("ganador"));
                    if (nom.isEmpty() || nom.contains("???")) continue;
                    dato(tit, nom, val);
                }
                continue;
            }
            if (m.containsKey("premios")) {
                ConfigurationSection ps = premios.getConfigurationSection("premios");
                if (ps == null) continue;
                boolean alguno = false;
                for (String k : ps.getKeys(false)) if (ganadores.containsKey(k)) alguno = true;
                if (!alguno) { y -= 240; continue; }   // sin el espacio de antes
                titulo(c.getString("premios_titulo", "PREMIOS"));
                for (String k : ps.getKeys(false)) {
                    String g = ganadores.get(k);
                    if (g == null) continue;
                    dato(ps.getString(k + ".titulo", k), g, null);
                }
                continue;
            }
            if (m.containsKey("jugadores")) {
                inicioJugadores = y;
                titulo(c.getString("jugadores_titulo", "JUGADORES"));
                jugadores(d, c.getString("sin_equipo", "Sin equipo"));
                continue;
            }
        }
        alto = y;
        for (Item it : items) if (it.y >= inicioJugadores) it.seccionJugadores = true;
    }

    private void titulo(String t) { linea(t, ORO, 6, W / 2.0); y += 60; }

    private void dato(String tit, String nombre, String valor) {
        linea(tit, ORO2, 3, W / 2.0);
        y += 8;
        Component c = Component.text(nombre, BL);
        String plano = nombre;
        if (valor != null) { c = c.append(Component.text("  " + valor, GR)); plano += "  " + valor; }
        agregar(c, plano, 4, W / 2.0, false);
        y += 34;
    }

    private void linea(String t, TextColor col, int s, double x) {
        agregar(Component.text(t, col), t, s, x, false);
    }

    private void agregar(Component c, String plano, int s, double x, boolean noAvanzar) {
        Item it = new Item();
        it.texto = c; it.s = s; it.x = x; it.y = y; it.h = 9 * s; it.ancho = ancho(plano) * s;
        if (it.ancho > maxAncho) { it.s = s * maxAncho / it.ancho; it.ancho = maxAncho; }
        items.add(it);
        if (!noAvanzar) y += 9 * s;
    }

    private void jugadores(Datos d, String sinEquipo) {
        Set<UUID> conEquipo = new HashSet<>();
        List<Object[]> bloques = new ArrayList<>();   // {nombre, color, List<String>}
        for (Datos.Equipo e : d.equipos) {
            conEquipo.addAll(e.miembros);
            if (d.equipoFuera(e)) continue;
            List<String> ns = new ArrayList<>();
            for (UUID u : e.miembros) {
                Datos.Jugador j = d.jugadores.get(u);
                String n = j != null ? j.nombre : org.bukkit.Bukkit.getOfflinePlayer(u).getName();
                if (n == null || (j != null && d.fuera(j))) continue;
                ns.add(n);
            }
            if (ns.isEmpty()) continue;
            ns.sort(String.CASE_INSENSITIVE_ORDER);
            bloques.add(new Object[]{e.nombre, colorEquipo(e.color), ns});
        }
        bloques.sort(Comparator.comparing(b -> ((String) b[0]).toLowerCase()));
        for (Object[] b : bloques) {
            agregar(Component.text((String) b[0], (TextColor) b[1]), (String) b[0], 4, W / 2.0, false);
            y += 14;
            @SuppressWarnings("unchecked") List<String> ns = (List<String>) b[2];
            for (String n : ns) { agregar(Component.text(n, BL), n, 3, W / 2.0, false); y += 8; }
            y += 50;
        }
        List<String> solos = new ArrayList<>();
        for (Datos.Jugador j : d.jugadores.values())
            if (!conEquipo.contains(j.uuid) && d.cuenta(j) && !d.fuera(j)) solos.add(j.nombre);
        solos.sort(String.CASE_INSENSITIVE_ORDER);
        if (!solos.isEmpty()) {
            linea(sinEquipo, VIO, 4, W / 2.0);
            y += 22;
            for (String n : solos) { agregar(Component.text(n, BL), n, 3, W / 2.0, false); y += 8; }
        }
    }

    static TextColor colorEquipo(String n) {
        int c = switch (n == null ? "" : n.toLowerCase()) {
            case "black", "dark_gray" -> 0xAAAAAA;     // en negro no se veria
            case "dark_blue" -> 0x5555FF;
            case "dark_green" -> 0x00AA00;
            case "dark_aqua" -> 0x00AAAA;
            case "dark_red" -> 0xCC3333;
            case "dark_purple" -> 0xAA00AA;
            case "gold" -> 0xFFAA00;
            case "gray" -> 0xAAAAAA;
            case "blue" -> 0x5555FF;
            case "green" -> 0x55FF55;
            case "aqua" -> 0x55FFFF;
            case "red" -> 0xFF5555;
            case "light_purple" -> 0xFF55FF;
            case "yellow" -> 0xFFFF55;
            default -> 0xFFFFFF;
        };
        return TextColor.color(c);
    }

    /** Ancho aproximado en pixeles de la letra de Minecraft (con el espacio entre letras). */
    static int ancho(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        int w = 0;
        for (char ch : n.toCharArray()) {
            w += switch (ch) {
                case ' ' -> 4;
                case '!', '\'', ',', '.', ':', ';', '|', 'i' -> 2;
                case '`', 'l' -> 3;
                case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
                case '<', '>', 'f', 'k' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return Math.max(0, w - 1);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static TextColor color(Object o, TextColor def) {
        if (o == null) return def;
        TextColor c = TextColor.fromHexString(String.valueOf(o));
        return c != null ? c : def;
    }

    /** "TRUCOFDEATH 4" como el titulo del sidebar: degradado, en negrita, el 4 en blanco. */
    static Component tituloFinal(int a, int b) {
        String t = "TRUCOFDEATH";
        Component out = Component.empty();
        for (int i = 0; i < t.length(); i++) {
            double u = i / (double) (t.length() - 1);
            int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * u);
            int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * u);
            int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * u);
            out = out.append(Component.text(String.valueOf(t.charAt(i)), TextColor.color(r, g, bl)).decoration(TextDecoration.BOLD, true));
        }
        return out.append(Component.text(" 4", BL).decoration(TextDecoration.BOLD, true));
    }
}
