package ar.tod4.video;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import ar.tod4.TOD4;

/**
 * Screamer: un video cortito a pantalla completa (con el mismo shader que /tod4:video) pero sin trabar la camara
 * ni cambiar el modo de juego: el jugador sigue jugando y la imagen le aparece encima (y se va desvaneciendo).
 *  - Los cuadros son un item de Oraxen/pack con un modelo por cuadro (custom_model_data 1..N), igual que los videos.
 *  - Solo lo ve ese jugador. Mientras dura, el cuadro lo sigue a donde vaya.
 * Uso: /tod4:screamer <jugador> [screamer]   (config.yml -> screamers; si no hay ninguno, usa "pesadilla")
 * Cualquier mob con la etiqueta "tod4_screamer" se lo tira al pegar (con espera entre uno y otro: screamer_espera_seg).
 */
public final class Screamer implements Listener {

    public static final String ETIQUETA_MOB = "tod4_screamer";

    private final TOD4 pl;
    private final Map<UUID, Sesion> sesiones = new HashMap<>();
    private final Map<UUID, Long> ultimo = new HashMap<>();

    private static final class Sesion {
        ItemDisplay pantalla;
        BukkitTask tarea;
        int tick;
    }

    public Screamer(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private ConfigurationSection cfg(String id) { return pl.getConfig().getConfigurationSection("screamers." + id); }

    public Set<String> ids() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("screamers");
        return s == null || s.getKeys(false).isEmpty() ? Set.of("pesadilla") : s.getKeys(false);
    }

    public boolean existe(String id) { return cfg(id) != null || id.equals("pesadilla"); }

    public String porDefecto() { return ids().iterator().next(); }

    private static ItemStack cuadro(String modelo, int n) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        NamespacedKey k = NamespacedKey.fromString(modelo);
        if (k != null) m.setItemModel(k);
        m.setCustomModelData(n);
        it.setItemMeta(m);
        return it;
    }

    /** Medio bloque adelante de los ojos, mirando al jugador (como en /tod4:video; pegado a los ojos el cliente no lo dibuja). */
    private static Location frente(Player p) {
        Location ojo = p.getEyeLocation();
        Location l = ojo.clone().add(ojo.getDirection().multiply(0.5));
        l.setYaw(ojo.getYaw() + 180);
        l.setPitch(-ojo.getPitch());
        return l;
    }

    public boolean tirar(Player p, String id) {
        if (sesiones.containsKey(p.getUniqueId()) || Video.viendo(p)) return false;
        ConfigurationSection c = cfg(id);
        // "pesadilla" anda sin nada en el config.yml (es el que viene en el pack)
        String modelo = c != null ? c.getString("modelo", "tod4:screamer_" + id) : "tod4:screamer";
        int cuadros = Math.max(1, c != null ? c.getInt("cuadros", 1) : 27);
        int cada = Math.max(1, c != null ? c.getInt("ticks_por_cuadro", 1) : 1);
        String sonido = c != null ? c.getString("sonido", "") : "tod4_screamer:screamer";
        float vol = (float) (c != null ? c.getDouble("volumen", 1.0) : 1.0);

        Sesion s = new Sesion();
        s.pantalla = p.getWorld().spawn(frente(p), ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setItemStack(cuadro(modelo, 1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.3f, 1.3f, 1.3f), new AxisAngle4f()));
            d.setViewRange(4f);
            d.setTeleportDuration(0);
        });
        p.showEntity(pl, s.pantalla);
        sesiones.put(p.getUniqueId(), s);
        ultimo.put(p.getUniqueId(), System.currentTimeMillis());
        if (!sonido.isEmpty()) p.playSound(p.getLocation(), sonido, SoundCategory.MASTER, vol, 1f);   // en el lugar, como /playsound

        int total = cuadros * cada;
        s.tarea = Bukkit.getScheduler().runTaskTimer(pl, () -> {
            if (!p.isOnline() || s.pantalla.isDead()) { terminar(p); return; }
            if (!s.pantalla.getWorld().equals(p.getWorld())) { terminar(p); return; }
            Location f = frente(p);
            if (s.pantalla.getLocation().distanceSquared(f) > 0.0004) s.pantalla.teleport(f);
            if (s.tick % cada == 0) {
                int n = Math.min(cuadros, s.tick / cada + 1);
                s.pantalla.setItemStack(cuadro(modelo, n));
            }
            s.tick++;
            if (s.tick >= total) terminar(p);
        }, 1L, 1L);
        return true;
    }

    public void terminar(Player p) {
        Sesion s = sesiones.remove(p.getUniqueId());
        if (s == null) return;
        if (s.tarea != null) s.tarea.cancel();
        if (s.pantalla != null) s.pantalla.remove();
    }

    public void terminarTodos() {
        for (UUID u : new java.util.ArrayList<>(sesiones.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) terminar(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golpe(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (!d.getScoreboardTags().contains(ETIQUETA_MOB)) return;
        long espera = pl.getConfig().getLong("screamer_espera_seg", 20) * 1000L;
        Long u = ultimo.get(p.getUniqueId());
        if (u != null && System.currentTimeMillis() - u < espera) return;
        tirar(p, porDefecto());
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) { terminar(e.getPlayer()); }
}
