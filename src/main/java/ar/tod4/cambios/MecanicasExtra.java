package ar.tod4.cambios;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;

/**
 * Cambios nuevos:
 *  Dia 1  d1.warden     el Warden aparece solo en las Ancient City (saliendo del piso).
 *  Dia 2  d2.silverfish picando debajo de Y 0 puede salir un silverfish que da fatiga minera.
 *  Dia 5  d5.wither     el Wither solo se invoca con la cabeza de un jugador en el pecho: "Wither de <jugador>".
 *  Dia 6  d6.caida      Proteccion ya no baja el dano por caida (solo Caida de Pluma).
 *         d6.bedrock    tocar la bedrock da Wither III y gasta la armadura (se rompe en 1 minuto).
 */
final class MecanicasExtra implements Listener {

    private final TOD4 pl;
    private final Cambios cb;
    private final NamespacedKey POLILLA, NATURAL;
    private final Random rnd = new Random();
    private final Map<UUID, UUID> polillas = new HashMap<>();
    private final Map<UUID, Long> tocoBedrock = new HashMap<>();
    private int ticks;

    MecanicasExtra(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        this.POLILLA = new NamespacedKey(pl, "silverfish_cueva");
        this.NATURAL = new NamespacedKey(pl, "warden_natural");
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada10, 10L, 10L);
    }

    private boolean on(String id) { return cb.on(id); }

    private static boolean juega(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void cada10() {
        ticks++;
        if (on("d6.bedrock")) bedrock();
        int cadaWarden = Math.max(1, pl.getConfig().getInt("cambios.warden_intervalo_seg", 30) * 2);
        if (on("d1.warden") && ticks % cadaWarden == 0) wardens();
    }

    // ================================================================= Dia 1: Warden en las Ancient City

    private void wardens() {
        double chance = pl.getConfig().getDouble("cambios.warden_chance", 0.25);
        int max = pl.getConfig().getInt("cambios.warden_max_por_ciudad", 1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!juega(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            Location pos = p.getLocation();
            if (pos.getY() > 0) continue;
            Collection<GeneratedStructure> ciudades;
            try { ciudades = pos.getChunk().getStructures(Structure.ANCIENT_CITY); } catch (Throwable t) { return; }
            for (GeneratedStructure g : ciudades) {
                BoundingBox bb = g.getBoundingBox();
                if (!bb.contains(pos.toVector())) continue;
                int hay = p.getWorld().getNearbyEntities(bb, en -> en.getType() == EntityType.WARDEN).size();
                if (hay < max && rnd.nextDouble() < chance) {
                    Location l = lugarWarden(p, bb);
                    if (l != null) aparecerWarden(l);
                }
                break;
            }
        }
    }

    /** Un lugar con piso y 3 de alto libre, a 8-20 bloques del jugador, dentro de la ciudad. */
    private Location lugarWarden(Player p, BoundingBox bb) {
        Location base = p.getLocation();
        for (int i = 0; i < 25; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, dist = 8 + rnd.nextDouble() * 12;
            int x = (int) Math.floor(base.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(base.getZ() + Math.sin(ang) * dist);
            for (int dy = 4; dy >= -4; dy--) {
                Block b = base.getWorld().getBlockAt(x, base.getBlockY() + dy, z);
                if (!bb.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)) continue;
                if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()
                        && b.getRelative(0, 3, 0).isPassable() && !b.getRelative(0, 1, 0).isLiquid())
                    return b.getLocation().add(0.5, 1, 0.5);
            }
        }
        return null;
    }

    private void aparecerWarden(Location l) {
        Warden w = l.getWorld().spawn(l, Warden.class, wd -> {
            wd.getPersistentDataContainer().set(NATURAL, PersistentDataType.BYTE, (byte) 1);
            wd.setAI(false);
            pose(wd, Pose.EMERGING, true);
        });
        l.getWorld().playSound(l, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 1f, 1f);
        // la animacion de salir del piso dura ~6.7 segundos; despues se mueve normal
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!w.isValid()) return;
            pose(w, Pose.STANDING, false);
            w.setAI(true);
        }, 134L);
    }

    /** Entity#setPose(Pose, boolean) de Paper (por reflexion, por si cambia entre versiones). */
    private static void pose(Entity e, Pose p, boolean fija) {
        try {
            e.getClass().getMethod("setPose", Pose.class, boolean.class).invoke(e, p, fija);
        } catch (Throwable ignored) { }
    }

    // ================================================================= Dia 2: silverfish en las cuevas

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pica(BlockBreakEvent e) {
        if (!on("d2.silverfish")) return;
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!juega(p) || b.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (b.getY() >= pl.getConfig().getInt("cambios.silverfish_y_max", 0)) return;
        if (!b.getType().isSolid()) return;
        UUID vivo = polillas.get(p.getUniqueId());
        if (vivo != null) {
            Entity en = Bukkit.getEntity(vivo);
            if (en != null && en.isValid()) return; // uno por jugador a la vez
            polillas.remove(p.getUniqueId());
        }
        if (rnd.nextDouble() >= pl.getConfig().getDouble("cambios.silverfish_chance", 0.033)) return;
        Location l = b.getLocation().add(0.5, 0.1, 0.5);
        Silverfish s = b.getWorld().spawn(l, Silverfish.class, sf -> {
            sf.getPersistentDataContainer().set(POLILLA, PersistentDataType.BYTE, (byte) 1);
            AttributeInstance a = sf.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) { a.setBaseValue(a.getBaseValue() * 2); sf.setHealth(a.getBaseValue()); }
        });
        s.setTarget(p);
        polillas.put(p.getUniqueId(), s.getUniqueId());
    }

    private boolean polilla(Entity e) {
        return e instanceof Silverfish && e.getPersistentDataContainer().has(POLILLA, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void muerdeFatiga(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !polilla(e.getDamager())) return;
        int suma = pl.getConfig().getInt("cambios.silverfish_fatiga_seg", 5) * 20;
        int tope = pl.getConfig().getInt("cambios.silverfish_fatiga_max_seg", 30) * 20;
        PotionEffect ya = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        int dur = Math.min(tope, (ya != null ? ya.getDuration() : 0) + suma);
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, dur, 0, false, true, true));
    }

    /** Que no despierte a otros silverfish ni se esconda en la piedra. */
    @EventHandler(ignoreCancelled = true)
    public void noRompe(EntityChangeBlockEvent e) {
        if (polilla(e.getEntity())) e.setCancelled(true);
    }

    // ================================================================= Dia 5: Wither con cabeza de jugador

    private static boolean arena(Block b) {
        return b.getType() == Material.SOUL_SAND || b.getType() == Material.SOUL_SOIL;
    }

    private static boolean craneo(Block b) {
        return b.getType() == Material.WITHER_SKELETON_SKULL || b.getType() == Material.WITHER_SKELETON_WALL_SKULL;
    }

    /** Con el cambio prendido, la forma vanilla (3 craneos) no invoca nada. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void witherVanilla(CreatureSpawnEvent e) {
        if (on("d5.wither") && e.getEntityType() == EntityType.WITHER && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void armaWither(BlockPlaceEvent e) {
        if (!on("d5.wither")) return;
        Block b = e.getBlockPlaced();
        if (!arena(b) && !craneo(b) && b.getType() != Material.PLAYER_WALL_HEAD) return;
        Bukkit.getScheduler().runTask(pl, () -> buscarWither(b));
    }

    private void buscarWither(Block puesto) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            Block c = puesto.getRelative(dx, dy, dz);
            if (!arena(c)) continue;
            for (boolean ejeX : new boolean[]{true, false}) {
                Block cabeza = forma(c, ejeX);
                if (cabeza != null) { invocar(c, ejeX, cabeza); return; }
            }
        }
    }

    /**
     * La T de arena de almas con los 3 craneos arriba y la cabeza del jugador pegada en el pecho
     * (la cara de adelante o de atras del bloque del medio de arriba). Devuelve la cabeza o null.
     */
    private Block forma(Block c, boolean ejeX) {
        int ax = ejeX ? 1 : 0, az = ejeX ? 0 : 1;
        Block izq = c.getRelative(-ax, 0, -az), der = c.getRelative(ax, 0, az), abajo = c.getRelative(0, -1, 0);
        if (!arena(izq) || !arena(der) || !arena(abajo)) return null;
        if (!c.getRelative(-ax, -1, -az).getType().isAir() || !c.getRelative(ax, -1, az).getType().isAir()) return null;
        if (!craneo(c.getRelative(0, 1, 0)) || !craneo(izq.getRelative(0, 1, 0)) || !craneo(der.getRelative(0, 1, 0))) return null;
        BlockFace[] pecho = ejeX ? new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH} : new BlockFace[]{BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : pecho) {
            Block h = c.getRelative(f);
            if (h.getType() == Material.PLAYER_WALL_HEAD && h.getBlockData() instanceof Directional d && d.getFacing() == f) return h;
        }
        return null;
    }

    private void invocar(Block c, boolean ejeX, Block cabeza) {
        String dueno = "???";
        BlockState st = cabeza.getState();
        if (st instanceof Skull sk) {
            if (sk.getPlayerProfile() != null && sk.getPlayerProfile().getName() != null) dueno = sk.getPlayerProfile().getName();
            else if (sk.getOwningPlayer() != null && sk.getOwningPlayer().getName() != null) dueno = sk.getOwningPlayer().getName();
        }
        int ax = ejeX ? 1 : 0, az = ejeX ? 0 : 1;
        Block[] todos = {c, c.getRelative(-ax, 0, -az), c.getRelative(ax, 0, az), c.getRelative(0, -1, 0),
                c.getRelative(0, 1, 0), c.getRelative(-ax, 1, -az), c.getRelative(ax, 1, az), cabeza};
        for (Block b : todos) {
            b.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, b.getBlockData());
            b.setType(Material.AIR);
        }
        Location l = c.getRelative(0, -1, 0).getLocation().add(0.5, 0.55, 0.5);
        String nombre = "Wither de " + dueno;
        Wither w = l.getWorld().spawn(l, Wither.class, wi -> wi.customName(Txt.c(nombre)));
        // la carga del principio (como cuando lo arma el juego)
        for (String m : new String[]{"setInvulnerabilityTicks", "setInvulnerableTicks"}) {
            try { w.getClass().getMethod(m, int.class).invoke(w, 220); break; } catch (Throwable ignored) { }
        }
        for (Player p : l.getWorld().getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1f, 1f);
    }

    // ================================================================= Dia 6: caida

    /** Proteccion ya no baja el dano por caida: solo Caida de Pluma (3 por nivel, como siempre, tope 80%). */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void caida(EntityDamageEvent e) {
        if (!on("d6.caida") || e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (!e.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) return;
        ItemStack botas = p.getInventory().getBoots();
        int ff = botas == null ? 0 : botas.getEnchantmentLevel(Enchantment.FEATHER_FALLING);
        double base = e.getDamage(EntityDamageEvent.DamageModifier.BASE);
        double res = e.isApplicable(EntityDamageEvent.DamageModifier.RESISTANCE) ? e.getDamage(EntityDamageEvent.DamageModifier.RESISTANCE) : 0;
        double reduccion = Math.min(20, ff * 3) / 25.0;
        e.setDamage(EntityDamageEvent.DamageModifier.MAGIC, -(base + res) * reduccion);
    }

    // ================================================================= Dia 6: bedrock

    @EventHandler(ignoreCancelled = true)
    public void golpeaBedrock(BlockDamageEvent e) {
        if (e.getBlock().getType() == Material.BEDROCK) tocoBedrock.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + 800);
    }

    @EventHandler
    public void clickBedrock(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.BEDROCK) return;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK)
            tocoBedrock.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + 800);
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        tocoBedrock.remove(e.getPlayer().getUniqueId());
        polillas.remove(e.getPlayer().getUniqueId());
    }

    private static boolean tocaBedrock(Player p) {
        BoundingBox bb = p.getBoundingBox().expand(0.05);
        World w = p.getWorld();
        for (int x = (int) Math.floor(bb.getMinX()); x <= (int) Math.floor(bb.getMaxX()); x++)
            for (int y = (int) Math.floor(bb.getMinY()); y <= (int) Math.floor(bb.getMaxY()); y++)
                for (int z = (int) Math.floor(bb.getMinZ()); z <= (int) Math.floor(bb.getMaxZ()); z++)
                    if (w.getBlockAt(x, y, z).getType() == Material.BEDROCK) return true;
        return false;
    }

    private void bedrock() {
        long ahora = System.currentTimeMillis();
        int nivel = Math.max(1, pl.getConfig().getInt("cambios.bedrock_wither_nivel", 3));
        double seg = Math.max(1, pl.getConfig().getDouble("cambios.bedrock_armadura_segundos", 60));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!juega(p)) continue;
            Long hasta = tocoBedrock.get(p.getUniqueId());
            boolean toca = (hasta != null && hasta > ahora) || tocaBedrock(p);
            if (!toca) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, nivel - 1, false, true, true));
            if (ticks % 2 != 0) continue; // la armadura se gasta una vez por segundo
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack it = p.getInventory().getItem(s);
                if (it == null || it.getType().isAir()) continue;
                int max = it.getType().getMaxDurability();
                if (max <= 0 || (it.hasItemMeta() && it.getItemMeta().isUnbreakable())) continue;
                // se rompe en "bedrock_armadura_segundos" (Irrompibilidad la hace durar un poco mas, como siempre)
                p.damageItemStack(s, (int) Math.ceil(max / seg));
            }
        }
    }
}
