package ar.tod4.mobs;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;

import ar.tod4.TOD4;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Murcielago Vampiro: reemplaza a TODOS los murcielagos comunes del overworld (aparecen donde aparecian ellos: cuevas
 * oscuras). No persiguen a los cuyes. Nunca aparece arriba de agua o lava, ni en el aire
 * sin piso abajo, ni donde hay luz de antorchas. Si les da el sol, se queman. Vuela RECTO hacia
 * un jugador, esquivando paredes. Si esta pegado, cada medio segundo le roba un corazon de la vida maxima (primero los
 * rojos; los amarillos no protegen). Si no le queda ninguno, muere. Lo robado vuelve al morir o con el Corazon Morado.
 * Se mata normal (poca vida). Se va solo si pasa mucho tiempo o no hay nadie cerca.
 * config.yml -> murcielagos. Para probar: /tod4 murcielago [cantidad]
 */
public final class Murcielagos implements Listener {

    public static final String ETIQUETA = "tod4_vampiro";

    private final TOD4 pl;
    private final Random rnd = new Random();
    private final Map<UUID, Estado> bichos = new HashMap<>();
    /** Por jugador: cuando le aparecio el ultimo (como mucho uno cada tanto) y el tick de la ultima mordida (de cualquiera). */
    private final Map<UUID, Long> ultimoNace = new HashMap<>();
    private final Map<UUID, Integer> ultimaMordida = new HashMap<>();
    private int tick;

    private static final class Estado {
        UUID objetivo;
        long nace;
        int ultimoGolpe = -1000;
        int sinObjetivo;
        Estado(UUID o) { objetivo = o; nace = System.currentTimeMillis(); }
    }

    public Murcielagos(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::mover, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(pl, this::aparecer, 200L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::sol, 20L, 20L);
        // los que quedaron de antes de reiniciar (no se guardan, pero por las dudas)
        Bukkit.getScheduler().runTask(pl, () -> {
            for (World w : Bukkit.getWorlds()) for (Bat b : w.getEntitiesByClass(Bat.class))
                if (b.getScoreboardTags().contains(ETIQUETA)) b.remove();
        });
    }

    private double d(String k, double def) { return pl.getConfig().getDouble("murcielagos." + k, def); }

    /** A quien persiguen: jugadores en supervivencia/aventura, menos los cuyes (que no pueden morir). */
    private static boolean jugable(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !ar.tod4.hook.Cuyes.activo(p);
    }

    /** El overworld principal (no el Agujero ni los Backrooms, que tambien son "normales"). */
    private World overworld() { return Bukkit.getWorlds().get(0); }

    // ================================================================= aparecer

    private int segundos;

    private boolean activo() {
        return pl.getConfig().getBoolean("murcielagos.activo", true) && pl.dia() >= pl.getConfig().getInt("murcielagos.desde_dia", 11);
    }

    private static boolean esDeDia(World w) {
        long t = w.getTime();
        return t < 12300 || t > 23850;
    }

    private void aparecer() {
        if (!activo()) return;
        segundos++;
        if (segundos < Math.max(1, (int) d("cada_seg", 20))) return;
        segundos = 0;
        World w = overworld();
        // los murcielagos comunes que hayan quedado (de antes, o de chunks que se cargaron) se van
        for (Bat b : w.getEntitiesByClass(Bat.class)) if (!b.getScoreboardTags().contains(ETIQUETA)) b.remove();
        // aparecer cerca de los jugadores de noche: apagado (solo salen donde salian los murcielagos comunes)
        if (!pl.getConfig().getBoolean("murcielagos.de_noche_cerca", false)) return;
        if (esDeDia(w)) return;
        int max = (int) d("max_por_jugador", 2);
        for (Player p : w.getPlayers()) {
            if (!jugable(p)) continue;
            if (cuantosDe(p) >= max || !leToca(p)) continue;
            if (rnd.nextDouble() >= d("probabilidad", 0.25)) continue;
            Location l = lugar(p.getLocation(), d("distancia_min", 16), d("distancia_max", 28));
            if (l != null) { ultimoNace.put(p.getUniqueId(), System.currentTimeMillis()); crear(l, p); }
        }
    }

