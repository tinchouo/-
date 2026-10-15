package ar.tod4.mobs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;

/**
 * Ayudas para los mobs de MythicMobs + Better Model:
 *  - Creepers con modelo: cuando empiezan a inflarse (la mecha) se les manda la senal SWELL, y UNSWELL si se
 *    desinflan. En su archivo de MythicMobs: "state{s=swell} @self ~onSignal:SWELL" (asi se agrandan como siempre,
 *    antes de explotar y no cuando ya exploto).
 *  - Howler: mientras persigue a alguien, camina directo hacia el (no se frena en las esquinas ni tarda en arrancar).
 */
public final class Mobs {

    private final TOD4 pl;
    private final Map<UUID, Integer> mecha = new HashMap<>();
    private final Set<UUID> noMythic = new HashSet<>();
    private int tick;

    public Mobs(TOD4 pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    private void tick() {
        tick++;
        if (tick % 2 == 0) creepers();
        if (tick % 4 == 0) howler();
        if (tick % 1200 == 0) { noMythic.clear(); mecha.keySet().removeIf(u -> Bukkit.getEntity(u) == null); }
    }

    private void creepers() {
        for (World w : Bukkit.getWorlds()) {
            for (Creeper c : w.getEntitiesByClass(Creeper.class)) {
                UUID u = c.getUniqueId();
                if (noMythic.contains(u)) continue;
                int ahora = c.getFuseTicks();
                Integer antes = mecha.put(u, ahora);
                if (antes == null) {
                    if (Ext.mythic(c) == null) { noMythic.add(u); mecha.remove(u); }
                    continue;
                }
                if (antes == 0 && ahora > 0) Ext.mythicSignal(c, "SWELL");
                else if (antes > 0 && ahora == 0) Ext.mythicSignal(c, "UNSWELL");
            }
        }
    }

    private void howler() {
        String etiqueta = pl.getConfig().getString("mobs.howler_etiqueta", "howler");
        double vel = pl.getConfig().getDouble("mobs.howler_velocidad", 1.0);
        World w = Bukkit.getWorld(pl.getConfig().getString("mobs.mundo_backrooms", "backrooms"));
        if (w == null) return;
        for (Mob m : w.getEntitiesByClass(Mob.class)) {
            if (!m.getScoreboardTags().contains(etiqueta) || !m.hasAI()) continue;
            if (!(m.getTarget() instanceof Player p) || p.getWorld() != w || p.isDead()) continue;
            m.getPathfinder().moveTo(p, vel);
        }
    }
}
