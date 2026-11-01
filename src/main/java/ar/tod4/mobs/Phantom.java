package ar.tod4.mobs;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.util.Vector;

import ar.tod4.TOD4;

/**
 * Phantom Truco: cualquier mob con la etiqueta "tod4_phantom" (PhantomTruco.yml de MythicMobs, o el del Agujero).
 *  - Si tambien tiene "tod4_screamer", al pegarte te tira el screamer (eso lo hace Screamer).
 *  - Te pega, te empuja fuerte y se teletransporta lejos (18 a 26 bloques). No te puede volver a pegar por unos segundos.
 *  - A los pocos segundos aparece a unos 10 bloques tuyo y viene corriendo.
 *  - Busca solo a los jugadores (aunque sea un enderman, no hace falta mirarlo). No se teletransporta solo ni agarra bloques.
 * Todo se ajusta en config.yml -> phantom.
 */
public final class Phantom implements Listener {

    public static final String ETIQUETA = "tod4_phantom";

    private final TOD4 pl;
    private final Random rnd = new Random();
    private final Map<UUID, Long> sinPegarHasta = new HashMap<>();
    private boolean moviendo;   // nuestro propio teletransporte (el del juego se cancela)

    public Phantom(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::buscar, 40L, 40L);
    }

    private double d(String k, double def) { return pl.getConfig().getDouble("phantom." + k, def); }

    private void log(String m) { if (pl.getConfig().getBoolean("phantom.debug", false)) pl.getLogger().info("[Phantom] " + m); }

    public static boolean es(Entity e) { return e != null && e.getScoreboardTags().contains(ETIQUETA); }

    private static boolean jugable(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    // ================================================================= el golpe

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void golpe(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !es(e.getDamager()) || !(e.getDamager() instanceof Mob m)) return;
        long ahora = System.currentTimeMillis();
        Long h = sinPegarHasta.get(m.getUniqueId());
        if (h != null && ahora < h) { e.setCancelled(true); return; }   // no te pega dos veces seguidas
        sinPegarHasta.put(m.getUniqueId(), ahora + (long) (d("sin_pegar_seg", 5) * 1000));
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!m.isValid()) return;
            // el empujon
            if (p.isOnline() && !p.isDead()) {
                Vector dir = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() < 1e-4) dir = p.getLocation().getDirection().multiply(-1).setY(0);
                p.setVelocity(dir.normalize().multiply(d("empuje", 1.4)).setY(d("empuje_arriba", 0.45)));
            }
            // se va lejos
            Location lejos = lugar(m.getLocation(), d("lejos_min", 18), d("lejos_max", 26), p.getLocation());
            m.setTarget(null);
            // se queda quieto donde se fue hasta que le toque volver (si no, vuelve caminando enseguida)
            m.getPathfinder().stopPathfinding();
            m.setAware(false);
            quieto(m, true);
            if (m instanceof Enderman en) en.setScreaming(false);
            if (lejos != null) mover(m, lejos);
            if (lejos != null && m.getLocation().distanceSquared(lejos) > 1) log("el teletransporte no se hizo (quedo en " + m.getLocation().toVector() + ")");
            log("le pego a " + p.getName() + (lejos == null ? " (no encontre lugar para irse)" : " y se fue a " + lejos.getBlockX() + " " + lejos.getBlockY() + " " + lejos.getBlockZ()));
            // y vuelve
            Bukkit.getScheduler().runTaskLater(pl, () -> volver(m, p), Math.max(1L, (long) (d("vuelve_seg", 4) * 20)));
            if (pl.getConfig().getBoolean("phantom.debug", false))
                for (int t = 10; t < d("vuelve_seg", 4) * 20; t += 10) {
                    Bukkit.getScheduler().runTaskLater(pl, () -> log("  esperando en " + m.getLocation().toVector() + " aware=" + m.isAware()), t);
                }
        });
    }

    private void volver(Mob m, Player p) {
        if (m.isValid()) { m.setAware(true); quieto(m, false); }
        if (!m.isValid() || !jugable(p) || !p.getWorld().equals(m.getWorld())) {
            log("no vuelve (mob valido " + m.isValid() + ", jugador " + p.getName() + " jugable " + jugable(p) + ")");
            return;
        }
        double dist = d("vuelve_distancia", 10);
        log("vuelve: esta a " + Math.round(m.getLocation().distance(p.getLocation())) + " bloques de " + p.getName());
        if (m.getLocation().distance(p.getLocation()) > dist + 4) {
            Location cerca = lugar(p.getLocation(), dist - 1.5, dist + 1.5, p.getLocation());
            if (cerca != null) mover(m, cerca);
            log("volvio cerca de " + p.getName() + (cerca == null ? " (corriendo, no encontre lugar)" : ""));
        }
        m.setTarget(p);
        if (m instanceof Enderman en) en.setScreaming(true);
    }

    /** Busca jugadores aunque nadie lo mire (el enderman comun no ataca si no lo miras). */
    private void buscar() {
        double r = d("busca_radio", 30);
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity le : w.getLivingEntities()) {
                if (!(le instanceof Mob m) || !es(m)) continue;
                if (m.getTarget() instanceof Player t && jugable(t) && t.getWorld().equals(m.getWorld())) continue;
                Long h = sinPegarHasta.get(m.getUniqueId());
                if (h != null && System.currentTimeMillis() < h) continue;   // se esta yendo
                Player mejor = null;
                double md = r * r;
                for (Player p : w.getPlayers()) {
                    if (!jugable(p)) continue;
                    double dd = p.getLocation().distanceSquared(m.getLocation());
                    if (dd < md) { md = dd; mejor = p; }
                }
                if (mejor != null) {
                    m.setTarget(mejor);
                    if (m instanceof Enderman en) en.setScreaming(true);
                }
            }
        }
        sinPegarHasta.entrySet().removeIf(en -> System.currentTimeMillis() > en.getValue() + 60000);
    }

    private final org.bukkit.NamespacedKey kQuieto = new org.bukkit.NamespacedKey("tod4", "phantom_quieto");

    /** Velocidad en 0 mientras espera lejos (se saca al volver; no se guarda si se reinicia el server). */
    private void quieto(Mob m, boolean si) {
        var a = m.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (a == null) return;
        if (a.getModifier(kQuieto) != null) a.removeModifier(kQuieto);
        if (si) a.addTransientModifier(new org.bukkit.attribute.AttributeModifier(kQuieto, -1.0,
                org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, org.bukkit.inventory.EquipmentSlotGroup.ANY));
    }

    // ================================================================= teletransporte

    private void mover(Mob m, Location to) {
        Location de = m.getLocation();
        World w = de.getWorld();
        w.spawnParticle(Particle.PORTAL, de.clone().add(0, 1.4, 0), 50, 0.4, 0.9, 0.4, 0.4);
        w.playSound(de, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 0.8f);
        moviendo = true;
        try { m.teleport(to); } finally { moviendo = false; }
        w.spawnParticle(Particle.PORTAL, to.clone().add(0, 1.4, 0), 50, 0.4, 0.9, 0.4, 0.4);
        w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 0.8f);
    }

    /** Un lugar con piso y 3 de aire (lo que mide un enderman), entre dmin y dmax de c, mirando hacia mira. */
    private Location lugar(Location c, double dmin, double dmax, Location mira) {
        World w = c.getWorld();
        dmin = Math.max(1, dmin);
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, dist = dmin + rnd.nextDouble() * Math.max(0, dmax - dmin);
            int x = (int) Math.floor(c.getX() + Math.cos(ang) * dist), z = (int) Math.floor(c.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y0 = c.getBlockY();
            for (int k = 0; k <= 16; k++) {
                int y = y0 + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);   // 0, -1, 1, -2, 2...
                if (y <= w.getMinHeight() || y + 3 >= w.getMaxHeight()) continue;
                if (!libre(w, x, y, z)) continue;
                Location l = new Location(w, x + 0.5, y, z + 0.5);
                Vector v = mira.toVector().subtract(l.toVector());
                l.setDirection(v.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : v);
                l.setPitch(0);
                return l;
            }
        }
        return null;
    }

    private static boolean libre(World w, int x, int y, int z) {
        Block piso = w.getBlockAt(x, y - 1, z);
        if (!piso.getType().isSolid() || piso.isLiquid()) return false;
        for (int i = 0; i < 3; i++) {
            Block b = w.getBlockAt(x, y + i, z);
            if (!b.isPassable() || b.isLiquid()) return false;
        }
        return true;
    }

    // ================================================================= lo que no hace

    /** El enderman no se teletransporta por su cuenta (solo cuando lo manda esto). */
    @EventHandler(ignoreCancelled = true)
    public void tele(EntityTeleportEvent e) {
        if (!moviendo && es(e.getEntity())) {
            e.setCancelled(true);
            log("cancele un teletransporte del juego a " + (e.getTo() == null ? "?" : e.getTo().toVector()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void teleVer(EntityTeleportEvent e) {
        if (es(e.getEntity()) && !e.isCancelled() && !moviendo) log("se teletransporto solo (otro plugin lo dejo) a " + e.getTo());
    }

    /** No agarra ni deja bloques (la cueva no se toca). */
    @EventHandler(ignoreCancelled = true)
    public void bloque(EntityChangeBlockEvent e) {
        if (es(e.getEntity())) e.setCancelled(true);
    }
}
