package ar.tod4.sidebar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import ar.tod4.TOD4;
import ar.tod4.misiones.Misiones;
import ar.tod4.momento.Momentos;
import ar.tod4.util.Ancho;
import ar.tod4.util.Txt;
import io.papermc.paper.scoreboard.numbers.NumberFormat;

/**
 * Sidebar + tab de TOD4.
 * Sidebar (abajo de la linea, por prioridad): Momento Truco > misiones > frase de calma, y otra linea al final.
 * Durante el Momento Truco todo lo que tiene color (menos blanco y gris) se pone rojo; el titulo queda igual.
 * Tab: [mision] [alma] [EQUIPO] [L] nombre + corazones de vida. Arriba de la cabeza lo mismo sin los corazones
 * (un team propio por jugador en cada scoreboard: prefijo con mision, alma y equipo; el nombre en el color del equipo).
 * Cada jugador tiene su propio scoreboard; por eso aca adentro se copian la vida y los teams del scoreboard principal.
 */
public final class Sidebar implements Listener {

    private final TOD4 pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> tabCache = new HashMap<>();
    private final Map<UUID, List<String>> lineasCache = new HashMap<>();
    private final Map<UUID, String> tituloModo = new HashMap<>();   // normal | truco | gulag
    private final Random rnd = new Random();
    private String calma = null;
    private long calmaHasta = 0;
    private int ticks = 0;

    private static final String[] ENTRIES;
    static {
        ENTRIES = new String[15];
        String hex = "0123456789abcdef";
        for (int i = 0; i < 15; i++) ENTRIES[i] = "§" + hex.charAt(i) + "§r";
    }

