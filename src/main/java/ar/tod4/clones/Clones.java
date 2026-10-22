package ar.tod4.clones;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import ar.tod4.TOD4;
import ar.tod4.core.Causas;
import ar.tod4.data.Store;
import ar.tod4.util.Style;
import net.kyori.adventure.text.Component;

/**
 * Cosmic Clones (como en Mario Galaxy 2). En una zona marcada, el plugin graba al jugador tick por tick y unos clones
 * violetas repiten exactamente sus movimientos con atraso. Si uno lo toca: 3 corazones fijos (sin armadura, escudo,
 * resistencia ni totem), un empujon, y ese clon se va al final de la fila entre particulas.
 *
 * Modos por zona: FILA (10 clones del tamanio del jugador) o GIGANTE (x2, a 3 s).
 * El modo se toma al entrar: si se cambia con alguien adentro, ese sigue con el que tenia.
 * Paso del Momento Truco: mientras esta activo, todos los jugadores tienen clones en cualquier lugar.
 * Cada jugador ve solo sus clones (y sus particulas y sonidos). Los clones copian la pose y el brazo del jugador.
 *
 * Aspecto: disfraz guardado de LibsDisguises (clones.disfraz, por defecto "cosmic_clone"). Sin LibsDisguises o sin ese
 * disfraz, se ve como un soporte de armaduras con armadura violeta oscura (para poder probar igual).
 */
public final class Clones implements Listener {

    private static final int BUFFER = 1024;           // ticks guardados por jugador (~51 s)

    private static final class Frame {
        final double x, y, z;
        final float yaw, pitch;
        final boolean sneak;
        final String pose;              // pose del jugador (parado, agachado, nadando, elytra, tridente, durmiendo...)
        final boolean swing;            // ese tick movio el brazo (pegar, usar)
        Frame(Location l, boolean s, String pose, boolean swing) {
            x = l.getX(); y = l.getY(); z = l.getZ(); yaw = l.getYaw(); pitch = l.getPitch(); sneak = s; this.pose = pose; this.swing = swing;
        }
    }

    private static final class Clon {
        ArmorStand host;
        Object disfraz;                 // disfraz de LibsDisguises (o null)
        double atraso;                  // en ticks (puede ser decimal mientras alcanza su lugar en la fila)
        boolean agachado;
        String pose = "STANDING";
        long volviendoHasta;            // despues de pegar: solo particulas que viajan al final de la fila
        Location desde;                 // donde pego (de ahi salen las particulas)
    }

    private final class Sesion {
        final UUID jugador;
        final String zona;
        final boolean gigante;
        final double tam;               // tamanio de los clones (el del jugador; x2 el gigante)
        final World mundo;
        boolean swingPendiente;
        final Frame[] frames = new Frame[BUFFER];
        long t = -1;                    // ultimo tick grabado
        long inmuneHasta;
        Location portal;                // donde entro: ahi se abre el portal del que salen los clones
        final List<Clon> fila = new ArrayList<>();

        Sesion(Player p, String zona, boolean gigante, int cantidad) {
            this.jugador = p.getUniqueId();
            this.zona = zona;
            this.gigante = gigante;
            this.tam = escalaDe(p) * (gigante ? Math.max(0.1, cfgD("gigante_escala", 2.0)) : 1.0);
            this.mundo = p.getWorld();
            this.portal = p.getLocation().clone();
            int n = cantidad > 0 ? Math.min(30, cantidad)
                    : gigante ? Math.max(1, Math.min(10, cfgI("gigante_cantidad", 1))) : Math.max(1, Math.min(30, cfgI("cantidad", 10)));
            for (int i = 0; i < n; i++) { Clon c = new Clon(); c.atraso = objetivo(i); fila.add(c); }
        }

        double objetivo(int i) {
            // el gigante va siempre a 2 s (el mismo retraso que el primero de la fila)
            if (gigante) return cfgD("retraso_seg", 2.0) * 20.0 + i * cfgD("gigante_distancia_seg", 3.0) * 20.0;
            return cfgD("retraso_seg", 2.0) * 20.0 + i * cfgD("distancia_seg", 2.0) * 20.0;
        }

        Frame frame(long tick) {
            if (tick < 0 || tick > t || t - tick >= BUFFER) return null;
            return frames[(int) (tick % BUFFER)];
        }
    }

    private final TOD4 pl;
    private final Store st;
    private final NamespacedKey K_CLON;
    private final Map<UUID, Sesion> sesiones = new HashMap<>();
    private final Map<UUID, Location[]> seleccion = new HashMap<>();
    private boolean avisoDisfraz;
    private String estadoDisfraz = "todavía no apareció ningún clon";

