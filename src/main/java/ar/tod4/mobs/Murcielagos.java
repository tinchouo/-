package ar.tod4.mobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
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
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import ar.tod4.TOD4;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Murcielago Vampiro.
 * APARECEN: solo en el overworld principal (ni el Agujero/cueva, ni los Backrooms, ni otras dimensiones), en cualquier
 * bioma. Cada "spawn_cada_seg" segundos, a cada jugador le puede salir (con "spawn_chance" %) una manada de 3 a 5 lejos
 * (24 a 40 bloques), como mucho una cada "minutos_entre" minutos. Nunca arriba de agua o lava, ni en el aire sin piso
 * abajo, ni donde hay luz de antorchas; de dia solo donde no llega el sol (al sol se queman). Los comunes ya no aparecen.
 * IA: andan dando vueltas como murcielagos comunes hasta que VEN a alguien (sin paredes en el medio, a menos de "vision"
 * bloques). Ahi avisan a los de su manada y lo persiguen: recto si se puede, y si no, buscando el camino por los
 * pasillos. Si lo pierden de vista van a donde lo vieron por ultima vez; si no lo vuelven a ver, se olvidan. Solo muerden
 * si lo ven. No se ahogan ni se golpean contra las paredes. No persiguen a los cuyes ni a los que estan en el login.
 * Morder: cada "muerde_cada_ticks" le roba un corazon de la vida maxima (por jugador: aunque haya varios, de a uno).
 * config.yml -> murcielagos. Para probar: /tod4 murcielago [cantidad] [jugador] (esos ya saben donde esta).
 */
public final class Murcielagos implements Listener {

    public static final String ETIQUETA = "tod4_vampiro";

    private final TOD4 pl;
    private final Random rnd = new Random();
    private final Map<UUID, Estado> bichos = new HashMap<>();
    /** Por jugador: cuando le aparecio la ultima manada y el tick de la ultima mordida (de cualquiera). */
    private final Map<UUID, Long> ultimoNace = new HashMap<>();
    private final Map<UUID, Integer> ultimaMordida = new HashMap<>();
    private int tick;

    private static final class Estado {
        UUID para;              // el jugador al que le aparecio (para contar cuantos tiene cada uno)
        UUID objetivo;          // al que esta persiguiendo (null = dando vueltas)
        boolean ve;             // lo esta viendo ahora
        int sinVer;             // ticks sin verlo
        Location vistoEn;       // donde lo vio por ultima vez
        boolean rastrea;        // los del comando: siempre saben donde esta (para probar)
        long nace = System.currentTimeMillis();
        int sinNadie;
        final int fase = new Random().nextInt(20);   // para que no piensen todos en el mismo tick
        // dando vueltas
        Vector paseo;
        int cambia;
        // camino
        List<Location> camino;
        int caminoTick = -1000;
        long caminoMeta = Long.MIN_VALUE;
        // trabado
        Location antes;
        int quieto;
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

    private void log(String m) { if (pl.getConfig().getBoolean("murcielagos.debug", false)) pl.getLogger().info("[Murcielago] " + m); }

    /** A quien persiguen: jugadores en supervivencia/aventura, menos los cuyes (que no pueden morir) y los del login. */
    private static boolean jugable(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !ar.tod4.hook.Cuyes.activo(p) && !enLogin(p);
    }

    private static java.lang.reflect.Method authmeOk;
    private static Object authmeApi;