    public Sidebar(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 10L, 10L);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
    }

    private boolean enabled() { return pl.getConfig().getBoolean("sidebar.activa", true); }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        tabCache.remove(e.getPlayer().getUniqueId());
        if (enabled()) attach(e.getPlayer());
        tab(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        tabCache.remove(e.getPlayer().getUniqueId());
        lineasCache.remove(e.getPlayer().getUniqueId());
        tituloModo.remove(e.getPlayer().getUniqueId());
    }

    private void attach(Player p) {
        if (!enabled()) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("tod4", Criteria.DUMMY, Txt.c(pl.getConfig().getString("sidebar.titulo", "&a&lTOD4")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        if (pl.getConfig().getBoolean("tab.vida", true)) {
            Objective v = sb.registerNewObjective("tod4_vida", Criteria.DUMMY, Txt.c("&cVida"), RenderType.HEARTS);
            v.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        }
        copiarTeams(sb);
        nametags(sb);
        boards.put(p.getUniqueId(), sb);
        lineasCache.remove(p.getUniqueId());
        p.setScoreboard(sb);
        render(p, sb);
    }

    private void tick() {
        ticks++;
        boolean sidebar = enabled();
        if (sidebar && System.currentTimeMillis() > calmaHasta) {
            List<String> f = pl.getConfig().getStringList("sidebar.frases_calma");
            calma = f.isEmpty() ? "&7Todo tranquilo..." : f.get(rnd.nextInt(f.size()));
            calmaHasta = System.currentTimeMillis() + pl.getConfig().getLong("sidebar.cambio_frase_segundos", 60) * 1000L;
        }
        boolean teams = ticks % 10 == 0; // cada 5 segundos
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (ar.tod4.video.Video.viendo(p)) continue; // mirando un video: sin sidebar
            if (sidebar) {
                Scoreboard sb = boards.get(p.getUniqueId());
                if (sb == null || p.getScoreboard() != sb) { attach(p); sb = boards.get(p.getUniqueId()); }
                if (sb != null) {
                    render(p, sb);
                    if (teams) copiarTeams(sb);
                    if (ticks % 2 == 0) { vida(sb); nametags(sb); }
                }
            }
            if (ticks % 2 == 0) tab(p);
        }
    }

    // ================================================================= sidebar

    /** TRUCOFDEATH en degrade (como el verde de siempre) con el logo del color a cada lado. */
    private String titulo(String modo) {
        if (modo.equals("normal")) return pl.getConfig().getString("sidebar.titulo", "&a&lTOD4");
        boolean rojo = modo.equals("truco");
        String logo = pl.getConfig().getString(rojo ? "sidebar.logo_rojo" : "sidebar.logo_azul", rojo ? "\ue905" : "\ue906");
        int[] a = rojo ? new int[]{0xff, 0x55, 0x55} : new int[]{0x55, 0xaa, 0xff};
        int[] b = rojo ? new int[]{0x78, 0x11, 0x11} : new int[]{0x11, 0x33, 0x88};
        String txt = "TRUCOFDEATH";
        StringBuilder sb = new StringBuilder("&f" + logo + "&r ");
        for (int i = 0; i < txt.length(); i++) {
            double t = i / (double) (txt.length() - 1);
            int r = (int) Math.round(a[0] + (b[0] - a[0]) * t), g = (int) Math.round(a[1] + (b[1] - a[1]) * t), bl = (int) Math.round(a[2] + (b[2] - a[2]) * t);
            sb.append(String.format("&#%02x%02x%02x&l", r, g, bl)).append(txt.charAt(i));
        }
        sb.append(" &f&l4 &f").append(logo).append("&r");
        return sb.toString();
    }

    private void render(Player p, Scoreboard sb) {
        Objective o = sb.getObjective("tod4");
        if (o == null) return;
        // el titulo es el mismo TRUCOFDEATH 4 con su logo, pero en rojo durante el Momento Truco y en azul en el gulag
        String modo = pl.momentos() != null && pl.momentos().enCurso() ? "truco" : ar.tod4.hook.Gulag.activo() ? "gulag" : "normal";
        if (!modo.equals(tituloModo.get(p.getUniqueId()))) {
            o.displayName(Txt.c(titulo(modo)));
            tituloModo.put(p.getUniqueId(), modo);
        }
        List<String> lines = build(p);
        if (lines.equals(lineasCache.get(p.getUniqueId()))) return;
        lineasCache.put(p.getUniqueId(), lines);
        int n = Math.min(lines.size(), 15);
        for (int i = 0; i < 15; i++) {
            String entry = ENTRIES[i];
            if (i < n) {
                Score s = o.getScore(entry);
                s.setScore(n - i);
                s.customName(Txt.c(lines.get(i)));
            } else {
                sb.resetScores(entry);
            }
        }
    }

    private String separador() {
        return pl.getConfig().getString("sidebar.separador", "&8&m                                  ");
    }

    private int anchoMax() { return Ancho.de(separador()); }

    private List<String> build(Player p) {
        // Momento Truco: sidebar nueva que reemplaza todo (sin perfil, mundo ni misiones)
        if (pl.momentos() != null && pl.momentos().enCurso()) return pl.momentos().sidebar(p, separador(), anchoMax());
        // Gulag (script gulag.sk): su propia sidebar en azul
        if (ar.tod4.hook.Gulag.activo()) return ar.tod4.hook.Gulag.sidebar(p, separador(), anchoMax());
        ConfigurationSection c = pl.getConfig().getConfigurationSection("sidebar");
        List<String> out = new ArrayList<>();
        String equipo = pl.equipos() == null ? null : pl.equipos().sidebar(p.getUniqueId());
        for (String l : c.getStringList("arriba")) {
            if (l.contains("{equipo}") && equipo == null) continue;
            if (l.trim().equals("{separador}")) { out.add(separador()); continue; }
            out.add(l.replace("{jugador}", p.getName())
                    .replace("{equipo}", equipo == null ? "" : equipo)
                    .replace("{ping}", String.valueOf(p.getPing()))
                    .replace("{dia}", String.valueOf(pl.dia()))
                    .replace("{mundo}", mundo(pl, p.getWorld()))
                    .replace("{jugadores}", jugadores(pl))
                    .replace("{muertes_hoy}", pl.extras() == null ? "0" : String.valueOf(pl.extras().muertesHoy()))
                    .replace("{muertes}", pl.extras() == null ? "0" : String.valueOf(pl.extras().muertesTotal())));
        }
        String sep = separador();
        out.add(sep);
        List<String> ab = abajo(p);
        // la sidebar tiene 15 renglones como maximo: si no entra, primero se sacan los renglones vacios de arriba,
        // despues el separador de arriba de todo (queda pegado al titulo) y recien ahi se recorta lo de abajo
        while (out.size() + ab.size() + 1 > 15) {
            int vacio = -1;
            for (int i = 0; i < out.size() - 1; i++) if (Txt.norm(out.get(i)).replaceAll("&.", "").isBlank() && !out.get(i).equals(sep)) { vacio = i; break; }
            if (vacio >= 0) { out.remove(vacio); continue; }
            if (out.size() > 1 && out.get(0).equals(sep)) { out.remove(0); continue; }
            break;
        }
        while (out.size() + ab.size() + 1 > 15 && !ab.isEmpty()) ab.remove(ab.size() - 1);
        out.addAll(ab);
        out.add(sep);
        return out;
    }

    /** Un renglon con " | " adelante, cortado si es muy largo. */
    private void item(List<String> out, String texto) {
        out.addAll(Ancho.cortar(texto, " &8| ", " &8| ", anchoMax()));
    }

    /** La parte de abajo segun la prioridad: Momento Truco > misiones > frase de calma. */
    private List<String> abajo(Player p) {
        List<String> out = new ArrayList<>();
        Misiones ms = pl.misiones();
        boolean dia = ms != null && ms.activa(Misiones.DIA) != null;
        boolean op = ms != null && ms.activa(Misiones.OP) != null;
        if (dia || op) {
            int anim = ticks % 4;
            if (dia) {
                out.add("&a■ &f&lMisión diaria");
                item(out, ms.textoEstado(ms.estado(Misiones.DIA, p), anim));
            }
            if (op) {
                out.add("&b■ &f&lMisión opcional");
                item(out, ms.textoEstado(ms.estado(Misiones.OP, p), anim));
            }
            return out;
        }
        if (calma != null) out.addAll(Ancho.cortar(calma, " ", " ", anchoMax()));
        return out;
    }

    private static final Pattern HEX = Pattern.compile("&#[0-9a-fA-F]{6}");
    private static final Pattern COLOR = Pattern.compile("&[0-69a-eA-E]");

    /** Todo lo que tenga color (menos blanco, gris y gris oscuro) pasa a rojo. */
    public static String rojo(String s) {
        s = Txt.norm(s);
        s = HEX.matcher(s).replaceAll("&c");
        return COLOR.matcher(s).replaceAll("&c");
    }

    // ================================================================= tab

    /** [mision] [alma] (sin el nombre). */
    private String simbolos(Player p) {
        String mision = pl.misiones() == null ? "" : pl.misiones().simboloTab(p);
        String alma = pl.revivir().tieneAlma(p.getName()) ? "&f✦ " : "&f✧ ";
        return mision + alma;
    }

    /** "&a[ABC] [L] " o "" y el color del nombre ("&a" o "&f"). */
    private String[] equipo(Player p) {
        if (pl.equipos() == null) return new String[]{"", "&f"};
        String pre = pl.equipos().prefijo(p.getUniqueId());
        return new String[]{pre, pre.isEmpty() ? "&f" : pre.substring(0, 2)};
    }

    /** [mision] [alma] [EQUIPO] [L] nombre (lo del equipo en su color). */
    private void tab(Player p) {
        if (!pl.getConfig().getBoolean("tab.activo", true)) return;
        String[] eq = equipo(p);
        String nombre = simbolos(p) + eq[0] + eq[1] + p.getName();
        if (nombre.equals(tabCache.get(p.getUniqueId()))) return;
        tabCache.put(p.getUniqueId(), nombre);
        p.playerListName(Txt.c(nombre));
    }

    /** Corazones del tab (como /scoreboard objectives add vida health + setdisplay list). */
    private void vida(Scoreboard sb) {
        Objective v = sb.getObjective("tod4_vida");
        if (v == null) return;
        for (Player t : Bukkit.getOnlinePlayers()) {
            int hp = t.isDead() ? 0 : (int) Math.ceil(t.getHealth());
            Score s = v.getScore(t.getName());
            if (!s.isScoreSet() || s.getScore() != hp) s.setScore(hp);
        }
    }

    /**
     * Arriba de la cabeza: lo mismo que en el tab sin los corazones. Cada jugador conectado va en un team propio
     * ("tod4n_<equipo>_<nombre>", asi el tab queda ordenado por equipo) con el prefijo y el color del nombre.
     */
    private void nametags(Scoreboard sb) {
        if (!pl.getConfig().getBoolean("tab.activo", true)) return;
        Set<String> usados = new HashSet<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            String[] eq = equipo(t);
            String orden = pl.equipos() == null ? "zz" : pl.equipos().orden(t.getUniqueId());
            String nombre = (NT + orden + "_" + t.getName().toLowerCase());
            usados.add(nombre);
            Team tm = sb.getTeam(nombre);
            if (tm == null) tm = sb.registerNewTeam(nombre);
            net.kyori.adventure.text.Component pre = Txt.c(simbolos(t) + eq[0]);
            if (!pre.equals(tm.prefix())) tm.prefix(pre);
            net.kyori.adventure.text.format.NamedTextColor col = pl.equipos() == null ? null : pl.equipos().color(t.getUniqueId());
            if (col == null) col = net.kyori.adventure.text.format.NamedTextColor.WHITE;
            // gulag: el brillo del que tiene la llave es amarillo mientras es inmune y blanco despues
            String llave = ar.tod4.hook.Gulag.llave(t.getUniqueId());
            if ("inmune".equals(llave)) col = net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            else if ("si".equals(llave)) col = net.kyori.adventure.text.format.NamedTextColor.WHITE;
            if (!tm.hasColor() || !col.equals(tm.color())) tm.color(col);
            if (!tm.hasEntry(t.getName())) tm.addEntry(t.getName());
        }
        for (Team tm : new HashSet<>(sb.getTeams()))
            if (tm.getName().startsWith(NT) && !usados.contains(tm.getName())) tm.unregister();
    }

    private static final String NT = "tod4n_";

    /** Copia los teams del scoreboard principal (colores de nombre, prefijos de otros scripts, etc.). */
    @SuppressWarnings("deprecation")
    private void copiarTeams(Scoreboard dest) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> nombres = new HashSet<>();
        Set<String> conectados = new HashSet<>();
        if (pl.getConfig().getBoolean("tab.activo", true)) for (Player p : Bukkit.getOnlinePlayers()) conectados.add(p.getName());
        for (Team t : main.getTeams()) {
            if (t.getName().startsWith(NT)) continue;
            nombres.add(t.getName());
            Team d = dest.getTeam(t.getName());
            if (d == null) d = dest.registerNewTeam(t.getName());
            try {
                d.displayName(t.displayName());
                d.prefix(t.prefix());
                d.suffix(t.suffix());
                if (t.hasColor() && t.color() instanceof net.kyori.adventure.text.format.NamedTextColor nc) d.color(nc);
                d.setAllowFriendlyFire(t.allowFriendlyFire());
                d.setCanSeeFriendlyInvisibles(t.canSeeFriendlyInvisibles());
                for (Team.Option op : Team.Option.values()) d.setOption(op, t.getOption(op));
                Set<String> ent = t.getEntries();
                // los jugadores conectados van en su team propio (nametags): no se copian aca
                for (String e : ent) if (!conectados.contains(e) && !d.hasEntry(e)) d.addEntry(e);
                for (String e : new HashSet<>(d.getEntries())) if (!ent.contains(e) || conectados.contains(e)) d.removeEntry(e);
            } catch (Exception ignored) { }
        }
        for (Team d : new HashSet<>(dest.getTeams())) if (!d.getName().startsWith(NT) && !nombres.contains(d.getName())) d.unregister();
    }

    // ================================================================== utilidades publicas

    public static String jugadores(TOD4 pl) {
        return Bukkit.getOnlinePlayers().size() + "&8/&7" + pl.getConfig().getInt("sidebar.jugadores_max", 100);
    }

    public static String mundo(TOD4 pl, World w) {
        ConfigurationSection m = pl.getConfig().getConfigurationSection("mundos");
        String name = w.getName().toLowerCase();
        String key = w.getKey().toString().toLowerCase();
        if (m != null) {
            ConfigurationSection custom = m.getConfigurationSection("por_nombre");
            if (custom != null) {
                for (String k : custom.getKeys(false)) {
                    if (name.contains(k.toLowerCase()) || key.contains(k.toLowerCase())) return custom.getString(k);
                }
            }
        }
        switch (w.getEnvironment()) {
            case NETHER: return m != null ? m.getString("nether", "&cNether") : "&cNether";
            case THE_END: return m != null ? m.getString("end", "&dEnd") : "&dEnd";
            default: return m != null ? m.getString("overworld", "&aOverworld") : "&aOverworld";
        }
    }
}
