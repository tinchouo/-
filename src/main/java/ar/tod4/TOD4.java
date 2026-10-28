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

    private static final int CONFIG_VERSION = 12;

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
    private ar.tod4.video.Video video;
    private ar.tod4.equipos.Equipos equipos;
    private ar.tod4.mobs.Creepers creepers;
    private ar.tod4.clones.Clones clones;
    private ar.tod4.core.Extras extras;
    private ar.tod4.discord.Discord discord;
    private ar.tod4.subasta.Subasta subasta;
    private ar.tod4.items.Corazon corazon;

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
        video = new ar.tod4.video.Video(this);
        new ar.tod4.mobs.Mobs(this);
        creepers = new ar.tod4.mobs.Creepers(this);
        equipos = new ar.tod4.equipos.Equipos(this);
        new ar.tod4.core.Chat(this);
        clones = new ar.tod4.clones.Clones(this);
        extras = new ar.tod4.core.Extras(this);
        discord = new ar.tod4.discord.Discord(this);
        subasta = new ar.tod4.subasta.Subasta(this);
        corazon = new ar.tod4.items.Corazon(this);
        new ar.tod4.items.SoloOraxen(this);
        Bukkit.getPluginManager().registerEvents(new ar.tod4.hook.GulagTab(), this);
        new Sidebar(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new Papi(this).register();
            getLogger().info("Placeholders %tod4_...% registrados.");
        }
        for (String c : getDescription().getCommands().keySet()) {
            if (getCommand(c) != null) getCommand(c).setTabCompleter(this);
        }
        // los comandos de admin solo existen como /tod4:<comando>; /misiones queda normal para todos
        Bukkit.getScheduler().runTask(this, this::soloConPrefijo);
        getLogger().info("TOD4 listo. Dia actual: " + dia());
    }

    private static final List<String> SOLO_ADMIN = List.of("panel", "completar", "momentostruco", "cambios", "avisos", "altar", "alma", "revivir", "palo", "video",
            "equipo", "creeper", "clones", "totems");

    /** Saca los nombres sueltos (/panel, /alma...) y deja solo /tod4:panel, /tod4:alma... Tambien saca /tod4:misiones. */
    private void soloConPrefijo() {
        java.util.Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        List<String> sacar = new ArrayList<>(SOLO_ADMIN);
        sacar.add("tod4:misiones");
        sacar.add("tod4:quest");
        sacar.add("tod4:quests");
        sacar.add("tod4:eq");
        for (String k : sacar) {
            Command c = known.get(k);
            if (c instanceof org.bukkit.command.PluginCommand pc && pc.getPlugin() == this) known.remove(k);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    public static final String GULAG_DISCORD = "## ⛓ {jugador} ha caído en el Gulag.\nHa caído de manera definitiva y no podrá ser revivido.";

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
        // mensaje del Gulag en Discord: si tiene el de antes, va el nuevo
        if ("## ⛓ {jugador} ha caído en el Gulag.\nHa muerto de forma permanente.".equals(getConfig().getString("discord.gulag_mensaje"))) {
            getConfig().set("discord.gulag_mensaje", GULAG_DISCORD);
            saveConfig();
        }
    }

    @Override
    public void onDisable() {
        // el sistema de dormir pone la regla en 101 (el juego no salta la noche solo): al apagar vuelve a la normal
        for (org.bukkit.World w : Bukkit.getWorlds())
            if (Integer.valueOf(101).equals(w.getGameRuleValue(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE)))
                w.setGameRule(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE, 100);
        if (misionesMenu != null) misionesMenu.cerrarTodo();
        if (video != null) video.terminarTodos();
        if (creepers != null) creepers.apagar();
        if (cambios != null) cambios.apagar();
        if (momentos != null) momentos.apagar();
        if (clones != null) clones.apagar();
        for (Store s : new Store[]{core, avisos == null ? null : avisos.store(), revivir == null ? null : revivir.store(),
                misiones == null ? null : misiones.store(), momentos == null ? null : momentos.store(),
                equipos == null ? null : equipos.store(), clones == null ? null : clones.store()})
            if (s != null) s.save();
    }

    public ar.tod4.core.Extras extras() { return extras; }
    public ar.tod4.discord.Discord discord() { return discord; }

    public int dia() { return core.y().getInt("dia", 1); }

    public Store datos() { return core; }
    public Avisos avisos() { return avisos; }
    public ar.tod4.mobs.Creepers creepers() { return creepers; }
    public Revivir revivir() { return revivir; }
    public Mesa mesa() { return mesa; }
    public Cambios cambios() { return cambios; }
    public Misiones misiones() { return misiones; }
    public Momentos momentos() { return momentos; }
    public ar.tod4.clones.Clones clones() { return clones; }
    public ar.tod4.equipos.Equipos equipos() { return equipos; }

    // ================================================================= comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase();
        // comando para todos los jugadores
        if (c.equals("misiones")) {
            if (s instanceof Player p) misionesMenu.jugador(p);
            return true;
        }
        if (c.equals("eq")) {
            if (s instanceof Player p) equipos.comandoEq(p, a);
            return true;
        }
        if (c.equals("pujar")) { subasta.cmdPujar(s, a); return true; }
        if (!s.hasPermission("tod4.admin")) { Style.err(s, "No tienes permiso."); return true; }
        Player p = s instanceof Player pp ? pp : null;
        if (c.equals("equipo")) { equipos.comandoAdmin(s, a); return true; }
        if (c.equals("clones")) { clones.comando(s, a); return true; }
        if (c.equals("creeper")) {
            if (p == null) return true;
            if (a.length == 0 || !creepers.tipos().contains(a[0].toLowerCase())) { Style.info(s, "Uso: &e/tod4:creeper <" + String.join("|", creepers.tipos()) + ">"); return true; }
            if (creepers.aparecer(a[0].toLowerCase(), p.getLocation()) == null) Style.err(s, "No pude hacerlo aparecer (¿está el archivo de MythicMobs?).");
            return true;
        }
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
            case "totems": if (p != null) ar.tod4.cambios.Totems.abrir(this, p); return true;
            case "alma": revivir.cmdAlma(s, a); return true;
            case "revivir": revivir.cmdRevivir(s, a); return true;
            case "panel": if (p != null) misionesMenu.panel(p); return true;
            case "momentostruco": if (p != null) momentosMenu.lista(p); return true;
            case "palo": if (p != null) { p.getInventory().addItem(palo.item()); Style.ok(p, "Palo de Retorno entregado."); } return true;
            case "completar": completar(s, a); return true;
            case "video": {
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:video <jugador> <video>"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { Style.err(s, "Jugador no encontrado."); return true; }
                if (!video.existe(a[1])) { Style.err(s, "No existe el video &f" + a[1] + "&c (config.yml -> videos)."); return true; }
                if (!video.reproducir(t, a[1])) Style.err(s, "Ese jugador ya está viendo un video.");
                return true;
            }
            default: return false;
        }
    }

    /** /tod4:completar <jugador|todos> <id> [evento] (como el /completar viejo: por ID) */
    private void completar(CommandSender s, String[] a) {
        if (a.length < 2) { Style.info(s, "Uso: &e/tod4:completar <jugador|todos> <id> &7[evento]"); return; }
        String id = a[1];
        String clase = misiones.claseDe(id);
        if (clase == null) { Style.err(s, "Esa ID de misión no existe."); return; }
        if (!id.equals(misiones.activa(clase))) { Style.err(s, "La misión &f" + id + " &cno está activa."); return; }
        boolean evento = a.length > 2 && a[2].equalsIgnoreCase("evento");
        List<Player> ps = new ArrayList<>();
        if (a[0].equalsIgnoreCase("todos")) ps.addAll(Bukkit.getOnlinePlayers());
        else {
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { Style.err(s, "Jugador no encontrado."); return; }
            ps.add(t);
        }
        for (Player t : ps) {
            if (evento) {
                if (!misiones.completarEvento(clase, t)) { Style.err(s, "Esa misión no tiene ningún objetivo de evento."); return; }
            } else {
                misiones.completarTodo(clase, t);
            }
        }
        String quien = ps.size() == 1 ? ps.get(0).getName() : "todos";
        Style.ok(s, (evento ? "Evento de la misión &f" : "Misión &f") + id + " &acompletado para &f" + quien + "&a.");
    }

    private boolean tod4(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equalsIgnoreCase("ayuda")) {
            Style.raw(s, "&8&m----------------------------------------");
            Style.raw(s, "&6&lTOD4 &7- comandos de admin &8(escribe /tod4: para verlos todos)");
            Style.raw(s, "&e/tod4 dia [número] &8- &7ver o cambiar el día actual");
            Style.raw(s, "&e/tod4 recargar &8- &7recarga config.yml y mesa.yml");
            Style.raw(s, "&e/tod4 muertes &8- &7muertes por día &8(&7poner <día> <n> para corregir&8)");
            Style.raw(s, "&e/tod4 donde &8- &7en qué dimensión está cada jugador");
            Style.raw(s, "&e/tod4 holo <hoy|total|borrar> &8- &7cartel flotante con las muertes");
            Style.raw(s, "&e/tod4:panel &8- &7panel de misiones (crear, editar, activar)");
            Style.raw(s, "&e/tod4:completar <jugador|todos> <id> &8- &7completar una misión o su evento");
            Style.raw(s, "&e/misiones &8- &7misiones del jugador (para todos)");
            Style.raw(s, "&e/tod4:momentostruco &8- &7Momentos Truco");
            Style.raw(s, "&e/tod4:cambios [día] &8- &7cambios de dificultad");
            Style.raw(s, "&e/tod4:avisos &8- &7mensajes, bienvenidas y ruleta");
            Style.raw(s, "&e/tod4:altar [agregar|quitar|gulag|bloquear|desbloquear|limpiar] &8- &7altar de revivir");
            Style.raw(s, "&e/tod4:totems &8- &7tótems que no dan ceguera (día 14)");
            Style.raw(s, "&e/tod4:alma <give|remove> <jugador> &8- &7dar o quitar un alma");
            Style.raw(s, "&e/tod4:revivir <jugador> &8- &7revivir a alguien sin ritual");
            Style.raw(s, "&e/tod4:palo &8- &7Palo de Retorno");
            Style.raw(s, "&e/tod4:video <jugador> <video> &8- &7video a pantalla completa");
            Style.raw(s, "&e/tod4:equipo &8- &7equipos (menú, crear, agregar, líder...)  &8| &e/eq &7para los jugadores");
            Style.raw(s, "&7El Aether ahora es el plugin &fTOD4Aether&7: &e/tod4:aether_ &7+ tab");
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
                } catch (NumberFormatException e) { Style.err(s, "Usa un número: &e/tod4 dia 3"); }
                return true;
            case "muertes": extras.cmdMuertes(s, a); return true;
            case "donde": extras.cmdDonde(s); return true;
            case "holo": extras.cmdHolo(s, a); return true;
            case "discord": discord.cmd(s, a); return true;
            case "subasta": subasta.cmd(s, a); return true;
            case "corazon": corazon.cmd(s, a); return true;
            case "ballestero": {
                if (!(s instanceof Player pj)) return true;
                String t = a.length > 1 ? a[1].toLowerCase() : "rojo";
                if (!cambios.ballestero(pj.getLocation(), t)) Style.err(s, "Uso: &e/tod4 ballestero <rojo|amarillo|negro>");
                return true;
            }
            case "minero": {
                if (s instanceof Player pj) cambios.minero(pj.getLocation());
                return true;
            }
            case "esqueleto": {
                if (s instanceof Player pj) cambios.esqueleto(pj.getLocation());
                return true;
            }
            case "recargar":
                reloadConfig();
                mesa.recargar();
                creepers.recargar();
                Style.ok(s, "Config recargada.");
                return true;
            default:
                Style.err(s, "No existe ese comando. Usa &e/tod4 help");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String c = cmd.getName().toLowerCase();
        if (c.equals("clones")) return s.hasPermission("tod4.admin") ? clones.tab(a) : out;
        if (c.equals("eq") || c.equals("equipo")) {
            if (c.equals("eq") && s instanceof Player p) out.addAll(equipos.tabEq(p, a));
            if (c.equals("equipo") && s.hasPermission("tod4.admin")) out.addAll(equipos.tabAdmin(a));
            String pre0 = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
            out.removeIf(x -> !x.toLowerCase().startsWith(pre0));
            return out;
        }
        if (c.equals("tod4") && a.length >= 2 && a[0].equalsIgnoreCase("subasta") && s.hasPermission("tod4.admin")) {
            out.addAll(subasta.tab(a));
            String pre1 = a[a.length - 1].toLowerCase();
            out.removeIf(x -> !x.toLowerCase().startsWith(pre1));
            return out;
        }
        if (a.length == 1) {
            switch (c) {
                case "tod4": out.addAll(List.of("help", "dia", "muertes", "donde", "holo", "discord", "subasta", "corazon", "ballestero", "minero", "esqueleto", "recargar")); break;
                case "video": for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); break;
                case "altar": out.addAll(List.of("agregar", "quitar", "gulag", "bloquear", "desbloquear", "limpiar")); break;
                case "alma": out.addAll(List.of("give", "remove")); break;
                case "revivir": out.addAll(revivir.store().y().getStringList("muertos")); out.addAll(revivir.store().y().getStringList("muertos_gulag")); break;
                case "creeper": out.addAll(creepers.tipos()); break;
                case "completar":
                    out.add("todos");
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                    break;
                default: break;
            }
        } else if (a.length == 2) {
            if (c.equals("alma") || c.equals("altar")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            if (c.equals("video")) out.addAll(video.ids());
            if (c.equals("completar")) {
                for (String cl : new String[]{Misiones.DIA, Misiones.OP}) if (misiones.activa(cl) != null) out.add(misiones.activa(cl));
            }
        } else if (a.length == 3 && c.equals("completar")) {
            out.add("evento");
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
