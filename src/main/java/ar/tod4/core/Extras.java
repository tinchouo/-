package ar.tod4.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import ar.tod4.TOD4;
import ar.tod4.sidebar.Sidebar;
import ar.tod4.util.Style;

/**
 * - Contador de muertes por dia (sidebar {muertes} {muertes_hoy}, placeholders, /tod4 muertes).
 * - Donde esta cada jugador (/tod4 donde y en el boton de portales del Momento Truco).
 * - Backrooms: las perlas no teletransportan (ni las que tira uno ni las de una camara de perlas de los companeros).
 */
public final class Extras implements Listener {
    private final TOD4 pl;

    public Extras(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    // ================================================================= muertes

    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        // las muertes dentro del gulag no cuentan (ya estaban muertos)
        if (ar.tod4.hook.Gulag.muereEnGulag(e.getEntity().getUniqueId())) return;
        String k = "muertes." + pl.dia();
        pl.datos().set(k, pl.datos().y().getInt(k, 0) + 1);
    }

    public int muertesDia(int dia) { return pl.datos().y().getInt("muertes." + dia, 0); }

    public int muertesHoy() { return muertesDia(pl.dia()); }

    public int muertesTotal() {
        ConfigurationSection s = pl.datos().y().getConfigurationSection("muertes");
        int t = 0;
        if (s != null) for (String k : s.getKeys(false)) t += s.getInt(k, 0);
        return t;
    }

    /** /tod4 muertes  |  /tod4 muertes poner <dia> <cantidad> */
    public void cmdMuertes(CommandSender s, String[] a) {
        if (a.length >= 4 && a[1].equalsIgnoreCase("poner")) {
            try {
                int d = Integer.parseInt(a[2]), n = Integer.parseInt(a[3]);
                if (d < 1 || n < 0) throw new NumberFormatException();
                pl.datos().set("muertes." + d, n);
                Style.ok(s, "Muertes del día &f" + d + "&a: &f" + n);
            } catch (NumberFormatException e) { Style.err(s, "Uso: &e/tod4 muertes poner <día> <cantidad>"); }
            return;
        }
        Map<Integer, Integer> por = new TreeMap<>();
        ConfigurationSection sec = pl.datos().y().getConfigurationSection("muertes");
        if (sec != null) for (String k : sec.getKeys(false)) {
            try { por.put(Integer.parseInt(k), sec.getInt(k, 0)); } catch (NumberFormatException ignored) { }
        }
        Style.raw(s, "&8&m--------------&r &c&lMuertes &8&m--------------");
        if (por.isEmpty()) Style.raw(s, "&7Todavía no hay muertes anotadas.");
        for (Map.Entry<Integer, Integer> en : por.entrySet())
            Style.raw(s, " &8| &7Día " + en.getKey() + ": &c" + en.getValue() + (en.getKey() == pl.dia() ? " &8(hoy)" : ""));
        Style.raw(s, " &8| &7Total: &c" + muertesTotal());
        Style.raw(s, "&8Para corregir un día: &e/tod4 muertes poner <día> <cantidad>");
    }

    // ================================================================= donde

    /** Jugadores por dimension (en orden: overworld, nether, end, el resto). Los espectadores no cuentan. */
    public Map<String, List<String>> porDimension() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<World> ws = new ArrayList<>(Bukkit.getWorlds());
        ws.sort((x, y) -> Integer.compare(x.getEnvironment().ordinal(), y.getEnvironment().ordinal()));
        for (World w : ws) {
            List<String> ns = new ArrayList<>();
            for (Player p : w.getPlayers()) if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) ns.add(p.getName());
            if (!ns.isEmpty()) out.computeIfAbsent(Sidebar.mundo(pl, w), k -> new ArrayList<>()).addAll(ns);
        }
        return out;
    }

    /** Para el boton de portales: "&cNether&7: 3 (Ana, Beto, Caro)" por cada dimension que no es el overworld. */
    public List<String> fueraDelOverworld() {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL && !w.getName().toLowerCase().contains("aether")
                    && !w.getName().toLowerCase().contains("backrooms")) continue;
            List<String> ns = new ArrayList<>();
            for (Player p : w.getPlayers()) if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) ns.add(p.getName());
            if (ns.isEmpty()) continue;
            String lista = ns.size() <= 4 ? String.join(", ", ns) : String.join(", ", ns.subList(0, 4)) + "...";
            out.add(Sidebar.mundo(pl, w) + "&7: &f" + ns.size() + " &8(" + lista + ")");
        }
        return out;
    }

    public void cmdDonde(CommandSender s) {
        Style.raw(s, "&8&m--------------&r &e&lDónde están &8&m--------------");
        Map<String, List<String>> m = porDimension();
        if (m.isEmpty()) Style.raw(s, "&7No hay nadie conectado.");
        for (Map.Entry<String, List<String>> en : m.entrySet())
            Style.raw(s, " " + en.getKey() + " &8(" + en.getValue().size() + ")&7: &f" + String.join("&7, &f", en.getValue()));
    }

    // ================================================================= backrooms sin perlas

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void perla(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        String br = pl.getConfig().getString("mobs.mundo_backrooms", "backrooms");
        if (e.getFrom().getWorld() != null && e.getFrom().getWorld().getName().equalsIgnoreCase(br)) e.setCancelled(true);
    }
}
