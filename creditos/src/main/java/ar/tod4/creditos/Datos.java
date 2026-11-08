package ar.tod4.creditos;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Los numeros de los creditos: lo que guarda TOD4 (plugins/TOD4/*.yml) y las estadisticas de Minecraft de cada
 * jugador (world/stats/*.json; los que estan conectados se leen en vivo).
 */
final class Datos {

    static final class Jugador {
        UUID uuid;
        String nombre;
        long muertes, ticks, saltos, cm, picado, totems, kills;
        double horas() { return ticks / 72000.0; }
        double stat(String s) {
            return switch (s) {
                case "muertes" -> muertes;
                case "horas" -> horas();
                case "saltos" -> saltos;
                case "caminado" -> cm / 100000.0;
                case "picado" -> picado;
                case "totems" -> totems;
                case "kills" -> kills;
                default -> 0;
            };
        }
    }

    static final class Equipo {
        String tag, nombre, color;
        List<UUID> miembros = new ArrayList<>();
    }

    final Map<UUID, Jugador> jugadores = new HashMap<>();
    final List<Equipo> equipos = new ArrayList<>();
    long muertesTOD4, totemsTOD4, revividosTOD4;
    int meteoritos, cambios;

    private final TOD4Creditos pl;

    Datos(TOD4Creditos pl) { this.pl = pl; }

    void cargar() {
        File tod = new File(pl.getDataFolder().getParentFile(), "TOD4");
        YamlConfiguration datos = YamlConfiguration.loadConfiguration(new File(tod, "datos.yml"));
        muertesTOD4 = suma(datos.getConfigurationSection("muertes"));
        totemsTOD4 = suma(datos.getConfigurationSection("totems"));
        revividosTOD4 = suma(datos.getConfigurationSection("revividos"));
        meteoritos = YamlConfiguration.loadConfiguration(new File(tod, "meteoritos.yml")).getInt("ultimo", 0);
        ConfigurationSection act = YamlConfiguration.loadConfiguration(new File(tod, "cambios.yml")).getConfigurationSection("activos");
        cambios = 0;
        if (act != null) for (String k : act.getKeys(false)) if (act.getBoolean(k)) cambios++;

        equipos.clear();
        ConfigurationSection eq = YamlConfiguration.loadConfiguration(new File(tod, "equipos.yml")).getConfigurationSection("equipos");
        if (eq != null) for (String k : eq.getKeys(false)) {
            ConfigurationSection s = eq.getConfigurationSection(k);
            if (s == null) continue;
            Equipo e = new Equipo();
            e.tag = s.getString("tag", k);
            e.nombre = s.getString("nombre", e.tag);
            e.color = s.getString("color", "white");
            for (String u : s.getStringList("miembros")) try { e.miembros.add(UUID.fromString(u)); } catch (Exception ignored) { }
            equipos.add(e);
        }

        jugadores.clear();
        File dir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "stats");
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) {
            try (Reader r = new FileReader(f, StandardCharsets.UTF_8)) {
                UUID u = UUID.fromString(f.getName().substring(0, f.getName().length() - 5));
                JsonObject st = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("stats");
                if (st == null) continue;
                Jugador j = new Jugador();
                j.uuid = u;
                JsonObject c = st.getAsJsonObject("minecraft:custom");
                j.muertes = num(c, "minecraft:deaths");
                j.ticks = num(c, "minecraft:play_time");
                j.saltos = num(c, "minecraft:jump");
                j.cm = num(c, "minecraft:walk_one_cm") + num(c, "minecraft:sprint_one_cm");
                j.kills = num(c, "minecraft:mob_kills");
                j.totems = num(st.getAsJsonObject("minecraft:used"), "minecraft:totem_of_undying");
                JsonObject m = st.getAsJsonObject("minecraft:mined");
                if (m != null) for (Map.Entry<String, JsonElement> en : m.entrySet()) j.picado += en.getValue().getAsLong();
                jugadores.put(u, j);
            } catch (Exception ex) {
                pl.getLogger().warning("No pude leer " + f.getName() + ": " + ex.getMessage());
            }
        }
        // los conectados: en vivo (el archivo se guarda cada tanto)
        for (Player p : Bukkit.getOnlinePlayers()) {
            Jugador j = jugadores.computeIfAbsent(p.getUniqueId(), k -> { Jugador n = new Jugador(); n.uuid = k; return n; });
            try {
                j.muertes = p.getStatistic(Statistic.DEATHS);
                j.ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
                j.saltos = p.getStatistic(Statistic.JUMP);
                j.cm = (long) p.getStatistic(Statistic.WALK_ONE_CM) + p.getStatistic(Statistic.SPRINT_ONE_CM);
                j.kills = p.getStatistic(Statistic.MOB_KILLS);
                j.totems = p.getStatistic(Statistic.USE_ITEM, Material.TOTEM_OF_UNDYING);
                long pic = 0;
                for (Material mat : Material.values()) if (!mat.isLegacy() && mat.isBlock()) pic += p.getStatistic(Statistic.MINE_BLOCK, mat);
                j.picado = pic;
            } catch (Exception ignored) { }
        }
        // nombres (si dos cuentas tienen el mismo nombre, queda la que mas jugo)
        Map<String, Jugador> porNombre = new HashMap<>();
        for (Jugador j : new ArrayList<>(jugadores.values())) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(j.uuid);
            j.nombre = op.getName();
            if (j.nombre == null) { jugadores.remove(j.uuid); continue; }
            Jugador otro = porNombre.get(j.nombre.toLowerCase(Locale.ROOT));
            if (otro != null) {
                if (otro.ticks >= j.ticks) { jugadores.remove(j.uuid); continue; }
                jugadores.remove(otro.uuid);
            }
            porNombre.put(j.nombre.toLowerCase(Locale.ROOT), j);
        }
    }

    private static long suma(ConfigurationSection s) {
        long t = 0;
        if (s != null) for (String k : s.getKeys(false)) t += s.getLong(k);
        return t;
    }

    private static long num(JsonObject o, String k) {
        if (o == null || !o.has(k)) return 0;
        try { return o.get(k).getAsLong(); } catch (Exception e) { return 0; }
    }

    /** Fuera de JUGADORES y RECORDS (staff). */
    boolean fuera(Jugador j) {
        Set<String> nombres = new HashSet<>();
        for (String n : pl.getConfig().getStringList("jugadores.nombres_fuera")) nombres.add(n.toLowerCase(Locale.ROOT));
        if (nombres.contains(j.nombre.toLowerCase(Locale.ROOT))) return true;
        List<String> eqs = pl.getConfig().getStringList("jugadores.equipos_fuera");
        for (Equipo e : equipos)
            if (eqs.stream().anyMatch(t -> t.equalsIgnoreCase(e.tag)) && e.miembros.contains(j.uuid)) return true;
        return false;
    }

    boolean equipoFuera(Equipo e) {
        return pl.getConfig().getStringList("jugadores.equipos_fuera").stream().anyMatch(t -> t.equalsIgnoreCase(e.tag));
    }

    /** Jugaron lo suficiente para aparecer. */
    boolean cuenta(Jugador j) { return j.ticks >= pl.getConfig().getInt("jugadores.minimo_minutos", 10) * 1200L; }

    Map<String, String> valores() {
        Map<String, String> v = new HashMap<>();
        long ticks = 0, kills = 0;
        int n = 0;
        for (Jugador j : jugadores.values()) { ticks += j.ticks; kills += j.kills; if (cuenta(j) && !fuera(j)) n++; }
        int neq = 0;
        for (Equipo e : equipos) if (!equipoFuera(e)) neq++;
        v.put("muertes", miles(muertesTOD4));
        v.put("totems", miles(totemsTOD4));
        v.put("revividos", miles(revividosTOD4));
        v.put("horas", miles(ticks / 72000));
        v.put("mobs", miles(kills));
        v.put("jugadores", String.valueOf(n));
        v.put("equipos", String.valueOf(neq));
        v.put("meteoritos", String.valueOf(meteoritos));
        v.put("cambios", String.valueOf(cambios));
        return v;
    }

    static String miles(long n) { return String.format(Locale.GERMANY, "%,d", n); }

    /** El record: [nombre, valor] o null. */
    String[] record(String stat, boolean menor, double minHoras) {
        Jugador mejor = null;
        for (Jugador j : jugadores.values()) {
            if (fuera(j) || !cuenta(j) || j.horas() < minHoras) continue;
            if (mejor == null || (menor ? j.stat(stat) < mejor.stat(stat) : j.stat(stat) > mejor.stat(stat))) mejor = j;
        }
        if (mejor == null) return null;
        double v = mejor.stat(stat);
        String val = switch (stat) {
            case "horas" -> String.format(Locale.ROOT, "%.0f h", v);
            case "caminado" -> String.format(Locale.ROOT, "%.0f km", v);
            case "muertes" -> (long) v + ((long) v == 1 ? " muerte" : " muertes");
            case "totems" -> miles((long) v) + " tótems";
            case "saltos" -> miles((long) v) + " saltos";
            case "picado" -> miles((long) v) + " bloques";
            case "kills" -> miles((long) v) + " mobs";
            default -> miles((long) v);
        };
        return new String[]{mejor.nombre, val};
    }
}
