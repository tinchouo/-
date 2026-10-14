package ar.tod4;

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
import ar.tod4.core.Importar;
import ar.tod4.data.Store;
import ar.tod4.hook.Papi;
import ar.tod4.mesa.Mesa;
import ar.tod4.revivir.Revivir;
import ar.tod4.sidebar.Sidebar;
import ar.tod4.util.ChatInput;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;

public final class TOD4 extends JavaPlugin implements TabCompleter {

    private Store core;
    private Avisos avisos;
    private Revivir revivir;
    private Mesa mesa;
    private Cambios cambios;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = new Store(this, "datos");
        Bukkit.getPluginManager().registerEvents(new Menu.Listen(), this);
        ChatInput.init(this);
        avisos = new Avisos(this);
        revivir = new Revivir(this);
        mesa = new Mesa(this);
        cambios = new Cambios(this);
        new Sidebar(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new Papi(this).register();
            getLogger().info("Placeholders %tod4_...% registrados.");
        }
        for (String c : new String[]{"tod4", "cambios", "avisos", "altar", "alma", "revivir"}) {
            if (getCommand(c) != null) getCommand(c).setTabCompleter(this);
        }
        getLogger().info("TOD4 listo. Dia actual: " + dia());
    }

    @Override
    public void onDisable() {
        if (core != null) core.save();
        if (avisos != null) avisos.store().save();
        if (revivir != null) revivir.store().save();
    }

    public int dia() { return core.y().getInt("dia", 1); }

    public Store datos() { return core; }
    public Avisos avisos() { return avisos; }
    public Revivir revivir() { return revivir; }
    public Mesa mesa() { return mesa; }
    public Cambios cambios() { return cambios; }

    // ================================================================= comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase();
        if (!s.hasPermission("tod4.admin")) { Style.err(s, "No tenés permiso."); return true; }
        switch (c) {
            case "tod4": return tod4(s, a);
            case "cambios":
                if (s instanceof Player p) {
                    if (a.length > 0) {
                        try { cambios.abrirDia(p, Integer.parseInt(a[0])); return true; } catch (NumberFormatException ignored) { }
                    }
                    cambios.abrirDias(p);
                }
                return true;
            case "avisos":
                if (s instanceof Player p) avisos.abrirPanel(p);
                return true;
            case "altar":
                if (s instanceof Player p) revivir.cmdAltar(p, a);
                return true;
            case "alma": revivir.cmdAlma(s, a); return true;
            case "revivir": revivir.cmdRevivir(s, a); return true;
            default: return false;
        }
    }

    private boolean tod4(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equalsIgnoreCase("ayuda")) {
            Style.raw(s, "&8&m----------------------------------------");
            Style.raw(s, "&6&lTOD4 &7- comandos");
            Style.raw(s, "&e/tod4 dia [número] &8- &7ver o cambiar el día actual");
            Style.raw(s, "&e/tod4 importar &8- &7trae los datos de los scripts viejos");
            Style.raw(s, "&e/tod4 recargar &8- &7recarga config.yml y mesa.yml");
            Style.raw(s, "&e/cambios [día] &8- &7menú de cambios de dificultad");
            Style.raw(s, "&e/avisos &8- &7mensajes, bienvenidas y ruleta");
            Style.raw(s, "&e/altar [agregar|quitar|limpiar] &8- &7configuración del altar de revivir");
            Style.raw(s, "&e/alma <give|remove> <jugador> &8- &7dar o quitar un alma");
            Style.raw(s, "&e/revivir <jugador> &8- &7revivir a alguien sin ritual");
            Style.raw(s, "&7Siguen en Skript por ahora: &f/panel /completar &7(misiones) y &f/momentostruco");
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
            case "importar":
                new Importar(this).correr(s);
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
                case "tod4": out.addAll(List.of("help", "dia", "importar", "recargar")); break;
                case "altar": out.addAll(List.of("agregar", "quitar", "limpiar")); break;
                case "alma": out.addAll(List.of("give", "remove")); break;
                case "revivir": out.addAll(revivir.store().y().getStringList("muertos")); break;
                default: break;
            }
        } else if (a.length == 2 && (c.equals("alma") || c.equals("altar"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