    /** Skin del Cosmic Clone (MineSkin): valor y firma. Se pueden cambiar en clones.skin_valor / clones.skin_firma. */
    private static final String SKIN_VALOR = "ewogICJ0aW1lc3RhbXAiIDogMTc5MjMzNDE1NDY2NiwKICAicHJvZmlsZUlkIiA6ICJhYTUwNjRmMDUzOWQ0YmFhYTVkOGMzODllY2JiNGFlMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJ4WF9FbXBsb3ltZW50X1h4IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkY2ExNjliYmE3MTBlODk2ZDNlMzQyMTk3MTg1MjUzMzk2NjU3MDVkNjA3NGY2MDA2ZjZhMDU2NzhlYzQ1MGEiCiAgICB9CiAgfQp9";
    private static final String SKIN_FIRMA = "irWT75lnt9oNYm5l0mKOjI2lJ/DDo44N2VK533B2r5AHTyDdZc6rTSQZUFoyUv8EqR33JMFec5dH0zhjvbLTGHZt1CAn3s6luDTAHVymaYTbD2EZIvxMxEdktnWejJe+S/tTj2W8j8/BO64yea172xJehUljqvXQFa1uCfTKFJFRgIk+28PwR8ZkO+C5kokww4ANz9iwhs0z9j3lrrsEfPXlA/42u6NbEUcVR84Fp849kRCbylNhxFNSHylG32kgE7w5hd+VQCYaaljsaqSGrMLyInjB+L9nJhifTq8WIK5uQfR7F1u4IaMJ/9euaRDY8m9Y7D3x0Jzu0ub94KnE57Lnyf+3ydHEfll/NrikrHbzg3MCvoYTS/LYP/+i5Jbj7qFqyLuS+ZqfBGq/Zk/iaF6dX7QxYw5zvfrYk6vmKNY1Bq5D0ztdfAXBjInqZHTZCvrzxYnjvRwhYp3S/cHNBiVAMalKbE1TnydA5WOYkYEnbPQAzbSjy3HB3OBNVTKETgkXeS9tEuNx7RtusYdoI8t7JpLljXtPWSFy9v+WlruRXJMQYJxvywkNMeXK2mpE3ylORDuaSh0TKWMYo/n7rPVd4QrS/1nws6nlixgDzFu7zdp6w0L1fDOvdApRHRtDO0PuL2rZPQ2rDRii7jp2SaI6imIamc1Rmrn7UHTlsUM=";

    public Clones(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "clones");
        this.K_CLON = new NamespacedKey(pl, "clon_cosmico");
        limpiarViejos();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    public Store store() { return st; }

    private double cfgD(String k, double def) { return pl.getConfig().getDouble("clones." + k, def); }
    private int cfgI(String k, int def) { return pl.getConfig().getInt("clones." + k, def); }

    // ================================================================= zonas

    private record Zona(String id, String mundo, int x1, int y1, int z1, int x2, int y2, int z2, boolean gigante) {
        boolean contiene(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(mundo)
                    && l.getX() >= x1 && l.getX() < x2 + 1 && l.getY() >= y1 && l.getY() < y2 + 1 && l.getZ() >= z1 && l.getZ() < z2 + 1;
        }
    }

    private final List<Zona> zonas = new ArrayList<>();
    private boolean zonasCargadas;

