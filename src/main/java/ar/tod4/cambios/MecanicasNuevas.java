package ar.tod4.cambios;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import ar.tod4.TOD4;

/**
 * Cambios nuevos de los dias 7 a 14 (los numeros y chances se pueden cambiar en config.yml, seccion cambios.*):
 *  - d7.plantas:  todas las plantas y cultivos crecen 3 veces mas lento (el polvo de hueso no cambia).
 *  - d7.xp:       los mobs hostiles que mata un jugador (tambien los de MythicMobs) sueltan el doble de experiencia (no los de spawner),
 *                 y los minerales del Overworld dan el doble al picarlos (el cuarzo y el oro del Nether no).
 *  - d8.wither:   el Wither x2: doble vida y dano, craneos mas fuertes y mas seguidos, efecto Wither mas fuerte y largo,
 *                 mas rapido y la explosion del principio el doble de grande.
 *  - d8.equipo:   +5% de dano recibido por cada companero de equipo muerto (en el limbo, sin revivir). Sin tope.
 *  - d9.hueso:    el polvo de hueso no hace nada.
 *  - d9.esqueletos: los esqueletos salen con armadura (como los zombies), arco con Poder 1 a 3 y flechas especiales.
 *  - d10.minero:  Zombie Minero (cava hasta vos y pone TNT el doble de fuerte). Sale mas cuanto mas profundo.
 *  - d11.hambre:  el hambre baja el doble de rapido.
 *  - d12.noches_peligrosas: de noche, en el Overworld, los mobs hostiles pegan el doble.
 *  - d14.totem:   cuando el totem te salva, 3 segundos de ceguera.
 */
public final class MecanicasNuevas implements Listener {
    private final TOD4 pl;
    private final Cambios cb;
    private int tick;

