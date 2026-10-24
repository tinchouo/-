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
 *  - d10.ballesteros: algunos esqueletos salen con ballesta y cuero de color: rojo (flechas Poder V), amarillo (cohetes) o negro (TNT).
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
                    golpeFijo(p, m, c.getDouble("cambios.pacificos_dano", 1.0));
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

    /** Siempre saca exactamente eso (medio corazon), sin que la armadura lo baje. Si no le alcanza la vida, lo mata (el totem salva). */
    private void golpeFijo(Player p, org.bukkit.entity.LivingEntity quien, double dano) {
        double h = p.getHealth();
        if (h - dano > 0.0001) {
            p.damage(0.0001, quien);              // el golpe (sonido, empujon y quien fue)
            if (!p.isDead()) p.setHealth(Math.max(0.0001, Math.min(p.getHealth(), h) - dano));
        } else {
            p.damage(1000, quien);
        }
    }

    // ------------------------------------------------------------------ esqueletos ballesteros (dia 10)
    //  rojo: flechas con Poder V | amarillo: cohetes de fuegos artificiales (dano en area) | negro: TNT

    static final String BALLESTERO = "esqueleto_ballestero";
    static final java.util.List<String> TIPOS_BAL = java.util.List.of("rojo", "amarillo", "negro");
    private final java.util.Map<java.util.UUID, Integer> recarga = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void esqueleto(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.SKELETON || !cb.on("d10.ballesteros")) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= pl.getConfig().getDouble("cambios.ballestero_chance", 0.15)) return;
        // cual de los tres, segun su peso
        double tot = 0;
        for (String t : TIPOS_BAL) tot += Math.max(0, peso(t));
        double x = r.nextDouble() * tot;
        String tipo = "rojo";
        for (String t : TIPOS_BAL) { x -= Math.max(0, peso(t)); if (x < 0) { tipo = t; break; } }
        armarBallestero((org.bukkit.entity.Skeleton) e.getEntity(), tipo);
    }

    private double peso(String t) {
        return pl.getConfig().getDouble("cambios.ballesteros." + t + ".peso", t.equals("rojo") ? 60 : t.equals("amarillo") ? 25 : 15);
    }

    private String conf(String t, String k, String def) { return pl.getConfig().getString("cambios.ballesteros." + t + "." + k, def); }
    private double conf(String t, String k, double def) { return pl.getConfig().getDouble("cambios.ballesteros." + t + "." + k, def); }

    private static String tipoDe(org.bukkit.entity.Skeleton s) {
        for (String t : TIPOS_BAL) if (s.getScoreboardTags().contains("bal_" + t)) return t;
        return "rojo";
    }

    /** Arma un ballestero de ese tipo (tambien lo usa /tod4 ballestero para probar). */
    void armarBallestero(org.bukkit.entity.Skeleton s, String tipo) {
        s.addScoreboardTag(BALLESTERO);
        s.addScoreboardTag("bal_" + tipo);
        String nombre = switch (tipo) { case "amarillo" -> "&eEsqueleto Pirotécnico"; case "negro" -> "&8Esqueleto Dinamitero"; default -> "&cEsqueleto Ballestero"; };
        s.customName(ar.tod4.util.Txt.c(conf(tipo, "nombre", nombre)));
        s.setCustomNameVisible(false);
        org.bukkit.attribute.AttributeInstance v = s.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double vida = conf(tipo, "vida", tipo.equals("negro") ? 50 : 40);
        if (v != null) { v.setBaseValue(vida); s.setHealth(vida); }
        EntityEquipment eq = s.getEquipment();
        if (eq == null) return;
        ItemStack ball = new ItemStack(Material.CROSSBOW);
        Enchantment po = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("power")), qc = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("quick_charge"));
        if (po != null) ball.addUnsafeEnchantment(po, 5);
        if (qc != null) ball.addUnsafeEnchantment(qc, 3);
        eq.setItemInMainHand(ball);
        eq.setItemInMainHandDropChance(0f);
        int hex = switch (tipo) { case "amarillo" -> 0xFED83D; case "negro" -> 0x1D1D21; default -> 0xB02E26; };
        org.bukkit.Color col = org.bukkit.Color.fromRGB(hex);
        Material[] cuero = {Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
        for (int i = 0; i < 4; i++) {
            ItemStack it = new ItemStack(cuero[i]);
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) { lm.setColor(col); it.setItemMeta(lm); }
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            for (Entity en : p.getNearbyEntities(32, 16, 32)) {
                if (!(en instanceof org.bukkit.entity.Skeleton s) || !s.getScoreboardTags().contains(BALLESTERO) || s.isDead()) continue;
                if (tickB % 8 == 0) sinCuerpoACuerpo(s);   // si el chunk se recargo, el juego le vuelve a poner el cuerpo a cuerpo
                if (!(s.getTarget() instanceof Player t) || t != p || !s.hasLineOfSight(p)) continue;
                String tipo = tipoDe(s);
                double d = s.getLocation().distance(p.getLocation());
                if (d > (tipo.equals("negro") ? 20 : 28)) continue;
                double def = switch (tipo) { case "amarillo" -> 2.5; case "negro" -> 3.5; default -> 1.5; };
                int cada = Math.max(1, (int) Math.round(conf(tipo, "cada_seg", def) * 4));   // la tarea corre cada 5 ticks
                int r = recarga.getOrDefault(s.getUniqueId(), 0) + 1;
                if (r < cada) { recarga.put(s.getUniqueId(), r); continue; }
                recarga.put(s.getUniqueId(), 0);
                switch (tipo) {
                    case "amarillo" -> cohete(s, p);
                    case "negro" -> tnt(s, p);
                    default -> flecha(s, p);
                }
            }
        }
        if (recarga.size() > 300) recarga.keySet().removeIf(u -> Bukkit.getEntity(u) == null);
    }

    private org.bukkit.util.Vector apuntar(org.bukkit.entity.Skeleton s, Player p) {
        return p.getLocation().add(0, p.getHeight() * 0.6, 0).toVector().subtract(s.getEyeLocation().toVector());
    }

    /** Rojo: flecha con Poder V. */
    private void flecha(org.bukkit.entity.Skeleton s, Player p) {
        org.bukkit.util.Vector a = apuntar(s, p);
        a.setY(a.getY() + a.length() * 0.06);   // un poco mas alto por la caida de la flecha
        org.bukkit.entity.Arrow f = s.launchProjectile(org.bukkit.entity.Arrow.class, a.normalize().multiply(conf("rojo", "velocidad", 3.15)));
        f.setDamage(conf("rojo", "dano_base", 5.0));   // flecha comun 2 + Poder V (3)
        f.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        f.setShotFromCrossbow(true);
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ITEM_CROSSBOW_SHOOT, 1f, 1f);
    }

    /** Amarillo: cohete de fuegos artificiales en linea recta; explota al tocar algo (dano en area, mas estrellas = mas dano). */
    private void cohete(org.bukkit.entity.Skeleton s, Player p) {
        org.bukkit.util.Vector a = apuntar(s, p).normalize().multiply(conf("amarillo", "velocidad", 1.6));
        int estrellas = (int) Math.max(1, conf("amarillo", "estrellas", 3));
        Location o = s.getEyeLocation().add(a.clone().normalize().multiply(0.6));
        s.getWorld().spawn(o, org.bukkit.entity.Firework.class, fw -> {
            org.bukkit.inventory.meta.FireworkMeta m = fw.getFireworkMeta();
            m.clearEffects();
            org.bukkit.FireworkEffect.Type[] formas = {org.bukkit.FireworkEffect.Type.BALL_LARGE, org.bukkit.FireworkEffect.Type.BURST, org.bukkit.FireworkEffect.Type.STAR};
            org.bukkit.Color[][] colores = {
                {org.bukkit.Color.YELLOW, org.bukkit.Color.ORANGE},
                {org.bukkit.Color.fromRGB(0xFF3B1F), org.bukkit.Color.YELLOW},
                {org.bukkit.Color.WHITE, org.bukkit.Color.fromRGB(0xFFD54A)}};
            for (int i = 0; i < estrellas; i++) {
                m.addEffect(org.bukkit.FireworkEffect.builder().with(formas[i % formas.length]).withColor(colores[i % colores.length])
                        .withFade(org.bukkit.Color.RED).flicker(true).trail(true).build());
            }
            m.setPower(1);
            fw.setFireworkMeta(m);
            fw.setShotAtAngle(true);
            fw.setShooter(s);
            fw.setVelocity(a);
            fw.setTicksToDetonate((int) Math.min(60, apuntar(s, p).length() / a.length() + 6));
        });
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ITEM_CROSSBOW_SHOOT, 1f, 0.8f);
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
    }

    /** Negro: tira una TNT prendida en arco, que cae cerca tuyo. */
    private void tnt(org.bukkit.entity.Skeleton s, Player p) {
        Location o = s.getEyeLocation();
        org.bukkit.util.Vector d = p.getLocation().toVector().subtract(o.toVector());
        double horiz = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
        double T = Math.max(12, Math.min(40, horiz * 1.6));      // ticks de vuelo
        double g = 0.04;
        org.bukkit.util.Vector v = new org.bukkit.util.Vector(d.getX() / T, (d.getY() + 0.5 * g * T * T) / T, d.getZ() / T).multiply(1.06);   // un poco mas por el roce
        int mecha = (int) Math.round(T + conf("negro", "mecha_extra_ticks", 10));
        s.getWorld().spawn(o, org.bukkit.entity.TNTPrimed.class, t -> {
            t.setFuseTicks(mecha);
            t.setSource(s);
            t.setVelocity(v);
        });
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ITEM_CROSSBOW_SHOOT, 1f, 0.6f);
        s.getWorld().playSound(s.getLocation(), org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f);
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
