package ar.tod4.creditos;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * TOD4Creditos: los creditos finales en el juego y la entrega de premios.
 *   /creditos probar [segundo]   solo vos (lo mismo que verian todos). Con un segundo, arranca desde ahi (sin musica)
 *   /creditos empezar            para todos los conectados
 *   /creditos parar              corta y devuelve a todos a donde estaban
 *   /creditos datos              los numeros y records que van a salir (para revisar)
 *   /creditos recargar           vuelve a leer config.yml, creditos.yml y premios.yml
 *   /premios ...                 ver Premios
 * Es un plugin aparte: no toca nada de TOD4 (solo lee sus archivos).
 */
public final class TOD4Creditos extends JavaPlugin implements Listener {

    private YamlConfiguration creditos, premios, estado;
    private File fEstado;
    private Show show;
    private Premios prem;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "creditos.yml").exists()) saveResource("creditos.yml", false);
        if (!new File(getDataFolder(), "premios.yml").exists()) saveResource("premios.yml", false);
        fEstado = new File(getDataFolder(), "estado.yml");
        recargar();
        prem = new Premios(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() { if (show != null) show.terminar(); }

    void recargar() {
        reloadConfig();
        creditos = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "creditos.yml"));
        premios = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "premios.yml"));
        estado = YamlConfiguration.loadConfiguration(fEstado);
    }

    YamlConfiguration premios() { return premios; }
    YamlConfiguration estado() { return estado; }

    void guardarEstado() {
        try { estado.save(fEstado); } catch (Exception e) { getLogger().warning("No pude guardar estado.yml: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------------------------------------ jugadores

    /** Guarda donde estaba (tambien en el archivo, por si se cae el server) y lo deja listo para mirar. */
    void guardarYPreparar(Player p, Show s, Location ojo) {
        estado.createSection("pendientes." + p.getUniqueId(), Show.estado(p));
        guardarEstado();
        viendo(p, true);
        p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        p.setPlayerWeather(WeatherType.CLEAR);
        p.closeInventory();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(s.camara());
        p.setSpectatorTarget(s.camara());
    }

    /** Lo devuelve como estaba. */
    void restaurar(Player p) {
        ConfigurationSection m = estado.getConfigurationSection("pendientes." + p.getUniqueId());
        if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
        viendo(p, false);
        p.resetPlayerWeather();
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        if (m != null) {
            Location l = m.getLocation("lugar");
            if (l != null && l.getWorld() != null) p.teleport(l);
            try { p.setGameMode(GameMode.valueOf(m.getString("modo", "SURVIVAL"))); } catch (Exception e) { p.setGameMode(GameMode.SURVIVAL); }
            p.setAllowFlight(m.getBoolean("puede_volar", p.getAllowFlight()));
            if (p.getAllowFlight()) p.setFlying(m.getBoolean("volaba", false));
            p.setFallDistance(0);
        }
        estado.set("pendientes." + p.getUniqueId(), null);
        guardarEstado();
    }

    void showTermino(Show s) { if (show == s) show = null; }

    /** El sidebar y la barra del Momento Truco de TOD4 se saltean a los que estan en Video.VIENDO (y no les llega el chat). */
    @SuppressWarnings("unchecked")
    private void viendo(Player p, boolean si) {
        Plugin tod = Bukkit.getPluginManager().getPlugin("TOD4");
        if (tod == null) return;
        try {
            Class<?> c = tod.getClass().getClassLoader().loadClass("ar.tod4.video.Video");
            Set<UUID> set = (Set<UUID>) c.getField("VIENDO").get(null);
            if (si) set.add(p.getUniqueId()); else set.remove(p.getUniqueId());
        } catch (Throwable ignored) { }
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (show != null && show.incluye(p)) {
            show.viendo.remove(p);
            restaurar(p);
        }
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (estado.isConfigurationSection("pendientes." + p.getUniqueId()))
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) restaurar(p); }, 5L);
        // los que estan mirando no ven al que entra (y al reves no importa: esta lejos)
        if (show != null) for (Player v : show.viendo) v.hidePlayer(this, p);
    }

    /** Mientras miran no se mueven nada (si el cliente se despega de la camara, vuelve). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mueve(org.bukkit.event.player.PlayerMoveEvent e) {
        if (show == null || !show.incluye(e.getPlayer())) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 1e-6) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void dejaDeMirar(PlayerStopSpectatingEntityEvent e) {
        if (show != null && show.incluye(e.getPlayer()) && e.getSpectatorTarget() == show.camara()) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------ comandos

    private Contenido armar() {
        Datos d = new Datos(this);
        d.cargar();
        return new Contenido(this, d, creditos, prem.ganadores(), premios);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("premios")) return premiosCmd(s, a);
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "probar", "empezar" -> {
                if (show != null) { err(s, "Ya están pasando los créditos (/creditos parar para cortarlos)."); return true; }
                List<Player> ven = new ArrayList<>();
                boolean prueba = sub.equals("probar");
                if (prueba) {
                    if (!(s instanceof Player p)) { err(s, "Solo desde el juego."); return true; }
                    ven.add(p);
                } else ven.addAll(Bukkit.getOnlinePlayers());
                if (ven.isEmpty()) { err(s, "No hay nadie conectado."); return true; }
                int desde = 0;
                if (prueba && a.length > 1) try { desde = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) { }
                Contenido c;
                try { c = armar(); } catch (Exception e) { err(s, "No pude armar los créditos: " + e); e.printStackTrace(); return true; }
                show = new Show(this, ven, prueba, c, desde);
                try { show.empezar(); } catch (Exception e) {
                    err(s, "Falló al empezar: " + e);
                    e.printStackTrace();
                    Show x = show;
                    show = null;
                    x.terminar();
                    return true;
                }
                ok(s, prueba ? "Créditos de prueba (solo vos)" + (desde > 0 ? " desde el segundo " + desde + " (sin música)" : "") + ". Para cortar: /creditos parar"
                        : "Créditos para " + ven.size() + " jugadores.");
            }
            case "parar" -> {
                if (show == null) { err(s, "No están pasando los créditos."); return true; }
                show.terminar();
                show = null;
                ok(s, "Créditos cortados. Todos volvieron a donde estaban.");
            }
            case "recargar" -> { recargar(); ok(s, "Recargado (config.yml, creditos.yml, premios.yml)."); }
            case "datos" -> {
                Datos d = new Datos(this);
                d.cargar();
                s.sendMessage(Component.text("Números:", NamedTextColor.GOLD));
                for (Map.Entry<String, String> e : d.valores().entrySet())
                    s.sendMessage(Component.text("  {" + e.getKey() + "} = ", NamedTextColor.GRAY).append(Component.text(e.getValue(), NamedTextColor.WHITE)));
                s.sendMessage(Component.text("Récords:", NamedTextColor.GOLD));
                for (Map<?, ?> r : creditos.getMapList("records.lista")) {
                    if (!r.containsKey("stat")) { s.sendMessage(Component.text("  " + r.get("titulo") + ": " + r.get("ganador"), NamedTextColor.GRAY)); continue; }
                    Object mh = r.get("min_horas");
                    String[] rec = d.record(String.valueOf(r.get("stat")), "menor".equalsIgnoreCase(String.valueOf(r.get("orden"))),
                            mh instanceof Number n ? n.doubleValue() : 0);
                    s.sendMessage(Component.text("  " + r.get("titulo") + ": ", NamedTextColor.GRAY)
                            .append(Component.text(rec == null ? "-" : rec[0] + " (" + rec[1] + ")", NamedTextColor.WHITE)));
                }
                Contenido c = new Contenido(this, d, creditos, prem.ganadores(), premios);
                int caps = new Show(this, List.of(), true, c, 0).contarCapturas();
                ok(s, "Capturas: entran " + caps
                        + (prem.ganadores().isEmpty() ? ". PREMIOS no aparece: todavía no elegiste ningún ganador (/premios)." : "."));
                double seg = getConfig().getDouble("tiempos.termina_de_subir", 170) - getConfig().getDouble("tiempos.empieza_a_subir", 6);
                ok(s, c.items.size() + " renglones; suben a " + Math.round(c.alto / seg) + " píxeles por segundo.");
            }
            default -> {
                s.sendMessage(Component.text("/creditos probar [segundo]", NamedTextColor.YELLOW).append(Component.text("  solo vos", NamedTextColor.GRAY)));
                s.sendMessage(Component.text("/creditos empezar", NamedTextColor.YELLOW).append(Component.text("  para todos", NamedTextColor.GRAY)));
                s.sendMessage(Component.text("/creditos parar | datos | recargar", NamedTextColor.YELLOW));
            }
        }
        return true;
    }

    private boolean premiosCmd(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "entregar" -> {
                if (a.length < 2) { err(s, "Uso: /premios entregar <premio> [probar]"); return true; }
                prem.entregar(s, a[1], a.length > 2 && a[2].equalsIgnoreCase("probar"));
            }
            case "ver" -> prem.ver(s);
            case "ganador" -> {
                // por si el menu no anda: /premios ganador <premio> <nombre...>
                if (a.length < 3 || !prem.lista().containsKey(a[1])) { err(s, "Uso: /premios ganador <premio> <nombre>"); return true; }
                String n = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                estado.set("ganadores." + a[1], n);
                guardarEstado();
                ok(s, a[1] + ": " + n);
            }
            default -> {
                if (s instanceof Player p) prem.abrir(p);
                else prem.ver(s);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        Collection<String> op;
        if (cmd.getName().equalsIgnoreCase("premios")) {
            if (a.length == 1) op = List.of("entregar", "ver", "ganador");
            else if (a.length == 2 && (a[0].equalsIgnoreCase("entregar") || a[0].equalsIgnoreCase("ganador"))) op = prem.lista().keySet();
            else if (a.length == 3 && a[0].equalsIgnoreCase("entregar")) op = List.of("probar");
            else op = List.of();
        } else op = a.length == 1 ? List.of("probar", "empezar", "parar", "datos", "recargar") : List.of();
        String u = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        for (String o : op) if (o.toLowerCase().startsWith(u)) out.add(o);
        return out;
    }

    private static void ok(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.GREEN)); }
    private static void err(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.RED)); }
}
