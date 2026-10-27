package ar.tod4.aether;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import ar.tod4.aether.Estado.Lugar;
import ar.tod4.aether.util.Ext;
import ar.tod4.aether.util.Style;

/**
 * Mobs del Aether (MythicMobs), como si aparecieran solos: cada lugar (isla, aldea, molino, coliseo...) tiene los suyos,
 * aparecen cuando un jugador se acerca pero nunca al lado (a mas de distancia_min), y si los matan vuelven al rato.
 * Si te vas y volves, estan. Las islas de hongo no tienen nada. Opciones: plugins/TOD4Aether/mobs.yml
 * Tambien: el Breeze Old cambia de lugar con el jugador al que le pega (como el Blaze Cambiazo).
 */
final class Mobs implements Listener {

    private static final String TAG = "aeth_mob";

    private final TOD4Aether pl;
    private final Aether ae;
    private final Random rnd = new Random();
    private YamlConfiguration y;
    /** "lugar:mob" -> ultima vez que se lleno. */
    private final Map<String, Long> lleno = new HashMap<>();
    private final Set<String> faltan = new HashSet<>();
    private final Map<UUID, Long> cambiazo = new HashMap<>();
    private int tick;

    Mobs(TOD4Aether pl, Aether ae) {
        this.pl = pl;
        this.ae = ae;
        recargar();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada, 100L, 20L);
    }

    void recargar() {
        File f = new File(pl.getDataFolder(), "mobs.yml");
        if (!f.exists()) pl.saveResource("mobs.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
        faltan.clear();
    }

    void apagar() { }

    private String mm(String k) { return y.getString("mobs." + k, k); }

    // ================================================================= cada segundo

    private void cada() {
        tick++;
        if (!y.getBoolean("activado", true)) return;
        if (tick % Math.max(1, y.getInt("cada_seg", 5)) != 0) return;
        World w = ae.mundo();
        if (w == null) return;
        List<Player> ps = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getGameMode() != GameMode.SPECTATOR) ps.add(p);
        if (ps.isEmpty()) return;

        // cuantos hay vivos de cada lugar y de cada tipo
        Map<String, Integer> vivos = new HashMap<>();
        int total = 0;
        List<LivingEntity> phantoms = new ArrayList<>();
        for (LivingEntity le : w.getLivingEntities()) {
            if (!le.getScoreboardTags().contains(TAG)) continue;
            total++;
            for (String t : le.getScoreboardTags()) if (t.startsWith("aeth_l")) vivos.merge(t, 1, Integer::sum);
            if (le.getScoreboardTags().contains("aeth_phantom")) phantoms.add(le);
        }
        int maxTotal = y.getInt("maximo_total", 120);
        if (total >= maxTotal) return;

        double dmin = y.getDouble("distancia_min", 24), dmax = y.getDouble("distancia_activa", 80);
        long espera = y.getLong("reaparece_seg", 300) * 1000L, ahora = System.currentTimeMillis();
        for (Lugar l : ae.lugares()) {
            ConfigurationSection sec = y.getConfigurationSection("lugares." + l.tipo);
            if (sec == null) continue;
            // solo los lugares que tienen un jugador cerca
            double cerca = Double.MAX_VALUE;
            for (Player p : ps) cerca = Math.min(cerca, Math.hypot(p.getLocation().getX() - l.x, p.getLocation().getZ() - l.z) - l.r);
            if (cerca > dmax) continue;
            for (String mob : sec.getKeys(false)) {
                int quiere = objetivo(l, mob, sec.getString(mob, "0"));
                if (quiere <= 0) continue;
                String tag = "aeth_l" + l.id + "_" + mob;
                int hay = vivos.getOrDefault(tag, 0);
                if (hay >= quiere) continue;
                String key = l.id + ":" + mob;
                Long t = lleno.get(key);
                if (t != null && ahora - t < espera) continue;
                int puso = 0;
                boolean centro = y.getStringList("en_el_centro").contains(l.tipo);
                for (int i = hay; i < quiere && total < maxTotal; i++) {
                    Location at = centro ? centro(w, l) : lugar(w, l, mob, ps, dmin);
                    if (at == null) continue;
                    if (aparecer(mob, at, tag) != null) { puso++; total++; }
                }
                if (puso > 0 && hay + puso >= quiere) lleno.put(key, ahora);
            }
        }
        phantoms(w, ps, phantoms, dmin);
    }

    /**
     * Cuantos de un mob tiene un lugar: "2-3", "1", "1 50%" (la mitad de los lugares de ese tipo tienen uno).
     * Se sortea una vez por lugar (siempre da lo mismo para el mismo lugar).
     */
    private static int objetivo(Lugar l, String mob, String spec) {
        String[] p = spec.trim().split("\\s+");
        Random r = new Random(l.id * 7919L + mob.hashCode());
        int min = 0, max = 0;
        double chance = 100;
        for (String x : p) {
            if (x.isEmpty()) continue;
            if (x.endsWith("%")) chance = Double.parseDouble(x.substring(0, x.length() - 1).replace(',', '.'));
            else if (x.contains("-")) { String[] q = x.split("-"); min = Integer.parseInt(q[0]); max = Integer.parseInt(q[1]); }
            else { min = max = Integer.parseInt(x); }
        }
        if (r.nextDouble() * 100 >= chance) return 0;
        return min + r.nextInt(Math.max(1, max - min + 1));
    }

    /** En el medio del lugar (el coliseo): el piso de la arena, a unos bloques del centro (en el centro esta el cofre). */
    private Location centro(World w, Lugar l) {
        if (!w.isChunkLoaded(l.x >> 4, l.z >> 4)) return null;
        for (int i = 0; i < 8; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(l.x + Math.cos(a) * 6), z = (int) Math.floor(l.z + Math.sin(a) * 6);
            // de arriba hacia abajo: el primer piso con dos bloques libres encima (la arena, no las gradas)
            for (int yy = l.y + 4; yy >= l.y - 30; yy--) {
                Block b = w.getBlockAt(x, yy, z);
                if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable() && !b.getRelative(0, 1, 0).isLiquid())
                    return new Location(w, x + 0.5, yy + 1, z + 0.5, rnd.nextFloat() * 360f, 0f);
            }
        }
        return null;
    }

    /** Un lugar en el piso del lugar, lejos de todos los jugadores (como un mob que aparece solo). */
    private Location lugar(World w, Lugar l, String mob, List<Player> ps, double dmin) {
        boolean vuela = y.getStringList("voladores").contains(mob);
        double rr = Math.max(2, l.r * 0.75);
        for (int i = 0; i < 12; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * rr;
            int x = (int) Math.floor(l.x + Math.cos(a) * d), z = (int) Math.floor(l.z + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (top < l.y - 45 || top > l.y + 70) continue;
            Block b = w.getBlockAt(x, top, z);
            if (!b.getType().isSolid()) continue;
            if (!b.getRelative(0, 1, 0).isPassable() || !b.getRelative(0, 2, 0).isPassable()) continue;
            Location at = new Location(w, x + 0.5, top + 1 + (vuela ? 3 : 0), z + 0.5, rnd.nextFloat() * 360f, 0f);
            boolean ok = true;
            for (Player p : ps) if (p.getLocation().distanceSquared(at) < dmin * dmin) { ok = false; break; }
            if (ok) return at;
        }
        return null;
    }

    private Entity aparecer(String mob, Location at, String tag) {
        String id = mm(mob);
        Entity en = Ext.mythicSpawn(id, at);
        if (en == null) {
            if (faltan.add(id)) pl.getLogger().warning("[Mobs] No pude hacer aparecer " + id + " (¿existe en MythicMobs? nombre en mobs.yml)");
            return null;
        }
        en.addScoreboardTag(TAG);
        en.addScoreboardTag(tag);
        if (mob.equals("breeze")) en.addScoreboardTag("breeze_old");
        return en;
    }

    // ================================================================= Phantom Angelical: en el cielo, en todo el Aether menos el hongo

    private void phantoms(World w, List<Player> ps, List<LivingEntity> hay, double dmin) {
        int max = y.getInt("phantoms.max_por_jugador", 2);
        double chance = y.getDouble("phantoms.chance", 15);
        if (max <= 0 || chance <= 0) return;
        for (Player p : ps) {
            if (p.getGameMode() == GameMode.CREATIVE) continue;
            if (!y.getBoolean("phantoms.en_hongo", false) && Ext.bioma(p.getLocation()).equals("mushroom_island")) continue;
            int n = 0;
            for (LivingEntity le : hay) if (le.getLocation().distanceSquared(p.getLocation()) < 64 * 64) n++;
            if (n >= max || rnd.nextDouble() * 100 >= chance) continue;
            double a = rnd.nextDouble() * Math.PI * 2, d = dmin + 4 + rnd.nextDouble() * 16;
            Location at = p.getLocation().clone().add(Math.cos(a) * d, 12 + rnd.nextInt(10), Math.sin(a) * d);
            if (at.getY() >= w.getMaxHeight() - 2 || !at.getBlock().isPassable()) continue;
            if (!y.getBoolean("phantoms.en_hongo", false) && Ext.bioma(at).equals("mushroom_island")) continue;
            Entity en = aparecer("phantom", at, "aeth_phantom");
            if (en instanceof LivingEntity le) hay.add(le);
        }
    }

    /** En el Aether siempre es de dia: los mobs del Aether (los Phantom, zombies...) no se queman con el sol. */
    @EventHandler(ignoreCancelled = true)
    public void sol(org.bukkit.event.entity.EntityCombustEvent e) {
        if (e.getClass() != org.bukkit.event.entity.EntityCombustEvent.class) return;    // solo el sol (no lava ni fuego)
        if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    // ================================================================= Breeze Old: cambia de lugar con el que golpea

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cambiazo(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Entity d = e.getDamager();
        Breeze b = d instanceof Breeze bb ? bb : d instanceof Projectile pr && pr.getShooter() instanceof Breeze bb ? bb : null;
        if (b == null || !b.getScoreboardTags().contains("breeze_old")) return;
        Long t = cambiazo.get(v.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 1000) return;
        cambiazo.put(v.getUniqueId(), System.currentTimeMillis());
        Location pj = v.getLocation(), pb = b.getLocation();
        // cada uno sigue mirando para donde miraba
        Location aJ = pb.clone(); aJ.setYaw(pj.getYaw()); aJ.setPitch(pj.getPitch());
        Location aB = pj.clone(); aB.setYaw(pb.getYaw()); aB.setPitch(pb.getPitch());
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!v.isOnline() || v.isDead() || !b.isValid()) return;
            v.teleport(aJ);
            b.teleport(aB);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 1f);
        });
    }

    // ================================================================= comando

    void comando(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "estado";
        switch (m) {
            case "on", "off" -> {
                y.set("activado", m.equals("on"));
                try { y.save(new File(pl.getDataFolder(), "mobs.yml")); } catch (Exception ignored) { }
                Style.ok(s, "Mobs del Aether " + (m.equals("on") ? "&aprendidos" : "&capagados") + "&a.");
            }
            case "lugares" -> {
                Map<String, Integer> n = new HashMap<>();
                for (Lugar l : ae.lugares()) n.merge(l.tipo, 1, Integer::sum);
                Style.info(s, "Lugares con mobs: &f" + n);
                if (s instanceof Player p && ae.enAether(p)) {
                    Lugar mejor = null;
                    double bd = Double.MAX_VALUE;
                    for (Lugar l : ae.lugares()) {
                        double d = Math.hypot(p.getLocation().getX() - l.x, p.getLocation().getZ() - l.z);
                        if (d < bd) { bd = d; mejor = l; }
                    }
                    if (mejor != null) Style.info(s, "El más cercano: &f" + mejor.tipo + " #" + mejor.id + " &7a " + (int) bd + " bloques (radio " + mejor.r + ")");
                }
            }
            default -> {
                World w = ae.mundo();
                int n = 0;
                Map<String, Integer> por = new HashMap<>();
                if (w != null) for (LivingEntity le : w.getLivingEntities()) if (le.getScoreboardTags().contains(TAG)) {
                    n++;
                    String id = Ext.mythic(le);
                    por.merge(id == null ? le.getType().name() : id, 1, Integer::sum);
                }
                Style.info(s, "Mobs del Aether: " + (y.getBoolean("activado", true) ? "&aprendidos" : "&capagados") + " &7| vivos: &f" + n + " &7" + por);
                Style.info(s, "&e/tod4:aether_mobs on|off|lugares &7- opciones en &fplugins/TOD4Aether/mobs.yml");
            }
        }
    }
}