    MecanicasNuevas(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaMedioSegundo, 20L, 10L);
    }

    private FileConfiguration c() { return pl.getConfig(); }

    private static boolean jugando(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead();
    }

    private void cadaMedioSegundo() {
        tick++;
        if (tick % 4 == 0) soloCorazones();   // cada 2 segundos
        mineros();
        if (tick % 20 == 0) mineroAparece();   // cada 10 segundos
        if (tick % 2 == 0) withers();
    }

    // ================================================================= d7.plantas: 3 veces mas lento

    private static final Set<Material> PLANTAS = EnumSet.noneOf(Material.class);
    static {
        for (String n : new String[]{"WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA", "SWEET_BERRY_BUSH", "CACTUS",
                "SUGAR_CANE", "BAMBOO", "BAMBOO_SAPLING", "KELP", "KELP_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT", "WEEPING_VINES",
                "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT", "CHORUS_FLOWER", "CHORUS_PLANT", "MELON", "PUMPKIN", "MELON_STEM",
                "PUMPKIN_STEM", "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM", "TORCHFLOWER_CROP", "TORCHFLOWER", "PITCHER_CROP",
                "PITCHER_PLANT", "VINE", "BROWN_MUSHROOM", "RED_MUSHROOM", "GLOW_BERRIES", "SEA_PICKLE"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) PLANTAS.add(m);
        }
    }

    /** Bloques a los que se les puso polvo de hueso en este tick (eso no lo frena d7.plantas). */
    private final Map<Long, Integer> hueso = new HashMap<>();

    private static long clave(Block b) { return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL); }

    private boolean conHueso(Block b) {
        Integer t = hueso.get(clave(b));
        return t != null && Bukkit.getCurrentTick() - t <= 1;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void marcaHueso(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getItem() == null || e.getItem().getType() != Material.BONE_MEAL) return;
        if (hueso.size() > 200) hueso.clear();
        hueso.put(clave(e.getClickedBlock()), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void marcaHueso(BlockDispenseEvent e) {
        if (e.getItem().getType() != Material.BONE_MEAL || !(e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        if (hueso.size() > 200) hueso.clear();
        hueso.put(clave(e.getBlock().getRelative(d.getFacing())), Bukkit.getCurrentTick());
    }

    /** true = este crecimiento no pasa (pasan 1 de cada 3). */
    private boolean frenar() {
        double f = Math.max(1, c().getDouble("cambios.plantas_factor", 3));
        return ThreadLocalRandom.current().nextDouble() >= 1 / f;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void crece(BlockGrowEvent e) {
        if (e instanceof BlockFormEvent || !cb.on("d7.plantas")) return;   // BlockFormEvent: nieve, hielo, concreto... no son plantas
        if (!PLANTAS.contains(e.getNewState().getType()) && !PLANTAS.contains(e.getBlock().getType())) return;
        if (conHueso(e.getBlock())) return;
        if (frenar()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void crece(BlockSpreadEvent e) {
        if (!cb.on("d7.plantas")) return;
        if (!PLANTAS.contains(e.getNewState().getType()) && !PLANTAS.contains(e.getSource().getType())) return;
        if (frenar()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void arbol(StructureGrowEvent e) {
        if (e.isFromBonemeal()) {
            if (cb.on("d9.hueso")) e.setCancelled(true);
            return;
        }
        if (cb.on("d7.plantas") && frenar()) e.setCancelled(true);
    }

    // ================================================================= d9.hueso: el polvo de hueso no hace nada

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hueso(BlockFertilizeEvent e) {
        if (cb.on("d9.hueso")) e.setCancelled(true);
    }

    // ================================================================= d7.xp: doble experiencia de los hostiles

    @EventHandler(priority = EventPriority.HIGH)
    public void xp(EntityDeathEvent e) {
        if (!cb.on("d7.xp") || e.getEntity() instanceof Player || e.getEntity().getKiller() == null) return;
        // hostiles comunes y los de MythicMobs (dan la experiencia de su mob base: un zombie de MythicMobs, la de un zombie)
        if (!(e.getEntity() instanceof Enemy) && ar.tod4.hook.Ext.mythic(e.getEntity()) == null) return;
        CreatureSpawnEvent.SpawnReason r = e.getEntity().getEntitySpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.SPAWNER || r == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) return;
        e.setDroppedExp((int) Math.round(e.getDroppedExp() * c().getDouble("cambios.xp_multiplicador", 2)));
    }

    /** Minerales del Overworld (carbon, redstone, lapis, diamante, esmeralda y sus versiones de pizarra): el doble de experiencia. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void xpMineral(org.bukkit.event.block.BlockBreakEvent e) {
        if (!cb.on("d7.xp") || e.getExpToDrop() <= 0) return;
        String n = e.getBlock().getType().name();
        if (!n.endsWith("_ORE") || n.startsWith("NETHER_")) return;   // el cuarzo y el oro del Nether no cuentan
        e.setExpToDrop((int) Math.round(e.getExpToDrop() * c().getDouble("cambios.xp_multiplicador", 2)));
    }

    // ================================================================= d8.wither: el Wither x2

    static final String WITHER = "wither_x2";

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void witherNace(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Wither w) || !cb.on("d8.wither")) return;
        // un tick despues: asi ya se sabe si es de MythicMobs (esos no se tocan) y ya tiene la carga del principio
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!w.isValid() || ar.tod4.hook.Ext.mythic(w) != null) return;
            w.addScoreboardTag(WITHER);
            double x = c().getDouble("cambios.wither.vida", 2);
            AttributeInstance v = w.getAttribute(Attribute.MAX_HEALTH);
            if (v != null) {
                double antes = w.getHealth();
                v.setBaseValue(v.getBaseValue() * x);
                w.setHealth(Math.min(v.getValue(), antes * x));
            }
            double vel = c().getDouble("cambios.wither.velocidad", 1.5);
            for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
                AttributeInstance ai = w.getAttribute(a);
                if (ai != null) ai.setBaseValue(ai.getBaseValue() * vel);
            }
        });
    }

    private static int carga(Wither w) {
        for (String m : new String[]{"getInvulnerabilityTicks", "getInvulnerableTicks"}) {
            try { return (int) w.getClass().getMethod(m).invoke(w); } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** Mientras carga al principio se cura el doble (si no, con el doble de vida no llegaba a llenarse). */
    private void withers() {
        if (!cb.on("d8.wither")) return;
        for (World wo : Bukkit.getWorlds()) for (Wither w : wo.getEntitiesByClass(Wither.class)) {
            if (!w.getScoreboardTags().contains(WITHER) || w.isDead() || carga(w) <= 0) continue;
            AttributeInstance v = w.getAttribute(Attribute.MAX_HEALTH);
            if (v != null) w.setHealth(Math.min(v.getValue(), w.getHealth() + 10));
        }
    }

    private static Wither witherDe(Entity d) {
        if (d instanceof Wither w) return w.getScoreboardTags().contains(WITHER) ? w : null;
        if (d instanceof Projectile p && p.getShooter() instanceof Wither w) return w.getScoreboardTags().contains(WITHER) ? w : null;
        return null;
    }

    /** Pega el doble (los craneos y sus explosiones). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void witherPega(EntityDamageByEntityEvent e) {
        if (!cb.on("d8.wither") || witherDe(e.getDamager()) == null) return;
        if (e.getEntity() instanceof Wither) return;
        e.setDamage(e.getDamage() * c().getDouble("cambios.wither.dano", 2));
        if (e.getEntity() instanceof LivingEntity le) witherGolpe.put(le.getUniqueId(), Bukkit.getCurrentTick());
    }

    private final Map<UUID, Integer> witherGolpe = new HashMap<>();
    private boolean poniendo;

    /** El efecto Wither de sus craneos: un nivel mas y el doble de largo. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void witherEfecto(EntityPotionEffectEvent e) {
        if (poniendo || !cb.on("d8.wither") || e.getNewEffect() == null || e.getNewEffect().getType() != PotionEffectType.WITHER) return;
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Integer t = witherGolpe.get(le.getUniqueId());
        if (t == null || Bukkit.getCurrentTick() - t > 2) return;
        PotionEffect n = e.getNewEffect();
        PotionEffect fuerte = new PotionEffect(PotionEffectType.WITHER, (int) (n.getDuration() * c().getDouble("cambios.wither.efecto_duracion", 2)),
                n.getAmplifier() + c().getInt("cambios.wither.efecto_niveles", 1), n.isAmbient(), n.hasParticles(), n.hasIcon());
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!le.isValid()) return;
            poniendo = true;
            try { le.addPotionEffect(fuerte); } finally { poniendo = false; }
        });
    }

    private boolean extra;

    /** Dispara mas seguido: por cada craneo, otro un ratito despues. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void witherDispara(ProjectileLaunchEvent e) {
        if (extra || !cb.on("d8.wither") || !(e.getEntity() instanceof WitherSkull sk) || !(sk.getShooter() instanceof Wither w)) return;
        if (!w.getScoreboardTags().contains(WITHER)) return;
        if (ThreadLocalRandom.current().nextDouble() >= c().getDouble("cambios.wither.disparo_extra", 1.0)) return;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!w.isValid() || w.isDead() || carga(w) > 0) return;
            LivingEntity t = w.getTarget();
            if (t == null || !t.isValid() || t.getWorld() != w.getWorld()) return;
            Location o = w.getEyeLocation();
            Vector dir = t.getEyeLocation().toVector().subtract(o.toVector());
            if (dir.lengthSquared() < 0.01) return;
            extra = true;
            try {
                WitherSkull s2 = w.launchProjectile(WitherSkull.class, dir.normalize());
                s2.setCharged(ThreadLocalRandom.current().nextDouble() < 0.1);
            } finally { extra = false; }
            w.getWorld().playSound(o, Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 1f, 1f);
        }, 6L);
    }

    /** Craneos mas fuertes y la explosion del principio el doble de grande. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void witherExplota(ExplosionPrimeEvent e) {
        if (!cb.on("d8.wither")) return;
        if (e.getEntity() instanceof Wither w && w.getScoreboardTags().contains(WITHER))
            e.setRadius(e.getRadius() * (float) c().getDouble("cambios.wither.explosion_inicio", 2));
        else if (e.getEntity() instanceof WitherSkull sk && sk.getShooter() instanceof Wither w && w.getScoreboardTags().contains(WITHER))
            e.setRadius(e.getRadius() * (float) c().getDouble("cambios.wither.explosion_craneo", 2));
    }

    // ================================================================= d8.equipo: +5% de dano por companero muerto

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void equipo(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !cb.on("d8.equipo") || pl.equipos() == null) return;
        if (ar.tod4.hook.Cuyes.activo(p) || ar.tod4.hook.Gulag.participa(p.getUniqueId())) return;
        int n = pl.equipos().caidos(p.getUniqueId());
        if (n > 0) e.setDamage(e.getDamage() * (1 + n * pl.equipos().porCaido() / 100.0));
    }

    // ================================================================= solitario (sin equipo de verdad)

    private boolean solo(Player p) { return pl.equipos() != null && pl.equipos().solo(p); }

    /** d10.solo_uhc: no regenera sola (comida, pacifico). Las manzanas doradas y las pociones si curan. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void soloUhc(org.bukkit.event.entity.EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || !cb.on("d10.solo_uhc") || !solo(p)) return;
        switch (e.getRegainReason()) {
            case SATIATED, REGEN, EATING -> e.setCancelled(true);
            default -> { }
        }
    }

    /** d10.solo_corazones: cada 2 segundos, los corazones rojos se sacan o se devuelven segun tenga equipo o no. */
    private void soloCorazones() {
        if (pl.corazon() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.isDead()) pl.corazon().aplicar(p);
    }

    // ================================================================= d9.esqueletos: armadura, arco con Poder y flechas especiales

    static final String ARMADO = "esqueleto_armado";
    private static final List<String> FLECHAS = List.of("dano", "levitacion", "ceguera", "lentitud");
    private static final EquipmentSlot[] ARMADURA = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void esqueleto(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.SKELETON || !cb.on("d9.esqueletos")) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!(e.getEntity() instanceof Skeleton s) || s.getScoreboardTags().contains(MecanicasTarde.BALLESTERO)) return;
        armarEsqueleto(s);
    }

    void armarEsqueleto(Skeleton s) {
        EntityEquipment eq = s.getEquipment();
        if (eq == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        FileConfiguration c = c();
        float drop = (float) c.getDouble("cambios.zombies_drop", 0.0);
        s.addScoreboardTag(ARMADO);
        // la armadura, con las mismas chances que los zombies del dia 9
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
        // arco con Poder 1 a 3
        ItemStack arco = new ItemStack(Material.BOW);
        Enchantment po = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("power"));
        int pmin = c.getInt("cambios.esqueletos.poder_min", 1), pmax = c.getInt("cambios.esqueletos.poder_max", 3);
        if (po != null) arco.addUnsafeEnchantment(po, pmin + rnd.nextInt(Math.max(1, pmax - pmin + 1)));
        eq.setItemInMainHand(arco);
        eq.setItemInMainHandDropChance(drop);
        // su tipo de flecha
        s.addScoreboardTag("esq_" + FLECHAS.get(rnd.nextInt(FLECHAS.size())));
    }

    private static Material pieza(EquipmentSlot s, boolean diamante) {
        return switch (s) {
            case HEAD -> diamante ? Material.DIAMOND_HELMET : Material.IRON_HELMET;
            case CHEST -> diamante ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE;
            case LEGS -> diamante ? Material.DIAMOND_LEGGINGS : Material.IRON_LEGGINGS;
            default -> diamante ? Material.DIAMOND_BOOTS : Material.IRON_BOOTS;
        };
    }

    private static String flechaDe(Entity s) {
        for (String f : FLECHAS) if (s.getScoreboardTags().contains("esq_" + f)) return f;
        return null;
    }

    /** La flecha sale con el color de su efecto (se ven las particulas); el efecto se pone cuando te pega. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void esqueletoDispara(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Skeleton s) || !(e.getProjectile() instanceof Arrow a)) return;
        String f = flechaDe(s);
        if (f == null) return;
        a.addScoreboardTag("esq_" + f);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setColor(switch (f) {
            case "dano" -> org.bukkit.Color.fromRGB(0xA9656A);
            case "levitacion" -> org.bukkit.Color.fromRGB(0xCEFFFF);
            case "ceguera" -> org.bukkit.Color.fromRGB(0x1F1F23);
            default -> org.bukkit.Color.fromRGB(0x8BAFE0);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void flechaPega(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Arrow a) || !(e.getEntity() instanceof LivingEntity le)) return;
        String f = flechaDe(a);
        if (f == null) return;
        FileConfiguration c = c();
        PotionEffect ef = switch (f) {
            case "dano" -> new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, c.getInt("cambios.esqueletos.dano_nivel", 2) - 1);
            case "levitacion" -> new PotionEffect(PotionEffectType.LEVITATION, (int) (c.getDouble("cambios.esqueletos.levitacion_seg", 3) * 20),
                    c.getInt("cambios.esqueletos.levitacion_nivel", 5) - 1);
            case "ceguera" -> new PotionEffect(PotionEffectType.BLINDNESS, (int) (c.getDouble("cambios.esqueletos.ceguera_seg", 5) * 20), 0);
            default -> new PotionEffect(PotionEffectType.SLOWNESS, (int) (c.getDouble("cambios.esqueletos.lentitud_seg", 5) * 20),
                    c.getInt("cambios.esqueletos.lentitud_nivel", 3) - 1);
        };
        Bukkit.getScheduler().runTask(pl, () -> { if (le.isValid() && !le.isDead()) le.addPotionEffect(ef); });
    }

    // ================================================================= d10.minero: Zombie Minero

    static final String MINERO = "zombie_minero";
    private final Map<UUID, Location> mineroAntes = new HashMap<>();
    private final Map<UUID, Long> mineroTnt = new HashMap<>(), mineroPica = new HashMap<>();

    /** Cada 10 segundos, por jugador bajo tierra: cuanto mas abajo, mas chance (a Y -50, uno cada 5 minutos). */
    private void mineroAparece() {
        if (!cb.on("d10.minero")) return;
        FileConfiguration c = c();
        double techo = c.getDouble("cambios.minero.techo_y", 40), ref = c.getDouble("cambios.minero.referencia_y", -50);
        double cadaSeg = Math.max(10, c.getDouble("cambios.minero.minutos_en_referencia", 5) * 60);
        int max = c.getInt("cambios.minero.max_por_jugador", 2);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!jugando(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL || ar.tod4.hook.Cuyes.activo(p)) continue;
            double y = p.getLocation().getY();
            if (y >= techo) continue;
            double chance = (techo - y) / (techo - ref) * 10 / cadaSeg;
            if (rnd.nextDouble() >= chance) continue;
            int hay = 0;
            for (Entity en : p.getNearbyEntities(64, 32, 64)) if (en.getScoreboardTags().contains(MINERO)) hay++;
            if (hay >= max) continue;
            Location at = cueva(p, techo);
            if (at != null) crearMinero(at);
        }
    }

    /** Un lugar con dos bloques libres y piso, entre 12 y 28 bloques del jugador, debajo del techo. */
    private Location cueva(Player p, double techo) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = p.getWorld();
        for (int i = 0; i < 16; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 12 + rnd.nextDouble() * 16;
            int x = (int) Math.floor(p.getLocation().getX() + Math.cos(a) * d), z = (int) Math.floor(p.getLocation().getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y0 = p.getLocation().getBlockY();
            for (int y = y0 + 8; y >= Math.max(w.getMinHeight() + 1, y0 - 8); y--) {
                if (y >= techo) continue;
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isSolid() || b.getType() == Material.BEDROCK) continue;
                Block a1 = b.getRelative(0, 1, 0), a2 = b.getRelative(0, 2, 0);
                if (!a1.isPassable() || !a2.isPassable() || a1.isLiquid() || a2.isLiquid()) continue;
                Location at = a1.getLocation().add(0.5, 0, 0.5);
                if (at.distanceSquared(p.getLocation()) < 10 * 10) continue;
                return at;
            }
        }
        return null;
    }

    /** Tambien lo usa /tod4 minero para probar. */
    Zombie crearMinero(Location at) {
        FileConfiguration c = c();
        return at.getWorld().spawn(at, Zombie.class, z -> {
            z.addScoreboardTag(MINERO);
            z.setAdult();
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
            z.customName(ar.tod4.util.Txt.c(c.getString("cambios.minero.nombre", "&6Zombie Minero")));
            z.setCustomNameVisible(false);
            AttributeInstance v = z.getAttribute(Attribute.MAX_HEALTH);
            double vida = c.getDouble("cambios.minero.vida", 50);
            if (v != null) { v.setBaseValue(vida); z.setHealth(vida); }
            EntityEquipment eq = z.getEquipment();
            if (eq == null) return;
            eq.clear();
            eq.setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
            eq.setItemInMainHandDropChance(0f);
            // cada parte: 25% de oro con Proteccion 1 a 4
            Enchantment pro = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("protection"));
            Material[] oro = {Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS};
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < 4; i++) {
                if (rnd.nextDouble() >= c.getDouble("cambios.minero.oro_chance", 0.25)) continue;
                ItemStack it = new ItemStack(oro[i]);
                if (pro != null) it.addUnsafeEnchantment(pro, 1 + rnd.nextInt(4));
                eq.setItem(ARMADURA[i], it);
                eq.setDropChance(ARMADURA[i], 0f);
            }
        });
    }

    /** Te ve a traves de los bloques, camina hacia vos, si se traba cava, y de cerca pone TNT. */
    private void mineros() {
        if (!cb.on("d10.minero")) return;
        FileConfiguration c = c();
        double vision = c.getDouble("cambios.minero.vision", 40), tntDist = c.getDouble("cambios.minero.tnt_distancia", 4);
        long ahora = System.currentTimeMillis();
        Set<UUID> vistos = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!jugando(p) || ar.tod4.hook.Cuyes.activo(p)) continue;
            for (Entity en : p.getNearbyEntities(vision, vision / 2, vision)) {
                if (!(en instanceof Zombie z) || !z.getScoreboardTags().contains(MINERO) || z.isDead() || !vistos.add(z.getUniqueId())) continue;
                // el jugador mas cercano, aunque no lo vea
                Player obj = p;
                if (z.getTarget() instanceof Player t && jugando(t) && t.getWorld() == z.getWorld()
                        && t.getLocation().distanceSquared(z.getLocation()) <= p.getLocation().distanceSquared(z.getLocation())) obj = t;
                if (z.getTarget() != obj) z.setTarget(obj);
                z.getPathfinder().moveTo(obj, 1.0);
                double d = z.getLocation().distance(obj.getLocation());
                // TNT
                if (d <= tntDist && ahora >= mineroTnt.getOrDefault(z.getUniqueId(), 0L)) {
                    mineroTnt.put(z.getUniqueId(), ahora + (long) (c.getDouble("cambios.minero.tnt_cada_seg", 8) * 1000));
                    z.getWorld().spawn(z.getLocation(), TNTPrimed.class, t -> {
                        t.setFuseTicks(c.getInt("cambios.minero.tnt_mecha", 50));
                        t.setYield((float) (4 * c.getDouble("cambios.minero.tnt_fuerza", 2)));
                        t.setSource(z);
                        t.addScoreboardTag("minero_tnt");
                    });
                    z.getWorld().playSound(z.getLocation(), Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 1f, 1f);
                }
                // trabado: cava hacia el jugador (un bloque por vez)
                Location antes = mineroAntes.get(z.getUniqueId());
                if (tick % 2 == 0) mineroAntes.put(z.getUniqueId(), z.getLocation());
                if (antes == null || antes.getWorld() != z.getWorld() || d < 1.8) continue;
                if (antes.distanceSquared(z.getLocation()) > 0.5 * 0.5) continue;
                if (ahora < mineroPica.getOrDefault(z.getUniqueId(), 0L)) continue;
                if (cavar(z, obj)) mineroPica.put(z.getUniqueId(), ahora + (long) (c.getDouble("cambios.minero.cava_cada_seg", 0.75) * 1000));
            }
        }
        if (mineroAntes.size() > 200) mineroAntes.keySet().removeIf(u -> Bukkit.getEntity(u) == null);
        if (mineroTnt.size() > 200) mineroTnt.values().removeIf(v -> v < ahora);
        if (mineroPica.size() > 200) mineroPica.values().removeIf(v -> v < ahora);
    }

    /** Rompe el primer bloque que le tapa el camino hacia el jugador (escalera para arriba o para abajo si hace falta). */
    private boolean cavar(Zombie z, Player obj) {
        Location zl = z.getLocation(), pl0 = obj.getLocation();
        double dx = pl0.getX() - zl.getX(), dz = pl0.getZ() - zl.getZ(), dy = pl0.getY() - zl.getY();
        Block pies = zl.getBlock();
        List<Block> romper = new java.util.ArrayList<>();
        if (Math.hypot(dx, dz) < 1.2) {
            if (dy < -1.5) romper.add(pies.getRelative(BlockFace.DOWN));
            else if (dy > 1.5) romper.add(pies.getRelative(0, 2, 0));
        } else {
            BlockFace f = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST) : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            Block frente = pies.getRelative(f);
            if (dy > 1.5) {   // para arriba: deja el bloque de adelante como escalon
                romper.add(pies.getRelative(0, 2, 0));
                romper.add(frente.getRelative(0, 1, 0));
                romper.add(frente.getRelative(0, 2, 0));
            } else if (dy < -1.5) {   // para abajo
                romper.add(frente.getRelative(0, 1, 0));
                romper.add(frente);
                romper.add(frente.getRelative(BlockFace.DOWN));
            } else {
                romper.add(frente.getRelative(0, 1, 0));
                romper.add(frente);
            }
        }
        for (Block b : romper) {
            if (!rompible(b)) continue;
            z.swingMainHand();
            b.breakNaturally(new ItemStack(Material.IRON_PICKAXE), true);
            return true;
        }
        return false;
    }

    private static boolean rompible(Block b) {
        if (b.isEmpty() || b.isLiquid() || b.isPassable()) return false;
        float h = b.getType().getHardness();
        if (h < 0 || h >= 50) return false;                    // bedrock, obsidiana...
        return !(b.getState() instanceof org.bukkit.block.Container);   // cofres, barriles, hornos: no
    }

    /** 3 corazones por golpe; y no se lastima con su propia TNT. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void mineroPega(EntityDamageByEntityEvent e) {
        if (e.getDamager().getScoreboardTags().contains(MINERO) && e.getEntity() instanceof Player)
            e.setDamage(c().getDouble("cambios.minero.dano", 6));
        else if (e.getDamager() instanceof TNTPrimed t && t.getScoreboardTags().contains("minero_tnt") && e.getEntity().getScoreboardTags().contains(MINERO))
            e.setCancelled(true);
    }

    // ================================================================= d11.hambre

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hambre(EntityExhaustionEvent e) {
        if (cb.on("d11.hambre") && e.getEntity() instanceof Player)
            e.setExhaustion(e.getExhaustion() * (float) c().getDouble("cambios.hambre_multiplicador", 2));
    }

    // ================================================================= d12.noches_peligrosas

    /** Solo el Overworld (el mundo principal), de noche: desde que se puede dormir hasta que amanece. */
    private boolean nochePeligrosa(World w) {
        return cb.on("d12.noches_peligrosas") && w.equals(Bukkit.getWorlds().get(0)) && Sueno.deNoche(pl, w.getTime());
    }

    void apagar() { }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void nochePega(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !nochePeligrosa(p.getWorld())) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        else if (d instanceof TNTPrimed t && t.getSource() != null) d = t.getSource();
        if (!(d instanceof Enemy) || d instanceof Player) return;
        e.setDamage(e.getDamage() * c().getDouble("cambios.noches_dano", 2));
    }

    // ================================================================= d14.totem: ceguera al salvarte

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void totem(EntityResurrectEvent e) {
        if (!cb.on("d14.totem") || !(e.getEntity() instanceof Player p)) return;
        // los totems de /tod4:totems no dan ceguera
        ItemStack usado = null;
        EquipmentSlot mano = e.getHand();
        if (mano != null) usado = p.getInventory().getItem(mano);
        if (Totems.libre(pl, usado)) return;
        int t = (int) (c().getDouble("cambios.totem_ceguera_seg", 2) * 20);
        // un tick despues: el totem borra los efectos al salvarte
        Bukkit.getScheduler().runTask(pl, () -> {
            if (p.isOnline() && !p.isDead()) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, t, 0));
        });
    }

    // ================================================================= d8.herramientas: solo las herramientas TOD

    private static final NamespacedKey ORAXEN_ID = new NamespacedKey("oraxen", "id");

    /** Romper un bloque de pico o de hacha con otra cosa que no sea el TOD Pickaxe o el TOD Axe: medio corazon fijo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void herramientas(org.bukkit.event.block.BlockBreakEvent e) {
        Material m = e.getBlock().getType();
        if (m.getHardness() <= 0) return;   // los que se rompen al toque no cuentan
        if (!org.bukkit.Tag.MINEABLE_PICKAXE.isTagged(m) && !org.bukkit.Tag.MINEABLE_AXE.isTagged(m)) return;
        castigoHerramienta(e.getPlayer());
    }

    /** El castigo de d8.herramientas si no tiene una herramienta TOD en la mano (tambien lo usan los bloques del meteorito). */
    public void castigoHerramienta(Player p) {
        if (!cb.on("d8.herramientas")) return;
        if (!jugando(p) || ar.tod4.hook.Cuyes.activo(p)) return;
        ItemStack mano = p.getInventory().getItemInMainHand();
        String ox = mano.hasItemMeta() ? mano.getItemMeta().getPersistentDataContainer().get(ORAXEN_ID, org.bukkit.persistence.PersistentDataType.STRING) : null;
        List<String> ok = c().getStringList("cambios.herramientas.permitidas");
        if (ok.isEmpty()) ok = List.of("todpico", "todaxe");
        if (ox != null && ok.contains(ox)) return;
        double dano = c().getDouble("cambios.herramientas.dano", 1.0);
        double h = p.getHealth();
        if (h - dano > 0.0001) {
            p.damage(0.0001);                         // el golpe (sonido y animacion)
            if (!p.isDead()) p.setHealth(Math.max(0.0001, Math.min(p.getHealth(), h) - dano));
        } else {
            ar.tod4.core.Causas.anotar(p.getUniqueId(), net.kyori.adventure.text.Component.text(p.getName() + " picó sin herramientas TOD"));
            p.damage(1000);
        }
    }

    // ================================================================= d13.backrooms: al dormir, a veces despertas en las backrooms

    @EventHandler(priority = EventPriority.MONITOR)
    public void suena(org.bukkit.event.player.PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.OK || !cb.on("d13.backrooms")) return;
        Player p = e.getPlayer();
        if (!jugando(p) || ThreadLocalRandom.current().nextDouble() * 100 >= c().getDouble("cambios.backrooms.chance", 5)) return;
        // un ratito dormido y de golpe... las backrooms
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || !p.isSleeping()) return;
            Location dst = backrooms();
            if (dst == null) { pl.getLogger().warning("Backrooms al dormir: no encuentro el mundo de las backrooms (mobs.mundo_backrooms)."); return; }
            p.wakeup(false);
            p.teleport(dst);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, false, false));
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 0.6f);
            p.sendActionBar(ar.tod4.util.Txt.c("&7...esto no es tu cama."));
        }, (long) (c().getDouble("cambios.backrooms.espera_seg", 2.5) * 20));
    }

    /** A donde caes: el punto puesto con /tod4 backrooms, o el spawn del mundo de las backrooms. */
    private Location backrooms() {
        Location l = pl.datos().y().getLocation("backrooms_destino");
        if (l != null && l.getWorld() != null) return l;
        World w = Bukkit.getWorld(c().getString("mobs.mundo_backrooms", "backrooms"));
        return w == null ? null : w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** /tod4 backrooms: donde caen los que suenan con las backrooms (donde estas parado). */
    void ponerBackrooms(Player p) {
        pl.datos().set("backrooms_destino", p.getLocation());
        ar.tod4.util.Style.ok(p, "Los que caigan en las backrooms al dormir aparecen acá.");
    }

    // ================================================================= d14.totem_falla: algunos totems fallan

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void totemFalla(EntityResurrectEvent e) {
        if (!cb.on("d14.totem_falla") || !(e.getEntity() instanceof Player p)) return;
        EquipmentSlot mano = e.getHand();
        ItemStack usado = mano == null ? null : p.getInventory().getItem(mano);
        if (!Totems.falla(pl, usado)) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= Totems.chance(pl)) return;
        e.setCancelled(true);   // no te salva
        ar.tod4.util.Style.broadcast("&c✖ &fEl tótem de &c" + p.getName() + " &ffalló.");
    }
}
