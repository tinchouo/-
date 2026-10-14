package ar.tod4;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import ar.tod4.avisos.Avisos;
import ar.tod4.cambios.Cambios;
import ar.tod4.core.Palo;
import ar.tod4.data.Store;
import ar.tod4.hook.Papi;
import ar.tod4.mesa.Mesa;
import ar.tod4.misiones.Misiones;
import ar.tod4.misiones.MisionesMenu;
import ar.tod4.momento.Momentos;
import ar.tod4.momento.MomentosMenu;
import ar.tod4.revivir.Revivir;
import ar.tod4.sidebar.Sidebar;
import ar.tod4.util.ChatInput;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;

/**
 * TrucOfDeath 4. Todos los comandos tambien existen como /tod4:<comando> (escribiendo "/tod4:" se ven todos).
 */
public final class TOD4 extends JavaPlugin implements TabCompleter {

    private static final int CONFIG_VERSION = 2;

    private Store core;
    private Avisos avisos;
    private Revivir revivir;
    private Mesa mesa;
    private Cambios cambios;
    private Misiones misiones;
    private MisionesMenu misionesMenu;
    private Momentos momentos;
    private MomentosMenu momentosMenu;
    private Palo palo;

    @Override
    public void onEnable() {
        actualizarConfig();
        core = new Store(this, "datos");
        Bukkit.getPluginManager().registerEvents(new Menu.Listen(), this);
        ChatInput.init(this);
        avisos = new Avisos(this);
        revivir = new Revivir(this);
        mesa = new Mesa(this);
        cambios = new Cambios(this);
        misiones = new Misiones(this);
        misionesMenu = new MisionesMenu(this, misiones);
        momentos = new Momentos(this);
        momentosMenu = new MomentosMenu(this, momentos);
        palo = new Palo(this);
        new Sidebar(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new Papi(this).register();
            getLogger().info("Placeholders %tod4_...% registrados.");
        }
        for (String c : getDescription().getCommands().keySet()) {
            if (getCommand(c) != null) getCommand(c).setTabCompleter(this);
        }
        getLogger().info("TOD4 listo. Dia actual: " + dia());
    }

    /** Si el config.yml es de una version vieja, se guarda como config_viejo.yml y se pone el nuevo. */
    private void actualizarConfig() {
        saveDefaultConfig();
        reloadConfig();
        if (getConfig().getInt("config_version", 1) < CONFIG_VERSION) {
            File f = new File(getDataFolder(), "config.yml");
            File viejo = new File(getDataFolder(), "config_viejo.yml");
            if (viejo.exists()) viejo.delete();
            f.renameTo(viejo);
            saveDefaultConfig();
            reloadConfig();
            getLogger().info("config.yml actualizado (el anterior quedo en config_viejo.yml).");
        }
    }

    @Override
    public void onDisable() {
        for (Store s : new Store[]{core, avisos == null ? null : avisos.store(), revivir == null ? null : revivir.store(),
                misiones == null ? null : misiones.store(), momentos == null ? null : momentos.store()})
            if (s != null) s.save();
    }

    public int dia() { return core.y().getInt("dia", 1); }

    public Store datos() { return core; }
    public Avisos avisos() { return avisos; }
    public Revivir revivir() { return revivir; }
    public Mesa mesa() { return mesa; }
    public Cambios cambios() { return cambios; }
    public Misiones misiones() { return misiones; }
    public Momentos momentos() { return momentos; }

