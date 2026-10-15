package ar.tod4.fiesta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.Style;
import ar.tod4.video.Video;

/**
 * Level Fun (la fiesta de los partygoers), una zona fija adentro del mundo de las backrooms.
 *  - Se entra por un pasillo negro en un borde de las backrooms; se vuelve por el mismo pasillo del otro lado.
 *  - Con luz: suena la musica (en pedazos de 10 s: cuando vuelve la luz sigue desde donde se corto).
 *  - Cada tanto: parpadeo, apagon, se corta la musica y aparecen partygoers con particulas rojas, con su sonido.
 *    Te persiguen siempre y cada golpe saca un tercio de la vida. En un casillero estas a salvo.
 *  - Vuelve la luz: desaparecen y sigue la musica.
 */
public final class Fiesta implements Listener {

    private enum Estado { LUZ, PARPADEO, APAGON, VUELVE }

    private static final int[] MUSICA = {200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 66};
    private static final int APAGON_TICKS = 629;
    public static final String ETIQUETA = "tod4_fiesta";

    private final TOD4 pl;
    private final Store st;
    private final Casilleros casilleros;
    private final NamespacedKey PARTY;
    private final Random rnd = new Random();
    private final Set<UUID> dentro = new HashSet<>();
    private final Map<UUID, Long> ultimoGolpe = new HashMap<>();

    private Mapa mapa;
    private Estado estado = Estado.LUZ;
    private long t, hasta, segFin, apagonSonido, proxAparicion;
    private int seg = -1;
    private boolean construyendo;

    public Fiesta(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "fiesta");
        this.casilleros = new Casilleros(pl);
        this.PARTY = new NamespacedKey(pl, "partygoer");
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    // ================================================================= lugares

    private World mundo() { return Bukkit.getWorld(pl.getConfig().getString("fiesta.mundo", "backrooms")); }

    private Mapa mapa() {
        World w = mundo();
        if (w == null) return null;
        if (mapa == null) {
            List<Integer> o = pl.getConfig().getIntegerList("fiesta.origen");
            int x = o.size() > 0 ? o.get(0) : -1000, y = o.size() > 1 ? o.get(1) : 100, z = o.size() > 2 ? o.get(2) : -1000;
            mapa = new Mapa(new Location(w, x, y, z));
        }
        return mapa;
    }

    /** Fila del pasillo en las backrooms (la del medio del borde oeste). */
    private int filaZ() { return (pl.getConfig().getInt("fiesta.backrooms_celdas", 50) / 2) * 6; }

    private boolean enPasilloBackrooms(Location l) {
        if (l.getWorld() != mundo()) return false;
        int z0 = filaZ();
        return l.getBlockX() <= -12 && l.getBlockX() >= -17 && l.getBlockZ() > z0 && l.getBlockZ() < z0 + 6
                && l.getBlockY() >= 100 && l.getBlockY() <= 104;
    }

    private Location vueltaBackrooms() {
        Location l = new Location(mundo(), -3.5, 101, filaZ() + 3.5);
        l.setYaw(-90);
        return l;
    }

    /** El pasillo de entrada en el borde oeste de las backrooms (se vuelve a abrir despues de /lobotomia crear). */
    public void conectar() {
        World w = mundo();
        if (w == null) return;
        int z0 = filaZ();
        for (int x = -17; x <= -1; x++) for (int z = z0 + 1; z <= z0 + 5; z++) for (int y = 100; y <= 104; y++) {
            boolean hueco = x >= -16 && z >= z0 + 2 && z <= z0 + 4 && y >= 101 && y <= 103;
            w.getBlockAt(x, y, z).setType(hueco ? Material.AIR : Material.BLACK_CONCRETE, false);
        }
        for (int z = z0 + 2; z <= z0 + 4; z++) for (int y = 101; y <= 103; y++) w.getBlockAt(0, y, z).setType(Material.AIR, false);
    }

    // ================================================================= ciclo

