package ar.tod4.sidebar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import ar.tod4.TOD4;
import ar.tod4.hook.SkriptVars;
import ar.tod4.util.Txt;
import io.papermc.paper.scoreboard.numbers.NumberFormat;

/**
 * Sidebar de TOD4 con prioridades:
 *  1) Momento Truco activo -> la parte de abajo es solo del momento.
 *  2) Misiones (diaria / opcional) con su estado en color.
 *  3) Nada -> frase de calma al azar.
 * Mientras las misiones y el Momento Truco sigan en Skript, se leen sus variables.
 */
public final class Sidebar implements Listener {

    private final TOD4 pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Random rnd = new Random();
    private String calma = null;
    private long calmaHasta = 0;

    private static final String[] ENTRIES;
    static {
        ENTRIES = new String[15];
        String hex = "0123456789abcdef";
        for (int i = 0; i < 15; i++) ENTRIES[i] = "§" + hex.charAt(i) + "§r";
    }

    public Sidebar(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
    }

    private boolean enabled() { return pl.getConfig().getBoolean("sidebar.activa", true); }

    @EventHandler
    public void join(PlayerJoinEvent e) { if (enabled()) attach(e.getPlayer()); }

    @EventHandler
    public void quit(PlayerQuitEvent e) { boards.remove(e.getPlayer().getUniqueId()); }

    private void attach(Player p) {
        if (!enabled()) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("tod4", Criteria.DUMMY, Txt.c(pl.getConfig().getString("sidebar.titulo", "&a&lTOD4")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        render(p, sb);
    }

    private void tick() {
        if (!enabled()) return;
        if (System.currentTimeMillis() > calmaHasta) {
            List<String> f = pl.getConfig().getStringList("sidebar.frases_calma");
            calma = f.isEmpty() ? "&7Todo tranquilo..." : f.get(rnd.nextInt(f.size()));
            calmaHasta = System.currentTimeMillis() + pl.getConfig().getLong("sidebar.cambio_frase_segundos", 60) * 1000L;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = boards.get(p.getUniqueId());
            if (sb == null || p.getScoreboard() != sb) { attach(p); continue; }
            render(p, sb);
        }
    }

    private void render(Player p, Scoreboard sb) {
        Objective o = sb.getObjective("tod4");
        if (o == null) return;
        List<String> lines = build(p);
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
        out.addAll(abajo(p));
        out.add(c.getString("linea_final", "&8&m                              "));
        return out;
    }

    /** La parte de abajo segun la prioridad. */
    private List<String> abajo(Player p) {
        List<String> out = new ArrayList<>();
        String n = p.getName();
        if (SkriptVars.isTrue("momentotruco.activo")) {
            out.add("&c■ &f&lMomento Truco");
            boolean salvado = SkriptVars.isTrue("momentotruco.salvado::" + p.getUniqueId());
            out.add(salvado ? " &8| &a✔ A salvo" : " &8| &c✖ En peligro");
            out.add("");
            return out;
        }
        boolean diaria = SkriptVars.isSet("mision::activa");
        boolean opcional = SkriptVars.isSet("mision_op::activa");
        if (diaria) {
            boolean recl = SkriptVars.isTrue("p_reclamo::dia::" + n);
            out.add("&a■ &f&lMisión diaria");
            out.add(" &8| " + (recl ? "&e✔ Reclamada ✔" : "&7En curso"));
            if (opcional) {
                boolean reclOp = SkriptVars.isTrue("p_reclamo::op::" + n);
                out.add("&b■ &f&lMisión opcional");
                if (!recl) out.add(" &8| &c🔒 Bloqueada");
                else out.add(" &8| " + (reclOp ? "&e✔ Reclamada ✔" : "&7En curso"));
            }
            out.add("");
            return out;
        }
        if (calma != null) {
            out.add(" " + calma);
            out.add("");
        }
        return out;
    }

    // ------------------------------------------------------------------ utilidades publicas

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
