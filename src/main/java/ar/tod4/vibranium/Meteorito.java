package ar.tod4.vibranium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Meteorito de vibranium: /tod4 meteorito [x y z]  (sin coordenadas: cae DONDE ESTAS PARADO; nunca al azar).
 *  - Baja del cielo prendido fuego, explota (sin romper nada alrededor) y deja un crater chico con Piedra de Meteorito,
 *    las menas de vibranium en la roca del medio y un cofre con una Mejora de Vibranium.
 *  - Las menas solo se pican con Toque de Seda (si no, no se rompen). Cada mena picada hace salir mobs.
 *  - Ni creepers ni TNT rompen los bloques del meteorito; los pistones tampoco los mueven.
 *  - La regla del dia 8 (herramientas TOD) vale igual que en cualquier bloque de pico.
 *  - Si Oraxen tiene los bloques (oraxen_mena / oraxen_piedra), se ven con su textura; si no, con los de respaldo.
 * config.yml -> meteorito.  /tod4 meteorito lista | borrar <n>
 */
public final class Meteorito implements Listener {

    private final TOD4 pl;
    private final Vibranium vib;
    private final Random rnd = new Random();
    private final Store store;
    /** Bloques del meteorito: "mundo;x;y;z" -> "M<n>" (mena del meteorito n) o "P<n>" (piedra). */
    private final Map<String, String> bloques = new HashMap<>();
    private int ultimo;

    public Meteorito(TOD4 pl, Vibranium vib) {
        this.pl = pl;
        this.vib = vib;
        this.store = new Store(pl, "meteoritos");
        for (String s : store.y().getStringList("bloques")) {
            int i = s.lastIndexOf('=');
            if (i > 0) bloques.put(s.substring(0, i), s.substring(i + 1));
        }
        ultimo = store.y().getInt("ultimo", 0);
        Bukkit.getPluginManager().registerEvents(this, pl);
        oraxenRomper();
    }

    private double d(String k, double def) { return pl.getConfig().getDouble("meteorito." + k, def); }
    private String s(String k, String def) { return pl.getConfig().getString("meteorito." + k, def); }

