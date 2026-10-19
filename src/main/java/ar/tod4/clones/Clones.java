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
 * Modos por zona: FILA (10 clones, el primero a 2 s y cada uno 0,5 s mas atras) o GIGANTE (uno solo, grande, a 3 s).
 * El modo se toma al entrar: si se cambia con alguien adentro, ese sigue con el que tenia.
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
        Frame(Location l, boolean s) { x = l.getX(); y = l.getY(); z = l.getZ(); yaw = l.getYaw(); pitch = l.getPitch(); sneak = s; }
    }

    private static final class Clon {
        ArmorStand host;
        Object disfraz;                 // disfraz de LibsDisguises (o null)
        double atraso;                  // en ticks (puede ser decimal mientras alcanza su lugar en la fila)
        boolean agachado;
    }

    private final class Sesion {
        final UUID jugador;
        final String zona;
        final boolean gigante;
        final World mundo;
        final Frame[] frames = new Frame[BUFFER];
        long t = -1;                    // ultimo tick grabado
        long inmuneHasta;
        final List<Clon> fila = new ArrayList<>();

        Sesion(Player p, String zona, boolean gigante) {
            this.jugador = p.getUniqueId();
            this.zona = zona;
            this.gigante = gigante;
            this.mundo = p.getWorld();
            int n = gigante ? 1 : Math.max(1, Math.min(30, cfgI("cantidad", 10)));
            for (int i = 0; i < n; i++) { Clon c = new Clon(); c.atraso = objetivo(i); fila.add(c); }
        }

        double objetivo(int i) {
            if (gigante) return cfgD("gigante_retraso_seg", 3.0) * 20.0;
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
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Sesion s = sesiones.get(p.getUniqueId());
            boolean puede = afecta(p) && !p.isDead();
            Zona z = puede ? zonaEn(p.getLocation()) : null;
            if (s != null && (z == null || !z.id().equals(s.zona) || p.getWorld() != s.mundo)) { terminar(s); s = null; }
            if (s == null && z != null) { s = new Sesion(p, z.id(), z.gigante()); sesiones.put(p.getUniqueId(), s); }
            if (s != null) avanzar(p, s);
        }
    }

    private void avanzar(Player p, Sesion s) {
        s.t++;
        s.frames[(int) (s.t % BUFFER)] = new Frame(p.getLocation(), p.isSneaking());
        double alcance = Math.max(0, cfgD("velocidad_recuperar", 2.0) - 1.0);   // cuanto mas rapido va el que quedo atras
        long ahora = System.currentTimeMillis();
        boolean particulas = s.t % 2 == 0;
        for (int i = 0; i < s.fila.size(); i++) {
            Clon c = s.fila.get(i);
            double obj = s.objetivo(i);
            if (c.atraso > obj) c.atraso = Math.max(obj, c.atraso - alcance);
            Frame f = s.frame(s.t - Math.round(c.atraso));
            if (f == null) { if (c.host != null) sacar(c, false); continue; }
            Location l = new Location(s.mundo, f.x, f.y, f.z, f.yaw, f.pitch);
            if (c.host == null || !c.host.isValid()) {
                crear(c, l, s.gigante);
                rafaga(l, s.gigante);
                sonido(l, "aparece", "entity.illusioner.prepare_mirror", 0.8, 0.7);
                sonido(l, "aparece2", "block.fire.extinguish", 0.6, 0.5);
            } else {
                c.host.teleport(l);
            }
            if (f.sneak != c.agachado) { c.agachado = f.sneak; agachar(c); }
            if (particulas) estela(l, s.gigante);
            // cada tanto un susurro de humo que sale del clon (cada uno a su ritmo)
            if ((s.t + i * 7L) % Math.max(10, cfgI("ambiente_cada_ticks", 40)) == 0)
                sonido(l, "ambiente", "particle.soul_escape", 0.7, 0.5);
        }
        if (ahora >= s.inmuneHasta) golpes(p, s, ahora);
    }

    /** Si un clon toca al jugador: 3 corazones fijos, empujon y ese clon al final de la fila. */
    private void golpes(Player p, Sesion s, long ahora) {
        double radio = s.gigante ? cfgD("radio_gigante", 2.0) : cfgD("radio", 0.8);
        double alto = s.gigante ? 1.8 * cfgD("gigante_tamano", 3.0) : 1.8;
        Location pl0 = p.getLocation();
        for (Clon c : new ArrayList<>(s.fila)) {
            if (c.host == null || !c.host.isValid()) continue;
            Location cl = c.host.getLocation();
            double dx = cl.getX() - pl0.getX(), dz = cl.getZ() - pl0.getZ(), dy = pl0.getY() - cl.getY();
            if (dx * dx + dz * dz > radio * radio || dy < -1.6 || dy > alto) continue;
            s.inmuneHasta = ahora + (long) (cfgD("inmune_seg", 1.0) * 1000);
            pegar(p, cl);
            // al final de la fila (el gigante: unos segundos mas atras); desaparece entre particulas y reaparece atras
            rafaga(cl, s.gigante);
            sonido(cl, "vuelve", "entity.illusioner.mirror_move", 1.0, 0.6);
            sonido(cl, "vuelve2", "block.fire.extinguish", 0.8, 0.6);
            s.fila.remove(c);
            s.fila.add(c);
            double obj = s.objetivo(s.fila.size() - 1);
            c.atraso = Math.max(obj, c.atraso) + (s.gigante ? cfgD("gigante_retroceso_seg", 2.0) * 20.0 : 0);
            Frame f = s.frame(s.t - Math.round(c.atraso));
            if (f == null) { sacar(c, false); }
            else {
                Location nl = new Location(s.mundo, f.x, f.y, f.z, f.yaw, f.pitch);
                c.host.teleport(nl);
                rafaga(nl, s.gigante);
            }
            return;
        }
    }

    /** Sonido configurable: clones.sonidos.<clave> = "sonido" (o "" para ninguno), clones.sonidos.<clave>_tono, _volumen. */
    private void sonido(Location l, String clave, String def, double vol, double tono) {
        String id = pl.getConfig().getString("clones.sonidos." + clave, def);
        if (id == null || id.isBlank()) return;
        l.getWorld().playSound(l, id, SoundCategory.HOSTILE,
                (float) pl.getConfig().getDouble("clones.sonidos." + clave + "_volumen", vol),
                (float) pl.getConfig().getDouble("clones.sonidos." + clave + "_tono", tono));
    }

    private void pegar(Player p, Location desde) {
        double dano = cfgD("dano", 6.0);
        p.playHurtAnimation(0);
        p.getWorld().playSound(p.getLocation(), "entity.player.hurt", SoundCategory.PLAYERS, 1f, 1f);
        Vector v = p.getLocation().toVector().subtract(desde.toVector()).setY(0);
        if (v.lengthSquared() < 0.0001) v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() > 0.0001) p.setVelocity(v.normalize().multiply(cfgD("empujon", 0.7)).setY(0.3));
        // dano fijo: no lo baja nada (armadura, escudo, resistencia, encantamientos) y no salva el totem
        if (p.getHealth() - dano <= 0) {
            Causas.anotar(p.getUniqueId(), Component.text(p.getName() + " fue atrapado por su clon cósmico"));
            p.setHealth(0);
        } else {
            p.setHealth(p.getHealth() - dano);
        }
    }

    private void terminar(Sesion s) {
        sesiones.remove(s.jugador);
        for (Clon c : s.fila) if (c.host != null) sacar(c, true);
    }

    // ================================================================= el clon

    private void crear(Clon c, Location l, boolean gigante) {
        ArmorStand a = l.getWorld().spawn(l, ArmorStand.class, as -> {
            as.setMarker(true);              // sin caja: no se le puede pegar ni empujar
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
            as.setBasePlate(false);
            as.setArms(true);
            as.setInvisible(true);
            as.addScoreboardTag("tod4_clon");
            as.getPersistentDataContainer().set(K_CLON, PersistentDataType.BYTE, (byte) 1);
        });
        double tam = gigante ? cfgD("gigante_tamano", 3.0) : 1.0;
        if (tam != 1.0) {
            AttributeInstance sc = a.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(tam);
        }
        if (cfgB("brillo", false)) a.setGlowing(true);
        c.host = a;
        c.agachado = false;
        c.disfraz = disfrazar(a, tam);
        if (c.disfraz == null) aspectoSinDisfraz(a);
    }

    private boolean cfgB(String k, boolean def) { return pl.getConfig().getBoolean("clones." + k, def); }

    private void sacar(Clon c, boolean conEfecto) {
        if (c.host != null && c.host.isValid()) {
            if (conEfecto) rafaga(c.host.getLocation(), false);
            c.host.remove();
        }
        c.host = null;
        c.disfraz = null;
    }

    /** Sin LibsDisguises: soporte de armaduras visible con armadura de cuero violeta oscuro. */
    private static void aspectoSinDisfraz(ArmorStand a) {
        a.setInvisible(false);
        EntityEquipment eq = a.getEquipment();
        Color col = Color.fromRGB(28, 20, 80);
        eq.setHelmet(cuero(Material.LEATHER_HELMET, col));
        eq.setChestplate(cuero(Material.LEATHER_CHESTPLATE, col));
        eq.setLeggings(cuero(Material.LEATHER_LEGGINGS, col));
        eq.setBoots(cuero(Material.LEATHER_BOOTS, col));
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

    private static void estela(Location l, boolean gigante) {
        double k = gigante ? 3 : 1;
        Location c = l.clone().add(0, 0.9 * k, 0);
        l.getWorld().spawnParticle(Particle.DUST, c, (int) (3 * k), 0.25 * k, 0.5 * k, 0.25 * k, 0, VIOLETA);
        l.getWorld().spawnParticle(Particle.DUST, c, (int) (2 * k), 0.25 * k, 0.5 * k, 0.25 * k, 0, NEGRO);
    }

    private static void rafaga(Location l, boolean gigante) {
        double k = gigante ? 3 : 1;
        Location c = l.clone().add(0, 0.9 * k, 0);
        World w = l.getWorld();
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
    private Object disfrazar(Entity host, double tam) {
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") == null) {
            estadoDisfraz = "LibsDisguises no está instalado";
            return null;
        }
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Class<?> dc = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            if (mDisguiseToAll == null) mDisguiseToAll = api.getMethod("disguiseToAll", Entity.class, dc);
            Object d = null;
            String valor = pl.getConfig().getString("clones.skin_valor", SKIN_VALOR);
            String firma = pl.getConfig().getString("clones.skin_firma", SKIN_FIRMA);
            String nombreFalso = pl.getConfig().getString("clones.nombre", "Cosmic");
            if (valor != null && !valor.isBlank()) {
                Class<?> pd = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
                d = pd.getConstructor(String.class).newInstance(nombreFalso);
                String json = "{\"id\":\"aa5064f0-539d-4baa-a5d8-c389ecbb4ae1\",\"name\":\"" + nombreFalso
                        + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + valor + "\""
                        + (firma != null && !firma.isBlank() ? ",\"signature\":\"" + firma + "\"" : "") + "}]}";
                pd.getMethod("setSkin", String.class).invoke(d, json);
                estadoDisfraz = "disfraz de jugador con la skin del config";
            } else {
                String nombre = pl.getConfig().getString("clones.disfraz", "cosmic_clone");
                if (mGetCustom == null) mGetCustom = api.getMethod("getCustomDisguise", String.class);
                d = mGetCustom.invoke(null, nombre);
                if (d == null) {
                    estadoDisfraz = "no existe el disfraz guardado '" + nombre + "'";
                    if (!avisoDisfraz) { avisoDisfraz = true; pl.getLogger().warning("Clones: " + estadoDisfraz + ". Se ven con armadura violeta."); }
                    return null;
                }
                estadoDisfraz = "disfraz guardado '" + nombre + "'";
            }
            try { d.getClass().getMethod("setNameVisible", boolean.class).invoke(d, false); } catch (Exception ignored) { }
            if (tam != 1.0) {
                Object w = watcher(d);
                if (w != null) {
                    try { w.getClass().getMethod("setScale", double.class).invoke(w, tam); }
                    catch (Exception e1) { try { w.getClass().getMethod("setScale", float.class).invoke(w, (float) tam); } catch (Exception ignored) { } }
                }
            }
            mDisguiseToAll.invoke(null, host, d);
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
                crear(c, l, a.length > 1 && a[1].equalsIgnoreCase("gigante"));
                rafaga(l, false);
                sonido(l, "aparece", "entity.illusioner.prepare_mirror", 0.8, 0.7);
                Style.info(p, "Clon de prueba (10 s). Aspecto: &f" + estadoDisfraz);
                Bukkit.getScheduler().runTaskLater(pl, () -> sacar(c, true), 200L);
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
