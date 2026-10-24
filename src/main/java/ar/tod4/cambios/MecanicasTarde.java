package ar.tod4.cambios;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import ar.tod4.TOD4;

/**
 * Cambios de los dias 7 en adelante.
 *  - d7.creeper_muerte: al morir, 10% de que aparezca un creeper electrico con 1 de vida donde moriste.
 *  - d8.pigman: los Pigman (zombis piglin) estan siempre enojados con los jugadores cercanos.
 *  - d12.nuclear_muerte: al morir, 1% de que aparezca un Creeper Nuclear donde moriste (la tumba no se rompe).
 * No cuentan las muertes del gulag, de los cuyes ni de los OP.
 */
final class MecanicasTarde implements Listener {
    private final TOD4 pl;
    private final Cambios cb;

    MecanicasTarde(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::pigman, 40L, 40L);
    }

    // ------------------------------------------------------------------ creepers al morir

    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.isOp() || ar.tod4.hook.Cuyes.activo(p) || ar.tod4.hook.Gulag.muereEnGulag(p.getUniqueId())) return;
        Location l = p.getLocation().clone();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean nuclear = cb.on("d12.nuclear_muerte") && r.nextDouble() < pl.getConfig().getDouble("cambios.nuclear_muerte_chance", 0.01);
        boolean electrico = !nuclear && cb.on("d7.creeper_muerte") && r.nextDouble() < pl.getConfig().getDouble("cambios.creeper_muerte_chance", 0.10);
        if (!nuclear && !electrico) return;
        // un instante despues, cuando ya esta la tumba
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (nuclear) {
                if (pl.creepers() != null) pl.creepers().aparecer("nuclear", l);
                return;
            }
            l.getWorld().spawn(l, Creeper.class, c -> {
                c.setPowered(true);
                c.setHealth(Math.min(c.getHealth(), pl.getConfig().getDouble("cambios.creeper_muerte_vida", 1.0)));
            });
        }, 2L);
    }

    // ------------------------------------------------------------------ pigman enojados

    private void pigman() {
        if (!cb.on("d8.pigman")) return;
        double r = pl.getConfig().getDouble("cambios.pigman_radio", 16);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || ar.tod4.hook.Cuyes.activo(p)) continue;
            for (Entity en : p.getNearbyEntities(r, r / 2, r)) {
                if (!(en instanceof PigZombie pz) || pz.isDead()) continue;
                if (pz.getTarget() instanceof Player t && t.isValid() && !t.isDead() && t.getWorld() == pz.getWorld()) continue;
                if (!pz.hasLineOfSight(p)) continue;
                pz.setAngry(true);
                pz.setAnger(Math.max(pz.getAnger(), 400));
                pz.setTarget(p);
            }
        }
    }
}