    private void guardar() {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : bloques.entrySet()) l.add(e.getKey() + "=" + e.getValue());
        store.set("bloques", l);
        store.set("ultimo", ultimo);
        store.save();
    }

    private static String clave(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    // ================================================================= caer

    public void cmd(CommandSender s, String[] a) {
        if (a.length > 1 && a[1].equalsIgnoreCase("lista")) { lista(s); return; }
        if (a.length > 2 && a[1].equalsIgnoreCase("borrar")) { borrar(s, a[2]); return; }
        Location donde;
        if (a.length >= 4) {
            World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
            try {
                donde = new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
            } catch (NumberFormatException e) { Style.err(s, "Uso: &e/tod4 meteorito [x y z] &7| lista | borrar <n>"); return; }
        } else if (s instanceof Player p) {
            donde = p.getLocation();
        } else { Style.err(s, "Desde la consola: &e/tod4 meteorito <x> <y> <z>"); return; }
        Block suelo = suelo(donde.getBlock());
        if (suelo == null) { Style.err(s, "No hay suelo abajo de ese lugar."); return; }
        int n = ++ultimo;
        guardar();
        Style.ok(s, "&6☄ Meteorito #" + n + " cayendo en &f" + suelo.getX() + " " + suelo.getY() + " " + suelo.getZ()
                + " &7(" + suelo.getWorld().getName() + ")&a. Las coordenadas solo las ves vos.");
        caer(suelo, n);
    }

    /** El primer bloque solido de ahi para abajo. */
    private static Block suelo(Block b) {
        World w = b.getWorld();
        for (int i = 0; i < 64 && b.getY() > w.getMinHeight(); i++, b = b.getRelative(BlockFace.DOWN))
            if (b.getType().isSolid()) return b;
        return null;
    }

    private void caer(Block suelo, int n) {
        World w = suelo.getWorld();
        Location fin = suelo.getLocation().add(0.5, 1, 0.5);
        double alto = d("altura_caida", 70);
        Location ini = fin.clone().add(alto * 0.35, alto, alto * 0.2);
        int ticks = Math.max(10, (int) (d("caida_seg", 3) * 20));
        BlockDisplay roca = w.spawn(ini, BlockDisplay.class, x -> {
            x.setBlock(Material.MAGMA_BLOCK.createBlockData());
            x.setPersistent(false);
            x.setTransformation(new Transformation(new Vector3f(-1.25f, -1.25f, -1.25f), new AxisAngle4f(), new Vector3f(2.5f, 2.5f, 2.5f), new AxisAngle4f()));
            x.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            x.setTeleportDuration(2);
        });
        // todos escuchan que algo viene del cielo
        if (pl.getConfig().getBoolean("meteorito.aviso_a_todos", true)) {
            for (Player p : w.getPlayers()) {
                p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, SoundCategory.AMBIENT, 0.8f, 0.5f);
                p.sendMessage(Txt.c(s("mensaje", "&8[&6Mini evento&8] &7¡Algo cayó del cielo!")));
            }
        }
        Bukkit.getScheduler().runTaskTimer(pl, new java.util.function.Consumer<org.bukkit.scheduler.BukkitTask>() {
            int t = 0;
            @Override public void accept(org.bukkit.scheduler.BukkitTask task) {
                t++;
                double f = Math.min(1, t / (double) ticks);
                Location l = ini.clone().add(fin.clone().subtract(ini).toVector().multiply(f));
                if (roca.isValid()) roca.teleport(l);
                w.spawnParticle(Particle.FLAME, l, 25, 0.8, 0.8, 0.8, 0.05, null, true);
                w.spawnParticle(Particle.LARGE_SMOKE, l, 12, 0.8, 0.8, 0.8, 0.02, null, true);
                w.spawnParticle(Particle.LAVA, l, 3, 0.6, 0.6, 0.6, 0, null, true);
                if (t % 5 == 0) w.playSound(l, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.AMBIENT, 4f, 0.5f);
                if (t >= ticks) {
                    task.cancel();
                    roca.remove();
                    impacto(suelo, n);
                }
            }
        }, 1L, 1L);
    }

    private void impacto(Block suelo, int n) {
        World w = suelo.getWorld();
        Location c = suelo.getLocation().add(0.5, 1, 0.5);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 0.5, 1, 0, null, true);
        w.spawnParticle(Particle.LAVA, c, 60, 2, 1, 2, 0, null, true);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 8f, 0.6f);
        w.playSound(c, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.AMBIENT, 6f, 0.5f);
        // empuja a los que estaban muy cerca (no lastima)
        for (Entity e : w.getNearbyEntities(c, 6, 4, 6)) {
            if (!(e instanceof Player p)) continue;
            org.bukkit.util.Vector v = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (v.lengthSquared() < 1e-3) v = new org.bukkit.util.Vector(1, 0, 0);
            p.setVelocity(v.normalize().multiply(1.2).setY(0.6));
        }
        crater(suelo, n);
        // humo un rato
        long hasta = System.currentTimeMillis() + (long) (d("humo_seg", 60) * 1000);
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (System.currentTimeMillis() > hasta) { task.cancel(); return; }
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c, 3, 1, 0.3, 1, 0.01, null, true);
        }, 10L, 10L);
        int mobs = (int) d("mobs_al_caer", 3);
        if (mobs > 0) Bukkit.getScheduler().runTaskLater(pl, () -> mobs(c, mobs), 40L);
    }

    /** Un bloque se puede pisar si no es cofre/horno/etc., ni bedrock, ni irrompible. */
    private static boolean tocable(Block b) {
        Material m = b.getType();
        if (m == Material.BEDROCK || m.getHardness() < 0) return false;
        return !(b.getState() instanceof TileState);
    }

    private void crater(Block suelo, int n) {
        World w = suelo.getWorld();
        int r = Math.max(2, (int) d("radio", 4));
        double prof = d("profundidad", 0.6);
        int cx = suelo.getX(), cy = suelo.getY(), cz = suelo.getZ();
        List<String> mezcla = pl.getConfig().getStringList("meteorito.borde_mezcla");
        if (mezcla.isEmpty()) mezcla = List.of("BLACKSTONE", "MAGMA_BLOCK", "TUFF");
        double chancePiedra = d("chance_piedra", 60);
        int fondo = cy - (int) Math.round(r * prof);
        // el pozo: aire arriba, el fondo con Piedra de Meteorito (y algo de piedra negra, magma...)
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r + 0.4) continue;
            int hondo = cy - (int) Math.round((r - dist) * prof);
            for (int y = hondo + 1; y <= cy + 3; y++) {
                Block b = w.getBlockAt(cx + dx, y, cz + dz);
                if (!b.getType().isAir() && tocable(b)) b.setType(Material.AIR, false);
            }
            Block piso = w.getBlockAt(cx + dx, hondo, cz + dz);
            if (!tocable(piso)) continue;
            if (rnd.nextDouble() * 100 < chancePiedra) poner(piso, false, n);
            else {
                Material m = Material.matchMaterial(mezcla.get(rnd.nextInt(mezcla.size())));
                piso.setType(m == null ? Material.BLACKSTONE : m, false);
            }
        }
        // la roca del medio: piedra con las menas
        Block centro = w.getBlockAt(cx, Math.min(cy, fondo + 1), cz);
        List<Block> roca = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            double dd = dx * dx + dy * dy * 1.3 + dz * dz;
            if (dd > 3.2) continue;
            Block b = centro.getRelative(dx, dy, dz);
            if (!tocable(b)) continue;
            roca.add(b);
        }
        Collections.shuffle(roca, rnd);
        // las menas, primero las que se ven (tienen aire al lado)
        roca.sort((x, y) -> Boolean.compare(!expuesto(x, roca), !expuesto(y, roca)));
        int menas = (int) d("menas", 4);
        if (rnd.nextDouble() * 100 < d("chance_suerte", 15)) menas = (int) d("menas_suerte", 8);
        int puestas = 0;
        for (Block b : roca) {
            boolean mena = puestas < menas && expuesto(b, roca);
            poner(b, mena, n);
            if (mena) puestas++;
        }
        for (Iterator<Block> it = roca.iterator(); puestas < menas && it.hasNext(); ) {   // si no hubo suficientes a la vista
            Block b = it.next();
            if (!esMena(b)) { poner(b, true, n); puestas++; }
        }
        // el cofre con la Mejora de Vibranium
        Block cofre = null;
        for (int i = 0; i < 30 && cofre == null; i++) {
            int dx = rnd.nextInt(5) - 2, dz = rnd.nextInt(5) - 2;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            Block b = w.getHighestBlockAt(cx + dx, cz + dz);
            for (int y = cy + 3; y > fondo - 1; y--) {
                Block bb = w.getBlockAt(cx + dx, y, cz + dz);
                if (bb.getType().isAir() && bb.getRelative(BlockFace.DOWN).getType().isSolid() && !bloques.containsKey(clave(bb))) { b = bb; break; }
            }
            if (b.getType().isAir()) cofre = b;
        }
        if (cofre != null) {
            cofre.setType(Material.CHEST, false);
            if (cofre.getState() instanceof Chest ch) {
                ch.customName(Txt.c("<#9B7BFF>Cofre del Meteorito"));
                ch.update();
                ch = (Chest) cofre.getState();
                int mejoras = Math.max(0, (int) d("mejoras_en_cofre", 1));
                if (mejoras > 0) ch.getBlockInventory().setItem(13, vib.item(Vibranium.Tipo.MEJORA, mejoras));
            }
        }
        guardar();
        if (pl.getConfig().getBoolean("meteorito.debug", false))
            pl.getLogger().info("[Meteorito] #" + n + " en " + cx + " " + cy + " " + cz + ": " + puestas + " menas, cofre " + (cofre != null));
    }

    private boolean esMena(Block b) { String v = bloques.get(clave(b)); return v != null && v.startsWith("M"); }

    private static boolean expuesto(Block b, List<Block> roca) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block o = b.getRelative(f);
            if (o.getType().isAir() && !roca.contains(o)) return true;
        }
        return false;
    }

    /** Pone una mena o una piedra del meteorito (de Oraxen si esta, si no la de respaldo) y la anota. */
    private void poner(Block b, boolean mena, int n) {
        String ox = s(mena ? "oraxen_mena" : "oraxen_piedra", mena ? "mena_vibranium" : "piedra_meteorito");
        if (!oraxenPoner(ox, b)) {
            Material m = Material.matchMaterial(s(mena ? "respaldo_mena" : "respaldo_piedra", mena ? "AMETHYST_BLOCK" : "BLACKSTONE"));
            b.setType(m == null ? Material.STONE : m, false);
        }
        bloques.put(clave(b), (mena ? "M" : "P") + n);
    }

    private static java.lang.reflect.Method OX_PLACE, OX_EXISTS;
    private static boolean oxTried;

    private static boolean oraxenPoner(String id, Block b) {
        if (id == null || id.isEmpty()) return false;
        try {
            if (!oxTried) {
                oxTried = true;
                Class<?> c = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
                OX_PLACE = c.getMethod("place", String.class, Location.class);
                OX_EXISTS = c.getMethod("isOraxenBlock", String.class);
            }
            if (OX_PLACE == null || !(Boolean) OX_EXISTS.invoke(null, id)) return false;
            OX_PLACE.invoke(null, id, b.getLocation());
            return !b.getType().isAir();
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================= romper

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void rompe(BlockBreakEvent e) {
        if (!bloques.containsKey(clave(e.getBlock()))) return;
        e.setCancelled(true);
        romper(e.getPlayer(), e.getBlock());
    }

    /** Oraxen rompe sus bloques con su propio evento: lo mismo. */
    private void oraxenRomper() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> c = (Class<? extends Event>) Class.forName("io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockBreakEvent");
            java.lang.reflect.Method gb = c.getMethod("getBlock"), gp = c.getMethod("getPlayer");
            Bukkit.getPluginManager().registerEvent(c, this, EventPriority.LOWEST, (l, ev) -> {
                if (!c.isInstance(ev)) return;
                try {
                    Block b = (Block) gb.invoke(ev);
                    if (b == null || !bloques.containsKey(clave(b))) return;
                    ((org.bukkit.event.Cancellable) ev).setCancelled(true);
                    romper((Player) gp.invoke(ev), b);
                } catch (Throwable ignored) { }
            }, pl, true);
        } catch (Throwable ignored) { }   // sin Oraxen
    }

    private void romper(Player p, Block b) {
        String k = clave(b);
        String v = bloques.get(k);
        if (v == null || p == null) return;
        boolean mena = v.startsWith("M");
        if (p.getGameMode() == GameMode.CREATIVE) {
            bloques.remove(k);
            b.setType(Material.AIR);
            guardar();
            return;
        }
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (mena && !mano.containsEnchantment(Enchantment.SILK_TOUCH)) {
            p.sendActionBar(Txt.c("&cNecesitas un pico con &fToque de Seda &cpara sacar el vibranium."));
            p.playSound(b.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.4f, 1.6f);
            return;
        }
        if (!org.bukkit.Tag.ITEMS_PICKAXES.isTagged(mano.getType())) {
            p.sendActionBar(Txt.c("&cNecesitas un pico."));
            return;
        }
        if (pl.cambios() != null && pl.cambios().nuevas() != null) pl.cambios().nuevas().castigoHerramienta(p);
        bloques.remove(k);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, c, 30, 0.3, 0.3, 0.3, 0, Material.DEEPSLATE.createBlockData());
        b.getWorld().playSound(c, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1f, 0.8f);
        b.setType(Material.AIR);
        b.getWorld().dropItemNaturally(c, vib.item(mena ? Vibranium.Tipo.MENA : Vibranium.Tipo.PIEDRA, 1));
        p.damageItemStack(EquipmentSlot.HAND, 1);
        guardar();
        if (pl.getConfig().getBoolean("meteorito.debug", false))
            pl.getLogger().info("[Meteorito] " + p.getName() + " saco " + (mena ? "una mena" : "una piedra") + " del meteorito " + v.substring(1));
        if (mena) {
            int m = (int) d("mobs_por_mena", 2);
            if (m > 0) mobs(c, m);
        }
    }

    /** Mobs que salen (config meteorito.mobs: "ZOMBIE" o "mm:NombreDeMythicMobs"). */
    private void mobs(Location c, int cuantos) {
        List<String> tipos = pl.getConfig().getStringList("meteorito.mobs");
        if (tipos.isEmpty()) tipos = List.of("ZOMBIE", "SKELETON", "SPIDER");
        for (int i = 0; i < cuantos; i++) {
            Location l = lugarMob(c);
            if (l == null) continue;
            String t = tipos.get(rnd.nextInt(tipos.size()));
            Entity e = null;
            if (t.toLowerCase().startsWith("mm:")) e = ar.tod4.hook.Ext.mythicSpawn(t.substring(3), l);
            else {
                try {
                    EntityType et = EntityType.valueOf(t.toUpperCase());
                    e = l.getWorld().spawnEntity(l, et, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
                } catch (IllegalArgumentException ignored) { }
            }
            if (e != null) l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        }
    }

    private Location lugarMob(Location c) {
        World w = c.getWorld();
        for (int i = 0; i < 30; i++) {
            int x = c.getBlockX() + rnd.nextInt(9) - 4, z = c.getBlockZ() + rnd.nextInt(9) - 4;
            for (int y = c.getBlockY() + 4; y > c.getBlockY() - 6; y--) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isAir() && b.getRelative(BlockFace.UP).getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid())
                    return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    // ================================================================= que nada lo rompa ni lo mueva

    @EventHandler(ignoreCancelled = true)
    public void explota(EntityExplodeEvent e) { e.blockList().removeIf(b -> bloques.containsKey(clave(b))); }

    @EventHandler(ignoreCancelled = true)
    public void explotaBloque(BlockExplodeEvent e) { e.blockList().removeIf(b -> bloques.containsKey(clave(b))); }

    @EventHandler(ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (bloques.containsKey(clave(b))) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void piston(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (bloques.containsKey(clave(b))) { e.setCancelled(true); return; } }

    // ================================================================= lista / borrar

    private void lista(CommandSender s) {
        Map<String, int[]> por = new java.util.TreeMap<>();
        Map<String, String> donde = new HashMap<>();
        for (Map.Entry<String, String> e : bloques.entrySet()) {
            String n = e.getValue().substring(1);
            int[] c = por.computeIfAbsent(n, k -> new int[2]);
            c[e.getValue().startsWith("M") ? 0 : 1]++;
            if (e.getValue().startsWith("M")) donde.put(n, e.getKey().replace(';', ' '));
            else donde.putIfAbsent(n, e.getKey().replace(';', ' '));
        }
        if (por.isEmpty()) { Style.info(s, "No hay meteoritos."); return; }
        for (Map.Entry<String, int[]> e : por.entrySet())
            Style.raw(s, "&6☄ #" + e.getKey() + " &7en &f" + donde.get(e.getKey()) + "&7: &d" + e.getValue()[0] + " menas&7, " + e.getValue()[1] + " piedras");
    }

    /** Saca lo que quedo de ese meteorito (los bloques que no se picaron). */
    private void borrar(CommandSender s, String n) {
        int c = 0;
        for (Iterator<Map.Entry<String, String>> it = bloques.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            if (!e.getValue().substring(1).equals(n)) continue;
            String[] p = e.getKey().split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w != null) w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])).setType(Material.AIR);
            it.remove();
            c++;
        }
        guardar();
        Style.ok(s, "Meteorito #" + n + ": " + c + " bloques sacados.");
    }
}
