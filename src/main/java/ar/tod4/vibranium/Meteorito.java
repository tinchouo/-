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
                + " &7(" + suelo.getWorld().getName() + ")&a.");
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
            float t = (float) Math.max(2, d("meteorito_radio", 4) * 2);
            x.setTransformation(new Transformation(new Vector3f(-t / 2, -t / 2, -t / 2), new AxisAngle4f(), new Vector3f(t, t, t), new AxisAngle4f()));
            x.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            x.setTeleportDuration(2);
        });
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
        // todos lo escuchan y ven el mensaje (sin coordenadas)
        if (pl.getConfig().getBoolean("meteorito.aviso_a_todos", true)) {
            for (Player p : w.getPlayers()) {
                p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, SoundCategory.AMBIENT, 0.8f, 0.5f);
                p.sendMessage(Txt.c(s("mensaje", "&9✦ ¡Cayó un meteorito en &f{x} {y} {z}&9!")
                        .replace("{x}", String.valueOf(suelo.getX())).replace("{y}", String.valueOf(suelo.getY())).replace("{z}", String.valueOf(suelo.getZ()))));
            }
        }
        Location c = suelo.getLocation().add(0.5, 1, 0.5);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 0.5, 1, 0, null, true);
        w.spawnParticle(Particle.LAVA, c, 60, 2, 1, 2, 0, null, true);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 8f, 0.6f);
        w.playSound(c, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.AMBIENT, 6f, 0.5f);
        // empuja a los que estaban muy cerca (no lastima)
        double rr = Math.max(6, d("radio", 15));
        for (Entity e : w.getNearbyEntities(c, rr, 8, rr)) {
            if (!(e instanceof Player p)) continue;
            org.bukkit.util.Vector v = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (v.lengthSquared() < 1e-3) v = new org.bukkit.util.Vector(1, 0, 0);
            p.setVelocity(v.normalize().multiply(1.6).setY(0.7));
        }
        crater(suelo, n);
        // humo un rato
        long hasta = System.currentTimeMillis() + (long) (d("humo_seg", 60) * 1000);
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (System.currentTimeMillis() > hasta) { task.cancel(); return; }
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c, 3, 1, 0.3, 1, 0.01, null, true);
        }, 10L, 10L);
    }

    /** Un bloque se puede pisar si no es cofre/horno/etc., ni bedrock, ni irrompible. */
    private static boolean tocable(Block b) {
        Material m = b.getType();
        if (m == Material.BEDROCK || m.getHardness() < 0) return false;
        return !(b.getState() instanceof TileState);
    }

    /**
     * El crater: un pozo grande e irregular (como el del creeper nuclear), el fondo quemado, y en el medio el meteorito
     * (una roca redonda de Piedra de Meteorito, medio enterrada) con las menas a la vista y el cofre escondido adentro.
     * Se cava de a capas para no trabar el server.
     */
    private void crater(Block suelo, int n) {
        World w = suelo.getWorld();
        int R = Math.max(4, (int) d("radio", 15));
        int abajo = Math.max(2, (int) Math.round(R * d("profundidad", 0.45))), arriba = Math.max(2, (int) Math.round(R * 0.4));
        double f1 = rnd.nextDouble() * 6.28, f2 = rnd.nextDouble() * 6.28, f3 = rnd.nextDouble() * 6.28;
        int cx = suelo.getX(), cy = suelo.getY(), cz = suelo.getZ();
        new org.bukkit.scheduler.BukkitRunnable() {
            int dy = arriba;
            @Override
            public void run() {
                for (int k = 0; k < 2 && dy >= -abajo; k++, dy--) {
                    double ry = dy >= 0 ? arriba : abajo;
                    for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                        double a = Math.atan2(dz, dx);
                        double borde = 0.85 + 0.08 * Math.sin(a * 3 + f1) + 0.05 * Math.sin(a * 7 + f2) + 0.03 * Math.sin(dy * 0.7 + f3);
                        double q = (dx * dx + dz * dz) / (double) (R * R) + (dy * dy) / (ry * ry);
                        if (q > borde * borde) continue;
                        int y = cy + dy;
                        if (y <= w.getMinHeight() || y >= w.getMaxHeight()) continue;
                        Block b = w.getBlockAt(cx + dx, y, cz + dz);
                        if (b.getType().isAir() || b.isLiquid() || !tocable(b)) continue;
                        b.setType(Material.AIR, false);
                    }
                }
                if (dy < -abajo) {
                    cancel();
                    fondo(w, cx, cy, cz, R, abajo);
                    // el piso de verdad en el medio (si abajo hay roca madre, queda mas arriba)
                    int piso = cy - abajo;
                    for (int y = cy; y >= cy - abajo - 3 && y > w.getMinHeight(); y--)
                        if (!w.getBlockAt(cx, y, cz).getType().isAir()) { piso = y; break; }
                    meteoro(w, cx, Math.max(piso, w.getMinHeight() + 1), cz, n);
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    private boolean tocableAca(Block b) {
        return tocable(b) && (pl.revivir() == null || !pl.revivir().protegido(b));
    }

    /** El fondo del pozo, quemado: piedra negra, magma, toba (y algo de Piedra de Meteorito). */
    private void fondo(World w, int cx, int cy, int cz, int R, int abajo) {
        List<String> mezcla = pl.getConfig().getStringList("meteorito.borde_mezcla");
        if (mezcla.isEmpty()) mezcla = List.of("BLACKSTONE", "MAGMA_BLOCK", "TUFF", "BASALT");
        double quemado = d("chance_quemado", 70);
        for (int dx = -R - 1; dx <= R + 1; dx++) for (int dz = -R - 1; dz <= R + 1; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > R + 1) continue;
            // el primer bloque solido de arriba para abajo en esa columna (dentro del pozo)
            for (int y = cy + (int) Math.round(R * 0.4); y >= cy - abajo - 2; y--) {
                Block b = w.getBlockAt(cx + dx, y, cz + dz);
                if (b.getType().isAir()) continue;
                if (b.isLiquid() || !tocableAca(b)) break;
                if (rnd.nextDouble() * 100 < quemado * (1 - dist / (R + 2) * 0.6)) {
                    Material m = Material.matchMaterial(mezcla.get(rnd.nextInt(mezcla.size())));
                    b.setType(m == null ? Material.BLACKSTONE : m, false);
                }
                break;
            }
        }
    }

    /** La roca del medio (medio enterrada), con las menas a la vista y el cofre escondido en el centro. */
    private void meteoro(World w, int cx, int piso, int cz, int n) {
        int M = Math.max(2, (int) d("meteorito_radio", 4));
        int my = piso + M - 1;   // el centro: queda un poco enterrado
        List<Block> roca = new ArrayList<>();
        for (int dx = -M; dx <= M; dx++) for (int dy = -M; dy <= M; dy++) for (int dz = -M; dz <= M; dz++) {
            double q = dx * dx + dy * dy * 1.15 + dz * dz;
            if (q > (M + 0.4) * (M + 0.4) - rnd.nextDouble() * 1.2) continue;
            Block b = w.getBlockAt(cx + dx, my + dy, cz + dz);
            if (!tocableAca(b)) continue;
            roca.add(b);
        }
        Block cofre = w.getBlockAt(cx, my, cz);
        roca.remove(cofre);
        for (Block b : roca) b.setType(Material.STONE, false);   // primero todo solido, para saber cuales quedan a la vista
        // las menas: en la superficie (con aire al lado), repartidas
        List<Block> afuera = new ArrayList<>();
        for (Block b : roca) if (aLaVista(b)) afuera.add(b);
        Collections.shuffle(afuera, rnd);
        int menas = (int) d("menas", 4);
        if (rnd.nextDouble() * 100 < d("chance_suerte", 15)) menas = (int) d("menas_suerte", 8);
        List<Block> elegidas = new ArrayList<>();
        for (Block b : afuera) {
            if (elegidas.size() >= menas) break;
            boolean lejos = true;
            for (Block o : elegidas) if (o.getLocation().distanceSquared(b.getLocation()) < 9) { lejos = false; break; }
            if (lejos) elegidas.add(b);
        }
        for (Block b : afuera) { if (elegidas.size() >= menas) break; if (!elegidas.contains(b)) elegidas.add(b); }
        for (Block b : roca) poner(b, elegidas.contains(b), n);
        // el cofre, escondido en el medio
        if (tocableAca(cofre)) {
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
            pl.getLogger().info("[Meteorito] #" + n + " en " + cx + " " + my + " " + cz + ": " + elegidas.size() + " menas, " + (roca.size() - elegidas.size())
                    + " piedras, cofre " + (cofre.getType() == Material.CHEST));
        Location centro = new Location(w, cx + 0.5, piso + 1, cz + 0.5);
        Bukkit.getScheduler().runTaskLater(pl, () -> mobsAlCaer(centro), 20L);
    }

    private static boolean aLaVista(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN})
            if (b.getRelative(f).getType().isAir()) return true;
        return false;
    }

    /**
     * Los mobs del meteorito (config meteorito.mobs_al_caer: nombre -> cantidad; con "variacion" de mas o menos, y
     * meteorito.mobs_extra: nombre -> % de que salga uno mas). El nombre es de MythicMobs o de Minecraft.
     */
    private void mobsAlCaer(Location c) {
        org.bukkit.configuration.ConfigurationSection cs = pl.getConfig().getConfigurationSection("meteorito.mobs_al_caer");
        Map<String, Integer> cant = new java.util.LinkedHashMap<>();
        if (cs != null && !cs.getKeys(false).isEmpty()) for (String k : cs.getKeys(false)) cant.put(k, cs.getInt(k));
        else { cant.put("AlienSoldado", 5); cant.put("AlienVolador", 3); cant.put("AlienCentinela", 1); }
        org.bukkit.configuration.ConfigurationSection ex = pl.getConfig().getConfigurationSection("meteorito.mobs_extra");
        int var = (int) d("variacion", 1);
        for (Map.Entry<String, Integer> e : cant.entrySet()) {
            int n = e.getValue();
            if (n >= 3 && var > 0) n += rnd.nextInt(var * 2 + 1) - var;   // "mas o menos"
            double extra = ex != null ? ex.getDouble(e.getKey(), 0) : (e.getKey().equals("AlienCentinela") && cs == null ? 35 : 0);
            if (rnd.nextDouble() * 100 < extra) n++;
            for (int i = 0; i < n; i++) mob(e.getKey(), c, (int) d("radio", 15));
        }
    }

    private void mob(String tipo, Location c, int r) {
        Location l = lugarMob(c, Math.max(4, r - 3));
        if (l == null) return;
        Entity e = ar.tod4.hook.Ext.mythicSpawn(tipo, l);
        if (e == null) {
            try {
                e = l.getWorld().spawnEntity(l, EntityType.valueOf(tipo.toUpperCase()), org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            } catch (IllegalArgumentException ex) {
                pl.getLogger().warning("[Meteorito] No existe el mob \"" + tipo + "\" (ni en MythicMobs ni en Minecraft).");
                return;
            }
        }
        l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
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
            p.sendActionBar(Txt.c("&cNecesitas Toque de Seda"));
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
            int m = (int) d("mobs_por_mena", 0);
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

    private Location lugarMob(Location c) { return lugarMob(c, 4); }

    private Location lugarMob(Location c, int r) {
        World w = c.getWorld();
        for (int i = 0; i < 40; i++) {
            int x = c.getBlockX() + rnd.nextInt(r * 2 + 1) - r, z = c.getBlockZ() + rnd.nextInt(r * 2 + 1) - r;
            if (Math.abs(x - c.getBlockX()) <= 4 && Math.abs(z - c.getBlockZ()) <= 4) continue;   // arriba de la roca no
            for (int y = c.getBlockY() + r; y > c.getBlockY() - 6; y--) {
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