    /** Los murcielagos comunes del overworld no aparecen mas: en su lugar (si se puede) aparece uno vampiro. */
    @EventHandler(ignoreCancelled = true)
    public void comun(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Bat) || e.getEntity().getScoreboardTags().contains(ETIQUETA)) return;
        if (!e.getEntity().getWorld().equals(overworld())) return;
        if (e.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM
                || e.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.COMMAND) return;   // los de comandos/plugins, no
        if (!pl.getConfig().getBoolean("murcielagos.reemplazar_comunes", true) || !activo()) return;   // antes del dia: comunes
        e.setCancelled(true);
        // solo algunos de los comunes pasan a ser vampiros (el resto, nada)
        if (rnd.nextDouble() * 100 >= d("chance_reemplazo", 15)) return;
        Location l = e.getLocation();
        Player p = cercano(l, 40);
        if (p == null || cuantosDe(p) >= (int) d("max_por_jugador", 2) || !leToca(p)) return;
        if (!puedeAparecer(l)) return;
        ultimoNace.put(p.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) crear(l, p); });
    }

    /**
     * Donde puede aparecer: aire, sin luz de antorchas, y abajo (lo primero que no es aire) un bloque solido: nada de
     * agua ni lava ni caer al vacio. De dia, solo donde no llega el sol.
     */
    private boolean puedeAparecer(Location l) {
        Block b = l.getBlock();
        World w = l.getWorld();
        if (!b.isPassable() || b.isLiquid()) return false;
        if (b.getLightFromBlocks() > (int) d("luz_maxima", 7)) return false;
        if (esDeDia(w) && b.getLightFromSky() >= 15) return false;
        for (int i = 1; i <= 24; i++) {
            Block a = b.getRelative(0, -i, 0);
            if (a.getY() <= w.getMinHeight()) return false;
            if (a.isLiquid()) return false;
            if (!a.isPassable()) return a.getType().isSolid();
        }
        return false;   // mas de 24 bloques de aire abajo
    }

    /** Si les da el sol (de dia, sin lluvia, cielo abierto), se queman. */
    private void sol() {
        if (bichos.isEmpty()) return;
        for (UUID u : new java.util.ArrayList<>(bichos.keySet())) {
            Entity ent = Bukkit.getEntity(u);
            if (!(ent instanceof Bat b) || !b.isValid()) continue;
            World w = b.getWorld();
            if (!esDeDia(w) || w.hasStorm()) continue;
            if (b.getLocation().getBlock().getLightFromSky() < 15) continue;
            b.setFireTicks(Math.max(b.getFireTicks(), 60));
            b.damage(d("dano_sol", 3));
        }
    }

    /** Como mucho uno cada "minutos_entre" minutos para cada jugador. */
    private boolean leToca(Player p) {
        Long u = ultimoNace.get(p.getUniqueId());
        return u == null || System.currentTimeMillis() - u >= (long) (d("minutos_entre", 5) * 60000);
    }

    private int cuantosDe(Player p) {
        int n = 0;
        for (Estado e : bichos.values()) if (p.getUniqueId().equals(e.objetivo)) n++;
        return n;
    }

    public Bat crear(Location l, Player objetivo) {
        Bat b = l.getWorld().spawn(l, Bat.class, x -> {
            x.setAI(false);           // lo movemos nosotros: recto
            x.setAwake(true);
            x.setPersistent(false);
            x.setRemoveWhenFarAway(true);
            x.customName(Txt.c("&4Murciélago Vampiro"));
            x.setCustomNameVisible(false);
            var hp = x.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(Math.max(1, d("vida", 1)));   // 1 = muere de un golpe
            x.setHealth(Math.max(1, d("vida", 1)));
            x.addScoreboardTag(ETIQUETA);
        });
        bichos.put(b.getUniqueId(), new Estado(objetivo == null ? null : objetivo.getUniqueId()));
        l.getWorld().playSound(l, Sound.ENTITY_BAT_TAKEOFF, SoundCategory.HOSTILE, 1f, 0.6f);
        return b;
    }

    /** Un lugar con aire (2 de alto) entre dmin y dmax del jugador, cerca de su altura, donde puede aparecer. */
    private Location lugar(Location c, double dmin, double dmax) { return lugar(c, dmin, dmax, true); }

    private Location lugar(Location c, double dmin, double dmax, boolean reglas) {
        World w = c.getWorld();
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, dist = dmin + rnd.nextDouble() * Math.max(0, dmax - dmin);
            int x = (int) Math.floor(c.getX() + Math.cos(ang) * dist), z = (int) Math.floor(c.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = c.getBlockY() + 1 + rnd.nextInt(6);
            for (int k = 0; k < 8; k++, y--) {
                if (y <= w.getMinHeight() + 1 || y + 2 >= w.getMaxHeight()) continue;
                Block b = w.getBlockAt(x, y, z);
                if (!b.isPassable() || b.isLiquid() || !b.getRelative(0, 1, 0).isPassable() || b.getRelative(0, 1, 0).isLiquid()) continue;
                Location l = new Location(w, x + 0.5, y + 0.2, z + 0.5);
                if (!reglas || puedeAparecer(l)) return l;
            }
        }
        return null;
    }

    // ================================================================= volar y morder

    private void mover() {
        tick++;
        if (bichos.isEmpty()) return;
        double vel = d("velocidad", 0.3);
        int cada = Math.max(1, (int) d("muerde_cada_ticks", 10));
        long vida = (long) (d("dura_seg", 90) * 1000);
        for (Iterator<Map.Entry<UUID, Estado>> it = bichos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Estado> en = it.next();
            Entity ent = Bukkit.getEntity(en.getKey());
            Estado st = en.getValue();
            if (!(ent instanceof Bat b) || !b.isValid()) { it.remove(); continue; }
            if (System.currentTimeMillis() - st.nace > vida) { irse(b); it.remove(); continue; }
            Player p = st.objetivo == null ? null : Bukkit.getPlayer(st.objetivo);
            if (p == null || !jugable(p) || !p.getWorld().equals(b.getWorld()) || p.getLocation().distanceSquared(b.getLocation()) > 48 * 48) {
                p = cercano(b);
                st.objetivo = p == null ? null : p.getUniqueId();
            }
            if (p == null) {
                if (++st.sinObjetivo > 200) { irse(b); it.remove(); }   // 10 s sin nadie: se va
                continue;
            }
            st.sinObjetivo = 0;
            Location bl = b.getLocation();
            Location meta = p.getLocation().add(0, 1.1, 0);
            Vector dir = meta.toVector().subtract(bl.toVector());
            double dist = dir.length();
            if (dist > 0.7) {
                dir.multiply(1 / dist);
                double paso = Math.min(vel, dist - 0.6);
                Location sig = siguiente(bl, dir, paso);
                if (sig != null) {
                    sig.setDirection(dir);
                    b.teleport(sig);
                }
            }
            if (tick % 40 == 0 && rnd.nextInt(3) == 0) b.getWorld().playSound(bl, Sound.ENTITY_BAT_AMBIENT, SoundCategory.HOSTILE, 0.8f, 0.6f);
            // muerde
            // el tiempo entre mordidas es por jugador: aunque haya varios encima, de a una
            int ult = ultimaMordida.getOrDefault(p.getUniqueId(), -100000);
            if (dist < d("alcance", 1.4) && tick - ult >= cada) {
                ultimaMordida.put(p.getUniqueId(), tick);
                morder(b, p);
            }
        }
    }

    /** El proximo paso recto; si hay pared, prueba por arriba, por abajo y por los costados. */
    private Location siguiente(Location bl, Vector dir, double paso) {
        Vector[] prueba = {
            dir.clone(),
            dir.clone().setY(0).normalize().add(new Vector(0, 0.9, 0)).normalize(),
            new Vector(0, 1, 0),
            new Vector(-dir.getZ(), 0, dir.getX()).normalize(),
            new Vector(dir.getZ(), 0, -dir.getX()).normalize(),
            new Vector(0, -1, 0)
        };
        for (Vector v : prueba) {
            if (Double.isNaN(v.getX())) continue;
            Location l = bl.clone().add(v.multiply(paso));
            if (libre(l)) return l;
        }
        return null;
    }

    private static boolean libre(Location l) {
        Block b = l.getBlock(), a = l.clone().add(0, 0.9, 0).getBlock();
        return b.isPassable() && !b.isLiquid() && a.isPassable() && !a.isLiquid();
    }

    private Player cercano(Bat b) { return cercano(b.getLocation(), 40); }

    private Player cercano(Location l, double r) {
        Player mejor = null;
        double md = r * r;
        for (Player p : l.getWorld().getPlayers()) {
            if (!jugable(p)) continue;
            double dd = p.getLocation().distanceSquared(l);
            if (dd < md) { md = dd; mejor = p; }
        }
        return mejor;
    }

    private void morder(Bat b, Player p) {
        if (pl.corazon() == null || !jugable(p)) return;
        Location l = p.getLocation().add(0, 1.2, 0);
        p.getWorld().playSound(l, Sound.ENTITY_PHANTOM_BITE, SoundCategory.HOSTILE, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, l, 4, 0.2, 0.3, 0.2, 0.05);
        p.playHurtAnimation(0);
        boolean murio = pl.corazon().robar(p, 1, Txt.c(p.getName() + " fue desangrado por un Murciélago Vampiro"));
        if (pl.getConfig().getBoolean("murcielagos.debug", false)) {
            var mx = p.getAttribute(Attribute.MAX_HEALTH);
            pl.getLogger().info("[Murcielago] mordio a " + p.getName() + ": vida maxima " + (mx == null ? "?" : mx.getValue()) + (murio ? " (murio)" : ""));
        }
        if (!murio) p.sendActionBar(Txt.c("&4❤ &c-1 &7¡Un murciélago vampiro te robó un corazón!"));
    }

    private void irse(Bat b) {
        b.getWorld().spawnParticle(Particle.SMOKE, b.getLocation(), 8, 0.2, 0.2, 0.2, 0.02);
        b.remove();
    }

    @EventHandler
    public void muere(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ETIQUETA)) bichos.remove(e.getEntity().getUniqueId());
    }

    /** /tod4 murcielago [cantidad] [jugador]: aparecen cerca (tuyo o de ese jugador) y lo persiguen (para probar). */
    public void cmd(CommandSender s, String[] a) {
        Player p = s instanceof Player pp ? pp : null;
        if (a.length > 2) p = Bukkit.getPlayerExact(a[2]);
        if (p == null) { Style.err(s, "Uso: &e/tod4 murcielago [cantidad] [jugador]"); return; }
        int n = 1;
        if (a.length > 1) try { n = Math.max(1, Math.min(10, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
        int hechos = 0;
        for (int i = 0; i < n; i++) {
            Location l = lugar(p.getLocation(), 6, 10, false);   // para probar: aparece aunque sea de dia al aire libre
            if (l != null) { crear(l, p); hechos++; }
        }
        Style.ok(s, hechos + " murciélago(s) vampiro persiguen a " + p.getName() + ".");
    }
}
