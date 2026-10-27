package ar.tod4.aether;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import ar.tod4.aether.util.Style;

/**
 * TOD4Aether: el Aether como plugin aparte (antes estaba dentro de TOD4).
 * Los comandos siguen siendo /tod4:aether_... (solo admins).
 * Datos en plugins/TOD4Aether (la primera vez copia el mapa y aether.yml de plugins/TOD4).
 */
public final class TOD4Aether extends JavaPlugin {

    static final String[] COMANDOS = {"aether_pocion", "aether_init", "aether_reset", "aether_tp", "aether_locate", "aether_goto", "aether_fill",
            "aether_test", "aether_force", "aether_music", "aether_caida", "aether_loot", "aether_mobs", "aether_casco", "aether_aire", "aether_recargar"};

    private Aether aether;
    private Loot loot;
    private Mobs mobs;
    private Aire aire;
    private YamlConfiguration datos;
    private File datosF;

    @Override
    public void onEnable() {
        migrar();
        datosF = new File(getDataFolder(), "datos.yml");
        datos = YamlConfiguration.loadConfiguration(datosF);
        aether = new Aether(this);
        loot = new Loot(this);
        mobs = new Mobs(this, aether);
        aire = new Aire(this, aether);
        for (String c : COMANDOS) Bukkit.getCommandMap().register("tod4", new Cmd(c));
        // como en TOD4: solo existen con el prefijo (/tod4:aether_...)
        Bukkit.getScheduler().runTask(this, () -> {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            for (String c : COMANDOS) if (known.get(c) instanceof Cmd) known.remove(c);
            for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        });
        getLogger().info("TOD4Aether listo.");
    }

    @Override
    public void onDisable() {
        if (aether != null) aether.apagar();
        if (mobs != null) mobs.apagar();
    }

