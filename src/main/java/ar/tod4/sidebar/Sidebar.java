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
 * Tab: [mision] [alma] nombre + corazones de vida.
 * Cada jugador tiene su propio scoreboard; por eso aca adentro se copian la vida y los teams del scoreboard principal.
 */
public final class Sidebar implements Listener {

    private final TOD4 pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> tabCache = new HashMap<>();
    private final Map<UUID, List<String>> lineasCache = new HashMap<>();
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
            if (sidebar) {
                Scoreboard sb = boards.get(p.getUniqueId());
                if (sb == null || p.getScoreboard() != sb) { attach(p); sb = boards.get(p.getUniqueId()); }
                if (sb != null) {
                    render(p, sb);
                    if (ticks % 2 == 0) vida(sb);
                    if (teams) copiarTeams(sb);
                }
            }
            if (ticks % 2 == 0) tab(p);
        }
    }

    // ================================================================= sidebar

    private void render(Player p, Scoreboard sb) {
        Objective o = sb.getObjective("tod4");
        if (o == null) return;
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
        ConfigurationSection c = pl.getConfig().getConfigurationSection("sidebar");
        List<String> out = new ArrayList<>();
        for (String l : c.getStringList("arriba")) {
            out.add(l.replace("{jugador}", p.getName())
                    .replace("{ping}", String.valueOf(p.getPing()))
                    .replace("{dia}", String.valueOf(pl.dia()))
                    .replace("{mundo}", mundo(pl, p.getWorld()))
                    .replace("{jugadores}", jugadores(pl)));
        }
        boolean truco = pl.momentos() != null && pl.momentos().enCurso();
        // durante el Momento Truco todo lo de arriba se pone rojo (la parte del momento tiene sus propios colores)
        if (truco) out.replaceAll(Sidebar::rojo);
        String sep = truco ? rojo(separador()) : separador();
        out.add(sep);
        List<String> ab = abajo(p);
        // la sidebar tiene 15 renglones como maximo: si no entra, se recorta lo de abajo
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
        Momentos mo = pl.momentos();
        if (mo != null && mo.enCurso()) {
            int r = mo.restante();
            out.add("&c■ &c&lMomento Truco &c☠");
            item(out, "&7Tiempo: &c" + String.format("%d:%02d", r / 60, r % 60));
            if (mo.participa(p)) item(out, mo.aSalvo(p) ? "&fA salvo ✔" : "&cEn peligro ✖");
            else item(out, "&eNo te afecta (" + (p.getGameMode() == org.bukkit.GameMode.SPECTATOR ? "espectador" : "creativo") + ")");
            return out;
        }
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

    /** [mision] [alma] nombre, igual que el tabulador.sk viejo. */
    private void tab(Player p) {
        if (!pl.getConfig().getBoolean("tab.activo", true)) return;
        String mision = pl.misiones() == null ? "" : pl.misiones().simboloTab(p);
        String alma = pl.revivir().tieneAlma(p.getName()) ? "&f✦ " : "&f✧ ";
        String nombre = mision + alma + "&f" + p.getName();
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

    /** Copia los teams del scoreboard principal (colores de nombre, prefijos de otros scripts, etc.). */
    @SuppressWarnings("deprecation")
    private void copiarTeams(Scoreboard dest) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> nombres = new HashSet<>();
        for (Team t : main.getTeams()) {
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
                for (String e : ent) if (!d.hasEntry(e)) d.addEntry(e);
                for (String e : new HashSet<>(d.getEntries())) if (!ent.contains(e)) d.removeEntry(e);
            } catch (Exception ignored) { }
        }
        for (Team d : new HashSet<>(dest.getTeams())) if (!nombres.contains(d.getName())) d.unregister();
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
