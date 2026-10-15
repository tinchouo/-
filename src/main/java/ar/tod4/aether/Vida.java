package ar.tod4.aether;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Vida del Aether:
 *  - Animales en las islas cuando se generan (la dimension no hace aparecer mobs sola):
 *    aldea = parejas de vaca, cerdo, gallina y oveja; hongo = vacas champinon; molino = ovejas y gallinas;
 *    islas grandes = a veces una pareja (oveja, vaca, cerdo, gallina, conejo o zorro) y loros; medianas = a veces conejos o gallinas.
 *  - Viento: rafagas de nubecitas que pasan cerca de cada jugador (solo las ve el).
 */
final class Vida {

    private static final Set<Material> SUELO = Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.ROOTED_DIRT, Material.DIRT_PATH);

    private static final EntityType[] GRANJA = {EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP};
    private static final EntityType[] GRANDE = {EntityType.SHEEP, EntityType.COW, EntityType.PIG, EntityType.CHICKEN,
            EntityType.RABBIT, EntityType.FOX};
    private static final EntityType[] CHICA = {EntityType.RABBIT, EntityType.CHICKEN, EntityType.SHEEP};

    private Vida() { }

    static void poblar(World w, String n, int cx, int cy, int cz, int rax, Random r) {
        List<EntityType> l = new ArrayList<>();
        boolean loros = false;
        if (n.startsWith("village")) {
            List<EntityType> g = new ArrayList<>(List.of(GRANJA));
            java.util.Collections.shuffle(g, r);
            for (int i = 0; i < 3; i++) { l.add(g.get(i)); l.add(g.get(i)); }
        } else if (n.startsWith("mushroom_island")) {
            for (int i = 0; i < 3; i++) l.add(EntityType.MOOSHROOM);
        } else if (n.equals("windmill")) {
            l.add(EntityType.SHEEP); l.add(EntityType.SHEEP); l.add(EntityType.CHICKEN); l.add(EntityType.CHICKEN);
        } else if (n.startsWith("large") || n.startsWith("bl_")) {
            if (r.nextInt(100) < 45) { EntityType t = GRANDE[r.nextInt(GRANDE.length)]; l.add(t); l.add(t); }
            loros = r.nextInt(100) < 25;
        } else if (n.startsWith("medium") || n.startsWith("bm_")) {
            if (r.nextInt(100) < 15) { EntityType t = CHICA[r.nextInt(CHICA.length)]; l.add(t); l.add(t); }
        }
        if (loros) { l.add(EntityType.PARROT); l.add(EntityType.PARROT); }
        Location ant = null;
        EntityType tAnt = null;
        int radio = Math.max(4, (int) (rax * 0.6));
        for (EntityType t : l) {
            // la pareja aparece junta
            Location at = t == tAnt && ant != null ? lugar(w, ant.getBlockX(), ant.getBlockZ(), 3, cy, r) : null;
            if (at == null) at = lugar(w, cx, cz, radio, cy, r);
            if (at == null) continue;
            Entity en = w.spawnEntity(at, t);
            if (en instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
            en.setPersistent(true);
            if (en instanceof MushroomCow mc && r.nextInt(4) == 0) mc.setVariant(MushroomCow.Variant.BROWN);
            if (en instanceof Parrot pa) pa.setVariant(Parrot.Variant.values()[r.nextInt(Parrot.Variant.values().length)]);
            ant = at;
            tAnt = t;
        }
    }

    private static Location lugar(World w, int cx, int cz, int radio, int cy, Random r) {
        for (int i = 0; i < 14; i++) {
            int x = cx + r.nextInt(radio * 2 + 1) - radio;
            int z = cz + r.nextInt(radio * 2 + 1) - radio;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y < cy - 14 || y > cy + 40) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!SUELO.contains(b.getType())) continue;
            if (!b.getRelative(0, 1, 0).isPassable() || !b.getRelative(0, 2, 0).isPassable()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5, r.nextFloat() * 360f, 0f);
        }
        return null;
    }

    /** Una rafaga de viento: una linea de nubecitas que pasa cerca del jugador. */
    static void viento(Player p, Random r) {
        Location o = p.getLocation();
        double ang = r.nextDouble() * Math.PI * 2;
        double d = 6 + r.nextDouble() * 8;
        Location c = o.clone().add(Math.cos(ang) * d, -1 + r.nextDouble() * 5, Math.sin(ang) * d);
        // el viento del Aether sopla siempre parecido (del oeste, con un poco de variacion)
        Vector dir = new Vector(1, 0, 0.25 + (r.nextDouble() - 0.5) * 0.4).normalize();
        for (int i = 0; i < 9; i++) {
            Location q = c.clone().add(dir.clone().multiply(-i * 0.7));
            p.spawnParticle(Particle.CLOUD, q, 0, dir.getX(), 0.01, dir.getZ(), 0.35);
        }
    }
}
