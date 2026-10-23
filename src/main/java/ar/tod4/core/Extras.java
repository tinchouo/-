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
        Bukkit.getScheduler().runTaskTimer(pl, this::actualizarHolos, 40L, 100L);   // cada 5 s
    }

    // ================================================================= hologramas de muertes

    private String textoHolo(String tipo) {
        int n = tipo.equals("hoy") ? muertesHoy() : muertesTotal();
        String def = tipo.equals("hoy") ? "&c☠ &fMuertes de hoy: &c{n}" : "&4☠ &fMuertes totales: &4{n}";
        return pl.getConfig().getString("muertes.holograma_" + tipo, def).replace("{n}", String.valueOf(n));
    }

    private void actualizarHolos() {
        for (String tipo : new String[]{"hoy", "total"}) {
            java.util.List<String> ids = pl.datos().y().getStringList("holos." + tipo);
            if (ids.isEmpty()) continue;
            net.kyori.adventure.text.Component t = ar.tod4.util.Txt.c(textoHolo(tipo));
            for (String id : ids) {
                try {
                    org.bukkit.entity.Entity en = Bukkit.getEntity(java.util.UUID.fromString(id));
                    if (en instanceof org.bukkit.entity.TextDisplay td && !t.equals(td.text())) td.text(t);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    /** /tod4 holo <hoy|total|borrar>: un cartel flotante que se actualiza solo, donde estas parado. */
    public void cmdHolo(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { Style.err(s, "Solo en el juego."); return; }
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        if (sub.equals("borrar")) {
            org.bukkit.entity.Entity mejor = null;
            double d = 9;
            for (org.bukkit.entity.Entity en : p.getNearbyEntities(3, 3, 3)) {
                if (!(en instanceof org.bukkit.entity.TextDisplay) || !en.getScoreboardTags().contains("tod4_holo")) continue;
                double x = en.getLocation().distanceSquared(p.getLocation());
                if (x < d) { d = x; mejor = en; }
            }
            if (mejor == null) { Style.err(s, "No hay ningún cartel de muertes a menos de 3 bloques."); return; }
            for (String tipo : new String[]{"hoy", "total"}) {
                java.util.List<String> ids = new ArrayList<>(pl.datos().y().getStringList("holos." + tipo));
                if (ids.remove(mejor.getUniqueId().toString())) pl.datos().set("holos." + tipo, ids);
            }
            mejor.remove();
            Style.ok(s, "Cartel borrado.");
            return;
        }
        if (!sub.equals("hoy") && !sub.equals("total")) { Style.err(s, "Uso: &e/tod4 holo <hoy|total|borrar>"); return; }
        org.bukkit.Location l = p.getLocation().getBlock().getLocation().add(0.5, 1.5, 0.5);
        org.bukkit.entity.TextDisplay td = p.getWorld().spawn(l, org.bukkit.entity.TextDisplay.class, t -> {
            t.text(ar.tod4.util.Txt.c(textoHolo(sub)));
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(true);
            t.setPersistent(true);
            t.addScoreboardTag("tod4_holo");
        });
        java.util.List<String> ids = new ArrayList<>(pl.datos().y().getStringList("holos." + sub));
        ids.add(td.getUniqueId().toString());
        pl.datos().set("holos." + sub, ids);
        Style.ok(s, "Cartel de muertes (" + sub + ") puesto. Se actualiza solo. Para sacarlo: &e/tod4 holo borrar");
    }

    // ================================================================= muertes

    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        // las muertes dentro del gulag no cuentan (ya estaban muertos)
        if (ar.tod4.hook.Gulag.muereEnGulag(e.getEntity().getUniqueId())) return;
        // los admins (OP) y los cuyes tampoco
        if (e.getEntity().isOp() || ar.tod4.hook.Cuyes.activo(e.getEntity())) return;
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

    // ---- revividos y totems (misma lista)

    /** Suma 1 al contador del dia de hoy: "muertes", "revividos" o "totems". */
    public void contar(String tipo) {
        String k = tipo + "." + pl.dia();
        pl.datos().set(k, pl.datos().y().getInt(k, 0) + 1);
    }

    public int del(String tipo, int dia) { return pl.datos().y().getInt(tipo + "." + dia, 0); }

    public int total(String tipo) {
        ConfigurationSection s = pl.datos().y().getConfigurationSection(tipo);
        int t = 0;
        if (s != null) for (String k : s.getKeys(false)) t += s.getInt(k, 0);
        return t;
    }

    /** Totems: no cuentan los OP, los cuyes ni el gulag. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void totem(org.bukkit.event.entity.EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (p.isOp() || ar.tod4.hook.Cuyes.activo(p) || (ar.tod4.hook.Gulag.activo() && ar.tod4.hook.Gulag.participa(p.getUniqueId()))) return;
        contar("totems");
    }

    private static final String[] TIPOS = {"muertes", "revividos", "totems"};

    /** /tod4 muertes  |  /tod4 muertes poner <dia> <cantidad> [muertes|revividos|totems] */
    public void cmdMuertes(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("poner")) {
            String tipo = a.length >= 5 ? a[4].toLowerCase() : "muertes";
            try {
                if (a.length < 4 || !java.util.Arrays.asList(TIPOS).contains(tipo)) throw new NumberFormatException();
                int d = Integer.parseInt(a[2]), n = Integer.parseInt(a[3]);
                if (d < 1 || n < 0) throw new NumberFormatException();
                pl.datos().set(tipo + "." + d, n);
                Style.ok(s, "Día &f" + d + "&a, " + tipo + ": &f" + n);
            } catch (NumberFormatException e) { Style.err(s, "Uso: &e/tod4 muertes poner <día> <cantidad> [muertes|revividos|totems]"); }
            return;
        }
        java.util.TreeSet<Integer> dias = new java.util.TreeSet<>();
        for (String t : TIPOS) {
            ConfigurationSection sec = pl.datos().y().getConfigurationSection(t);
            if (sec != null) for (String k : sec.getKeys(false)) {
                try { dias.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
            }
        }
        Style.raw(s, "&8&m-----------&r &c&lMuertes &8· &e&lRevividos &8· &6&lTótems &8&m-----------");
        if (dias.isEmpty()) Style.raw(s, "&7Todavía no hay nada anotado.");
        for (int d : dias)
            Style.raw(s, " &8| &7Día " + d + ": &c☠ " + del("muertes", d) + " &8· &e✦ " + del("revividos", d)
                    + " &8· &6⚝ " + del("totems", d) + (d == pl.dia() ? " &8(hoy)" : ""));
        Style.raw(s, " &8| &fTotal: &c☠ " + total("muertes") + " &8· &e✦ " + total("revividos") + " &8· &6⚝ " + total("totems"));
        Style.raw(s, "&8Corregir: &e/tod4 muertes poner <día> <cantidad> [muertes|revividos|totems]");
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
