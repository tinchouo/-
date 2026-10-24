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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import ar.tod4.TOD4;

/**
 * Cambios de los dias 7 en adelante.
 *  - d7.creeper_muerte: al morir, 10% de que aparezca un creeper electrico (vida completa) donde moriste. No rompe bloques.
 *  - d8.pigman: los Pigman (zombis piglin y brutos piglin) estan siempre enojados con los jugadores cercanos.
 *  - d9.zombies: los zombies comunes salen con armadura (2 a 4 partes, hierro o diamante, 50% Proteccion IV) y casi siempre con espada.
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
                Entity n = pl.creepers() == null ? null : pl.creepers().aparecer("nuclear", l);
                if (n != null) n.addScoreboardTag(TUMBA);
                return;
            }
            l.getWorld().spawn(l, Creeper.class, c -> {
                c.setPowered(true);
                c.addScoreboardTag(TUMBA);
            });
        }, 2L);
    }

    /** Los creepers que salen donde murio alguien no rompen bloques (por las dudas, aunque la tumba ya esta protegida). */
    static final String TUMBA = "creeper_tumba";

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explotaTumba(EntityExplodeEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TUMBA)) e.blockList().clear();
    }

    // ------------------------------------------------------------------ zombies armados (dia 9)

    private static final EquipmentSlot[] ARMADURA = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void zombie(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.ZOMBIE || !cb.on("d9.zombies")) return;   // solo el zombie comun (no husk, ahogado ni aldeano)
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        if (!(e.getEntity() instanceof Zombie z) || !z.isAdult()) return;
        EntityEquipment eq = z.getEquipment();
        if (eq == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        org.bukkit.configuration.file.FileConfiguration c = pl.getConfig();
        float drop = (float) c.getDouble("cambios.zombies_drop", 0.0);

        // armadura: entre 2 y 4 partes, cada una de hierro o de diamante; 50% de que todas tengan Proteccion IV
        int min = c.getInt("cambios.zombies_partes_min", 2), max = c.getInt("cambios.zombies_partes_max", 4);
        int partes = min + rnd.nextInt(Math.max(1, max - min + 1));
        boolean prote = rnd.nextDouble() < c.getDouble("cambios.zombies_prote4", 0.5);
        Enchantment pro = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("protection"));
        java.util.List<EquipmentSlot> slots = new java.util.ArrayList<>(java.util.List.of(ARMADURA));
        java.util.Collections.shuffle(slots);
        for (int i = 0; i < partes && i < slots.size(); i++) {
            boolean diamante = rnd.nextDouble() < c.getDouble("cambios.zombies_diamante", 0.5);
            ItemStack it = new ItemStack(pieza(slots.get(i), diamante));
            if (prote && pro != null) it.addUnsafeEnchantment(pro, 4);
            eq.setItem(slots.get(i), it);
            eq.setDropChance(slots.get(i), drop);
        }
        // espada: casi siempre; mitad hierro, mitad diamante; mitad encantada con Filo (1 a filo_max)
        if (rnd.nextDouble() < c.getDouble("cambios.zombies_espada", 0.9)) {
            ItemStack esp = new ItemStack(rnd.nextBoolean() ? Material.IRON_SWORD : Material.DIAMOND_SWORD);
            Enchantment filo = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"));
            if (filo != null && rnd.nextDouble() < c.getDouble("cambios.zombies_espada_encantada", 0.5))
                esp.addUnsafeEnchantment(filo, 1 + rnd.nextInt(Math.max(1, c.getInt("cambios.zombies_filo_max", 3))));
            eq.setItemInMainHand(esp);
            eq.setItemInMainHandDropChance(drop);
        }
    }

    private static Material pieza(EquipmentSlot s, boolean diamante) {
        return switch (s) {
            case HEAD -> diamante ? Material.DIAMOND_HELMET : Material.IRON_HELMET;
            case CHEST -> diamante ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE;
            case LEGS -> diamante ? Material.DIAMOND_LEGGINGS : Material.IRON_LEGGINGS;
            default -> diamante ? Material.DIAMOND_BOOTS : Material.IRON_BOOTS;
        };
    }

    // ------------------------------------------------------------------ pigman enojados

    private void pigman() {
        if (!cb.on("d8.pigman")) return;
        double r = pl.getConfig().getDouble("cambios.pigman_radio", 16);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || ar.tod4.hook.Cuyes.activo(p)) continue;
            for (Entity en : p.getNearbyEntities(r, r / 2, r)) {
                if (!(en instanceof PigZombie || en instanceof org.bukkit.entity.PiglinBrute) || !(en instanceof org.bukkit.entity.Mob m) || m.isDead()) continue;
                if (m.getTarget() instanceof Player t && t.isValid() && !t.isDead() && t.getWorld() == m.getWorld()) continue;
                if (!m.hasLineOfSight(p)) continue;
                if (m instanceof PigZombie pz) { pz.setAngry(true); pz.setAnger(Math.max(pz.getAnger(), 400)); }
                m.setTarget(p);
            }
        }
    }
}