    // ================================================================= comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase();
        // comando para todos los jugadores
        if (c.equals("misiones")) {
            if (s instanceof Player p) misionesMenu.jugador(p);
            return true;
        }
        if (!s.hasPermission("tod4.admin")) { Style.err(s, "No tenés permiso."); return true; }
        Player p = s instanceof Player pp ? pp : null;
        switch (c) {
            case "tod4": return tod4(s, a);
            case "cambios":
                if (p != null) {
                    if (a.length > 0) {
                        try { cambios.abrirDia(p, Integer.parseInt(a[0])); return true; } catch (NumberFormatException ignored) { }
                    }
                    cambios.abrirDias(p);
                }
                return true;
            case "avisos": if (p != null) avisos.abrirPanel(p); return true;
            case "altar": if (p != null) revivir.cmdAltar(p, a); return true;
            case "alma": revivir.cmdAlma(s, a); return true;
            case "revivir": revivir.cmdRevivir(s, a); return true;
            case "panel": if (p != null) misionesMenu.panel(p); return true;
            case "momentostruco": if (p != null) momentosMenu.lista(p); return true;
            case "palo": if (p != null) { p.getInventory().addItem(palo.item()); Style.ok(p, "Palo de Retorno entregado."); } return true;
            case "completar": completar(s, a); return true;
            default: return false;
        }
    }

    /** /tod4:completar <jugador|todos> [dia|op] */
    private void completar(CommandSender s, String[] a) {
        if (a.length < 1) { Style.info(s, "Uso: &e/tod4:completar <jugador|todos> [dia|op]"); return; }
        String clase = a.length > 1 && a[1].equalsIgnoreCase("op") ? Misiones.OP : Misiones.DIA;
        if (misiones.activa(clase) == null) { Style.err(s, "No hay misión " + (clase.equals(Misiones.DIA) ? "diaria" : "opcional") + " activa."); return; }
        List<Player> ps = new ArrayList<>();
        if (a[0].equalsIgnoreCase("todos")) ps.addAll(Bukkit.getOnlinePlayers());
        else {
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { Style.err(s, "Ese jugador no está conectado."); return; }
            ps.add(t);
        }
        for (Player t : ps) misiones.completar(clase, t);
        Style.ok(s, "Misión marcada como completada para &f" + (ps.size() == 1 ? ps.get(0).getName() : ps.size() + " jugadores") + "&a.");
    }

    private boolean tod4(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equalsIgnoreCase("ayuda")) {
            Style.raw(s, "&8&m----------------------------------------");
            Style.raw(s, "&6&lTOD4 &7- comandos &8(todos existen también como /tod4:...)");
            Style.raw(s, "&e/tod4 dia [número] &8- &7ver o cambiar el día actual");
            Style.raw(s, "&e/tod4 recargar &8- &7recarga config.yml y mesa.yml");
            Style.raw(s, "&e/tod4:panel &8- &7panel de misiones (crear, activar)");
            Style.raw(s, "&e/tod4:misiones &8- &7misiones del jugador (todos)");
            Style.raw(s, "&e/tod4:completar <jugador|todos> [dia|op] &8- &7marcar misión completada");
            Style.raw(s, "&e/tod4:momentostruco &8- &7Momentos Truco");
            Style.raw(s, "&e/tod4:cambios [día] &8- &7cambios de dificultad");
            Style.raw(s, "&e/tod4:avisos &8- &7mensajes, bienvenidas y ruleta");
            Style.raw(s, "&e/tod4:altar [agregar|quitar|limpiar] &8- &7altar de revivir");
            Style.raw(s, "&e/tod4:alma <give|remove> <jugador> &8- &7dar o quitar un alma");
            Style.raw(s, "&e/tod4:revivir <jugador> &8- &7revivir a alguien sin ritual");
            Style.raw(s, "&e/tod4:palo &8- &7Palo de Retorno");
            Style.raw(s, "&8&m----------------------------------------");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "dia":
                if (a.length < 2) { Style.info(s, "Día actual: &f" + dia()); return true; }
                try {
                    int d = Integer.parseInt(a[1]);
                    if (d < 1 || d > 99) throw new NumberFormatException();
                    core.set("dia", d);
                    Style.ok(s, "Día actual: &f" + d);
                } catch (NumberFormatException e) { Style.err(s, "Usá un número: &e/tod4 dia 3"); }
                return true;
            case "recargar":
                reloadConfig();
                mesa.recargar();
                Style.ok(s, "Config recargada.");
                return true;
            default:
                Style.err(s, "No existe ese comando. Usá &e/tod4 help");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String c = cmd.getName().toLowerCase();
        if (a.length == 1) {
            switch (c) {
                case "tod4": out.addAll(List.of("help", "dia", "recargar")); break;
                case "altar": out.addAll(List.of("agregar", "quitar", "limpiar")); break;
                case "alma": out.addAll(List.of("give", "remove")); break;
                case "revivir": out.addAll(revivir.store().y().getStringList("muertos")); break;
                case "completar":
                    out.add("todos");
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                    break;
                default: break;
            }
        } else if (a.length == 2) {
            if (c.equals("alma") || c.equals("altar")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            if (c.equals("completar")) out.addAll(List.of("dia", "op"));
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