    private List<Zona> zonas() {
        if (zonasCargadas) return zonas;
        zonas.clear();
        ConfigurationSection s = st.y().getConfigurationSection("zonas");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection z = s.getConfigurationSection(id);
            if (z == null) continue;
            zonas.add(new Zona(id, z.getString("mundo", "world"), z.getInt("x1"), z.getInt("y1"), z.getInt("z1"),
                    z.getInt("x2"), z.getInt("y2"), z.getInt("z2"), "gigante".equalsIgnoreCase(z.getString("modo", "fila"))));
        }
        zonasCargadas = true;
        return zonas;
    }

    private Zona zonaEn(Location l) {
        for (Zona z : zonas()) if (z.contiene(l)) return z;
        return null;
    }

    // ================================================================= tick

    private static boolean afecta(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !ar.tod4.hook.Cuyes.activo(p);
    }

    // ---- paso de clones del Momento Truco: a todos los jugadores, en cualquier lugar
    private static final String MOMENTO = "#momento";
    private boolean momentoOn, momentoGigante;
    private int momentoCant;

    /** Desde ahora todos los jugadores (menos exentos y cuyes) tienen clones, esten donde esten. */
    public void momento(boolean gigante, int cantidad) {
        momentoTerminar();
        momentoOn = true;
        momentoGigante = gigante;
        momentoCant = Math.max(1, Math.min(10, cantidad));
    }

    /** Termina el paso de clones del Momento Truco: sus clones se deshacen. */
    public void momentoTerminar() {
        momentoOn = false;
        for (Sesion s : new ArrayList<>(sesiones.values())) if (MOMENTO.equals(s.zona)) terminar(s);
    }

    private static double escalaDe(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a != null ? a.getValue() : 1.0;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Sesion s = sesiones.get(p.getUniqueId());
            boolean puede = afecta(p) && !p.isDead();
            String zid = null;
            boolean gig = false;
            int cant = 0;
            if (puede && momentoOn && !pl.momentos().exentoDeMomentos(p)) { zid = MOMENTO; gig = momentoGigante; cant = momentoCant; }
            else if (puede) { Zona z = zonaEn(p.getLocation()); if (z != null) { zid = z.id(); gig = z.gigante(); } }
            if (s != null && (zid == null || !zid.equals(s.zona) || p.getWorld() != s.mundo)) { terminar(s); s = null; }
            if (s == null && zid != null) { s = new Sesion(p, zid, gig, cant); sesiones.put(p.getUniqueId(), s); }
            if (s != null) avanzar(p, s);
        }
    }

    private void avanzar(Player p, Sesion s) {
        s.t++;
        s.frames[(int) (s.t % BUFFER)] = new Frame(p.getLocation(), p.isSneaking(), p.getPose().name(), s.swingPendiente);
        s.swingPendiente = false;
        double alcance = Math.max(0, cfgD("velocidad_recuperar", 2.0) - 1.0);   // cuanto mas rapido va el que quedo atras
        long ahora = System.currentTimeMillis();
        boolean particulas = s.t % 2 == 0;
        // el portal queda abierto en la entrada hasta que salio el ultimo clon
        if (s.portal != null) {
            boolean faltan = false;
            for (Clon c : s.fila) if (c.host == null && c.volviendoHasta == 0) { faltan = true; break; }
            if (faltan) { if (particulas) portal(p, s.portal, s.tam, s.t); }
            else s.portal = null;
        }
        for (int i = 0; i < s.fila.size(); i++) {
            Clon c = s.fila.get(i);
            double obj = s.objetivo(i);
            if (c.atraso > obj) c.atraso = Math.max(obj, c.atraso - alcance);
            Frame f = s.frame(s.t - Math.round(c.atraso));
            if (f == null) { if (c.host != null) sacar(c, false, p); continue; }
            Location l = new Location(s.mundo, f.x, f.y, f.z, f.yaw, f.pitch);
            // volviendo al final de la fila: no hay cuerpo, solo una bola de particulas que viaja hasta su lugar
            if (c.volviendoHasta > ahora) {
                if (s.gigante) {
                    // el gigante: la sombra de particulas sigue en su lugar (2 s detras) hasta que vuelve a aparecer
                    orbe(p, l, s.tam);
                    continue;
                }
                long total = (long) (cfgD("transicion_seg", 3.0) * 1000);
                double prog = 1.0 - (c.volviendoHasta - ahora) / (double) Math.max(1, total);
                Location desde = c.desde != null && c.desde.getWorld() == l.getWorld() ? c.desde : l;
                Location orbe = desde.clone().add(l.clone().subtract(desde).toVector().multiply(Math.max(0, Math.min(1, prog))));
                orbe(p, orbe, s.tam);
                continue;
            }
            c.volviendoHasta = 0;
            if (c.host == null || !c.host.isValid()) {
                crear(c, l, s.tam, p);
                rafaga(p, l, s.tam);
                sonido(p, l, "aparece", "entity.illusioner.prepare_mirror", 0.8, 0.7);
                sonido(p, l, "aparece2", "block.amethyst_block.resonate", 0.8, 0.8);
            } else {
                c.host.teleport(l);
            }
            if (f.sneak != c.agachado) { c.agachado = f.sneak; agachar(c); }
            if (f.pose != null && !f.pose.equals(c.pose)) { c.pose = f.pose; posar(c); }
            if (f.swing && c.host != null) try { c.host.swingMainHand(); } catch (Throwable ignored) { }
            if (particulas) estela(p, l, s.tam);
            // cada tanto un susurro de humo que sale del clon (cada uno a su ritmo)
            if ((s.t + i * 7L) % Math.max(10, cfgI("ambiente_cada_ticks", 40)) == 0)
                sonido(p, l, "ambiente", "particle.soul_escape", 0.7, 0.5);
        }
        if (ahora >= s.inmuneHasta) golpes(p, s, ahora);
    }

    /** Si un clon toca al jugador: 3 corazones fijos, empujon y ese clon al final de la fila. */
    private void golpes(Player p, Sesion s, long ahora) {
        double radio = cfgD("radio", 0.8) * s.tam;
        double alto = 1.8 * s.tam;
        Location pl0 = p.getLocation();
        for (Clon c : new ArrayList<>(s.fila)) {
            if (c.host == null || !c.host.isValid()) continue;
            Location cl = c.host.getLocation();
            double dx = cl.getX() - pl0.getX(), dz = cl.getZ() - pl0.getZ(), dy = pl0.getY() - cl.getY();
            if (dx * dx + dz * dz > radio * radio || dy < -1.6 || dy > alto) continue;
            s.inmuneHasta = ahora + (long) (cfgD("inmune_seg", 1.0) * 1000);
            pegar(p, cl, s.gigante);
            // el cuerpo se deshace en particulas (el gigante explota) y esas particulas viajan al final de la fila
            rafaga(p, cl, s.tam);
            if (s.gigante) {
                // estallido cosmico (no una explosion de Minecraft): rafaga de viento, estrellas y anillo violeta
                double r = 1.5 * s.tam;
                Location m = cl.clone().add(0, 0.9 * s.tam, 0);
                p.spawnParticle(Particle.GUST, m, 3, 0.5 * s.tam, 0.5 * s.tam, 0.5 * s.tam, 0);
                p.spawnParticle(Particle.END_ROD, m, 60, 0.3, 0.3, 0.3, 0.25);
                for (int i = 0; i < 24; i++) {
                    double ang = i * Math.PI / 12;
                    p.spawnParticle(Particle.DUST, m.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 3, 0.1, 0.1, 0.1, 0, VIOLETA);
                }
                sonido(p, cl, "explota", "entity.wind_charge.wind_burst", 1.5, 0.6);
                sonido(p, cl, "explota2", "block.beacon.deactivate", 1.5, 1.4);
            }
            sonido(p, cl, "vuelve", "entity.illusioner.mirror_move", 1.0, 0.6);
            sonido(p, cl, "vuelve2", "entity.breeze.shoot", 0.9, 0.7);
            sacar(c, false, p);
            s.fila.remove(c);
            s.fila.add(c);
            c.atraso = Math.max(s.objetivo(s.fila.size() - 1), c.atraso);
            c.desde = cl.clone();
            c.volviendoHasta = ahora + (long) ((s.gigante ? cfgD("retraso_seg", 2.0) : cfgD("transicion_seg", 3.0)) * 1000);
            return;
        }
    }

    /** Sonido configurable: clones.sonidos.<clave> = "sonido" (o "" para ninguno), clones.sonidos.<clave>_tono, _volumen. */
    private void sonido(Player v, Location l, String clave, String def, double vol, double tono) {
        String id = pl.getConfig().getString("clones.sonidos." + clave, def);
        if ("block.fire.extinguish".equals(id)) id = def;   // el sonido viejo (de humo) pasa al nuevo, cosmico
        if (id == null || id.isBlank()) return;
        if (v == null || !v.isOnline()) return;
        v.playSound(l, id, SoundCategory.HOSTILE,
                (float) pl.getConfig().getDouble("clones.sonidos." + clave + "_volumen", vol),
                (float) pl.getConfig().getDouble("clones.sonidos." + clave + "_tono", tono));
    }

    /** Jugadores a los que un golpe letal de clon esta pegando ahora (para que nada baje ese danio). */
    private final java.util.Set<UUID> letal = new java.util.HashSet<>();

    /** El golpe letal no lo baja nada: armadura, proteccion, Resistencia ni absorcion (el totem si salva). */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    @SuppressWarnings("deprecation")
    public void golpeLetal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !letal.contains(p.getUniqueId())) return;
        e.setCancelled(false);
        for (EntityDamageEvent.DamageModifier m : EntityDamageEvent.DamageModifier.values())
            if (m != EntityDamageEvent.DamageModifier.BASE && e.isApplicable(m)) e.setDamage(m, 0);
    }

    /** gigante = te mata de un golpe siempre. */
    private void pegar(Player p, Location desde, boolean gigante) {
        double dano = cfgD("dano", 6.0);
        p.playHurtAnimation(0);
        p.getWorld().playSound(p.getLocation(), "entity.player.hurt", SoundCategory.PLAYERS, 1f, 1f);
        Vector v = p.getLocation().toVector().subtract(desde.toVector()).setY(0);
        if (v.lengthSquared() < 0.0001) v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() > 0.0001) p.setVelocity(v.normalize().multiply(cfgD("empujon", 0.7)).setY(0.3));
        // dano fijo: no lo baja nada (armadura, escudo, resistencia, encantamientos). Si te mataria, el totem si te salva.
        // (GENERIC no gasta la armadura; el totem salva porque no es un danio que lo saltee)
        if (gigante || p.getHealth() - dano <= 0) {
            Causas.anotar(p.getUniqueId(), Component.text(p.getName() + " fue atrapado por su clon cósmico"));
            letal.add(p.getUniqueId());
            try {
                p.damage(1_000_000, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build());
            } finally {
                letal.remove(p.getUniqueId());
            }
        } else {
            p.setHealth(p.getHealth() - dano);
        }
    }

    private void terminar(Sesion s) {
        sesiones.remove(s.jugador);
        Player v = Bukkit.getPlayer(s.jugador);
        for (Clon c : s.fila) if (c.host != null) sacar(c, true, v);
    }

    // ================================================================= el clon

    /** El clon solo lo ve su duenio (los demas jugadores no lo reciben). */
    private void crear(Clon c, Location l, double tam, Player duenio) {
        ArmorStand a = l.getWorld().spawn(l, ArmorStand.class, as -> {
            as.setVisibleByDefault(false);
            as.setMarker(true);              // sin caja: no se le puede pegar ni empujar
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
            as.setBasePlate(false);
            as.setArms(true);
            // NO invisible: LibsDisguises copia la invisibilidad al disfraz y el clon no se veria
            as.addScoreboardTag("tod4_clon");
            as.getPersistentDataContainer().set(K_CLON, PersistentDataType.BYTE, (byte) 1);
        });
        AttributeInstance sc = a.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(tam);
        if (cfgB("brillo", false)) a.setGlowing(true);
        if (duenio != null) duenio.showEntity(pl, a);
        c.host = a;
        c.agachado = false;
        c.pose = "STANDING";
        c.disfraz = disfrazar(a, tam, duenio);
        // sin skin no se muestra ningun cuerpo: el clon queda solo como particulas
        if (c.disfraz == null) a.setInvisible(true);
    }

    private boolean cfgB(String k, boolean def) { return pl.getConfig().getBoolean("clones." + k, def); }

    private void sacar(Clon c, boolean conEfecto, Player v) {
        if (c.host != null && c.host.isValid()) {
            if (conEfecto && v != null && v.isOnline() && v.getWorld() == c.host.getWorld()) rafaga(v, c.host.getLocation(), 1.0);
            c.host.remove();
        }
        c.host = null;
        c.disfraz = null;
    }

    /** Sin disfraz: soporte de armaduras con la cabeza del Cosmic Clone (la skin) y armadura azul noche. */
    private void aspectoSinDisfraz(ArmorStand a) {
        a.setInvisible(false);
        EntityEquipment eq = a.getEquipment();
        Color col = Color.fromRGB(18, 16, 62);
        eq.setHelmet(cabeza());
        eq.setChestplate(cuero(Material.LEATHER_CHESTPLATE, col));
        eq.setLeggings(cuero(Material.LEATHER_LEGGINGS, col));
        eq.setBoots(cuero(Material.LEATHER_BOOTS, col));
    }

    private ItemStack cabezaCache;

    /** Cabeza de jugador con la skin del Cosmic Clone (valor + firma), sin depender de LibsDisguises. */
    private ItemStack cabeza() {
        if (cabezaCache != null) return cabezaCache.clone();
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        try {
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                com.destroystokyo.paper.profile.PlayerProfile prof =
                        Bukkit.createProfile(UUID.fromString("aa5064f0-539d-4baa-a5d8-c389ecbb4ae1"), "Cosmic");
                prof.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures",
                        pl.getConfig().getString("clones.skin_valor", SKIN_VALOR), pl.getConfig().getString("clones.skin_firma", SKIN_FIRMA)));
                sm.setPlayerProfile(prof);
                it.setItemMeta(sm);
            }
        } catch (Throwable ignored) { }
        cabezaCache = it;
        return it.clone();
    }

    private static ItemStack cuero(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(c); it.setItemMeta(lm); }
        return it;
    }

    // ================================================================= particulas (violeta y negro)

    private static final Particle.DustOptions VIOLETA = new Particle.DustOptions(Color.fromRGB(120, 40, 200), 1.3f);
    private static final Particle.DustOptions VIOLETA_OSC = new Particle.DustOptions(Color.fromRGB(60, 15, 110), 1.5f);
    private static final Particle.DustOptions NEGRO = new Particle.DustOptions(Color.fromRGB(12, 8, 24), 1.5f);

    // las particulas y sonidos de los clones solo los ve/escucha su duenio (v)
    private static void estela(Player v, Location l, double k) {
        Location c = l.clone().add(0, 0.9 * k, 0);
        v.spawnParticle(Particle.DUST, c, (int) Math.ceil(3 * k), 0.25 * k, 0.5 * k, 0.25 * k, 0, VIOLETA);
        v.spawnParticle(Particle.DUST, c, (int) Math.ceil(2 * k), 0.25 * k, 0.5 * k, 0.25 * k, 0, NEGRO);
    }

    /** Portal cosmico en la entrada: un anillo violeta que gira, del que salen los clones. */
    private static void portal(Player w, Location l, double k, long t) {
        double r = 1.0 * k;
        Location c = l.clone().add(0, 1.0 * k, 0);
        for (int i = 0; i < 6; i++) {
            double ang = t * 0.25 + i * Math.PI / 3;
            Location q = c.clone().add(Math.cos(ang) * r, Math.sin(ang) * r, 0);
            w.spawnParticle(Particle.DUST, q, 1, 0, 0, 0, 0, i % 2 == 0 ? VIOLETA : NEGRO);
        }
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 4, r * 0.4, r * 0.4, 0.1, 0.02);
    }

    /** La "bola" de particulas que viaja al final de la fila despues de pegar. */
    private static void orbe(Player w, Location l, double k) {
        Location c = l.clone().add(0, 0.9 * k, 0);
        w.spawnParticle(Particle.DUST, c, (int) (6 * k), 0.2 * k, 0.3 * k, 0.2 * k, 0, VIOLETA);
        w.spawnParticle(Particle.DUST, c, (int) (4 * k), 0.2 * k, 0.3 * k, 0.2 * k, 0, NEGRO);
        w.spawnParticle(Particle.REVERSE_PORTAL, c, (int) (3 * k), 0.15 * k, 0.2 * k, 0.15 * k, 0.01);
    }

    private static void rafaga(Player w, Location l, double k) {
        Location c = l.clone().add(0, 0.9 * k, 0);
        w.spawnParticle(Particle.DUST, c, (int) (40 * k), 0.4 * k, 0.8 * k, 0.4 * k, 0, VIOLETA);
        w.spawnParticle(Particle.DUST, c, (int) (30 * k), 0.4 * k, 0.8 * k, 0.4 * k, 0, VIOLETA_OSC);
        w.spawnParticle(Particle.DUST, c, (int) (30 * k), 0.4 * k, 0.8 * k, 0.4 * k, 0, NEGRO);
        w.spawnParticle(Particle.REVERSE_PORTAL, c, (int) (40 * k), 0.3 * k, 0.7 * k, 0.3 * k, 0.05);
        w.spawnParticle(Particle.SQUID_INK, c, (int) (8 * k), 0.3 * k, 0.6 * k, 0.3 * k, 0.02);
    }

    // ================================================================= LibsDisguises (por reflexion: no es obligatorio)

    private Method mGetCustom, mDisguiseToAll, mGetWatcher, mSetSneaking;

    private static Throwable causa(Throwable t) {
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t;
    }

    /**
     * 1) Si hay skin en el config (o la de fabrica): disfraz de jugador con esa skin (valor + firma de MineSkin).
     * 2) Si no: el disfraz guardado de LibsDisguises (clones.disfraz).
     * Sin LibsDisguises: null (se ve con armadura violeta).
     */
    private Object disfrazar(Entity host, double tam, Player duenio) {
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") == null) {
            estadoDisfraz = "LibsDisguises no está instalado";
            return null;
        }
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Class<?> dc = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            if (mDisguiseToAll == null) mDisguiseToAll = api.getMethod("disguiseToAll", Entity.class, dc);
            Object d = null;
            // 1) el disfraz guardado (el que hizo Trick: cosmic_clone); 2) si no existe, la skin que viene en el plugin
            String nombre = pl.getConfig().getString("clones.disfraz", "cosmic_clone");
            if (nombre != null && !nombre.isBlank()) {
                try {
                    if (mGetCustom == null) mGetCustom = api.getMethod("getCustomDisguise", String.class);
                    d = mGetCustom.invoke(null, nombre);
                    if (d != null) estadoDisfraz = "disfraz guardado '" + nombre + "'";
                } catch (Throwable ex) {
                    estadoDisfraz = "no pude leer el disfraz guardado: " + causa(ex);
                }
            }
            String valor = pl.getConfig().getString("clones.skin_valor", SKIN_VALOR);
            String firma = pl.getConfig().getString("clones.skin_firma", SKIN_FIRMA);
            String nombreFalso = pl.getConfig().getString("clones.nombre", "Cosmic");
            if (d == null && valor != null && !valor.isBlank()) {
                Class<?> pd = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
                d = pd.getConstructor(String.class).newInstance(nombreFalso);
                String json = "{\"id\":\"aa5064f0-539d-4baa-a5d8-c389ecbb4ae1\",\"name\":\"" + nombreFalso
                        + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + valor + "\""
                        + (firma != null && !firma.isBlank() ? ",\"signature\":\"" + firma + "\"" : "") + "}]}";
                pd.getMethod("setSkin", String.class).invoke(d, json);
                estadoDisfraz = "skin que viene en el plugin (no encontré el disfraz '" + nombre + "')";
            }
            if (d == null) {
                estadoDisfraz = "no existe el disfraz guardado '" + nombre + "'";
                if (!avisoDisfraz) { avisoDisfraz = true; pl.getLogger().warning("Clones: " + estadoDisfraz + ". Se ven con armadura violeta."); }
                return null;
            }
            // el disfraz guardado se comparte: cada clon necesita su copia
            try { d = d.getClass().getMethod("clone").invoke(d); } catch (Exception ignored) { }
            try { d.getClass().getMethod("setNameVisible", boolean.class).invoke(d, false); } catch (Exception ignored) { }
            // siempre el tamanio pedido (si el disfraz guardado traia otro tamanio, se pisa)
            {
                Object w = watcher(d);
                if (w != null) {
                    try { w.getClass().getMethod("setScale", double.class).invoke(w, tam); }
                    catch (Exception e1) { try { w.getClass().getMethod("setScale", float.class).invoke(w, (float) tam); } catch (Exception ignored) { } }
                }
            }
            Object wt = watcher(d);
            if (wt != null) try { wt.getClass().getMethod("setInvisible", boolean.class).invoke(wt, false); } catch (Exception ignored) { }
            // solo para el duenio (ademas el soporte esta oculto para los demas)
            boolean listo = false;
            if (duenio != null) {
                try {
                    api.getMethod("disguiseToPlayers", Entity.class, dc, java.util.Collection.class).invoke(null, host, d, List.of(duenio));
                    listo = true;
                } catch (Throwable ignored) { }
            }
            if (!listo) mDisguiseToAll.invoke(null, host, d);
            return d;
        } catch (Throwable ex) {
            Throwable c = causa(ex);
            estadoDisfraz = "error con LibsDisguises: " + c;
            if (!avisoDisfraz) {
                avisoDisfraz = true;
                pl.getLogger().warning("Clones: no pude usar LibsDisguises (" + c + "). Se ven con armadura violeta.");
            }
            return null;
        }
    }

    /** Para /tod4:clones probar: que hay de LibsDisguises y que disfraces guardados encuentra. */
    private void diagnostico(CommandSender s) {
        org.bukkit.plugin.Plugin libs = Bukkit.getPluginManager().getPlugin("LibsDisguises");
        if (libs == null) { Style.raw(s, " &8- &cLibsDisguises no está instalado (o no cargó)."); return; }
        Style.raw(s, " &8- &7LibsDisguises: &f" + libs.getPluginMeta().getVersion() + (libs.isEnabled() ? " &a(activo)" : " &c(desactivado)"));
        String nombre = pl.getConfig().getString("clones.disfraz", "cosmic_clone");
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Object d = api.getMethod("getCustomDisguise", String.class).invoke(null, nombre);
            Style.raw(s, " &8- &7Disfraz guardado '&f" + nombre + "&7': " + (d != null ? "&aencontrado" : "&cno existe con ese nombre"));
        } catch (Throwable ex) {
            Style.raw(s, " &8- &cNo pude buscar el disfraz guardado: &f" + causa(ex));
        }
        try {
            Class<?> cfg = Class.forName("me.libraryaddict.disguise.DisguiseConfig");
            Object m = cfg.getMethod("getCustomDisguises").invoke(null);
            if (m instanceof Map<?, ?> mapa) {
                List<String> nombres = new ArrayList<>();
                for (Object k : mapa.keySet()) {
                    String n;
                    try { n = String.valueOf(k.getClass().getMethod("toReadable").invoke(k)); } catch (Exception e) { n = String.valueOf(k); }
                    nombres.add(n);
                }
                Style.raw(s, " &8- &7Disfraces guardados en LibsDisguises: &f" + (nombres.isEmpty() ? "ninguno" : String.join(", ", nombres)));
            }
        } catch (Throwable ignored) { }
    }

    private Object watcher(Object d) {
        try {
            if (mGetWatcher == null) mGetWatcher = d.getClass().getMethod("getWatcher");
            return mGetWatcher.invoke(d);
        } catch (Exception e) { return null; }
    }

    private void agachar(Clon c) {
        if (c.disfraz == null) return;
        try {
            Object w = watcher(c.disfraz);
            if (w == null) return;
            if (mSetSneaking == null) mSetSneaking = w.getClass().getMethod("setSneaking", boolean.class);
            mSetSneaking.invoke(w, c.agachado);
        } catch (Exception ignored) { }
    }

    /** Copia la pose del jugador al disfraz (nadar, elytra, tridente, dormir, agacharse...). Lo que no exista en LibsDisguises se ignora. */
    private void posar(Clon c) {
        if (c.disfraz == null) return;
        Object w = watcher(c.disfraz);
        if (w == null) return;
        String pose = c.pose;
        try {
            Class<?> ep = Class.forName("me.libraryaddict.disguise.disguisetypes.EntityPose");
            Object val = null;
            for (String n : pose.equals("SNEAKING") ? new String[]{"SNEAKING", "CROUCHING"} : new String[]{pose}) {
                try { val = ep.getMethod("valueOf", String.class).invoke(null, n); break; } catch (Throwable ignored) { }
            }
            if (val != null) llamar(w, "setEntityPose", val);
        } catch (Throwable ignored) { }
        llamar(w, "setSwimming", pose.equals("SWIMMING"));
        llamar(w, "setFlyingWithElytra", pose.equals("FALL_FLYING"));
        llamar(w, "setSpinning", pose.equals("SPIN_ATTACK"));
        llamar(w, "setSleeping", pose.equals("SLEEPING"));
    }

    /** Llama a un metodo de un argumento si existe (por nombre); si no, no hace nada. */
    private static void llamar(Object o, String metodo, Object arg) {
        for (Method m : o.getClass().getMethods()) {
            if (!m.getName().equals(metodo) || m.getParameterCount() != 1) continue;
            Class<?> t = m.getParameterTypes()[0];
            boolean ok = arg instanceof Boolean ? (t == boolean.class || t == Boolean.class) : t.isInstance(arg);
            if (!ok) continue;
            try { m.invoke(o, arg); } catch (Throwable ignored) { }
            return;
        }
    }

    @EventHandler
    public void brazo(org.bukkit.event.player.PlayerAnimationEvent e) {
        Sesion s = sesiones.get(e.getPlayer().getUniqueId());
        if (s != null) s.swingPendiente = true;
    }

    // ================================================================= limpieza y eventos

    private void limpiarViejos() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(ArmorStand.class))
                if (e.getScoreboardTags().contains("tod4_clon")) e.remove();
    }

    public void apagar() {
        for (Sesion s : new ArrayList<>(sesiones.values())) terminar(s);
        limpiarViejos();
    }

    private boolean esClon(Entity e) { return e.getPersistentDataContainer().has(K_CLON, PersistentDataType.BYTE); }

    @EventHandler(ignoreCancelled = true)
    public void dano(EntityDamageEvent e) { if (esClon(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void manipula(PlayerArmorStandManipulateEvent e) { if (esClon(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void toca(PlayerInteractAtEntityEvent e) { if (esClon(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        Sesion s = sesiones.get(e.getPlayer().getUniqueId());
        if (s != null) terminar(s);
        seleccion.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= comando /tod4:clones

    public void comando(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        Player p = s instanceof Player pp ? pp : null;
        switch (sub) {
            case "pos1", "pos2" -> {
                if (p == null) return;
                Location[] sel = seleccion.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                sel[sub.equals("pos1") ? 0 : 1] = p.getLocation().getBlock().getLocation();
                Style.ok(p, "Punto " + sub.charAt(3) + " marcado en &f" + p.getLocation().getBlockX() + " " + p.getLocation().getBlockY() + " " + p.getLocation().getBlockZ() + "&a.");
            }
            case "crear" -> {
                if (p == null) return;
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:clones crear <nombre> &7(antes pos1 y pos2)"); return; }
                Location[] sel = seleccion.get(p.getUniqueId());
                if (sel == null || sel[0] == null || sel[1] == null) { Style.err(s, "Primero marca &f/tod4:clones pos1 &cy &fpos2&c."); return; }
                if (!sel[0].getWorld().equals(sel[1].getWorld())) { Style.err(s, "Los dos puntos tienen que estar en el mismo mundo."); return; }
                String base = "zonas." + a[1].toLowerCase() + ".";
                st.set(base + "mundo", sel[0].getWorld().getName());
                st.set(base + "x1", Math.min(sel[0].getBlockX(), sel[1].getBlockX()));
                st.set(base + "y1", Math.min(sel[0].getBlockY(), sel[1].getBlockY()));
                st.set(base + "z1", Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()));
                st.set(base + "x2", Math.max(sel[0].getBlockX(), sel[1].getBlockX()));
                st.set(base + "y2", Math.max(sel[0].getBlockY(), sel[1].getBlockY()));
                st.set(base + "z2", Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
                if (st.y().getString(base + "modo") == null) st.set(base + "modo", "fila");
                st.save();
                zonasCargadas = false;
                Style.ok(s, "Zona de clones &f" + a[1].toLowerCase() + " &acreada (modo &f" + st.y().getString(base + "modo") + "&a).");
            }
            case "modo" -> {
                if (a.length < 3 || !(a[2].equalsIgnoreCase("fila") || a[2].equalsIgnoreCase("gigante"))) {
                    Style.info(s, "Uso: &e/tod4:clones modo <zona> <fila|gigante>"); return;
                }
                String id = a[1].toLowerCase();
                if (!st.y().isConfigurationSection("zonas." + id)) { Style.err(s, "No existe la zona &f" + id + "&c."); return; }
                st.set("zonas." + id + ".modo", a[2].toLowerCase());
                st.save();
                zonasCargadas = false;
                Style.ok(s, "Zona &f" + id + "&a en modo &f" + a[2].toLowerCase() + "&a. &7(Vale para los que entren desde ahora.)");
            }
            case "borrar" -> {
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:clones borrar <zona>"); return; }
                String id = a[1].toLowerCase();
                if (!st.y().isConfigurationSection("zonas." + id)) { Style.err(s, "No existe la zona &f" + id + "&c."); return; }
                st.set("zonas." + id, null);
                st.save();
                zonasCargadas = false;
                for (Sesion se : new ArrayList<>(sesiones.values())) if (se.zona.equals(id)) terminar(se);
                Style.ok(s, "Zona &f" + id + "&a borrada.");
            }
            case "probar" -> {
                if (p == null) return;
                Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
                l.setYaw(p.getLocation().getYaw() + 180);
                Clon c = new Clon();
                avisoDisfraz = false;
                boolean gig = a.length > 1 && a[1].equalsIgnoreCase("gigante");
                double tam = escalaDe(p) * (gig ? Math.max(0.1, cfgD("gigante_escala", 2.0)) : 1.0);
                crear(c, l, tam, p);
                rafaga(p, l, tam);
                sonido(p, l, "aparece", "entity.illusioner.prepare_mirror", 0.8, 0.7);
                Style.info(p, "Clon de prueba (10 s). Aspecto: &f" + estadoDisfraz);
                diagnostico(p);
                Bukkit.getScheduler().runTaskLater(pl, () -> sacar(c, true, p), 200L);
            }
            case "lista" -> {
                if (zonas().isEmpty()) { Style.info(s, "No hay zonas de clones."); return; }
                for (Zona z : zonas())
                    Style.raw(s, "&5■ &f" + z.id() + " &7(" + (z.gigante() ? "gigante" : "fila") + ") &8" + z.mundo() + " "
                            + z.x1() + " " + z.y1() + " " + z.z1() + " → " + z.x2() + " " + z.y2() + " " + z.z2());
            }
            default -> {
                Style.info(s, "&5Cosmic Clones:");
                Style.raw(s, " &e/tod4:clones pos1 &7/ &epos2 &8- &7marca las esquinas (donde estás parado)");
                Style.raw(s, " &e/tod4:clones crear <nombre> &8- &7crea la zona");
                Style.raw(s, " &e/tod4:clones modo <zona> <fila|gigante>");
                Style.raw(s, " &e/tod4:clones borrar <zona> &7/ &elista");
                Style.raw(s, " &e/tod4:clones probar [gigante] &8- &7hace aparecer uno adelante tuyo y dice cómo se ve");
            }
        }
    }

    public List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("pos1", "pos2", "crear", "modo", "borrar", "lista", "probar"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("modo") || a[0].equalsIgnoreCase("borrar")))
            for (Zona z : zonas()) out.add(z.id());
        else if (a.length == 3 && a[0].equalsIgnoreCase("modo")) out.addAll(List.of("fila", "gigante"));
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase() : "";
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
