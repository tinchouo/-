package ar.tod4.equipos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Equipos:
 *  - Los admins los crean (/tod4:equipo crear <tag> <color> <nombre completo>) y eligen al lider. Menu: /tod4:equipo
 *  - Tab y arriba de la cabeza: "[GOC] [L] Rusbik" en el color del equipo (lo arma la Sidebar con prefijo()).
 *  - /eq: chat de equipo (alterna) o /eq <mensaje>. El lider invita (se acepta desde el chat) y pasa el liderazgo.
 *    Nadie sale ni es expulsado de un equipo: solo un admin lo saca (/tod4:equipo quitar).
 *  - No toca nada de PvP.
 */
public final class Equipos implements Listener {

    static final class Equipo {
        String tag, nombre;
        NamedTextColor color;
        volatile UUID lider;
        final List<UUID> miembros = new CopyOnWriteArrayList<>();   // en el orden en que entraron
    }

    private record Invitacion(String tag, UUID de, BukkitTask vence) { }

    static final Map<String, NamedTextColor> COLORES = new LinkedHashMap<>();
    static {
        COLORES.put("negro", NamedTextColor.BLACK); COLORES.put("azul_oscuro", NamedTextColor.DARK_BLUE);
        COLORES.put("verde_oscuro", NamedTextColor.DARK_GREEN); COLORES.put("turquesa", NamedTextColor.DARK_AQUA);
        COLORES.put("rojo_oscuro", NamedTextColor.DARK_RED); COLORES.put("violeta", NamedTextColor.DARK_PURPLE);
        COLORES.put("dorado", NamedTextColor.GOLD); COLORES.put("gris", NamedTextColor.GRAY);
        COLORES.put("gris_oscuro", NamedTextColor.DARK_GRAY); COLORES.put("azul", NamedTextColor.BLUE);
        COLORES.put("verde", NamedTextColor.GREEN); COLORES.put("celeste", NamedTextColor.AQUA);
        COLORES.put("rojo", NamedTextColor.RED); COLORES.put("rosa", NamedTextColor.LIGHT_PURPLE);
        COLORES.put("amarillo", NamedTextColor.YELLOW); COLORES.put("blanco", NamedTextColor.WHITE);
    }

    private final TOD4 pl;
    private final Store store;
    private final Map<String, Equipo> equipos = new ConcurrentHashMap<>();
    private final Map<UUID, Equipo> de = new ConcurrentHashMap<>();
    private final Set<UUID> modoEquipo = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Invitacion> invitaciones = new HashMap<>();

    public Equipos(TOD4 pl) {
        this.pl = pl;
        this.store = new Store(pl, "equipos");
        cargar();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTask(pl, this::limpiarTeamsViejos);
    }

    public Store store() { return store; }

    // ================================================================= datos