    /** La primera vez: el mapa ya generado y la config del Aether estaban en plugins/TOD4. */
    private void migrar() {
        getDataFolder().mkdirs();
        File viejo = new File(getDataFolder().getParentFile(), "TOD4");
        for (String n : new String[]{"aether_estado.json.gz", "aether.yml"}) {
            File de = new File(viejo, n), a = new File(getDataFolder(), n);
            if (a.exists() || !de.exists()) continue;
            try {
                Files.copy(de.toPath(), a.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                getLogger().info("Copiado " + n + " desde plugins/TOD4 (el mapa del Aether sigue igual).");
            } catch (IOException ex) {
                getLogger().warning("No pude copiar " + n + " de plugins/TOD4: " + ex.getMessage());
            }
        }
    }

    // ================================================================= punto de caida (al volver del Aether)

    /** Donde caes al volver del Aether (null = sobre el spawn del Overworld). */
    Location caida(World ow) {
        if (!datos.isSet("caida.x")) return null;
        return new Location(ow, datos.getDouble("caida.x"), datos.getDouble("caida.y"), datos.getDouble("caida.z"), (float) datos.getDouble("caida.yaw"), 0f);
    }

    private void guardarDatos() {
        try { datos.save(datosF); } catch (IOException ex) { getLogger().warning("No pude guardar datos.yml: " + ex.getMessage()); }
    }

    // ================================================================= TOD4 (cambios del dia)

    private Object cambios;
    private Method on;

    /** Si un cambio de /tod4:cambios esta prendido (sin TOD4: false). */
    boolean cambio(String id) {
        try {
            if (on == null) {
                Plugin t = Bukkit.getPluginManager().getPlugin("TOD4");
                if (t == null || !t.isEnabled()) return false;
                cambios = t.getClass().getMethod("cambios").invoke(t);
                on = cambios.getClass().getMethod("on", String.class);
            }
            return (Boolean) on.invoke(cambios, id);
        } catch (Throwable ex) {
            on = null;
            return false;
        }
    }

    // ================================================================= comandos

    private final class Cmd extends Command {
        Cmd(String n) {
            super(n);
            setPermission("tod4.admin");
            setDescription("Aether (TOD4Aether)");
        }

        @Override
        public boolean execute(CommandSender s, String label, String[] a) {
            if (!s.hasPermission("tod4.admin")) { Style.err(s, "No tienes permiso."); return true; }
            String c = getName();
            switch (c) {
                case "aether_caida" -> caidaCmd(s, a);
                case "aether_loot" -> loot.comando(s, a);
                case "aether_mobs" -> mobs.comando(s, a);
                case "aether_casco", "aether_aire" -> aire.comando(s, c, a);
                case "aether_recargar" -> {
                    aether.recargar();
                    loot.recargar();
                    mobs.recargar();
                    aire.recargar();
                    Style.ok(s, "Aether recargado (aether.yml, loot.yml, mobs.yml y aire.yml).");
                }
                default -> aether.comando(s, c, a);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender s, String alias, String[] a) {
            List<String> out = new ArrayList<>();
            if (!s.hasPermission("tod4.admin")) return out;
            String c = getName();
            if (a.length == 1) {
                switch (c) {
                    case "aether_pocion", "aether_casco" -> { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
                    case "aether_locate", "aether_goto", "aether_force" -> {
                        out.addAll(aether.nombres());
                        if (c.equals("aether_goto")) out.add("spawn");
                    }
                    case "aether_reset" -> out.addAll(List.of("clear", "forget", "status"));
                    case "aether_music" -> out.add("stop");
                    case "aether_caida" -> out.addAll(List.of("poner", "ver", "borrar"));
                    case "aether_loot" -> out.addAll(List.of("guardar", "probar", "ver"));
                    case "aether_mobs" -> out.addAll(List.of("on", "off", "estado", "lugares", "probar"));
                    case "aether_aire" -> out.addAll(List.of("estado", "vaciar", "llenar"));
                    default -> { }
                }
            } else if (a.length == 2 && c.equals("aether_loot") && !a[0].equalsIgnoreCase("guardar")) {
                out.addAll(List.of("tier1", "tier2", "tier3", "tier4"));
            } else if (a.length == 2 && c.equals("aether_loot")) {
                out.addAll(loot.nombres());
            }
            String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
            out.removeIf(x -> !x.toLowerCase().startsWith(pre));
            return out;
        }
    }

    /** /tod4:aether_caida poner [altura] | ver | borrar */
    private void caidaCmd(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "ver";
        switch (m) {
            case "poner" -> {
                if (!(s instanceof Player p)) { Style.err(s, "Solo en el juego."); return; }
                Location l = p.getLocation();
                double y = l.getY() + 120;
                if (a.length > 1) try { y = Double.parseDouble(a[1]); } catch (NumberFormatException ignored) { }
                datos.set("caida.x", l.getBlockX() + 0.5);
                datos.set("caida.y", y);
                datos.set("caida.z", l.getBlockZ() + 0.5);
                datos.set("caida.yaw", (double) l.getYaw());
                guardarDatos();
                Style.ok(s, "Punto de caída: &fX " + l.getBlockX() + " Z " + l.getBlockZ() + "&a, cayendo desde &fY " + (int) y
                        + "&a. Los que se caen del Aether aparecen ahí arriba y caen justo en este bloque.");
            }
            case "borrar" -> {
                datos.set("caida", null);
                guardarDatos();
                Style.ok(s, "Punto de caída borrado: vuelven a caer sobre el spawn del Overworld.");
            }
            default -> {
                if (!datos.isSet("caida.x")) { Style.info(s, "No hay punto de caída: se cae sobre el spawn del Overworld. Ponerlo: &e/tod4:aether_caida poner [altura]"); return; }
                Style.info(s, "Punto de caída: &fX " + datos.getDouble("caida.x") + " Z " + datos.getDouble("caida.z") + " &7desde &fY " + datos.getDouble("caida.y"));
            }
        }
    }
}
