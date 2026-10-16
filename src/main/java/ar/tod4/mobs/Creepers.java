package ar.tod4.mobs;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.destroystokyo.paper.entity.ai.Goal;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/**
 * Creepers del dia 5 (submenu "Mobs" de /tod4:cambios):
 *  - Electronico: los creepers normales salen cargados.
 *  - Random, Mini, Calamar, Void y Nuclear: aparecen ADEMAS de los creepers normales (con una chance cada vez que sale uno).
 * Los modelos y las animaciones son de Better Model y los mobs de MythicMobs; aca va todo lo que MythicMobs no hace solo.
 * Opciones: plugins/TOD4/creepers.yml
 */
public final class Creepers implements Listener {

    /** El orden importa: el mas raro primero (a lo sumo sale un extra por creeper). */
    private static final String[] TIPOS = {"nuclear", "void", "calamar", "mini", "random"};
    private static final Map<String, String> TAG = Map.of("random", "creeper_random", "mini", "creeper_mini", "calamar", "creeper_calamar",
            "void", "lobotomia", "nuclear", "creeper_nuclear");
    private static final String[] RASGOS = {"velocidad", "salto", "invisible", "duro", "mecha", "teleport"};

    private final TOD4 pl;
    private final Random rnd = new Random();
    private YamlConfiguration cfg;
    private final Map<UUID, Nuke> nukes = new HashMap<>();
    private int tick;

    private static final class Nuke {
        Creeper c;
        int t, total;
        BossBar bar;
        int seg = -1;
        final Set<UUID> ven = new HashSet<>();
    }

