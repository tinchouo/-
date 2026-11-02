package ar.tod4.alucina;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;

import ar.tod4.TOD4;

/**
 * Alucinacion: por un rato, el jugador ve todo cambiado (solo el, los demas lo ven normal).
 *  - La vida y el hambre que ve saltan al azar cada segundo (de medio corazon a la vida llena). El hambre salta
 *    sin cruzar los 3 muslitos (con 3 o menos el juego no deja correr): correr sigue igual que con el hambre de verdad.
 *    Es solo lo que se ve: la vida y el hambre de verdad no se tocan.
 *  - En su tab, la vida de todos cambia al azar (la Sidebar lo pregunta con activa()).
 *  - En el Agujero del Infierno, el tiempo de arriba tambien (TOD4Eventos mira la marca "tod4_alucina").
 * La da cualquier mob con la etiqueta "tod4_alucina" al pegar (el Zombi Alucinado del Agujero la tiene),
 * o /tod4:alucinar <jugador> [segundos]. Si le vuelven a pegar, los segundos vuelven a empezar (no se suman).
 * Nunca muestra 0 de vida: con 0 el juego del jugador lo da por muerto (se tumba la camara y no se puede mover).
 */
public final class Alucinacion implements Listener {

    public static final String MARCA = "tod4_alucina";
    public static final String ETIQUETA_MOB = "tod4_alucina";

    private static final Map<UUID, Long> HASTA = new HashMap<>();

    private final TOD4 pl;
    private final Random rnd = new Random();
    private final Map<UUID, float[]> falso = new HashMap<>();
    private int ticks;

    public Alucinacion(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 2L, 2L);
    }

    /** true = ese jugador esta alucinando (para la Sidebar). */
    public static boolean activa(UUID u) {
        Long h = HASTA.get(u);
        return h != null && h > System.currentTimeMillis();
    }

    public int segundosPorDefecto() { return Math.max(1, pl.getConfig().getInt("alucinacion.segundos", 30)); }

    public void empezar(Player p, int seg) {
        boolean ya = activa(p.getUniqueId());
        HASTA.put(p.getUniqueId(), System.currentTimeMillis() + seg * 1000L);
        p.setMetadata(MARCA, new FixedMetadataValue(pl, true));
        if (!ya) {
            falso.remove(p.getUniqueId());
            p.playSound(p.getLocation(), "minecraft:entity.elder_guardian.curse", org.bukkit.SoundCategory.HOSTILE, 0.35f, 1.8f);
        }
    }

    public void terminar(Player p) {
        HASTA.remove(p.getUniqueId());
        falso.remove(p.getUniqueId());
        if (p.hasMetadata(MARCA)) p.removeMetadata(MARCA, pl);
        if (p.isOnline() && !p.isDead()) p.sendHealthUpdate();   // vuelve a ver lo de verdad
    }

    private void tick() {
        ticks++;
        if (HASTA.isEmpty()) return;
        long ahora = System.currentTimeMillis();
        int cada = Math.max(2, pl.getConfig().getInt("alucinacion.cambia_cada_ticks", 20));
        for (UUID u : new java.util.ArrayList<>(HASTA.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { HASTA.remove(u); falso.remove(u); continue; }
            if (HASTA.get(u) <= ahora || p.isDead()) { terminar(p); continue; }
            float[] f = falso.get(u);
            if (f == null || (ticks * 2) % cada < 2) {
                AttributeInstance mx = p.getAttribute(Attribute.MAX_HEALTH);
                double max = mx != null ? mx.getValue() : 20;
                // de medio corazon a la vida llena, en medios corazones
                int medios = (int) Math.max(1, Math.round(max));
                float vida = 1 + rnd.nextInt(medios);
                if (rnd.nextInt(5) == 0) vida = rnd.nextBoolean() ? 1 : (float) max;   // a veces los extremos
                // con 6 o menos de hambre (3 muslitos) el juego no deja correr: el hambre falsa queda del mismo lado que la
                // de verdad (si podias correr, seguis pudiendo; si no podias, seguis sin poder)
                int hambre;
                if (p.getFoodLevel() > 6) {
                    hambre = 7 + rnd.nextInt(14);
                    if (rnd.nextInt(5) == 0) hambre = rnd.nextBoolean() ? 7 : 20;
                } else {
                    hambre = rnd.nextInt(7);
                }
                f = new float[]{vida, hambre};
                falso.put(u, f);
            }
        }
    }

    /**
     * Al final de cada tick (despues de que el server mando lo suyo): asi lo ultimo que le llega es lo falso, aunque
     * el server u otro plugin le manden la vida de verdad en ese mismo tick.
     */
    @EventHandler
    public void finTick(com.destroystokyo.paper.event.server.ServerTickEndEvent e) {
        if (HASTA.isEmpty()) return;
        for (Map.Entry<UUID, float[]> en : falso.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead() || !activa(en.getKey())) continue;
            float[] f = en.getValue();
            p.sendHealthUpdate(f[0], (int) f[1], 0f);
        }
    }

    // ================================================================= quien la da

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golpe(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d.getScoreboardTags().contains(ETIQUETA_MOB)) empezar(p, segundosPorDefecto());
    }

    @EventHandler
    public void muere(PlayerDeathEvent e) { terminar(e.getEntity()); }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        HASTA.remove(e.getPlayer().getUniqueId());
        falso.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeMetadata(MARCA, pl);
    }
}
