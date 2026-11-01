package ar.tod4.video;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import ar.tod4.TOD4;
import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Videos a pantalla completa que no se van con F1.
 *  - La camara del jugador queda trabada en una entidad invisible (espectador).
 *  - Adelante hay un display con el cuadro del video (item de Oraxen con un modelo por cuadro).
 *  - El shader del pack (rendertype_item_entity_translucent_cull) estira ese cuadro a toda la pantalla.
 *  - El display solo lo ve ese jugador. Mientras dura: sin sidebar, sin chat y sin la barra del Momento Truco.
 * Uso: /tod4:video <jugador> <video>   (los videos se configuran en config.yml -> videos)
 */
public final class Video implements Listener {

    /** Jugadores viendo un video (la sidebar y el Momento Truco los saltean). */
    public static final Set<UUID> VIENDO = new HashSet<>();

    public static boolean viendo(Player p) { return VIENDO.contains(p.getUniqueId()); }

    private final TOD4 pl;
    private final Map<UUID, Sesion> sesiones = new HashMap<>();

    private static final class Sesion {
        ArmorStand camara;
        ItemDisplay pantalla;
        BukkitTask tarea;
        GameMode antes;
        int tick;
    }

    public Video(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public boolean existe(String id) { return id.equalsIgnoreCase(Screamer.ID) || pl.getConfig().isConfigurationSection("videos." + id); }

    /** Los videos del config.yml mas el screamer (que anda aunque el config.yml no lo tenga). */
    public java.util.Set<String> ids() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("videos");
        if (s != null) out.addAll(s.getKeys(false));
        out.add(Screamer.ID);
        return out;
    }

    /** Duracion del video en ticks. */
    public int duracion(String id) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("videos." + id);
        return c == null ? 0 : c.getInt("cuadros", 1) * Math.max(1, c.getInt("ticks_por_cuadro", 2));
    }

    private ItemStack cuadro(String modelo, int n) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        NamespacedKey k = NamespacedKey.fromString(modelo);
        if (k != null) m.setItemModel(k);
        m.setCustomModelData(n);
        it.setItemMeta(m);
        return it;
    }

    public boolean reproducir(Player p, String id) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("videos." + id);
        if (c == null || sesiones.containsKey(p.getUniqueId())) return false;
        String modelo = c.getString("modelo", "tod4:video_" + id);
        int cuadros = Math.max(1, c.getInt("cuadros", 1));
        int cada = Math.max(1, c.getInt("ticks_por_cuadro", 2));
        String sonido = c.getString("sonido", "");

        Sesion s = new Sesion();
        s.antes = p.getGameMode();
        Location ojo = p.getEyeLocation();
        ojo.setPitch(0);
        Vector frente = ojo.getDirection().setY(0).normalize();
        // la camara: un armor stand invisible a la altura de los ojos
        Location pie = ojo.clone().subtract(0, 1.7875, 0);
        s.camara = p.getWorld().spawn(pie, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            a.setRotation(ojo.getYaw(), 0);
        });
        // la pantalla: medio bloque adelante (si el shader no anda, igual tapa casi toda la vista)
        Location pos = ojo.clone().add(frente.clone().multiply(0.5));
        pos.setYaw(ojo.getYaw() + 180);
        s.pantalla = p.getWorld().spawn(pos, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setItemStack(cuadro(modelo, 1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.3f, 1.3f, 1.3f), new AxisAngle4f()));
            d.setViewRange(4f);
        });
        p.showEntity(pl, s.camara);
        p.showEntity(pl, s.pantalla);
        if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
        sesiones.put(p.getUniqueId(), s);
        VIENDO.add(p.getUniqueId());
        p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        if (!sonido.isEmpty()) p.playSound(p.getLocation(), sonido, SoundCategory.MASTER, 1f, 1f);

        int total = cuadros * cada;
        s.tarea = Bukkit.getScheduler().runTaskTimer(pl, () -> {
            if (!p.isOnline()) { terminar(p, false); return; }
            // la camara siempre trabada (aunque aprete shift)
            if (p.getSpectatorTarget() != s.camara) {
                if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                p.setSpectatorTarget(s.camara);
            }
            if (s.tick % cada == 0) {
                int n = Math.min(cuadros, s.tick / cada + 1);
                s.pantalla.setItemStack(cuadro(modelo, n));
            }
            s.tick++;
            if (s.tick >= total) terminar(p, true);
        }, 1L, 1L);
        return true;
    }

    /** Termina el video. Si lo cambio el plugin, vuelve el modo de juego (el script de lobotomia maneja el suyo). */
    public void terminar(Player p, boolean volverModo) {
        Sesion s = sesiones.remove(p.getUniqueId());
        VIENDO.remove(p.getUniqueId());
        if (s == null) return;
        if (s.tarea != null) s.tarea.cancel();
        if (p.isOnline() && p.getSpectatorTarget() == s.camara) p.setSpectatorTarget(null);
        if (s.camara != null) s.camara.remove();
        if (s.pantalla != null) s.pantalla.remove();
        if (volverModo && p.isOnline() && s.antes != GameMode.SPECTATOR && p.getGameMode() == GameMode.SPECTATOR
                && pl.getConfig().getBoolean("video_devuelve_modo", false)) p.setGameMode(s.antes);
        // la sidebar vuelve sola en el proximo segundo
    }

    public void terminarTodos() {
        for (UUID u : new java.util.ArrayList<>(sesiones.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) terminar(p, false);
        }
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) { terminar(e.getPlayer(), false); }

    /** Mientras mira un video, no le llegan los mensajes del chat. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        if (VIENDO.isEmpty()) return;
        e.viewers().removeIf(a -> a instanceof Player p && VIENDO.contains(p.getUniqueId()));
    }
}
