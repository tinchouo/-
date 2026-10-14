package ar.tod4.core;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import ar.tod4.TOD4;
import ar.tod4.avisos.Paleta;
import ar.tod4.util.Style;

/**
 * Trae los datos de los scripts viejos desde plugins/Skript/variables.csv:
 * mensajes y bienvenidas, almas, lista de muertos, dia actual, estado de los cambios y aldeanos legales.
 * No borra nada de Skript. Se puede correr varias veces (pisa lo importado).
 */
public final class Importar {

    private final TOD4 pl;

    public Importar(TOD4 pl) { this.pl = pl; }

    private static final Pattern LINE = Pattern.compile("^(?:\"((?:[^\"]|\"\")*)\"|([^,]*)),\\s*([^,]*),\\s*([0-9A-Fa-f]*)\\s*$");

    public void correr(CommandSender s) {
        File f = new File(pl.getDataFolder().getParentFile(), "Skript/variables.csv");
        if (!f.exists()) { Style.err(s, "No encontré plugins/Skript/variables.csv"); return; }
        Map<String, Object> v = new HashMap<>();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher m = LINE.matcher(line);
                if (!m.matches()) continue;
                String name = m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2).trim();
                Object val = decode(m.group(3).trim(), m.group(4));
                if (val != null) v.put(name, val);
            }
        } catch (Exception ex) {
            Style.err(s, "No pude leer variables.csv: " + ex.getMessage());
            return;
        }
        int msgs = 0, bien = 0, almas = 0, muertos = 0, cambios = 0, aldeanos = 0;

        // ---------------- mensajes
        for (String id : valores(v, "msg::lista_ids::")) {
            String b = "msg::data::" + id + "::";
            ConfigurationSection c = pl.avisos().store().y().createSection("mensajes." + id);
            int vieja = num(v.get(b + "paleta"), 2);
            c.set("paleta", Paleta.desdeVieja(vieja));
            c.set("sonido", num(v.get(b + "sonido"), 1));
            c.set("titulo", str(v.get(b + "chat_title"), "ninguno"));
            c.set("lineas", lista(v, b + "chat_lines::"));
            c.set("ruleta", vieja <= 5);
            c.set("icono", 0);
            c.set("dia", 0);
            msgs++;
        }
        for (String id : valores(v, "msg::join_ids::")) {
            String b = "msg::join_data::" + id + "::";
            ConfigurationSection c = pl.avisos().store().y().createSection("bienvenidas." + id);
            c.set("paleta", Paleta.desdeVieja(num(v.get(b + "paleta"), 1)));
            c.set("sonido", num(v.get(b + "sonido"), 8));
            c.set("lineas", lista(v, b + "lineas::"));
            bien++;
        }
        if (v.get("msg::join_activo") instanceof String act) pl.avisos().store().set("bienvenida_activa", act);
        pl.avisos().store().touch();

        // ---------------- revivir
        for (Map.Entry<String, Object> e : v.entrySet()) {
            if (e.getKey().startsWith("alma::") && e.getValue() instanceof Boolean b) {
                pl.revivir().setAlma(e.getKey().substring(6), b);
                almas++;
            }
        }
        List<String> lm = valores(v, "revivir::muertos::");
        if (!lm.isEmpty()) {
            pl.revivir().store().set("muertos", lm);
            for (String n : lm) {
                Object mot = v.get("revivir::motivo_muerte::" + n);
                if (mot != null) pl.revivir().store().set("motivo." + n, mot.toString());
                Object d = v.get("revivir::dia_muerte::" + n);
                if (d != null) pl.revivir().store().set("dia." + n, num(d, 1));
            }
            muertos = lm.size();
        }
        if (v.get("revivir::modo_prueba") instanceof Boolean b) pl.revivir().store().set("modo_prueba", b);

        // ---------------- dia
        Object dia = v.containsKey("tod4::dia") ? v.get("tod4::dia") : v.get("dia_actual");
        if (dia != null) pl.datos().set("dia", num(dia, 1));

        // ---------------- cambios
        String[][] mapa = {{"dia1::hornos", "d1.hornos"}, {"dia1::manzanas", "d1.manzanas"}, {"dia1::aldeanos", "d1.aldeanos"},
                {"dia1::totems", "d1.totems"}, {"dia1::nether", "d1.nether"}, {"dia2::reproduccion", "d2.reproduccion"},
                {"dia2::saplings", "d2.saplings"}, {"dia2::hornos", "d2.hornos"}, {"cambio::dia3::leche", "d3.leche"},
                {"cambio::dia3::lava", "d3.lava"}, {"cambio::dia4::agua", "d4.agua"}, {"cambio::dia4::puertas", "d4.puertas"},
                {"cambio::dia4::crianza", "d4.crianza"}, {"cambio::dia4::perla", "d4.perla"}};
        for (String[] m : mapa) {
            if (v.get(m[0]) instanceof Boolean b) { pl.cambios().importar(m[1], b); cambios++; }
        }
        List<String> legales = new ArrayList<>(pl.datos().y().getStringList("aldeanos_legales"));
        for (Map.Entry<String, Object> e : v.entrySet()) {
            if (e.getKey().startsWith("aldeano_legal::")) {
                String u = e.getKey().substring("aldeano_legal::".length());
                if (!legales.contains(u)) { legales.add(u); aldeanos++; }
            }
        }
        pl.datos().set("aldeanos_legales", legales);

        Style.ok(s, "Importado: &f" + msgs + " &amensajes, &f" + bien + " &abienvenidas, &f" + almas + " &aalmas, &f" + muertos
                + " &amuertos, &f" + cambios + " &acambios, &f" + aldeanos + " &aaldeanos legales.");
        Style.info(s, "Los ítems de revivir y el altar hay que volver a ponerlos con &e/altar&7.");
    }

    private static Object decode(String type, String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() % 2) != 0) return null;
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        switch (type) {
            case "string": return new String(b, StandardCharsets.UTF_8);
            case "long": return b.length == 8 ? ByteBuffer.wrap(b).getLong() : null;
            case "integer": return b.length == 4 ? (long) ByteBuffer.wrap(b).getInt() : null;
            case "double": return b.length == 8 ? ByteBuffer.wrap(b).getDouble() : null;
            case "boolean": return b.length >= 1 && b[0] != 0;
            default: return null;
        }
    }

    /** Valores de una lista de Skript {x::*} ordenados por indice. */
    private static List<String> valores(Map<String, Object> v, String prefijo) {
        TreeMap<String, String> t = new TreeMap<>((a, b) -> {
            try { return Long.compare(Long.parseLong(a), Long.parseLong(b)); } catch (NumberFormatException e) { return a.compareTo(b); }
        });
        for (Map.Entry<String, Object> e : v.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(prefijo) && !k.substring(prefijo.length()).contains("::")) t.put(k.substring(prefijo.length()), String.valueOf(e.getValue()));
        }
        return new ArrayList<>(t.values());
    }

    private static List<String> lista(Map<String, Object> v, String prefijo) { return valores(v, prefijo); }

    private static int num(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        return def;
    }

    private static String str(Object o, String def) { return o == null ? def : o.toString(); }
}