    private void cargar() {
        ConfigurationSection s = store.y().getConfigurationSection("equipos");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Equipo e = new Equipo();
            e.tag = s.getString(k + ".tag", k);
            e.nombre = s.getString(k + ".nombre", e.tag);
            e.color = NamedTextColor.NAMES.valueOr(s.getString(k + ".color", "white"), NamedTextColor.WHITE);
            String l = s.getString(k + ".lider", "");
            e.lider = l.isEmpty() ? null : UUID.fromString(l);
            for (String u : s.getStringList(k + ".miembros")) {
                UUID id = UUID.fromString(u);
                e.miembros.add(id);
                de.put(id, e);
            }
            equipos.put(e.tag.toLowerCase(), e);
        }
    }

    private void guardar(Equipo e) {
        String k = "equipos." + e.tag.toLowerCase();
        store.set(k + ".tag", e.tag);
        store.set(k + ".nombre", e.nombre);
        store.set(k + ".color", NamedTextColor.NAMES.key(e.color));
        store.set(k + ".lider", e.lider == null ? "" : e.lider.toString());
        List<String> l = new ArrayList<>();
        for (UUID u : e.miembros) l.add(u.toString());
        store.set(k + ".miembros", l);
    }

    Equipo equipo(String tag) { return tag == null ? null : equipos.get(tag.toLowerCase()); }

    List<Equipo> todos() { return new ArrayList<>(equipos.values()); }

    public List<String> tags() {
        List<String> l = new ArrayList<>();
        for (Equipo e : equipos.values()) l.add(e.tag);
        return l;
    }

    static String nombreDe(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() == null ? u.toString().substring(0, 8) : o.getName();
    }

    // ================================================================= como se ve el nombre (lo usa la Sidebar)

    /** Antes cada equipo tenia dos teams de Minecraft; ahora cada jugador tiene el suyo (Sidebar). */
    private void limpiarTeamsViejos() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams()))
            if (t.getName().startsWith("tod4e_") || t.getName().startsWith("tod4l_")) t.unregister();
    }

    /** No hace nada: la Sidebar lee el equipo de cada jugador cada segundo. */
    private void aplicar(Equipo e) { }

    private void borrarTeams(Equipo e) { }

    public String simboloLider() { return pl.getConfig().getString("equipos.simbolo_lider", "[L]"); }

    /** "&a[ABC] [L] " (o "&a[ABC] " si no es lider); "" si no tiene equipo. */
    public String prefijo(UUID u) {
        Equipo e = de.get(u);
        if (e == null) return "";
        return codigo(e.color) + "[" + e.tag + "] " + (u.equals(e.lider) ? simboloLider() + " " : "");
    }

    /** Si el jugador tiene equipo. */
    public boolean tieneEquipo(UUID u) { return de.get(u) != null; }

    /** Si los dos estan en el mismo equipo (y tienen uno). */
    public boolean mismoEquipo(UUID a, UUID b) {
        Equipo e = de.get(a);
        return e != null && e == de.get(b);
    }

    /** Tag del equipo con su color ("&aABC"), o null si no tiene. */
    public String tag(UUID u) {
        Equipo e = de.get(u);
        return e == null ? null : codigo(e.color) + e.tag;
    }

    /** Color del equipo del jugador, o null si no tiene. */
    public NamedTextColor color(UUID u) {
        Equipo e = de.get(u);
        return e == null ? null : e.color;
    }

    /** Para la sidebar: "&aABC" o "&aABC [L]"; null si no tiene equipo. */
    public String sidebar(UUID u) {
        Equipo e = de.get(u);
        if (e == null) return null;
        String s = codigo(e.color) + e.tag;
        // d8.equipo: cuanto dano de mas recibe por los companeros muertos (sin el simbolo de lider)
        int n = pl.cambios() != null && pl.cambios().on("d8.equipo") ? caidos(u) : 0;
        if (n > 0) s += " &7- &4+" + Math.round(n * porCaido()) + "% ☠";
        return s;
    }

    /** % de dano de mas por cada companero muerto (d8.equipo). */
    public double porCaido() { return pl.getConfig().getDouble("cambios.equipo_caido_porcentaje", 5); }

    /** Cuantos companeros de equipo de u (sin contarlo) estan muertos en el limbo, sin revivir. */
    public int caidos(UUID u) {
        Equipo e = de.get(u);
        if (e == null || pl.revivir() == null) return 0;
        // los del altar y los del Gulag (esos no se pueden revivir: cuentan para siempre)
        List<String> muertos = new ArrayList<>(pl.revivir().store().y().getStringList("muertos"));
        muertos.addAll(pl.revivir().store().y().getStringList("muertos_gulag"));
        if (muertos.isEmpty()) return 0;
        int n = 0;
        for (UUID m : e.miembros) {
            if (m.equals(u)) continue;
            String nom = nombreDe(m);
            if (nom == null) continue;
            for (String x : muertos) if (x.equalsIgnoreCase(nom)) { n++; break; }
        }
        return n;
    }

    /** Para ordenar el tab: los del mismo equipo quedan juntos. */
    public String orden(UUID u) {
        Equipo e = de.get(u);
        return e == null ? "zz" : e.tag.toLowerCase();
    }

    static String codigo(NamedTextColor c) {
        String s = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(Component.text("x", c));
        return s.length() >= 3 ? s.substring(0, 2) : "&f";
    }

    @EventHandler
    public void sale(PlayerQuitEvent ev) { modoEquipo.remove(ev.getPlayer().getUniqueId()); }

    // ================================================================= cambios (los usan los comandos y el menu)

    Equipo crear(String tag, NamedTextColor color, String nombre) {
        Equipo e = new Equipo();
        e.tag = tag; e.color = color; e.nombre = nombre;
        equipos.put(tag.toLowerCase(), e);
        guardar(e);
        aplicar(e);
        return e;
    }

    void borrar(Equipo e) {
        equipos.remove(e.tag.toLowerCase());
        for (UUID u : e.miembros) { de.remove(u); modoEquipo.remove(u); }
        store.set("equipos." + e.tag.toLowerCase(), null);
        borrarTeams(e);
    }

    /** Entra al equipo (si no tiene lider, queda de lider). */
    void unir(Equipo e, UUID u) {
        Equipo antes = de.get(u);
        if (antes != null) quitar(antes, u, false);
        e.miembros.add(u);
        de.put(u, e);
        if (e.lider == null) e.lider = u;
        guardar(e);
        aplicar(e);
    }

    /** Sale del equipo. Si era el lider, el liderazgo pasa al que lleva mas tiempo; si no queda nadie, el proximo que entre. */
    void quitar(Equipo e, UUID u, boolean avisar) {
        e.miembros.remove(u);
        de.remove(u);
        modoEquipo.remove(u);
        boolean eraLider = u.equals(e.lider);
        if (eraLider) e.lider = e.miembros.isEmpty() ? null : e.miembros.get(0);
        guardar(e);
        aplicar(e);
        if (avisar) {
            aEquipo(e, Component.text(nombreDe(u) + " dejó el equipo.", e.color));
            if (eraLider && e.lider != null) avisoNuevoLider(e);
        }
    }

    void hacerLider(Equipo e, UUID u) {
        e.lider = u;
        guardar(e);
        aplicar(e);
        avisoNuevoLider(e);
    }

    private void avisoNuevoLider(Equipo e) {
        Player n = Bukkit.getPlayer(e.lider);
        if (n != null) n.sendMessage(Component.text("Te pasaron el liderazgo del equipo.", e.color));
        for (UUID m : e.miembros) {
            if (m.equals(e.lider)) continue;
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(Component.text(nombreDe(e.lider) + " es el nuevo líder del equipo.", e.color));
        }
    }

    private void aEquipo(Equipo e, Component c) {
        for (UUID m : e.miembros) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(c);
        }
    }

    private static Component tagC(Equipo e) { return Component.text("[" + e.tag + "]", e.color); }

    private static Component tagNombre(Equipo e) {
        return Component.text("[" + e.tag + "] " + e.nombre, e.color);
    }

    // ================================================================= chat de equipo

    private Component formato(Equipo e, Player p, Component msg) {
        boolean lid = p.getUniqueId().equals(e.lider);
        return Component.text("[Equipo] ", e.color)
                .append(Component.text((lid ? simboloLider() + " " : "") + p.getName(), e.color))
                .append(Component.text(": ", e.color))
                .append(msg.colorIfAbsent(NamedTextColor.GRAY));
    }

    private void mandar(Equipo e, Player p, Component msg) {
        Component c = formato(e, p, msg);
        aEquipo(e, c);
        Bukkit.getConsoleSender().sendMessage(c);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void chat(AsyncChatEvent ev) {
        Player p = ev.getPlayer();
        if (!modoEquipo.contains(p.getUniqueId())) return;
        Equipo e = de.get(p.getUniqueId());
        if (e == null) { modoEquipo.remove(p.getUniqueId()); return; }
        ev.setCancelled(true);
        mandar(e, p, ev.message());
    }

    // ================================================================= /eq (jugadores)

    private static void rojo(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.RED)); }

    public void comandoEq(Player p, String[] a) {
        Equipo e = de.get(p.getUniqueId());
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "aceptar" -> { aceptar(p); return; }
            case "rechazar" -> { rechazar(p); return; }
            case "ayuda" -> { ayuda(p); return; }
            default -> { }
        }
        if (e == null) { rojo(p, "No perteneces a ningún equipo."); return; }
        switch (sub) {
            case "" -> {
                if (modoEquipo.remove(p.getUniqueId())) p.sendMessage(Component.text("Chat normal activado.", NamedTextColor.WHITE));
                else {
                    modoEquipo.add(p.getUniqueId());
                    p.sendMessage(Component.text("Chat de equipo activado. Solo tu equipo verá lo que escribas.", e.color));
                }
            }
            case "invitar" -> {
                if (!p.getUniqueId().equals(e.lider)) { rojo(p, "Solo el líder del equipo puede hacer eso."); return; }
                if (a.length < 2) { p.sendMessage(Component.text("Uso: /eq invitar <jugador>", NamedTextColor.WHITE)); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { rojo(p, "Ese jugador no está conectado."); return; }
                if (de.containsKey(t.getUniqueId())) { rojo(p, t.getName() + " ya está en un equipo."); return; }
                if (invitaciones.containsKey(t.getUniqueId())) { rojo(p, t.getName() + " ya tiene una invitación pendiente. Espera a que la responda o a que venza."); return; }
                invitar(e, p, t);
            }
            case "lider" -> {
                if (!p.getUniqueId().equals(e.lider)) { rojo(p, "Solo el líder del equipo puede hacer eso."); return; }
                UUID u = a.length > 1 ? miembro(e, a[1]) : null;
                if (u == null) { rojo(p, "Ese jugador no es de tu equipo."); return; }
                if (u.equals(p.getUniqueId())) return;
                p.sendMessage(Component.text("Le pasaste el liderazgo a " + nombreDe(u) + ".", e.color));
                hacerLider(e, u);
            }
            case "miembros" -> {
                p.sendMessage(tagNombre(e));
                for (UUID u : e.miembros) {
                    boolean on = Bukkit.getPlayer(u) != null;
                    p.sendMessage(Component.text(" • " + (u.equals(e.lider) ? simboloLider() + " " : "") + nombreDe(u), on ? NamedTextColor.WHITE : NamedTextColor.GRAY));
                }
            }
            default -> mandar(e, p, Component.text(String.join(" ", a)));
        }
    }

    private void ayuda(Player p) {
        p.sendMessage(Component.text("/eq", NamedTextColor.YELLOW).append(Component.text(" - cambia entre el chat de equipo y el normal", NamedTextColor.WHITE)));
        p.sendMessage(Component.text("/eq <mensaje>", NamedTextColor.YELLOW).append(Component.text(" - manda un mensaje a tu equipo", NamedTextColor.WHITE)));
        // ya no existen /eq salir ni /eq expulsar: de un equipo solo saca un admin (/tod4:equipo quitar)
        p.sendMessage(Component.text("/eq miembros", NamedTextColor.YELLOW).append(Component.text(" - tu equipo", NamedTextColor.WHITE)));
        p.sendMessage(Component.text("/eq invitar | lider <jugador>", NamedTextColor.YELLOW).append(Component.text(" - solo el líder", NamedTextColor.WHITE)));
    }

    private UUID miembro(Equipo e, String nombre) {
        for (UUID u : e.miembros) if (nombreDe(u).equalsIgnoreCase(nombre)) return u;
        return null;
    }

    private void invitar(Equipo e, Player lider, Player t) {
        Invitacion vieja = invitaciones.remove(t.getUniqueId());
        if (vieja != null) vieja.vence().cancel();
        UUID tu = t.getUniqueId(), lu = lider.getUniqueId();
        BukkitTask vence = Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (invitaciones.remove(tu) == null) return;
            Component c = Component.text("La invitación ha vencido.", NamedTextColor.GRAY);
            Player a = Bukkit.getPlayer(tu), b = Bukkit.getPlayer(lu);
            if (a != null) a.sendMessage(c);
            if (b != null) b.sendMessage(c);
        }, 60 * 20L);
        invitaciones.put(tu, new Invitacion(e.tag, lu, vence));
        t.sendMessage(Component.text(lider.getName() + " te invitó al equipo ", NamedTextColor.WHITE).append(tagNombre(e)).append(Component.text(".", NamedTextColor.WHITE)));
        t.sendMessage(Component.text("[ACEPTAR]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/eq aceptar")).hoverEvent(HoverEvent.showText(Component.text("Haz clic para unirte", NamedTextColor.GREEN)))
                .append(Component.text("   "))
                .append(Component.text("[RECHAZAR]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/eq rechazar")).hoverEvent(HoverEvent.showText(Component.text("Haz clic para rechazar", NamedTextColor.RED)))));
        lider.sendMessage(Component.text("Invitación enviada a " + t.getName() + ".", e.color));
    }

    private void aceptar(Player p) {
        Invitacion inv = invitaciones.remove(p.getUniqueId());
        if (inv == null) { rojo(p, "No tienes invitaciones pendientes."); return; }
        inv.vence().cancel();
        Equipo e = equipo(inv.tag());
        if (e == null) { rojo(p, "Ese equipo ya no existe."); return; }
        if (de.containsKey(p.getUniqueId())) { rojo(p, "Ya estás en un equipo."); return; }
        Player l = Bukkit.getPlayer(inv.de());
        if (l != null) l.sendMessage(Component.text(p.getName() + " aceptó tu invitación.", NamedTextColor.GREEN));
        aEquipo(e, Component.text(p.getName() + " se unió al equipo.", e.color));
        unir(e, p.getUniqueId());
        p.sendMessage(Component.text("Te uniste al equipo ", NamedTextColor.WHITE).append(tagNombre(e)).append(Component.text(".", NamedTextColor.WHITE)));
    }

    private void rechazar(Player p) {
        Invitacion inv = invitaciones.remove(p.getUniqueId());
        if (inv == null) { rojo(p, "No tienes invitaciones pendientes."); return; }
        inv.vence().cancel();
        Player l = Bukkit.getPlayer(inv.de());
        if (l != null) l.sendMessage(Component.text(p.getName() + " rechazó tu invitación.", NamedTextColor.RED));
        p.sendMessage(Component.text("Rechazaste la invitación.", NamedTextColor.GRAY));
    }

    public List<String> tabEq(Player p, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("invitar", "lider", "miembros", "ayuda"));
        else if (a.length == 2) {
            Equipo e = de.get(p.getUniqueId());
            if (a[0].equalsIgnoreCase("invitar")) for (Player o : Bukkit.getOnlinePlayers()) { if (!de.containsKey(o.getUniqueId())) out.add(o.getName()); }
            else if (e != null && (a[0].equalsIgnoreCase("lider")))
                for (UUID u : e.miembros) if (!u.equals(p.getUniqueId())) out.add(nombreDe(u));
        }
        return out;
    }

    // ================================================================= /tod4:equipo (admins)

    public void comandoAdmin(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) new EquiposMenu(this).lista(p);
            else lista(s);
            return;
        }
        switch (a[0].toLowerCase()) {
            case "crear" -> {
                if (a.length < 4) { Style.info(s, "Uso: &e/tod4:equipo crear <tag> <color> <nombre completo>"); Style.info(s, "Colores: &f" + String.join(", ", COLORES.keySet())); return; }
                String tag = a[1];
                if (!tag.matches("[A-Za-z0-9_]{1,8}")) { Style.err(s, "El tag tiene que tener de 1 a 8 letras o números."); return; }
                if (equipo(tag) != null) { Style.err(s, "Ya existe un equipo con esa abreviatura."); return; }
                NamedTextColor c = color(a[2]);
                if (c == null) { Style.err(s, "Color desconocido. Colores: &f" + String.join(", ", COLORES.keySet())); return; }
                String nombre = String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length));
                Equipo e = crear(tag, c, nombre);
                s.sendMessage(Component.text("Equipo ", NamedTextColor.WHITE).append(tagNombre(e)).append(Component.text(" creado.", NamedTextColor.WHITE)));
            }
            case "agregar" -> {
                if (a.length < 3) { Style.info(s, "Uso: &e/tod4:equipo agregar <tag> <jugador>"); return; }
                Equipo e = equipo(a[1]);
                if (e == null) { Style.err(s, "No existe ese equipo."); return; }
                OfflinePlayer o = jugador(a[2]);
                if (o == null) { Style.err(s, "Jugador no encontrado."); return; }
                if (e.miembros.contains(o.getUniqueId())) { Style.err(s, o.getName() + " ya está en ese equipo."); return; }
                aEquipo(e, Component.text(o.getName() + " se unió al equipo.", e.color));
                unir(e, o.getUniqueId());
                if (o.getPlayer() != null) o.getPlayer().sendMessage(Component.text("Te uniste al equipo ", NamedTextColor.WHITE).append(tagNombre(e)).append(Component.text(".", NamedTextColor.WHITE)));
                s.sendMessage(Component.text(o.getName() + " ahora es parte de ", NamedTextColor.WHITE).append(tagC(e)).append(Component.text(".", NamedTextColor.WHITE)));
            }
            case "quitar" -> {
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:equipo quitar <jugador>"); return; }
                OfflinePlayer o = jugador(a[1]);
                Equipo e = o == null ? null : de.get(o.getUniqueId());
                if (e == null) { Style.err(s, "Ese jugador no está en ningún equipo."); return; }
                quitarAdmin(e, o.getUniqueId());
                s.sendMessage(Component.text(o.getName() + " ya no pertenece a ningún equipo.", NamedTextColor.WHITE));
            }
            case "lider" -> {
                if (a.length < 3) { Style.info(s, "Uso: &e/tod4:equipo lider <tag> <jugador>"); return; }
                Equipo e = equipo(a[1]);
                if (e == null) { Style.err(s, "No existe ese equipo."); return; }
                OfflinePlayer o = jugador(a[2]);
                if (o == null) { Style.err(s, "Jugador no encontrado."); return; }
                if (!e.miembros.contains(o.getUniqueId())) unir(e, o.getUniqueId());
                hacerLider(e, o.getUniqueId());
                s.sendMessage(Component.text(o.getName() + " ahora es el líder de ", NamedTextColor.WHITE).append(tagC(e)).append(Component.text(".", NamedTextColor.WHITE)));
            }
            case "borrar" -> {
                Equipo e = a.length > 1 ? equipo(a[1]) : null;
                if (e == null) { Style.err(s, "No existe ese equipo."); return; }
                borrar(e);
                s.sendMessage(Component.text("Equipo ", NamedTextColor.WHITE).append(tagC(e)).append(Component.text(" eliminado.", NamedTextColor.WHITE)));
            }
            case "lista" -> lista(s);
            default -> Style.info(s, "Uso: &e/tod4:equipo &7[crear | agregar | quitar | lider | borrar | lista]");
        }
    }

    void quitarAdmin(Equipo e, UUID u) {
        quitar(e, u, true);
        Player t = Bukkit.getPlayer(u);
        if (t != null) t.sendMessage(Component.text("Ya no formas parte del equipo ", NamedTextColor.WHITE).append(tagC(e)).append(Component.text(".", NamedTextColor.WHITE)));
    }

    private void lista(CommandSender s) {
        if (equipos.isEmpty()) { Style.info(s, "No hay equipos."); return; }
        for (Equipo e : equipos.values()) {
            s.sendMessage(tagNombre(e).append(Component.text(" — " + e.miembros.size() + " miembros — líder: "
                    + (e.lider == null ? "nadie" : nombreDe(e.lider)), NamedTextColor.WHITE)));
        }
    }

    private static OfflinePlayer jugador(String n) {
        Player p = Bukkit.getPlayerExact(n);
        if (p != null) return p;
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(n);
        return o != null && o.hasPlayedBefore() ? o : null;
    }

    static NamedTextColor color(String s) {
        NamedTextColor c = COLORES.get(s.toLowerCase());
        return c != null ? c : NamedTextColor.NAMES.value(s.toLowerCase());
    }

    public List<String> tabAdmin(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("crear", "agregar", "quitar", "lider", "borrar", "lista"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "agregar", "lider", "borrar" -> out.addAll(tags());
                case "quitar" -> { for (UUID u : de.keySet()) out.add(nombreDe(u)); }
                default -> { }
            }
        } else if (a.length == 3) {
            if (a[0].equalsIgnoreCase("crear")) out.addAll(COLORES.keySet());
            if (a[0].equalsIgnoreCase("agregar") || a[0].equalsIgnoreCase("lider")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        return out;
    }

    // ================================================================= utilidades para el menu

    static Material lana(NamedTextColor c) {
        if (c == NamedTextColor.BLACK) return Material.BLACK_WOOL;
        if (c == NamedTextColor.DARK_BLUE) return Material.BLUE_WOOL;
        if (c == NamedTextColor.DARK_GREEN) return Material.GREEN_WOOL;
        if (c == NamedTextColor.DARK_AQUA) return Material.CYAN_WOOL;
        if (c == NamedTextColor.DARK_RED) return Material.RED_WOOL;
        if (c == NamedTextColor.DARK_PURPLE) return Material.PURPLE_WOOL;
        if (c == NamedTextColor.GOLD) return Material.ORANGE_WOOL;
        if (c == NamedTextColor.GRAY) return Material.LIGHT_GRAY_WOOL;
        if (c == NamedTextColor.DARK_GRAY) return Material.GRAY_WOOL;
        if (c == NamedTextColor.BLUE) return Material.LIGHT_BLUE_WOOL;
        if (c == NamedTextColor.GREEN) return Material.LIME_WOOL;
        if (c == NamedTextColor.AQUA) return Material.LIGHT_BLUE_WOOL;
        if (c == NamedTextColor.RED) return Material.RED_WOOL;
        if (c == NamedTextColor.LIGHT_PURPLE) return Material.MAGENTA_WOOL;
        if (c == NamedTextColor.YELLOW) return Material.YELLOW_WOOL;
        return Material.WHITE_WOOL;
    }

    static ItemStack cabeza(UUID u, String nombre, List<String> lore) {
        ItemStack it = Items.of(Material.PLAYER_HEAD, nombre, lore);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            it.setItemMeta(sm);
        }
        return it;
    }

    Menu nuevoMenu(int filas, String titulo) { return new Menu(filas, titulo); }
}
