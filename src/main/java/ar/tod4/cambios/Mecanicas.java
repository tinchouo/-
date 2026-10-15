package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import ar.tod4.TOD4;

/** Las mecanicas de los cambios de los dias 1 a 4 (portadas de cambios_dia_1..4.sk, por eventos). */
final class Mecanicas implements Listener {

    private final TOD4 pl;
    private final Cambios cb;
    private final NamespacedKey LEGAL;
    private final Set<Location> hornos = new HashSet<>();
    private final Map<UUID, Integer> calor = new HashMap<>();
    private final List<NamespacedKey> recetasD2 = new ArrayList<>();
    private final List<NamespacedKey> recetasD3 = new ArrayList<>();

    Mecanicas(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        this.LEGAL = new NamespacedKey(pl, "aldeano_legal");
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada10, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada20, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada40, 40L, 40L);
        cb.hook("d2.hornos", this::recetasD2);
        cb.hook("d3.hornos", this::recetasD3);
        cb.hook("d4.mesa", on -> pl.mesa().setActiva(on));
    }

    private boolean on(String id) { return cb.on(id); }

    private static boolean juega(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    // ================================================================= tareas

    private void cada10() {
        // Dia 1: el Nether evapora el agua de los calderos
        if (on("d1.nether")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().getEnvironment() != World.Environment.NETHER) continue;
                Block b = p.getLocation().getBlock();
                if (b.getType() == Material.WATER_CAULDRON) {
                    b.setType(Material.CAULDRON);
                    evaporar(p, b.getLocation());
                }
            }
        }
        // Dia 3: lava con resistencia al fuego
        if (on("d3.lava")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!juega(p) || !p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) continue;
                Location l = p.getLocation();
                if (l.getBlock().getType() == Material.LAVA || l.clone().add(0, 1, 0).getBlock().getType() == Material.LAVA) {
                    p.setFireTicks(Math.max(p.getFireTicks(), 300));
                    p.damage(pl.getConfig().getDouble("cambios.lava_dano", 4.0), DamageSource.builder(DamageType.GENERIC).build());
                }
            }
        }
    }

    private void cada20() {
        // Dia 1: hornos prendidos cerca de jugadores -> 1% por segundo de explotar
        if (on("d1.hornos")) {
            Iterator<Location> it = hornos.iterator();
            while (it.hasNext()) {
                Location l = it.next();
                if (!l.isWorldLoaded() || !l.getChunk().isLoaded()) continue;
                Block b = l.getBlock();
                if (b.getType() != Material.FURNACE) { it.remove(); continue; }
                BlockState s = b.getState();
                if (!(s instanceof Furnace f) || f.getBurnTime() <= 0) { it.remove(); continue; }
                boolean cerca = false;
                for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) <= 64) { cerca = true; break; }
                if (cerca && Math.random() < pl.getConfig().getDouble("cambios.horno_chance", 0.01)) {
                    f.getInventory().clear();
                    b.setType(Material.AIR);
                    it.remove();
                    l.getWorld().createExplosion(l.clone().add(0.5, 0.5, 0.5), 2f, false, false);
                }
            }
        }
    }

    private void cada40() {
        // Dia 1: solo quedan los aldeanos legales (huevo / comando)
        if (on("d1.aldeanos")) {
            Set<String> viejos = new HashSet<>(cb_legales());
            for (Player p : Bukkit.getOnlinePlayers()) {
                for (Entity e : p.getNearbyEntities(64, 64, 64)) {
                    if (e.getType() != EntityType.VILLAGER) continue;
                    if (e.getPersistentDataContainer().has(LEGAL, PersistentDataType.BYTE)) continue;
                    if (viejos.contains(e.getUniqueId().toString())) continue;
                    e.remove();
                }
            }
        }
        // Dia 2: los aldeanos no guardan comida
        if (on("d2.reproduccion")) {
            for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) v.getInventory().clear();
        }
        // Dia 1: calor del Nether
        if (on("d1.nether")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean enNether = p.getWorld().getEnvironment() == World.Environment.NETHER;
                if (enNether && p.getGameMode() == GameMode.SURVIVAL && !p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                    int c = calor.getOrDefault(p.getUniqueId(), 0) + 2;
                    calor.put(p.getUniqueId(), c);
                    p.setFireTicks(Math.max(p.getFireTicks(), 60));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, false, true));
                    DamageSource magia = DamageSource.builder(DamageType.MAGIC).build();
                    if (c >= 30) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, true, false, true));
                        p.damage(4.0, magia);
                    } else {
                        p.damage(2.0, magia);
                    }
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_ON_FIRE, SoundCategory.MASTER, 1.5f, 1f);
                    p.playSound(p.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.MASTER, 1.2f, 1.2f);
                } else if (calor.remove(p.getUniqueId()) != null) {
                    p.removePotionEffect(PotionEffectType.SLOWNESS);
                    p.removePotionEffect(PotionEffectType.NAUSEA);
                }
            }
        } else if (!calor.isEmpty()) {
            for (UUID u : calor.keySet()) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) { p.removePotionEffect(PotionEffectType.SLOWNESS); p.removePotionEffect(PotionEffectType.NAUSEA); }
            }
            calor.clear();
        }
    }

    private Set<String> cb_legales() {
        return new HashSet<>(pl.datos().y().getStringList("aldeanos_legales"));
    }

    private void evaporar(Player p, Location l) {
        p.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.MASTER, 1f, 1f);
        l.getWorld().spawnParticle(Particle.CLOUD, l.clone().add(0.5, 1, 0.5), 15, 0.5, 0.5, 0.5, 0.05);
    }

    // ================================================================= eventos

    @EventHandler(ignoreCancelled = true)
    public void hornoPrende(FurnaceBurnEvent e) {
        if (e.getBlock().getType() == Material.FURNACE) hornos.add(e.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void hojasRompe(BlockDropItemEvent e) {
        if (!Tag.LEAVES.isTagged(e.getBlockState().getType())) return;
        e.getItems().removeIf(i -> filtrada(i.getItemStack().getType()));
    }

    @EventHandler(ignoreCancelled = true)
    public void hojasDecae(LeavesDecayEvent e) {
        if (!on("d1.manzanas") && !on("d2.saplings")) return;
        Block b = e.getBlock();
        List<ItemStack> drops = new ArrayList<>(b.getDrops());
        e.setCancelled(true);
        b.setType(Material.AIR);
        for (ItemStack it : drops) if (!filtrada(it.getType())) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
    }

    private boolean filtrada(Material m) {
        if (m == Material.APPLE && on("d1.manzanas")) return true;
        String n = m.name();
        return on("d2.saplings") && (n.endsWith("_SAPLING") || n.contains("PROPAGULE"));
    }

    /** Dia 1: los aldeanos solo aparecen con huevo / comando (ni naturales, ni de crianza, ni de estructuras). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void aldeanoNoNace(CreatureSpawnEvent e) {
        if (!on("d1.aldeanos") || e.getEntityType() != EntityType.VILLAGER) return;
        switch (e.getSpawnReason()) {
            case SPAWNER_EGG: case COMMAND: case CUSTOM: case DISPENSE_EGG: return;
            default: e.setCancelled(true);
        }
    }

    /** Dia 1: los aldeanos de las aldeas que se cargan desaparecen antes de que se vean. */
    @EventHandler
    public void aldeanosCargan(org.bukkit.event.world.EntitiesLoadEvent e) {
        if (!on("d1.aldeanos")) return;
        Set<String> viejos = null;
        for (Entity en : e.getEntities()) {
            if (en.getType() != EntityType.VILLAGER) continue;
            if (en.getPersistentDataContainer().has(LEGAL, PersistentDataType.BYTE)) continue;
            if (viejos == null) viejos = cb_legales();
            if (viejos.contains(en.getUniqueId().toString())) continue;
            en.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void aldeanoNace(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.VILLAGER) return;
        switch (e.getSpawnReason()) {
            case SPAWNER_EGG: case COMMAND: case CUSTOM: case DISPENSE_EGG:
                e.getEntity().getPersistentDataContainer().set(LEGAL, PersistentDataType.BYTE, (byte) 1);
                break;
            default:
                break;
        }
    }

    /** Dia 1: los aldeanos zombi no se pueden curar (sin mensajes, como si el juego fuera asi). */
    @EventHandler(ignoreCancelled = true)
    public void curar(EntityTransformEvent e) {
        if (on("d1.aldeanos") && e.getTransformReason() == EntityTransformEvent.TransformReason.CURED) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void evocador(EntityDeathEvent e) {
        if (on("d1.totems") && e.getEntityType() == EntityType.EVOKER)
            e.getDrops().removeIf(i -> i.getType() == Material.TOTEM_OF_UNDYING);
    }

    @EventHandler(ignoreCancelled = true)
    public void calderoNether(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Material t = e.getClickedBlock().getType();
        // Dia 4: botones y puertas matan (menos los de hierro)
        if (on("d4.puertas") && juega(p)) {
            String n = t.name();
            if ((n.contains("BUTTON") || n.contains("DOOR")) && !n.contains("IRON")) {
                // golpe mortal normal: si tiene un totem en la mano, lo salva
                p.damage(1000.0);
                return;
            }
        }
        // Dia 1: en el Nether no se puede llenar un caldero
        if (on("d1.nether") && p.getWorld().getEnvironment() == World.Environment.NETHER
                && t == Material.CAULDRON && e.getItem() != null && e.getItem().getType() == Material.WATER_BUCKET) {
            e.setCancelled(true);
            if (e.getHand() != null) p.getInventory().setItem(e.getHand(), new ItemStack(Material.BUCKET));
            evaporar(p, e.getClickedBlock().getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void fuegoNether(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (on("d1.nether") && calor.containsKey(p.getUniqueId())
                && (e.getCause() == EntityDamageEvent.DamageCause.FIRE || e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK))
            e.setCancelled(true);
        // Dia 4: la perla hace mas dano (como si el juego lo tuviera asi)
        if (on("d4.perla") && e.getDamageSource().getDamageType().getKey().getKey().equals("ender_pearl") && juega(p))
            e.setDamage(pl.getConfig().getDouble("cambios.perla_corazones", 4) * 2.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void crianza(EntityBreedEvent e) {
        if (on("d4.crianza")) { e.setCancelled(true); return; }
        if (on("d2.reproduccion") && e.getEntityType() == EntityType.VILLAGER) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void aldeanoJunta(EntityPickupItemEvent e) {
        if (on("d2.reproduccion") && e.getEntityType() == EntityType.VILLAGER) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void ordenar(PlayerInteractEntityEvent e) {
        if (!on("d3.leche")) return;
        EntityType t = e.getRightClicked().getType();
        if (t != EntityType.COW && t != EntityType.MOOSHROOM) return;
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() == Material.BUCKET
                || p.getInventory().getItemInOffHand().getType() == Material.BUCKET) {
            e.setCancelled(true);
            Location l = e.getRightClicked().getLocation();
            l.getWorld().playSound(l, Sound.ENTITY_COW_HURT, SoundCategory.NEUTRAL, 1f, 1.1f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void leche(PlayerItemConsumeEvent e) {
        if (on("d3.leche") && e.getItem().getType() == Material.MILK_BUCKET) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void aire(EntityAirChangeEvent e) {
        if (!on("d4.agua") || !(e.getEntity() instanceof Player p) || !juega(p)) return;
        int viejo = p.getRemainingAir(), nuevo = e.getAmount();
        if (nuevo >= viejo) return;
        int factor = pl.getConfig().getInt("cambios.agua_factor", 5);
        e.setAmount(Math.max(-20, viejo - (viejo - nuevo) * factor));
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) { calor.remove(e.getPlayer().getUniqueId()); }

    // ================================================================= recetas

    private void recetasD2(boolean on) {
        quitar(recetasD2);
        if (!on) return;
        blast("arena", Material.SAND, Material.GLASS);
        blast("arena_roja", Material.RED_SAND, Material.GLASS);
        blast("piedra", Material.COBBLESTONE, Material.STONE);
        blast("pizarra", Material.COBBLED_DEEPSLATE, Material.DEEPSLATE);
        blast("basalto", Material.BASALT, Material.SMOOTH_BASALT);
        blast("arenisca", Material.SANDSTONE, Material.SMOOTH_SANDSTONE);
        blast("arenisca_roja", Material.RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE);
        for (DyeColor c : DyeColor.values()) {
            Material in = Material.matchMaterial(c.name() + "_TERRACOTTA");
            Material out = Material.matchMaterial(c.name() + "_GLAZED_TERRACOTTA");
            if (in != null && out != null) blast("terracota_" + c.name().toLowerCase(), in, out);
        }
        smoke("cactus", new RecipeChoice.MaterialChoice(Material.CACTUS), Material.GREEN_DYE);
        smoke("pepino", new RecipeChoice.MaterialChoice(Material.SEA_PICKLE), Material.LIME_DYE);
        smoke("chorus", new RecipeChoice.MaterialChoice(Material.CHORUS_FRUIT), Material.POPPED_CHORUS_FRUIT);
        smoke("troncos", new RecipeChoice.MaterialChoice(Tag.LOGS_THAT_BURN), Material.CHARCOAL);
        smoke("esponja", new RecipeChoice.MaterialChoice(Material.WET_SPONGE), Material.SPONGE);
        Bukkit.updateRecipes();
    }

    private void blast(String id, Material in, Material out) {
        NamespacedKey k = new NamespacedKey(pl, "d2_blast_" + id);
        Bukkit.addRecipe(new BlastingRecipe(k, new ItemStack(out), new RecipeChoice.MaterialChoice(in), 0.1f, 100), false);
        recetasD2.add(k);
    }

    private void smoke(String id, RecipeChoice in, Material out) {
        NamespacedKey k = new NamespacedKey(pl, "d2_smoke_" + id);
        Bukkit.addRecipe(new SmokingRecipe(k, new ItemStack(out), in, 0.1f, 100), false);
        recetasD2.add(k);
    }

    private void recetasD3(boolean on) {
        quitar(recetasD3);
        if (!on) return;
        List<FurnaceRecipe> base = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof FurnaceRecipe f && ((Keyed) f).getKey().getNamespace().equals("minecraft")) base.add(f);
        }
        for (FurnaceRecipe f : base) {
            String id = f.getKey().getKey();
            int t = Math.max(1, f.getCookingTime() / 2);
            NamespacedKey kb = new NamespacedKey(pl, "d3_blast_" + id);
            NamespacedKey ks = new NamespacedKey(pl, "d3_smoke_" + id);
            if (Bukkit.getRecipe(kb) == null) {
                Bukkit.addRecipe(new BlastingRecipe(kb, f.getResult(), f.getInputChoice(), f.getExperience(), t), false);
                recetasD3.add(kb);
            }
            if (Bukkit.getRecipe(ks) == null) {
                Bukkit.addRecipe(new SmokingRecipe(ks, f.getResult(), f.getInputChoice(), f.getExperience(), t), false);
                recetasD3.add(ks);
            }
        }
        Bukkit.updateRecipes();
    }

    private void quitar(List<NamespacedKey> l) {
        if (l.isEmpty()) return;
        for (NamespacedKey k : l) Bukkit.removeRecipe(k, false);
        l.clear();
        Bukkit.updateRecipes();
    }
}
