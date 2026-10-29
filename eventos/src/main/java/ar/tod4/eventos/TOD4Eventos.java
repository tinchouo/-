package ar.tod4.eventos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Plugin de eventos de TOD4 (separado del principal).
 *  - Ofrendas: un cofre que acepta un item (por ejemplo almas de Oraxen); al juntar la cantidad, se borra una "puerta".
 *    Boss bar para todos con el progreso.
 *  - Lava: sube por capas dentro de un volcan, llenando solo el aire conectado con el interior (se adapta a la forma).
 *  - Invasion Goblin: raid con mobs de MythicMobs (barra azul como las raids de Minecraft).
 *  - Agujero del Infierno 2.0: una cueva que se derrumba al ritmo de una cancion (ver Agujero).
 * Comandos solo con prefijo: /tod4eventos:ofrenda, /tod4eventos:lava y /tod4eventos:goblins.
 */
public final class TOD4Eventos extends JavaPlugin {

    public static final String PREFIX = "&8[&6&lTOD&e&l4 &c&lEventos&8]&r ";
    private final Map<UUID, Location[]> sel = new HashMap<>();
    private Ofrendas ofrendas;
    private Lava lava;
    private Goblins goblins;
    private Agujero agujero;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        ofrendas = new Ofrendas(this);
        lava = new Lava(this);
        goblins = new Goblins(this);
        agujero = new Agujero(this);
        for (String c : List.of("ofrenda", "lava", "goblins", "agujero")) if (getCommand(c) != null) getCommand(c).setTabCompleter(this);
        // los comandos de admin solo existen con prefijo (/tod4eventos:ofrenda)
        Bukkit.getScheduler().runTask(this, () -> {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            for (String k : List.of("ofrenda", "lava", "goblins", "agujero")) {
                Command c = known.get(k);
                if (c instanceof org.bukkit.command.PluginCommand pc && pc.getPlugin() == this) known.remove(k);
            }
            for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        });
    }

    @Override
    public void onDisable() {
        if (ofrendas != null) ofrendas.apagar();
        if (lava != null) lava.apagar();
        if (goblins != null) goblins.apagar();
        if (agujero != null) agujero.apagar();
    }

    // ================================================================= utilidades

    public static Component c(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s); }
    public static void ok(CommandSender s, String m) { s.sendMessage(c(PREFIX + "&a" + m)); }
    public static void err(CommandSender s, String m) { s.sendMessage(c(PREFIX + "&c" + m)); }
    public static void info(CommandSender s, String m) { s.sendMessage(c(PREFIX + "&7" + m)); }
    public static void raw(CommandSender s, String m) { s.sendMessage(c(m)); }

    /** El bloque que mira el jugador (hasta 8 bloques), o el de sus pies. */
    public static Block mirando(Player p) {
        Block b = p.getTargetBlockExact(8);
        return b != null ? b : p.getLocation().getBlock();
    }

    /** pos1 / pos2: el bloque que estas mirando. */
    boolean marcar(Player p, int i) {
        Block b = mirando(p);
        sel.computeIfAbsent(p.getUniqueId(), k -> new Location[2])[i] = b.getLocation();
        ok(p, "Punto " + (i + 1) + " marcado en &f" + b.getX() + " " + b.getY() + " " + b.getZ() + "&a.");
        return true;
    }

    /** La seleccion (pos1, pos2) o null con el error dicho. */
    Location[] seleccion(Player p) {
        Location[] s = sel.get(p.getUniqueId());
        if (s == null || s[0] == null || s[1] == null) { err(p, "Primero marca &fpos1 &cy &fpos2 &c(mirando cada esquina)."); return null; }
        if (!s[0].getWorld().equals(s[1].getWorld())) { err(p, "Los dos puntos tienen que estar en el mismo mundo."); return null; }
        return s;
    }

    // ---- items: "oraxen:<id>" o "mc:<MATERIAL>"

    private static Method OX_ID, OX_GET;
    private static boolean oxProbado;

    private static void ox() {
        if (oxProbado) return;
        oxProbado = true;
        try {
            Class<?> c = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            OX_ID = c.getMethod("getIdByItem", ItemStack.class);
            OX_GET = c.getMethod("getItemById", String.class);
        } catch (Throwable ignored) { }
    }

    public static String idDe(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        ox();
        try {
            if (OX_ID != null) {
                Object id = OX_ID.invoke(null, it);
                if (id instanceof String s && !s.isEmpty()) return "oraxen:" + s;
            }
        } catch (Throwable ignored) { }
        return "mc:" + it.getType().name();
    }

    public static boolean es(ItemStack it, String id) { return id != null && id.equals(idDe(it)); }

    /** Nombre lindo para mostrar el item. */
    public static String nombre(String id) {
        if (id == null) return "?";
        if (id.startsWith("oraxen:")) {
            ox();
            try {
                Object b = OX_GET == null ? null : OX_GET.invoke(null, id.substring(7));
                if (b != null) {
                    ItemStack it = (ItemStack) b.getClass().getMethod("build").invoke(b);
                    if (it != null && it.hasItemMeta() && it.getItemMeta().hasDisplayName())
                        return LegacyComponentSerializer.legacyAmpersand().serialize(it.getItemMeta().displayName());
                }
            } catch (Throwable ignored) { }
            return id.substring(7);
        }
        return id.substring(3).toLowerCase().replace('_', ' ');
    }

    // ================================================================= comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("tod4eventos.admin")) { err(s, "No tienes permiso."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "ofrenda" -> ofrendas.comando(s, a);
            case "lava" -> lava.comando(s, a);
            case "goblins" -> goblins.comando(s, a);
            case "agujero" -> agujero.comando(s, a);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("tod4eventos.admin")) return out;
        switch (cmd.getName().toLowerCase()) {
            case "ofrenda" -> out.addAll(ofrendas.tab(a));
            case "lava" -> out.addAll(lava.tab(a));
            case "goblins" -> out.addAll(goblins.tab(a));
            case "agujero" -> out.addAll(agujero.tab(a));
            default -> { }
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