    public Creepers(TOD4 pl) {
        this.pl = pl;
        recargar();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaTick, 40L, 1L);
    }

    public void recargar() {
        File f = new File(pl.getDataFolder(), "creepers.yml");
        if (!f.exists()) pl.saveResource("creepers.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
    }

    private boolean on(String tipo) { return pl.cambios().on("d5.creeper_" + tipo); }

    // ================================================================= aparicion

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void aparece(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        // tambien pueden aparecer junto a otros bichos hostiles (zombies, esqueletos, arañas...)
        if (!(e.getEntity() instanceof Creeper) && e.getEntity() instanceof org.bukkit.entity.Monster
                && rnd.nextDouble() * 100 < cfg.getDouble("chance_con_otros", 4)) {
            Location lo = e.getEntity().getLocation();
            Bukkit.getScheduler().runTask(pl, () -> extra(lo));
            return;
        }
        if (!(e.getEntity() instanceof Creeper c)) return;
        if (on("electronico")) c.setPowered(true);
        Location l = c.getLocation();
        Bukkit.getScheduler().runTask(pl, () -> extra(l));
    }

    /** Con cierta chance aparece un creeper especial cerca (ademas del normal). */
    private void extra(Location l) {
        for (String t : TIPOS) {
            if (!on(t)) continue;
            if (rnd.nextDouble() * 100 >= cfg.getDouble("chances." + t, 0)) continue;
            int n = 1;
            if (t.equals("mini")) {
                int a = cfg.getInt("mini.grupo_min", 3), b = cfg.getInt("mini.grupo_max", 5);
                n = a + rnd.nextInt(Math.max(1, b - a + 1));
            }
            for (int i = 0; i < n; i++) {
                Location s = lugar(l, 2, 5);
                if (s != null) aparecer(t, s);
            }
            return;
        }
    }

    /** Hace aparecer un creeper especial (tambien lo usa /tod4:creeper). */
    public Entity aparecer(String tipo, Location l) {
        Entity en = Ext.mythicSpawn(cfg.getString("mythic." + tipo, ""), l);
        if (en != null) preparar(en, tipo);
        return en;
    }

    private void preparar(Entity en, String tipo) {
        if (!(en instanceof Creeper c)) return;
        switch (tipo) {
            case "mini" -> escala(c, cfg.getDouble("mini.tamano", 0.5));
            case "nuclear" -> { escala(c, cfg.getDouble("nuclear.tamano", 1.5)); c.setMaxFuseTicks(32000); c.setExplosionRadius(0); nuclearBase(c); }
            default -> { }
        }
    }

    private static void escala(LivingEntity le, double v) {
        AttributeInstance a = le.getAttribute(Attribute.SCALE);
        if (a != null && Math.abs(a.getBaseValue() - v) > 0.001) a.setBaseValue(v);
    }

    private Location lugar(Location c, int dmin, int dmax) {
        World w = c.getWorld();
        for (int i = 0; i < 16; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = dmin + rnd.nextDouble() * (dmax - dmin);
            int x = (int) Math.floor(c.getX() + Math.cos(a) * d), z = (int) Math.floor(c.getZ() + Math.sin(a) * d);
            for (int dy = 2; dy >= -3; dy--) {
                int y = c.getBlockY() + dy;
                Block b = w.getBlockAt(x, y, z);
                if (b.isPassable() && !b.isLiquid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, -1, 0).getType().isSolid())
                    return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // ================================================================= cada tick

    private void cadaTick() {
        tick++;
        if (!nukes.isEmpty()) for (Nuke n : new ArrayList<>(nukes.values())) nuke(n);
        if (tick % 2 != 0) return;
        for (World w : Bukkit.getWorlds()) {
            for (Creeper c : w.getEntitiesByClass(Creeper.class)) {
                Set<String> tags = c.getScoreboardTags();
                if (tags.isEmpty()) continue;
                if (tags.contains("creeper_mini")) mini(c);
                else if (tags.contains("creeper_random")) random(c, tags);
                else if (tags.contains("creeper_nuclear")) nuclear(c);
                // los modelos de Better Model no muestran el fuego: llamitas cuando se esta quemando
                if (tick % 4 == 0 && c.getFireTicks() > 0 && (tags.contains("creeper_mini") || tags.contains("creeper_random")
                        || tags.contains("creeper_nuclear") || tags.contains("creeper_calamar") || tags.contains("lobotomia"))) {
                    double s = escalaDe(c);
                    w.spawnParticle(Particle.FLAME, c.getLocation().add(0, 0.9 * s, 0), 5, 0.3 * s, 0.6 * s, 0.3 * s, 0.01);
                }
            }
        }
    }

    private static double escalaDe(LivingEntity le) {
        AttributeInstance a = le.getAttribute(Attribute.SCALE);
        return a == null ? 1.0 : a.getValue();
    }

    // ----------------------------------------------------------------- mini: te ve a 10 bloques, prende la mecha (5 s) y te persigue

    private void mini(Creeper c) {
        escala(c, cfg.getDouble("mini.tamano", 0.5));
        // el "swell" de vanilla frena al creeper mientras se infla: sin eso sigue corriendo con la mecha prendida
        if (!c.getScoreboardTags().contains("mini_listo")) {
            c.addScoreboardTag("mini_listo");
            try {
                for (Goal<Creeper> g : new ArrayList<>(Bukkit.getMobGoals().getAllGoals(c)))
                    if (g.getKey().getNamespacedKey().getKey().toLowerCase().contains("swell")) Bukkit.getMobGoals().removeGoal(c, g);
            } catch (Throwable ignored) { }
        }
        Player p = c.getTarget() instanceof Player t && !t.isDead() && t.getWorld() == c.getWorld() ? t : null;
        if (p == null && c.isIgnited()) {         // perdio el objetivo pero la mecha sigue: va al jugador mas cercano
            double best = 20 * 20;
            for (Player q : c.getWorld().getPlayers()) {
                if (q.isDead() || q.getGameMode() == GameMode.CREATIVE || q.getGameMode() == GameMode.SPECTATOR) continue;
                double d = q.getLocation().distanceSquared(c.getLocation());
                if (d < best) { best = d; p = q; }
            }
        }
        if (p == null) return;
        if (!c.isIgnited()) {
            if (p.getLocation().distanceSquared(c.getLocation()) > Math.pow(cfg.getDouble("mini.vision", 10), 2)) return;
            c.setMaxFuseTicks((int) (cfg.getDouble("mini.mecha_seg", 5) * 20));
            c.ignite();
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1f, 1.8f);
        }
        if (tick % 4 == 0) c.getPathfinder().moveTo(p, 1.0);
    }

    // ----------------------------------------------------------------- random (lucky block)

    private void random(Creeper c, Set<String> tags) {
        String rasgo = null;
        for (String t : tags) if (t.startsWith("rc_")) rasgo = t.substring(3);
        if (rasgo == null) {
            rasgo = RASGOS[rnd.nextInt(RASGOS.length)];
            c.addScoreboardTag("rc_" + rasgo);
            switch (rasgo) {
                case "velocidad" -> c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
                case "salto" -> c.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, PotionEffect.INFINITE_DURATION, 2, false, false));
                case "duro" -> c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 1, false, false));
                case "mecha" -> c.setMaxFuseTicks(12);
                default -> { }
            }
        }
        // invisible: un segundo se ve y un segundo no
        if (rasgo.equals("invisible") && tick % 40 == 0)
            c.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20, 0, false, false));
        // teleport: cada tanto aparece al lado tuyo
        if (rasgo.equals("teleport") && tick % 40 == 0 && c.getTarget() instanceof Player p && p.getWorld() == c.getWorld()
                && p.getLocation().distanceSquared(c.getLocation()) > 16 && p.getLocation().distanceSquared(c.getLocation()) < 400 && rnd.nextInt(100) < 60) {
            Location dst = lugar(p.getLocation(), 2, 4);
            if (dst != null) saltar(c, dst);
        }
        // inflandose: chispas de colores
        if (c.getFuseTicks() > 0) {
            Color col = Color.fromRGB(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
            c.getWorld().spawnParticle(Particle.DUST, c.getLocation().add(0, 1, 0), 6, 0.5, 0.7, 0.5, 0, new Particle.DustOptions(col, 1.3f));
        }
    }

    private static final PotionEffectType[] EFECTOS = {PotionEffectType.POISON, PotionEffectType.NAUSEA, PotionEffectType.BLINDNESS,
            PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.HUNGER, PotionEffectType.DARKNESS, PotionEffectType.MINING_FATIGUE};
    private static final int[][] DUR_AMP = {{8, 1}, {10, 0}, {5, 0}, {10, 1}, {15, 0}, {15, 0}, {8, 0}, {20, 0}};

    // ----------------------------------------------------------------- golpes de explosion: tinta (calamar) y efecto al azar (random)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golpe(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getDamager() instanceof Creeper c)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        Set<String> tags = c.getScoreboardTags();
        if (tags.contains("creeper_calamar")) Bukkit.getScheduler().runTask(pl, () -> tinta(p));
        if (tags.contains("creeper_random")) {
            int i = rnd.nextInt(EFECTOS.length);
            Bukkit.getScheduler().runTask(pl, () -> p.addPotionEffect(new PotionEffect(EFECTOS[i], DUR_AMP[i][0] * 20, DUR_AMP[i][1])));
        }
    }

    /** Random: no rompe bloques (mismo dano que un creeper) y la nube que deja es de dano instantaneo, no de sus rasgos. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explotaRandom(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof Creeper c) || !c.getScoreboardTags().contains("creeper_random")) return;
        e.blockList().clear();
        c.clearActivePotionEffects();             // sin efectos propios, vanilla no deja la nube de resistencia/salto
        Location l = c.getLocation();
        c.getWorld().spawn(l, AreaEffectCloud.class, nube -> {
            nube.setRadius(2.2f);
            nube.setDuration(80);
            nube.setReapplicationDelay(30);
            nube.setRadiusPerTick(-0.004f);
            nube.setColor(Color.fromRGB(190, 30, 60));
            nube.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), true);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explota(EntityExplodeEvent e) {
        if (e.getEntity() instanceof Creeper c && c.getScoreboardTags().contains("creeper_calamar"))
            c.getWorld().spawnParticle(Particle.SQUID_INK, c.getLocation().add(0, 1, 0), 120, 1.8, 1.2, 1.8, 0.15);
    }

    /** Random con teleport: como un Enderman, a veces se esfuma cuando le pegan. Nuclear: voz grave. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void herido(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Creeper c)) return;
        Set<String> tags = c.getScoreboardTags();
        if (tags.contains("rc_teleport") && e.getCause() != EntityDamageEvent.DamageCause.VOID && !c.isDead() && rnd.nextInt(100) < 55) {
            Location dst = lugar(c.getLocation(), 3, 8);
            if (dst != null) Bukkit.getScheduler().runTask(pl, () -> { if (c.isValid()) saltar(c, dst); });
        }
        if (tags.contains("creeper_nuclear") && !c.isDead() && c.getHealth() - e.getFinalDamage() > 0)
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_CREEPER_HURT, SoundCategory.HOSTILE, 1.4f, 0.5f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void murio(EntityDeathEvent e) {
        if (e.getEntity() instanceof Creeper c && c.getScoreboardTags().contains("creeper_nuclear"))
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_CREEPER_DEATH, SoundCategory.HOSTILE, 1.4f, 0.5f);
    }

    private void saltar(Creeper c, Location dst) {
        c.getWorld().spawnParticle(Particle.PORTAL, c.getLocation().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.3);
        c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 1.2f);
        c.teleport(dst);
        c.getWorld().playSound(dst, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 1.2f);
        c.getWorld().spawnParticle(Particle.PORTAL, dst.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.3);
    }

    /** La pantalla se mancha de tinta (uno de 4 dibujos del pack) y se va desvaneciendo. */
    private void tinta(Player p) {
        char ch = (char) (0xE101 + rnd.nextInt(4));
        Component g = Component.text(String.valueOf(ch)).font(Key.key("tod4", "tinta")).color(NamedTextColor.WHITE);
        double seg = cfg.getDouble("calamar.tinta_seg", 3);
        long stay = (long) (seg * 1000 * 0.2), fade = (long) (seg * 1000 * 0.8);
        p.showTitle(Title.title(g, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(stay), Duration.ofMillis(fade))));
        p.playSound(p, Sound.ENTITY_SQUID_SQUIRT, SoundCategory.HOSTILE, 1f, 0.8f);
    }

    // ----------------------------------------------------------------- nuclear

    /** Vida, silencio (la voz grave la ponemos nosotros) y nada mas. */
    private void nuclearBase(Creeper c) {
        double vida = cfg.getDouble("nuclear.vida", 50);
        AttributeInstance a = c.getAttribute(Attribute.MAX_HEALTH);
        if (a != null && a.getBaseValue() < vida) { a.setBaseValue(vida); c.setHealth(vida); }
        if (!c.isSilent()) c.setSilent(true);
    }

    private void nuclear(Creeper c) {
        escala(c, cfg.getDouble("nuclear.tamano", 1.5));
        nuclearBase(c);
        if (c.getMaxFuseTicks() < 32000) { c.setMaxFuseTicks(32000); c.setExplosionRadius(0); }
        if (nukes.containsKey(c.getUniqueId())) return;
        if (!(c.getTarget() instanceof Player p) || p.isDead() || p.getWorld() != c.getWorld()) return;
        if (p.getLocation().distanceSquared(c.getLocation()) > Math.pow(cfg.getDouble("nuclear.rango_activar", 3.5), 2)) return;
        // se planta: ya no hay vuelta atras (salvo que lo maten)
        Nuke n = new Nuke();
        n.c = c;
        n.total = n.t = (int) (cfg.getDouble("nuclear.cuenta_seg", 10) * 20);
        n.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        nukes.put(c.getUniqueId(), n);
        c.setAI(false);
        // casi imposible de matar de cerca: hay que usar arco
        c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, cfg.getInt("nuclear.resistencia", 3) - 1, false, false));
        Ext.mythicSignal(c, "NUCLEAR");
        c.getWorld().playSound(c.getLocation(), Sound.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 3f, 0.5f);
        c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 4f, 0.6f);
    }

    private void nuke(Nuke n) {
        Creeper c = n.c;
        if (c.isDead() || !c.isValid()) { terminar(n); return; }      // lo mataron: no explota
        Location l = c.getLocation();
        int s = (int) Math.ceil(n.t / 20.0);
        if (s != n.seg) {          // la barra baja un casillero por segundo (10 casilleros)
            n.seg = s;
            n.bar.name(Component.text("☢ CREEPER NUCLEAR ☢  ", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .append(Component.text(s + "s", NamedTextColor.YELLOW)));
            n.bar.progress(Math.max(0f, Math.min(1f, s / (float) Math.ceil(n.total / 20.0))));
        }
        if (n.t % 10 == 0) {
            double r2 = Math.pow(cfg.getDouble("nuclear.radio_aviso", 80), 2);
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean cerca = p.getWorld() == l.getWorld() && p.getLocation().distanceSquared(l) <= r2;
                if (cerca && n.ven.add(p.getUniqueId())) p.showBossBar(n.bar);
                else if (!cerca && n.ven.remove(p.getUniqueId())) p.hideBossBar(n.bar);
            }
        }
        // un sonido por segundo, desde donde esta el creeper: count1 (10-7), count2 (6-3), count4 (2-1)
        if (n.t % 20 == 0) {
            String snd = s >= 7 ? "count1" : s >= 3 ? "count2" : "count4";
            l.getWorld().playSound(l, "tod4:creeper.nuclear." + snd, SoundCategory.HOSTILE, (float) cfg.getDouble("nuclear.volumen_cuenta", 5), 1f);
        }
        if (n.t == 30) Ext.mythicSignal(c, "SWELL");
        if (--n.t <= 0) {
            terminar(n);
            detonar(l, c);   // el creeper todavia existe: figura como el que mato ("fue volado por Creeper Nuclear")
            c.remove();
        }
    }

    private void terminar(Nuke n) {
        nukes.remove(n.c.getUniqueId());
        for (UUID u : n.ven) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(n.bar); }
        n.ven.clear();
    }

    public void apagar() {
        for (Nuke n : new ArrayList<>(nukes.values())) terminar(n);
    }

    /** La explosion nuclear: crater de ~15, fuego y mortal hasta el mismo radio, dano decreciente hasta el borde, veneno y hongo de humo. */
    private void detonar(Location l, Creeper causa) {
        World w = l.getWorld();
        double mortal = cfg.getDouble("nuclear.radio_mortal", 22), dano = cfg.getDouble("nuclear.radio_dano", 40);
        double max = cfg.getDouble("nuclear.dano_centro", 800), min = cfg.getDouble("nuclear.dano_borde_mortal", 400);
        int veneno = cfg.getInt("nuclear.veneno_seg", 10);
        w.createExplosion(l, 6f, false, true);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 16f, 0.5f);
        w.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 16f, 0.6f);
        DamageSource.Builder sb = DamageSource.builder(DamageType.EXPLOSION).withDamageLocation(l);
        if (causa != null && causa.isValid()) sb.withCausingEntity(causa).withDirectEntity(causa);
        DamageSource src = sb.build();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(l);
            if (d > dano) continue;
            if (d <= mortal) p.damage(min + (max - min) * (1 - d / mortal), src);   // mortal incluso con prot 4 (solo te salva un totem); baja con la distancia
            else p.damage(4 + 20 * (1 - (d - mortal) / (dano - mortal)), src);
            if (!p.isDead()) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, veneno * 20, 2));
        }
        for (Entity en : w.getNearbyEntities(l, mortal, mortal, mortal)) {
            if (en instanceof LivingEntity le && !(en instanceof Player) && le.getLocation().distance(l) <= mortal) le.damage(200, src);
        }
        crater(l, (int) Math.round(mortal));
        hongo(l);
    }

    private static final Set<Material> DURO = Set.of(Material.BEDROCK, Material.BARRIER, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.REINFORCED_DEEPSLATE, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.END_GATEWAY, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.LIGHT,
            Material.RESPAWN_ANCHOR, Material.ANCIENT_DEBRIS, Material.NETHERITE_BLOCK, Material.ENCHANTING_TABLE, Material.ANVIL);

    /** Crater irregular (mas hondo que alto), de a capas para no trabar el server; despues fuego alrededor. */
    private void crater(Location l, int radioFuego) {
        World w = l.getWorld();
        int R = cfg.getInt("nuclear.radio_bloques", 15);
        int abajo = (int) Math.round(R * 0.75), arriba = (int) Math.round(R * 0.5);
        double f1 = rnd.nextDouble() * 6.28, f2 = rnd.nextDouble() * 6.28, f3 = rnd.nextDouble() * 6.28;
        int cx = l.getBlockX(), cy = l.getBlockY(), cz = l.getBlockZ();
        new BukkitRunnable() {
            int dy = arriba;
            @Override
            public void run() {
                for (int k = 0; k < 2 && dy >= -abajo; k++, dy--) {
                    double ry = dy >= 0 ? arriba : abajo;
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            double a = Math.atan2(dz, dx);
                            double borde = 0.82 + 0.1 * Math.sin(a * 3 + f1) + 0.06 * Math.sin(a * 7 + f2) + 0.04 * Math.sin(dy * 0.7 + f3);
                            double q = (dx * dx + dz * dz) / (double) (R * R) + (dy * dy) / (ry * ry);
                            if (q > borde * borde) continue;
                            int y = cy + dy;
                            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                            Block b = w.getBlockAt(cx + dx, y, cz + dz);
                            Material t = b.getType();
                            if (t.isAir() || b.isLiquid() || DURO.contains(t) || b.getState() instanceof Container) continue;
                            b.setType(Material.AIR, false);
                        }
                    }
                }
                if (dy < -abajo) {
                    cancel();
                    fuego(l, radioFuego);   // el fuego llega hasta donde mata la explosion
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    private void fuego(Location l, int radio) {
        World w = l.getWorld();
        for (int i = 0; i < radio * radio; i++) {
            if (rnd.nextInt(100) >= 35) continue;
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * radio;
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            for (int y = l.getBlockY() + 8; y > l.getBlockY() - 14; y--) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isSolid()) {
                    Block arriba = b.getRelative(0, 1, 0);
                    if (arriba.getType().isAir()) arriba.setType(Material.FIRE);
                    break;
                }
            }
        }
    }

    /** Hongo de humo: columna que sube y un sombrero que se abre arriba. */
    private void hongo(Location l) {
        World w = l.getWorld();
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ > 120) { cancel(); return; }
                double h = Math.min(30, t * 0.55);
                for (double k = 0; k < h; k += 2.5)
                    w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, l.clone().add(0, k, 0), 2, 1.2, 0.8, 1.2, 0.01, null, true);
                double rr = Math.min(13, 3 + t * 0.14);
                for (int i = 0; i < 18; i++) {
                    double a = i * Math.PI * 2 / 18 + t * 0.05;
                    Location c = l.clone().add(Math.cos(a) * rr, h + 2 + Math.sin(a * 3) * 1.2, Math.sin(a) * rr);
                    w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 2, 1.5, 1.0, 1.5, 0.01, null, true);
                    w.spawnParticle(Particle.LARGE_SMOKE, c, 2, 1.5, 1.2, 1.5, 0.02, null, true);
                }
                w.spawnParticle(Particle.CLOUD, l.clone().add(0, h + 3, 0), 10, rr * 0.5, 1.5, rr * 0.5, 0.02, null, true);
                if (t < 25) {
                    double br = t * 1.4;
                    for (int i = 0; i < 24; i++) {
                        double a = i * Math.PI * 2 / 24;
                        w.spawnParticle(Particle.CLOUD, l.clone().add(Math.cos(a) * br, 0.5, Math.sin(a) * br), 1, 0.3, 0.2, 0.3, 0.02, null, true);
                    }
                    if (t % 5 == 0) w.spawnParticle(Particle.EXPLOSION_EMITTER, l.clone().add(0, h, 0), 1, 2, 2, 2, 0, null, true);
                }
            }
        }.runTaskTimer(pl, 1L, 2L);
    }

    public List<String> tipos() { return List.of("random", "mini", "calamar", "void", "nuclear"); }
}