    private void tick() {
        t++;
        Mapa m = mapa();
        if (m == null || !st.y().getBoolean("creada", false)) return;
        if (t % 5 == 0) entradasYSalidas(m);
        if (dentro.isEmpty()) {
            if (estado != Estado.LUZ) { estado = Estado.LUZ; luces(m, true); borrarPartygoers(); }
            hasta = 0;
            if (segFin == Long.MAX_VALUE) segFin = 0;
            return;
        }
        if (hasta == 0) {
            // entra el primero: luz prendida (por si el server se apago en medio de un apagon)
            luces(m, true);
            hasta = t + seg(pl.getConfig().getInt("fiesta.luz_min_seg", 180), pl.getConfig().getInt("fiesta.luz_max_seg", 300));
        }
        switch (estado) {
            case LUZ:
                if (t >= segFin) {
                    seg = (seg + 1) % MUSICA.length;
                    segFin = t + MUSICA[seg];
                    for (Player p : jugadores()) musica(p);
                }
                if (t >= hasta) { estado = Estado.PARPADEO; hasta = t + 60; }
                break;
            case PARPADEO:
                if (t % 3 == 0) {
                    luces(m, rnd.nextBoolean());
                    if (rnd.nextInt(3) == 0) for (Player p : jugadores())
                        p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.4f, 0.6f + rnd.nextFloat() * 0.6f);
                }
                if (t >= hasta) empezarApagon(m);
                break;
            case APAGON:
                if (t >= apagonSonido) {
                    apagonSonido = t + APAGON_TICKS;
                    for (Player p : jugadores()) p.playSound(p.getLocation(), "tod4:fiesta.apagon", SoundCategory.MASTER, 1f, 1f);
                }
                if (t % 10 == 0) perseguir();
                if (t >= proxAparicion) { aparecer(m); proxAparicion = t + 20 + rnd.nextInt(30); }
                if (t >= hasta) { estado = Estado.VUELVE; hasta = t + 30; }
                break;
            case VUELVE:
                if (t % 3 == 0) luces(m, rnd.nextInt(3) != 0);
                if (t >= hasta) terminarApagon(m);
                break;
            default: break;
        }
    }

    private long seg(int min, int max) { return 20L * (min + rnd.nextInt(Math.max(1, max - min + 1))); }

    private List<Player> jugadores() {
        List<Player> out = new ArrayList<>();
        for (UUID u : dentro) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    private void musica(Player p) {
        if (seg < 0) return;
        for (int i = 0; i < MUSICA.length; i++) p.stopSound(String.format("tod4:fiesta.musica_%02d", i));
        p.playSound(p.getLocation(), String.format("tod4:fiesta.musica_%02d", seg), SoundCategory.MASTER, 0.8f, 1f);
    }

    private void callarTodo(Player p) {
        for (int i = 0; i < MUSICA.length; i++) p.stopSound(String.format("tod4:fiesta.musica_%02d", i));
        p.stopSound("tod4:fiesta.apagon");
    }

    private void luces(Mapa m, boolean prendidas) {
        Material on = Material.SEA_LANTERN, off = Material.GRAY_CONCRETE;
        for (Block b : m.luces()) if (b.getType() != (prendidas ? on : off)) b.setType(prendidas ? on : off, false);
    }

    private void empezarApagon(Mapa m) {
        estado = Estado.APAGON;
        hasta = t + seg(pl.getConfig().getInt("fiesta.apagon_min_seg", 60), pl.getConfig().getInt("fiesta.apagon_max_seg", 90));
        luces(m, false);
        for (Player p : jugadores()) {
            callarTodo(p);
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 1f, 0.5f);
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.BLOCKS, 1f, 0.5f);
        }
        apagonSonido = t + 30;
        proxAparicion = t + 40;
        // la cancion sigue desde este pedazo cuando vuelva la luz
        segFin = Long.MAX_VALUE;
    }

    private void terminarApagon(Mapa m) {
        estado = Estado.LUZ;
        hasta = t + seg(pl.getConfig().getInt("fiesta.luz_min_seg", 180), pl.getConfig().getInt("fiesta.luz_max_seg", 300));
        luces(m, true);
        borrarPartygoers();
        for (Player p : jugadores()) {
            p.stopSound("tod4:fiesta.apagon");
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.8f, 1.4f);
        }
        segFin = t + MUSICA[Math.max(0, seg)];
        for (Player p : jugadores()) musica(p);
    }

    // ================================================================= entrar / salir

    private boolean valido(Player p) {
        return !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && !Video.viendo(p);
    }

    private void entradasYSalidas(Mapa m) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean adentro = valido(p) && m.adentro(p.getLocation());
            if (valido(p) && enPasilloBackrooms(p.getLocation())) {
                p.teleport(m.llegada());
                p.playSound(m.llegada(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.6f, 0.6f);
                adentro = true;
            } else if (adentro && m.enSalida(p.getLocation())) {
                p.teleport(vueltaBackrooms());
                adentro = false;
            }
            if (adentro && dentro.add(p.getUniqueId())) {
                p.addScoreboardTag(ETIQUETA);
                if (estado == Estado.LUZ) musica(p);
                if (estado == Estado.APAGON) p.playSound(p.getLocation(), "tod4:fiesta.apagon", SoundCategory.MASTER, 1f, 1f);
            } else if (!adentro && dentro.remove(p.getUniqueId())) {
                p.removeScoreboardTag(ETIQUETA);
                callarTodo(p);
                if (casilleros.escondido(p)) casilleros.salir(p);
            }
        }
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        dentro.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeScoreboardTag(ETIQUETA);
        ultimoGolpe.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= partygoers

    private boolean party(Entity e) { return e != null && e.getPersistentDataContainer().has(PARTY, PersistentDataType.BYTE); }

    private List<Mob> partygoers() {
        List<Mob> out = new ArrayList<>();
        World w = mundo();
        if (w == null) return out;
        for (LivingEntity e : w.getLivingEntities()) if (e instanceof Mob mb && party(e)) out.add(mb);
        return out;
    }

    private void aparecer(Mapa m) {
        List<Player> js = jugadores();
        int max = Math.min(pl.getConfig().getInt("fiesta.partygoers_max", 12), 3 + 2 * js.size());
        if (partygoers().size() >= max) return;
        List<int[]> lugares = new ArrayList<>(Mapa.APARICIONES);
        java.util.Collections.shuffle(lugares, rnd);
        Location donde = null;
        for (int[] l : lugares) {
            Location c = m.centro(l[0], 1, l[1]);
            boolean lejos = true;
            for (Player p : js) if (p.getLocation().distanceSquared(c) < 100) { lejos = false; break; }
            if (lejos) { donde = c; break; }
        }
        if (donde == null) return;
        Location d = donde;
        d.getWorld().spawnParticle(Particle.DUST, d.clone().add(0, 1, 0), 60, 0.4, 0.9, 0.4, 0, new Particle.DustOptions(Color.RED, 1.6f));
        d.getWorld().playSound(d, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 1f, 0.5f);
        Entity e = spawnMythic(pl.getConfig().getString("fiesta.partygoer_mythic", "Partygoer"), d);
        if (e == null) e = d.getWorld().spawn(d, Husk.class, h -> { h.setSilent(true); h.setShouldBurnInDay(false); });
        e.getPersistentDataContainer().set(PARTY, PersistentDataType.BYTE, (byte) 1);
        e.setInvulnerable(true);
        e.setPersistent(false);
        if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
    }

    /** MythicMobs por reflexion: MythicBukkit.inst().getMobManager().spawnMob(nombre, lugar). */
    private Entity spawnMythic(String nombre, Location l) {
        try {
            Class<?> mb = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mb.getMethod("inst").invoke(null);
            Object mm = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object opt = mm.getClass().getMethod("getMythicMob", String.class).invoke(mm, nombre);
            if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) return null;
            Object am = mm.getClass().getMethod("spawnMob", String.class, Location.class).invoke(mm, nombre, l);
            if (am == null) return null;
            Object ae = am.getClass().getMethod("getEntity").invoke(am);
            Object be = ae.getClass().getMethod("getBukkitEntity").invoke(ae);
            return be instanceof Entity en ? en : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean presa(Player p) {
        return dentro.contains(p.getUniqueId()) && !casilleros.escondido(p) && !p.isDead()
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    /** Siempre persiguen al mas cercano que no este escondido. */
    private void perseguir() {
        List<Player> js = jugadores();
        for (Mob mb : partygoers()) {
            Player obj = null;
            double mejor = Double.MAX_VALUE;
            for (Player p : js) {
                if (!presa(p) || p.getWorld() != mb.getWorld()) continue;
                double d = p.getLocation().distanceSquared(mb.getLocation());
                if (d < mejor) { mejor = d; obj = p; }
            }
            if (obj == null) {
                if (mb.getTarget() != null) mb.setTarget(null);
                mb.getPathfinder().stopPathfinding();
                continue;
            }
            mb.setTarget(obj);
            mb.getPathfinder().moveTo(obj, pl.getConfig().getDouble("fiesta.velocidad", 1.25));
        }
    }

    private void borrarPartygoers() {
        for (Mob mb : partygoers()) {
            Location l = mb.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 25, 0.3, 0.8, 0.3, 0.02);
            mb.remove();
        }
    }

    /** No pueden apuntar a alguien escondido en un casillero. */
    @EventHandler(ignoreCancelled = true)
    public void apunta(EntityTargetEvent e) {
        if (!party(e.getEntity())) return;
        if (e.getTarget() instanceof Player p && !presa(p)) e.setCancelled(true);
    }

    /** Cada golpe saca un tercio de la vida (sin importar la armadura). Si te mata, el totem te puede salvar. */
    private boolean aplicando;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void golpe(EntityDamageByEntityEvent e) {
        if (party(e.getEntity())) { e.setCancelled(true); return; }
        if (aplicando || !(e.getEntity() instanceof Player p) || !party(e.getDamager())) return;
        e.setCancelled(true);
        long ahora = System.currentTimeMillis();
        Long ult = ultimoGolpe.get(p.getUniqueId());
        if (ult != null && ahora - ult < 900) return;
        ultimoGolpe.put(p.getUniqueId(), ahora);
        double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        double dano = max * pl.getConfig().getDouble("fiesta.golpe_fraccion", 1.0 / 3.0);
        double h = p.getHealth();
        Entity quien = e.getDamager();
        aplicando = true;
        try {
            if (h <= dano + 0.01) { p.damage(1000.0, quien); return; }
            p.damage(0.0001, quien); // golpe, empujon y sonido
        } finally {
            aplicando = false;
        }
        if (!p.isDead()) p.setHealth(Math.max(0.5, Math.min(p.getHealth(), h) - dano));
    }

    @EventHandler(ignoreCancelled = true)
    public void invulnerables(EntityDamageEvent e) {
        if (party(e.getEntity()) && !(e instanceof EntityDamageByEntityEvent)) e.setCancelled(true);
    }

    // ================================================================= admin

    public void comando(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "ayuda";
        Mapa m = mapa();
        switch (sub) {
            case "crear":
                if (m == null) { Style.err(s, "No existe el mundo &f" + pl.getConfig().getString("fiesta.mundo", "backrooms") + "&c."); return; }
                if (construyendo) { Style.err(s, "Ya se está construyendo."); return; }
                crear(s, m);
                return;
            case "conectar":
                conectar();
                Style.ok(s, "Pasillo de las backrooms a la fiesta abierto.");
                return;
            case "ir":
                if (s instanceof Player p && m != null) p.teleport(m.llegada());
                return;
            case "apagon":
                if (m != null && !dentro.isEmpty()) { empezarApagon(m); Style.ok(s, "Apagón."); }
                else Style.err(s, "No hay nadie en la fiesta.");
                return;
            case "luz":
                if (m != null) { terminarApagon(m); Style.ok(s, "Volvió la luz."); }
                return;
            case "casilleros":
                if (m != null) { ponerCasilleros(m); Style.ok(s, "Casilleros puestos de nuevo."); }
                return;
            case "estado":
                Style.info(s, "Creada: &f" + st.y().getBoolean("creada", false) + " &7| Estado: &f" + estado
                        + " &7| Adentro: &f" + dentro.size() + " &7| Partygoers: &f" + partygoers().size());
                return;
            default:
                Style.raw(s, "&e/tod4:fiesta crear &8- &7construye el Level Fun (una sola vez) y abre el pasillo");
                Style.raw(s, "&e/tod4:fiesta conectar &8- &7vuelve a abrir el pasillo (despues de /lobotomia crear)");
                Style.raw(s, "&e/tod4:fiesta ir &8- &7te lleva a la fiesta");
                Style.raw(s, "&e/tod4:fiesta apagon | luz &8- &7fuerza el apagón o la luz");
                Style.raw(s, "&e/tod4:fiesta casilleros &8- &7vuelve a poner los casilleros");
                Style.raw(s, "&e/tod4:fiesta estado");
        }
    }

    private void crear(CommandSender s, Mapa m) {
        construyendo = true;
        Mapa nuevo = new Mapa(m.centro(0, 0, 0).subtract(0.5, 0, 0.5));
        nuevo.construir();
        List<Runnable> pasos = nuevo.pasos;
        Style.info(s, "Construyendo la fiesta (&f" + pasos.size() + " &7bloques)...");
        int porTick = pl.getConfig().getInt("fiesta.bloques_por_tick", 4000);
        int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            int fin = Math.min(pasos.size(), i[0] + porTick);
            for (; i[0] < fin; i[0]++) pasos.get(i[0]).run();
            if (i[0] >= pasos.size()) {
                task.cancel();
                ponerCasilleros(m);
                conectar();
                st.set("creada", true);
                st.save();
                construyendo = false;
                Style.ok(s, "Fiesta lista: &f" + Mapa.CASILLEROS.size() + " &acasilleros. Usa &e/tod4:fiesta ir&a.");
            }
        }, 1L, 1L);
    }

    private void ponerCasilleros(Mapa m) {
        for (Mapa.Casillero c : Mapa.CASILLEROS) casilleros.poner(m.b(c.a(), 1, c.b()), c.frente());
    }

    public void apagar() {
        casilleros.salirTodos();
        borrarPartygoers();
        Mapa m = mapa();
        if (m != null && st.y().getBoolean("creada", false)) luces(m, true);
        for (Player p : jugadores()) { callarTodo(p); p.removeScoreboardTag(ETIQUETA); }
    }
}