    /**
     * Todavia no se logueo: con AuthMe se le pregunta a AuthMe; si no, el que tiene Resistencia muy alta (la que da el
     * login mientras escribe la contrasena) cuenta como que esta en el login.
     */
    static boolean enLogin(Player p) {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") != null) {
            try {
                if (authmeOk == null) {
                    Class<?> k = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
                    authmeApi = k.getMethod("getInstance").invoke(null);
                    authmeOk = k.getMethod("isAuthenticated", Player.class);
                }
                return !(Boolean) authmeOk.invoke(authmeApi, p);
            } catch (Throwable ignored) { }
        }
        org.bukkit.potion.PotionEffect r = p.getPotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE);
        return r != null && r.getAmplifier() >= 4;
    }

    /** El overworld principal (no el Agujero ni los Backrooms, que tambien son "normales"). */
    private World overworld() { return Bukkit.getWorlds().get(0); }

    // ================================================================= aparecer

    private int segLimpiar, segSpawn;

    private boolean activo() {
        return pl.getConfig().getBoolean("murcielagos.activo", true) && pl.dia() >= pl.getConfig().getInt("murcielagos.desde_dia", 11);
    }

    private static boolean esDeDia(World w) {
        long t = w.getTime();
        return t < 12300 || t > 23850;
    }

    private void aparecer() {
        if (!activo()) return;
        World w = overworld();
        // los murcielagos comunes que hayan quedado (de antes, o de chunks que se cargaron) se van
        if (++segLimpiar >= 20) {
            segLimpiar = 0;
            if (pl.getConfig().getBoolean("murcielagos.reemplazar_comunes", true))
                for (Bat b : w.getEntitiesByClass(Bat.class)) if (!b.getScoreboardTags().contains(ETIQUETA)) b.remove();
        }
        if (!pl.getConfig().getBoolean("murcielagos.spawn_por_tiempo", false)) return;
        if (++segSpawn < Math.max(1, (int) d("spawn_cada_seg", 30))) return;
        segSpawn = 0;
        int max = (int) d("max_por_jugador", 5);
        for (Player p : w.getPlayers()) {
            if (!jugable(p) || cuantosDe(p) >= max || !leToca(p)) continue;
            if (rnd.nextDouble() * 100 >= d("spawn_chance", 15)) continue;
            Location l = lugar(p.getLocation(), d("distancia_min", 24), d("distancia_max", 40));
            if (l == null) continue;
            ultimoNace.put(p.getUniqueId(), System.currentTimeMillis());
            int min = (int) d("manada_min", 3), mx = Math.max(min, (int) d("manada_max", 5));
            int n = Math.min(min + rnd.nextInt(mx - min + 1), Math.max(1, max - cuantosDe(p)));
            for (int i = 0; i < n; i++) {
                Location c = i == 0 ? l : l.clone().add(rnd.nextDouble() * 3 - 1.5, rnd.nextDouble() * 1.5, rnd.nextDouble() * 3 - 1.5);
                crear(libre(c) ? c : l, p, false);
            }
            log("aparecio una manada de " + n + " a " + Math.round(l.distance(p.getLocation())) + " bloques de " + p.getName());
        }
    }

    /**
     * Aparecen con los mobs: cada vez que el juego hace aparecer solo un mob hostil en el overworld (zombie, creeper,
     * esqueleto...; los phantoms no, que salen muy alto), hay "chance_por_mob" % de que ahi mismo salga una manada.
     * El mob aparece igual (no se toca). La manada es del jugador mas cercano (en supervivencia/aventura, a 128 bloques).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void conMobs(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        Entity ent = e.getEntity();
        if (!(ent instanceof org.bukkit.entity.Monster) || ent instanceof org.bukkit.entity.Phantom) return;
        if (!pl.getConfig().getBoolean("murcielagos.spawn_por_mobs", true) || !ent.getWorld().equals(overworld()) || !activo()) return;
        if (rnd.nextDouble() * 100 >= d("chance_por_mob", 1)) return;
        Location l = e.getLocation().clone().add(0, 1, 0);
        Player p = cercano(l, 128);
        if (p == null) { log("salio un " + ent.getType() + " pero no hay nadie jugando cerca"); return; }
        int max = (int) d("max_por_jugador", 5);
        if (cuantosDe(p) >= max || !leToca(p)) return;
        if (!libre(l) || !puedeAparecer(l)) { log("salio un " + ent.getType() + " pero ahi no puede aparecer (luz, agua o sin piso)"); return; }
        ultimoNace.put(p.getUniqueId(), System.currentTimeMillis());
        int min = (int) d("manada_min", 3), mx = Math.max(min, (int) d("manada_max", 5));
        int n = Math.min(min + rnd.nextInt(mx - min + 1), Math.max(1, max - cuantosDe(p)));
        // un tick despues: no se mete con el mob que esta apareciendo (ni con otros plugins que lo cambian)
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!p.isOnline()) return;
            for (int i = 0; i < n; i++) {
                Location c = i == 0 ? l : l.clone().add(rnd.nextDouble() * 3 - 1.5, rnd.nextDouble() * 1.5, rnd.nextDouble() * 3 - 1.5);
                crear(libre(c) ? c : l, p, false);
            }
            log("aparecio una manada de " + n + " con un " + ent.getType() + " a " + Math.round(l.distance(p.getLocation())) + " bloques de " + p.getName());
        });
    }

    /** Los murcielagos comunes ya no aparecen (en el overworld, desde el dia de los vampiros). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void natural(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Bat) || e.getEntity().getScoreboardTags().contains(ETIQUETA)) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !e.getEntity().getWorld().equals(overworld())) return;
        if (pl.getConfig().getBoolean("murcielagos.reemplazar_comunes", true) && activo()) e.setCancelled(true);
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
        for (UUID u : new ArrayList<>(bichos.keySet())) {
            Entity ent = Bukkit.getEntity(u);
            if (!(ent instanceof Bat b) || !b.isValid()) continue;
            World w = b.getWorld();
            if (!esDeDia(w) || w.hasStorm()) continue;
            if (b.getLocation().getBlock().getLightFromSky() < 15) continue;
            b.setFireTicks(Math.max(b.getFireTicks(), 60));
            b.damage(d("dano_sol", 3));
        }
    }

    /** Como mucho una manada cada "minutos_entre" minutos para cada jugador. */
    private boolean leToca(Player p) {
        Long u = ultimoNace.get(p.getUniqueId());
        return u == null || System.currentTimeMillis() - u >= (long) (d("minutos_entre", 0) * 60000);
    }

    private int cuantosDe(Player p) {
        int n = 0;
        for (Estado e : bichos.values()) if (p.getUniqueId().equals(e.para)) n++;
        return n;
    }

    /** sabe = ya sabe donde esta el jugador (el comando); si no, aparece dando vueltas hasta que lo vea. */
    public Bat crear(Location l, Player para, boolean sabe) {
        Bat b = l.getWorld().spawn(l, Bat.class, x -> {
            x.setAI(false);           // lo movemos nosotros
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
        Estado st = new Estado();
        if (para != null) {
            st.para = para.getUniqueId();
            if (sabe) { perseguir(st, para, false); st.rastrea = true; }
        }
        bichos.put(b.getUniqueId(), st);
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
                if (!libre(l)) continue;
                if (!reglas || puedeAparecer(l)) return l;
            }
        }
        return null;
    }

    // ================================================================= la IA

    private void perseguir(Estado st, Player p, boolean loVio) {
        st.objetivo = p.getUniqueId();
        st.ve = loVio;
        st.sinVer = 0;
        st.vistoEn = p.getLocation().add(0, 1.1, 0);
        st.camino = null;
    }

    private void olvidar(Estado st) {
        st.objetivo = null;
        st.ve = false;
        st.camino = null;
        st.vistoEn = null;
        st.cambia = 0;
    }

    private void mover() {
        tick++;
        if (bichos.isEmpty()) return;
        long vida = (long) (d("dura_seg", 90) * 1000);
        for (Iterator<Map.Entry<UUID, Estado>> it = bichos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Estado> en = it.next();
            Entity ent = Bukkit.getEntity(en.getKey());
            Estado st = en.getValue();
            if (!(ent instanceof Bat b) || !b.isValid()) { it.remove(); continue; }
            long edad = System.currentTimeMillis() - st.nace;
            // se va si paso mucho tiempo (si esta persiguiendo a alguien, aguanta el doble)
            if (edad > (st.objetivo == null ? vida : vida * 2)) { irse(b); it.remove(); continue; }
            // se va si no hay nadie cerca
            if ((tick + st.fase) % 20 == 0) {
                if (cercano(b.getLocation(), 64) == null) {
                    if ((st.sinNadie += 20) > 300) { irse(b); it.remove(); continue; }
                } else st.sinNadie = 0;
            }
            // si quedo metido en un bloque (alguien puso un bloque encima), sale
            if (!libre(b.getLocation())) destrabar(b);

            pensar(b, st);
            if (st.objetivo != null) cazar(b, st);
            else pasear(b, st);
            if (tick % 40 == 0 && rnd.nextInt(3) == 0)
                b.getWorld().playSound(b.getLocation(), Sound.ENTITY_BAT_AMBIENT, SoundCategory.HOSTILE, 0.8f, st.objetivo != null ? 0.6f : 0.9f);
        }
    }

    /** Cada 5 ticks: mira si ve a alguien (sin paredes en el medio). */
    private void pensar(Bat b, Estado st) {
        int olvida = (int) (d("olvida_seg", 5) * 20);
        if (st.objetivo != null) {
            Player p = Bukkit.getPlayer(st.objetivo);
            if (p == null || !jugable(p) || !p.getWorld().equals(b.getWorld()) || p.getLocation().distanceSquared(b.getLocation()) > 48 * 48) {
                olvidar(st);
            } else {
                st.sinVer++;
                if ((tick + st.fase) % 5 == 0) {
                    st.ve = p.getLocation().distanceSquared(b.getLocation()) < sq(d("vision", 20) * 1.5) && b.hasLineOfSight(p);
                    if (st.ve || st.rastrea) { st.sinVer = 0; st.vistoEn = p.getLocation().add(0, 1.1, 0); }
                }
                if (st.sinVer > olvida) {
                    log("perdio de vista a " + p.getName());
                    olvidar(st);
                }
            }
        }
        if (st.objetivo == null && (tick + st.fase) % 5 == 0) {
            double vision = d("vision", 20);
            Player mejor = null;
            double md = vision * vision;
            for (Player p : b.getWorld().getPlayers()) {
                if (!jugable(p)) continue;
                double dd = p.getLocation().distanceSquared(b.getLocation());
                if (dd < md && b.hasLineOfSight(p)) { md = dd; mejor = p; }
            }
            if (mejor != null) {
                perseguir(st, mejor, true);
                log("vio a " + mejor.getName() + " a " + Math.round(Math.sqrt(md)) + " bloques");
                b.getWorld().playSound(b.getLocation(), Sound.ENTITY_BAT_HURT, SoundCategory.HOSTILE, 1f, 0.5f);
                avisarManada(b, mejor);
            }
        }
    }

    /** Los de la manada que estan cerca y no persiguen a nadie van tambien (a donde lo vio este). */
    private void avisarManada(Bat b, Player p) {
        for (Entity e : b.getNearbyEntities(12, 8, 12)) {
            if (!(e instanceof Bat o) || !o.getScoreboardTags().contains(ETIQUETA)) continue;
            Estado so = bichos.get(o.getUniqueId());
            if (so == null || so.objetivo != null) continue;
            perseguir(so, p, false);
        }
    }

    /** Persigue: recto si lo ve y no hay nada en el medio; si no, por el camino (pasillos); si no lo ve, a donde lo vio. */
    private void cazar(Bat b, Estado st) {
        Player p = Bukkit.getPlayer(st.objetivo);
        if (p == null) { olvidar(st); return; }
        double vel = d("velocidad", 0.25);
        Location bl = b.getLocation();
        Location meta = st.ve ? p.getLocation().add(0, 1.1, 0) : st.vistoEn;
        if (meta == null) { olvidar(st); return; }
        Vector hacia = meta.toVector().subtract(bl.toVector());
        double dist = hacia.length();

        if (st.ve && dist < 24 && directo(bl, meta)) {
            st.camino = null;
            if (dist > 0.7) volar(b, st, hacia.multiply(Math.min(vel, dist - 0.6) / dist));
        } else if (!st.ve && dist < 1.2) {
            // llego a donde lo vio y no esta: lo sigue buscando un rato (pensar() se olvida solo)
            pasear(b, st);
        } else {
            seguirCamino(b, st, meta, vel);
        }

        // muerde: solo si lo ve (nada de morder a traves de la pared)
        double cerca = p.getLocation().add(0, 1.1, 0).distance(b.getLocation());
        int ult = ultimaMordida.getOrDefault(p.getUniqueId(), -100000);
        if (cerca < d("alcance", 1.4) && tick - ult >= Math.max(1, (int) d("muerde_cada_ticks", 20)) && b.hasLineOfSight(p)) {
            ultimaMordida.put(p.getUniqueId(), tick);
            st.ve = true; st.sinVer = 0;
            morder(b, p);
        }
    }

    private void seguirCamino(Bat b, Estado st, Location meta, double vel) {
        Location bl = b.getLocation();
        long clave = celdaClave(meta);
        boolean viejo = st.camino == null || st.camino.isEmpty()
                || (clave != st.caminoMeta && tick - st.caminoTick >= 10);
        if (viejo && tick - st.caminoTick >= 5) {
            st.camino = camino(bl, meta);
            st.caminoTick = tick;
            st.caminoMeta = clave;
            if (st.camino == null) log("no encontro camino hacia " + meta.getBlockX() + " " + meta.getBlockY() + " " + meta.getBlockZ());
        }
        if (st.camino == null || st.camino.isEmpty()) {
            // sin camino: intenta ir recto (desliza por las paredes); si no puede, da vueltas
            Vector v = meta.toVector().subtract(bl.toVector());
            if (v.lengthSquared() > 0.25 && volar(b, st, v.normalize().multiply(vel * 0.6))) return;
            pasear(b, st);
            return;
        }
        // atajos: si el siguiente punto del camino se ve directo, saltea el actual
        for (int i = 0; i < 3 && st.camino.size() > 1 && directo(bl, st.camino.get(1)); i++) st.camino.remove(0);
        Location wp = st.camino.get(0);
        Vector v = wp.toVector().subtract(bl.toVector());
        double dist = v.length();
        if (dist < 0.3) { st.camino.remove(0); return; }
        if (!volar(b, st, v.multiply(Math.min(vel, dist) / dist))) st.camino = null;   // algo cambio: recalcula
    }

    /** Dando vueltas como un murcielago comun: cambia de direccion seguido, aletea, y a veces va para donde hay gente. */
    private void pasear(Bat b, Estado st) {
        Location bl = b.getLocation();
        if (st.paseo == null || tick >= st.cambia) nuevoRumbo(b, st);
        double v = d("velocidad_paseo", 0.12);
        Vector m = st.paseo.clone().multiply(v);
        m.setY(m.getY() + Math.sin((tick + st.fase * 7) * 0.35) * 0.04);   // aleteo
        if (!volar(b, st, m)) { nuevoRumbo(b, st); }
        // muy alto: baja
        if (sueloAbajo(bl, 7) < 0 && st.paseo.getY() > -0.2) st.paseo.setY(-0.35).normalize();
    }

    private void nuevoRumbo(Bat b, Estado st) {
        st.cambia = tick + 20 + rnd.nextInt(40);
        Location bl = b.getLocation();
        Vector v = null;
        // a veces va para el lado de alguien (asi terminan encontrando gente), pero no lo "ve" hasta que no hay pared
        if (rnd.nextDouble() < d("paseo_hacia_gente", 0.35)) {
            Player p = st.para != null ? Bukkit.getPlayer(st.para) : null;
            if (p == null || !jugable(p) || !p.getWorld().equals(b.getWorld()) || p.getLocation().distanceSquared(bl) > 64 * 64)
                p = cercano(bl, 48);
            if (p != null) {
                v = p.getLocation().add(0, 1.5, 0).toVector().subtract(bl.toVector());
                v.add(new Vector(rnd.nextGaussian() * 3, 0, rnd.nextGaussian() * 3));
            }
        }
        for (int i = 0; i < 8; i++) {
            if (v == null || i > 0) {
                double a = rnd.nextDouble() * Math.PI * 2;
                v = new Vector(Math.cos(a), rnd.nextDouble() * 0.7 - 0.35, Math.sin(a));
            }
            if (v.lengthSquared() < 1e-4) continue;
            v.normalize();
            if (libre(bl.clone().add(v.clone().multiply(1.0)))) break;
        }
        st.paseo = v == null ? new Vector(0, 0.2, 0) : v;
    }

    /** Cuantos bloques de aire hay abajo hasta el piso (-1 si mas de "max"). */
    private static int sueloAbajo(Location l, int max) {
        Block b = l.getBlock();
        for (int i = 1; i <= max; i++) if (!b.getRelative(0, -i, 0).isPassable() || b.getRelative(0, -i, 0).isLiquid()) return i;
        return -1;
    }

    /**
     * Mueve el murcielago v (sin meterse en bloques: si choca, desliza por la pared como cualquier mob).
     * Devuelve false si no se pudo mover nada (esta trabado contra algo).
     */
    private boolean volar(Bat b, Estado st, Vector v) {
        Location bl = b.getLocation();
        Location sig = paso(bl, v);
        if (sig == null) return false;
        Vector dir = v.clone().setY(0);
        if (dir.lengthSquared() > 1e-4) sig.setDirection(dir);
        else { sig.setYaw(bl.getYaw()); sig.setPitch(0); }
        b.teleport(sig);
        // trabado: si en 15 ticks casi no se movio, cambia (otro camino / otro rumbo)
        if (st.antes == null || st.antes.distanceSquared(sig) > 0.04) { st.antes = sig.clone(); st.quieto = 0; }
        else if (++st.quieto > 15) { st.quieto = 0; st.camino = null; st.cambia = 0; return false; }
        return true;
    }

    private Location paso(Location bl, Vector v) {
        if (v.lengthSquared() < 1e-6) return null;
        double x = v.getX(), y = v.getY(), z = v.getZ();
        double[][] prueba = { {x, y, z}, {x, 0, z}, {x, y, 0}, {0, y, z}, {x, 0, 0}, {0, 0, z}, {0, y, 0} };
        for (double[] p : prueba) {
            if (Math.abs(p[0]) + Math.abs(p[1]) + Math.abs(p[2]) < 0.01) continue;
            Location l = bl.clone().add(p[0], p[1], p[2]);
            if (libre(l)) return l;
        }
        return null;
    }

    private void destrabar(Bat b) {
        Location bl = b.getLocation();
        World w = bl.getWorld();
        int bx = bl.getBlockX(), by = bl.getBlockY(), bz = bl.getBlockZ();
        for (int r = 0; r <= 2; r++)
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                if (celda(w, bx + dx, by + dy, bz + dz)) { b.teleport(centro(w, bx + dx, by + dy, bz + dz)); return; }
            }
    }

    // ================================================================= choques y caminos

    /** El cuerpo del murcielago (0,5 de ancho, 0,9 de alto) en esa posicion (los pies). */
    private static BoundingBox cuerpo(Location l) {
        return new BoundingBox(l.getX() - 0.25, l.getY(), l.getZ() - 0.25, l.getX() + 0.25, l.getY() + 0.9, l.getZ() + 0.25);
    }

    /** Entra ahi sin tocar ningun bloque (usa la forma real: losas, vallas, puertas...) ni agua o lava. */
    private static boolean libre(Location l) {
        World w = l.getWorld();
        BoundingBox c = cuerpo(l);
        int x0 = (int) Math.floor(c.getMinX()), x1 = (int) Math.floor(c.getMaxX());
        int y0 = (int) Math.floor(c.getMinY()) - 1, y1 = (int) Math.floor(c.getMaxY());
        int z0 = (int) Math.floor(c.getMinZ()), z1 = (int) Math.floor(c.getMaxZ());
        if (y0 < w.getMinHeight() || y1 >= w.getMaxHeight()) return false;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            for (int y = y0; y <= y1; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (b.isLiquid()) { if (y >= Math.floor(c.getMinY())) return false; continue; }
                if (b.isPassable() && y >= Math.floor(c.getMinY())) continue;
                if (b.getCollisionShape().overlaps(c.clone().shift(-x, -y, -z))) return false;
            }
        }
        return true;
    }

    /** Se puede ir en linea recta de a hasta b sin chocar con nada. */
    private static boolean directo(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 1e-3) return true;
        int pasos = (int) Math.ceil(len / 0.35);
        Vector paso = d.multiply(1.0 / pasos);
        Location l = a.clone();
        for (int i = 0; i < pasos; i++) {
            l.add(paso);
            if (!libre(l)) return false;
        }
        return true;
    }

    private static boolean celda(World w, int x, int y, int z) { return libre(centro(w, x, y, z)); }

    private static Location centro(World w, int x, int y, int z) { return new Location(w, x + 0.5, y + 0.05, z + 0.5); }

    private static long celdaClave(Location l) {
        return ((long) l.getBlockX() & 0x3FFFFFF) << 38 | ((long) l.getBlockZ() & 0x3FFFFFF) << 12 | ((long) l.getBlockY() & 0xFFF);
    }

    private static final int R = 32, MAX_NODOS = 3000;
    private static final int[][] DIRS = { {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0} };

    private static int clave(int dx, int dy, int dz) { return ((dx + 64) << 14) | ((dy + 64) << 7) | (dz + 64); }

    /**
     * Busca el camino por los bloques de aire (A*) desde el murcielago hasta meta. Si no llega, devuelve lo que mas se
     * acerca. null = no hay nada mejor que quedarse.
     */
    private List<Location> camino(Location desde, Location meta) {
        World w = desde.getWorld();
        int sx = desde.getBlockX(), sy = desde.getBlockY(), sz = desde.getBlockZ();
        if (!celda(w, sx, sy, sz)) {
            boolean ok = false;
            for (int[] dd : DIRS) if (celda(w, sx + dd[0], sy + dd[1], sz + dd[2])) { sx += dd[0]; sy += dd[1]; sz += dd[2]; ok = true; break; }
            if (!ok) return null;
        }
        int gx = meta.getBlockX(), gy = meta.getBlockY(), gz = meta.getBlockZ();
        if (Math.abs(gx - sx) > R || Math.abs(gy - sy) > R || Math.abs(gz - sz) > R) {
            // muy lejos: apunta a un punto en esa direccion dentro del radio
            Vector v = new Vector(gx - sx, gy - sy, gz - sz);
            v.multiply((R - 1) / Math.max(Math.abs(v.getX()), Math.max(Math.abs(v.getY()), Math.abs(v.getZ()))));
            gx = sx + (int) v.getX(); gy = sy + (int) v.getY(); gz = sz + (int) v.getZ();
        }
        final int fx = gx, fy = gy, fz = gz, ox = sx, oy = sy, oz = sz;
        Map<Integer, Integer> g = new HashMap<>(), padre = new HashMap<>();
        Set<Integer> cerrado = new HashSet<>(), bloqueado = new HashSet<>();
        PriorityQueue<int[]> abiertos = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
        int k0 = clave(0, 0, 0);
        int h0 = Math.abs(gx - sx) + Math.abs(gy - sy) + Math.abs(gz - sz);
        g.put(k0, 0);
        abiertos.add(new int[]{k0, h0 * 2});
        int mejor = k0, mejorH = h0, nodos = 0;
        while (!abiertos.isEmpty() && nodos < MAX_NODOS) {
            int k = abiertos.poll()[0];
            if (!cerrado.add(k)) continue;
            nodos++;
            int dx = (k >> 14) - 64, dy = ((k >> 7) & 127) - 64, dz = (k & 127) - 64;
            int x = ox + dx, y = oy + dy, z = oz + dz;
            int h = Math.abs(fx - x) + Math.abs(fy - y) + Math.abs(fz - z);
            if (h < mejorH) { mejorH = h; mejor = k; }
            if (h <= 1) break;
            int cg = g.get(k);
            for (int[] dd : DIRS) {
                int ndx = dx + dd[0], ndy = dy + dd[1], ndz = dz + dd[2];
                if (Math.abs(ndx) > R || Math.abs(ndy) > R || Math.abs(ndz) > R) continue;
                int nk = clave(ndx, ndy, ndz);
                if (cerrado.contains(nk) || bloqueado.contains(nk)) continue;
                Integer og = g.get(nk);
                if (og != null && og <= cg + 1) continue;
                if (og == null && !celda(w, ox + ndx, oy + ndy, oz + ndz)) { bloqueado.add(nk); continue; }
                g.put(nk, cg + 1);
                padre.put(nk, k);
                int nh = Math.abs(fx - ox - ndx) + Math.abs(fy - oy - ndy) + Math.abs(fz - oz - ndz);
                abiertos.add(new int[]{nk, cg + 1 + nh * 2});
            }
        }
        if (mejor == k0) return null;
        List<Location> res = new ArrayList<>();
        for (int k = mejor; k != k0; k = padre.get(k)) {
            int dx = (k >> 14) - 64, dy = ((k >> 7) & 127) - 64, dz = (k & 127) - 64;
            res.add(0, centro(w, ox + dx, oy + dy, oz + dz));
        }
        return res;
    }

    private static double sq(double x) { return x * x; }

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

    // ================================================================= morder, golpes y muerte

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
    }

    /** No se ahogan en las paredes, no se lastiman por caer ni por estar apretados: solo los mata el sol o un golpe. */
    @EventHandler(ignoreCancelled = true)
    public void dano(EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ETIQUETA)) return;
        switch (e.getCause()) {
            case SUFFOCATION, FALL, CRAMMING, FLY_INTO_WALL, DROWNING -> e.setCancelled(true);
            default -> { }
        }
    }

    /** Si alguien le pega (y no muere), lo persigue. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golpeado(EntityDamageByEntityEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ETIQUETA)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        Estado st = bichos.get(e.getEntity().getUniqueId());
        if (st != null && d instanceof Player p && jugable(p)) perseguir(st, p, true);
    }

    private void irse(Bat b) {
        b.getWorld().spawnParticle(Particle.SMOKE, b.getLocation(), 8, 0.2, 0.2, 0.2, 0.02);
        b.remove();
    }

    @EventHandler
    public void muere(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ETIQUETA)) bichos.remove(e.getEntity().getUniqueId());
    }

    /** /tod4 murcielago [cantidad] [jugador]: aparecen cerca (tuyo o de ese jugador) y ya saben donde esta (para probar). */
    public void cmd(CommandSender s, String[] a) {
        Player p = s instanceof Player pp ? pp : null;
        if (a.length > 2) p = Bukkit.getPlayerExact(a[2]);
        if (p == null) { Style.err(s, "Uso: &e/tod4 murcielago [cantidad] [jugador]"); return; }
        int n = 1;
        if (a.length > 1) try { n = Math.max(1, Math.min(10, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
        int hechos = 0;
        for (int i = 0; i < n; i++) {
            Location l = lugar(p.getLocation(), 6, 10, false);   // para probar: aparece aunque sea de dia al aire libre
            if (l != null) { crear(l, p, true); hechos++; }
        }
        Style.ok(s, hechos + " murciélago(s) vampiro persiguen a " + p.getName() + ".");
    }
}
