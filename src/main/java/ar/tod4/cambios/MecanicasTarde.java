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
 *  - d7.creeper_muerte: al morir, 10% de que aparezca un creeper electrico con 1 de vida donde moriste. No rompe bloques.
 *  - d8.pigman: los Pigman (zombis piglin y brutos piglin) estan siempre enojados con los jugadores cercanos.
 *  - d8.pacificos: los animales te persiguen, te pegan y, si estan cerca, te ponen una TNT.
 *  - d9.zombies: los zombies comunes salen con armadura (2 a 4 partes, hierro o diamante, 50% Proteccion IV) y siempre con espada.
 *  - d10.ballesteros: algunos esqueletos salen como Esqueleto Ballestero (cuero rojo, ballesta, mas vida) y disparan con Poder V.
 *  - d12.nuclear_muerte: al morir, 1% de que aparezca un Creeper Nuclear (vida completa) donde moriste. No rompe bloques.
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
        Bukkit.getScheduler().runTaskTimer(pl, this::pacificos, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(pl, this::ballesteros, 20L, 5L);
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
                c.setHealth(Math.min(c.getHealth(), pl.getConfig().getDouble("cambios.creeper_muerte_vida", 1.0)));
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
        if (rnd.nextDouble() < c.getDouble("cambios.zombies_espada", 1.0)) {
            ItemStack esp = new ItemStack(rnd.nextBoolean() ? Material.IRON_SWORD : Material.DIAMOND_SWORD);
            Enchantment filo = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"));
            if (filo != null && rnd.nextDouble() < c.getDouble("cambios.zombies_espada_encantada", 0.5))
                esp.addUnsafeEnchantment(filo, 1 + rnd.nextInt(Math.max(1, c.getInt("cambios.zombies_filo_max", 5))));   // 1 a 5, todos igual de probables
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

    // ------------------------------------------------------------------ animales con TNT (dia 8)

    private final java.util.Map<java.util.UUID, Long> tntHasta = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> pegaHasta = new java.util.HashMap<>();

    /** Los animales que te ven te persiguen, te pegan y, si estan cerca, te ponen una TNT. Los domesticados y con nombre no. */
    private void pacificos() {
        if (!cb.on("d8.pacificos")) return;
        org.bukkit.configuration.file.FileConfiguration c = pl.getConfig();
        double vision = c.getDouble("cambios.pacificos_vision", 16), tntDist = c.getDouble("cambios.pacificos_tnt_distancia", 3);
        long ahora = System.currentTimeMillis();
        java.util.Set<java.util.UUID> vistos = new java.util.HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || ar.tod4.hook.Cuyes.activo(p)) continue;
            for (Entity en : p.getNearbyEntities(vision, vision / 2, vision)) {
                if (!(en instanceof org.bukkit.entity.Animals || en instanceof org.bukkit.entity.WaterMob) || !(en instanceof org.bukkit.entity.Mob m)) continue;
                if (m.isDead() || !vistos.add(m.getUniqueId())) continue;
                if (m instanceof org.bukkit.entity.Tameable t && t.isTamed()) continue;
                if (m.customName() != null || m.hasMetadata("shopkeeper") || m.getScoreboardTags().contains("shopkeeper") || m.isLeashed()) continue;
                if (!m.hasLineOfSight(p)) continue;
                double d = m.getLocation().distance(p.getLocation());
                m.getPathfinder().moveTo(p, c.getDouble("cambios.pacificos_velocidad", 1.3));
                if (d <= 1.8 && ahora >= pegaHasta.getOrDefault(m.getUniqueId(), 0L)) {
                    pegaHasta.put(m.getUniqueId(), ahora + 1000);
                    p.damage(c.getDouble("cambios.pacificos_dano", 2.0), m);
                }
                if (d <= tntDist && ahora >= tntHasta.getOrDefault(m.getUniqueId(), 0L)) {
                    tntHasta.put(m.getUniqueId(), ahora + (long) (c.getDouble("cambios.pacificos_tnt_cada_seg", 10) * 1000));
                    m.getWorld().spawn(m.getLocation(), org.bukkit.entity.TNTPrimed.class, tnt -> {
                        tnt.setFuseTicks(c.getInt("cambios.pacificos_tnt_mecha", 40));
                        tnt.setSource(m);
                    });
                    m.getWorld().playSound(m.getLocation(), org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                }
            }
        }
        if (tntHasta.size() > 500) tntHasta.values().removeIf(v -> v < ahora);
        if (pegaHasta.size() > 500) pegaHasta.values().removeIf(v -> v < ahora);
    }

    // ------------------------------------------------------------------ esqueleto ballestero (dia 10)

    static final String BALLESTERO = "esqueleto_ballestero";
    private final java.util.Map<java.util.UUID, Integer> recarga = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void esqueleto(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.SKELETON || !cb.on("d10.ballesteros")) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (ThreadLocalRandom.current().nextDouble() >= pl.getConfig().getDouble("cambios.ballestero_chance", 0.15)) return;
        org.bukkit.entity.Skeleton s = (org.bukkit.entity.Skeleton) e.getEntity();
        armarBallestero(s);
    }

    /** Tambien lo usa /tod4:creeper ballestero? No: se prueba con /tod4 ballestero (ver TOD4). */
    void armarBallestero(org.bukkit.entity.Skeleton s) {
        org.bukkit.configuration.file.FileConfiguration c = pl.getConfig();
        s.addScoreboardTag(BALLESTERO);
        s.customName(ar.tod4.util.Txt.c(c.getString("cambios.ballestero_nombre", "&cEsqueleto Ballestero")));
        s.setCustomNameVisible(false);
        org.bukkit.attribute.AttributeInstance v = s.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double vida = c.getDouble("cambios.ballestero_vida", 40);
        if (v != null) { v.setBaseValue(vida); s.setHealth(vida); }
        EntityEquipment eq = s.getEquipment();
        if (eq == null) return;
        ItemStack ball = new ItemStack(Material.CROSSBOW);
        Enchantment po = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("power")), qc = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("quick_charge"));
        if (po != null) ball.addUnsafeEnchantment(po, 5);
        if (qc != null) ball.addUnsafeEnchantment(qc, 3);
        eq.setItemInMainHand(ball);
        eq.setItemInMainHandDropChance((float) c.getDouble("cambios.ballestero_drop_ballesta", 0.0));
        org.bukkit.Color rojo = org.bukkit.Color.fromRGB(0xB02E26);
        Material[] cuero = {Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
        for (int i = 0; i < 4; i++) {
            ItemStack it = new ItemStack(cuero[i]);
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) { lm.setColor(rojo); it.setItemMeta(lm); }
            eq.setItem(ARMADURA[i], it);
            eq.setDropChance(ARMADURA[i], 0f);
        }
        sinCuerpoACuerpo(s);
    }

    /** Con ballesta, el esqueleto de Minecraft corre a pegarte: se le saca eso y dispara el plugin. */
    private void sinCuerpoACuerpo(org.bukkit.entity.Skeleton s) {
        Bukkit.getScheduler().runTask(pl, () -> {
            try {
                for (com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Skeleton> g : new java.util.ArrayList<>(Bukkit.getMobGoals().getAllGoals(s)))
                    if (g.getKey().getNamespacedKey().getKey().toLowerCase().contains("melee")) Bukkit.getMobGoals().removeGoal(s, g);
            } catch (Throwable ignored) { }
        });
    }

    private int tickB;

    private void ballesteros() {
        tickB++;
        org.bukkit.configuration.file.FileConfiguration c = pl.getConfig();
        int cada = Math.max(1, (int) Math.round(c.getDouble("cambios.ballestero_cada_seg", 1.5) * 4));   // la tarea corre cada 5 ticks
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            for (Entity en : p.getNearbyEntities(32, 16, 32)) {
                if (!(en instanceof org.bukkit.entity.Skeleton s) || !s.getScoreboardTags().contains(BALLESTERO) || s.isDead()) continue;
                if (tickB % 8 == 0) sinCuerpoACuerpo(s);   // si el chunk se recargo, el juego le vuelve a poner el cuerpo a cuerpo
                if (!(s.getTarget() instanceof Player t) || t != p || !s.hasLineOfSight(p)) continue;
                double d = s.getLocation().distance(p.getLocation());
                if (d > 28) continue;
                int r = recarga.getOrDefault(s.getUniqueId(), 0) + 1;
                if (r < cada) { recarga.put(s.getUniqueId(), r); continue; }
                recarga.put(s.getUniqueId(), 0);
                disparar(s, p, c);
            }
        }
        if (recarga.size() > 300) recarga.keySet().removeIf(u -> Bukkit.getEntity(u) == null);
    }

    private void disparar(org.bukkit.entity.Skeleton s, Player p, org.bukkit.configuration.file.FileConfiguration c) {
        Location ojo = s.getEyeLocation();
        org.bukkit.util.Vector a = p.getLocation().add(0, p.getHeight() * 0.6, 0).toVector().subtract(ojo.toVector());
        double dist = a.length();
        a.setY(a.getY() + dist * 0.06);   // un poco mas alto por la caida de la flecha
        double vel = c.getDouble("cambios.ballestero_velocidad", 3.15);
        org.bukkit.entity.Arrow f = s.launchProjectile(org.bukkit.entity.Arrow.class, a.normalize().multiply(vel));
        f.setDamage(c.getDouble("cambios.ballestero_dano_base", 5.0));   // flecha comun 2 + Poder V (3)
        f.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        f.setShotFromCrossbow(true);
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ITEM_CROSSBOW_SHOOT, 1f, 1f);
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
