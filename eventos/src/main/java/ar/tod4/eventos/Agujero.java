package ar.tod4.eventos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import ar.tod4.eventos.agujero.CuevaGen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * AGUJERO DEL INFIERNO 2.0 (opciones en plugins/TOD4Eventos/agujero.yml, comando /tod4eventos:agujero).
 *
 * Una cueva en su propio mundo (la forma sale de CuevaGen). Se baja tocando o pisando el bloque de la entrada (afuera)
 * y se sube tocando o pisando el bloque de salida (en la sala de entrada, adentro).
 *  - La sala de entrada es segura. La "linea" es salir de ella: el primero que la cruza arranca todo.
 *  - Arranca: suena la musica para todos los del mundo, una barra con el tiempo, mobs repartidos y la cueva se derrumba.
 *  - Se termina la cancion: 3 s y a los que siguen pasando la linea les saca 1,5 corazones cada medio segundo (fijo).
 *  - Cuando ya no queda nadie pasando la linea (salieron o murieron), aparece una pared en la linea y queda derrumbada
 *    30 minutos. Se puede bajar a la sala de entrada, pero no pasar. Despues se reconstruye sola (como la foto guardada)
 *    con cofres en lugares nuevos. Cada jugador puede bajar a jugar una vez por hora.
 *  - Cuando alguien baja, a los admins les avisa en el chat con un sonido de cueva.
 */
final class Agujero implements Listener {

    private static final String MOB = "agujero_mob", CARTEL = "agujero_cartel", PERM = "tod4eventos.admin";
    private static final int VERSION_CFG = 3;

    private final TOD4Eventos pl;
    private final Random rnd = new Random();
    private YamlConfiguration y;
    private final Store d;
    private CuevaGen gen;
    private long semillaGen = Long.MIN_VALUE;
    private BlockData[] base;
    private World mundo;

    enum Estado { ABIERTO, CORRIENDO, TERMINANDO, CERRADO, RECONSTRUYENDO }
    private Estado estado = Estado.ABIERTO;
    private long inicio, cerradoHasta;
    private final Set<UUID> jugando = new HashSet<>();   // los que pasaron la linea en esta corrida
    private BossBar barra;
    private int tick;
    private TextDisplay cartel, cartel2;
    private boolean finalAvisado;

    Agujero(TOD4Eventos pl) {
        this.pl = pl;
        d = new Store(pl, "agujero_datos");
        recargar();
        cerradoHasta = d.y().getLong("cerrado_hasta", 0);
        if (cerradoHasta > System.currentTimeMillis()) estado = Estado.CERRADO;
        // se apago el server con una corrida en curso: se reconstruye enseguida
        if (d.y().getBoolean("en_curso", false)) { estado = Estado.CERRADO; cerradoHasta = System.currentTimeMillis() + 20000; d.set("en_curso", false); }
        Bukkit.getScheduler().runTask(pl, () -> { cargarMundo(false); versionForma(); });
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada, 40L, 10L);
    }

    /**
     * La forma de la cueva cambio (CuevaGen.VERSION): la foto y los lugares de adentro (cofres, salida, llegada) eran
     * de la cueva vieja. Se guardan aparte (agujero_foto_v1.bin.gz y "respaldo_v1" en agujero_datos.yml) y la cueva
     * se arma de cero con la forma nueva en unos segundos.
     */
    private void versionForma() {
        int v = d.y().getInt("forma_version", 1);
        if (v >= CuevaGen.VERSION) return;
        d.set("forma_version", CuevaGen.VERSION);
        if (mundo == null) return;   // todavia no se creo: sale con la forma nueva
        File f = fotoF();
        if (f.exists()) f.renameTo(new File(pl.getDataFolder(), "agujero_foto_v" + v + ".bin.gz"));
        foto = null;
        for (String k : new String[]{"cofres_extra", "bloque_salida", "bloque_salida2", "llegada"}) {
            Object o = d.y().get(k);
            if (o != null) { d.set("respaldo_v" + v + "." + k, o); d.set(k, null); }
        }
        estado = Estado.CERRADO;
        cerradoHasta = System.currentTimeMillis() + 15000;
        guardar();
        pl.getLogger().info("[Agujero] Cueva nueva (más grande, de pizarra): se arma de cero en 15 s. La foto vieja quedó en agujero_foto_v" + v
                + ".bin.gz. Hay que volver a poner la salida (bloquesalida 1/2) y los cofres.");
    }

    void recargar() {
        File f = new File(pl.getDataFolder(), "agujero.yml");
        if (!f.exists()) pl.saveResource("agujero.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
        if (y.getInt("version", 1) < VERSION_CFG) {
            // agujero.yml viejo: se guarda como agujero_viejo.yml y se pone el nuevo (mobs por zona, cancion de 3 min...)
            File viejo = new File(pl.getDataFolder(), "agujero_viejo.yml");
            if (viejo.exists()) viejo.delete();
            if (f.renameTo(viejo)) {
                pl.saveResource("agujero.yml", true);
                y = YamlConfiguration.loadConfiguration(f);
                pl.getLogger().info("[Agujero] agujero.yml actualizado (el anterior quedó en agujero_viejo.yml)");
            }
        }
        linea = null;
        long s = y.getLong("semilla", 2026);
        if (gen == null || s != semillaGen) {
            semillaGen = s;
            gen = new CuevaGen(s);
            base = new BlockData[CuevaGen.PALETA.length];
            for (int i = 0; i < base.length; i++) base[i] = Bukkit.createBlockData("minecraft:" + CuevaGen.PALETA[i]);
        }
    }

    void apagar() {
        if (barra != null) barra.removeAll();
        if (cartel != null) cartel.remove();
        if (cartel2 != null) cartel2.remove();
        guardarFotoYa();
        guardar();
        d.save();
    }

    private void guardar() { d.set("cerrado_hasta", cerradoHasta); }

    private String nombreMundo() { return y.getString("mundo", "agujero_infierno"); }

    private static Component c(String s) { return TOD4Eventos.c(s); }

    // ================================================================= el mundo

    private final class Generador extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo w, java.util.Random r, int cx, int cz, ChunkData cd) {
            if (cx * 16 + 15 < CuevaGen.MIN_X || cx * 16 > CuevaGen.MAX_X || cz * 16 + 15 < CuevaGen.MIN_Z || cz * 16 > CuevaGen.MAX_Z) return;
            short[] b = gen.chunk(cx, cz);
            for (int i = 0; i < b.length; i++) {
                if (b[i] == CuevaGen.AIRE) continue;
                cd.setBlock(i % 16, i / 256 + CuevaGen.MIN_Y, (i % 256) / 16, base[b[i]]);
            }
        }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo w) { return new Biomas(); }
        @Override public Location getFixedSpawnLocation(World w, java.util.Random r) { return sala(w); }
    }

    private final class Biomas extends BiomeProvider {
        private Biome b() {
            Biome b = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(y.getString("bioma", "dripstone_caves")));
            return b != null ? b : Biome.DRIPSTONE_CAVES;
        }
        @Override public Biome getBiome(WorldInfo w, int x, int yy, int z) { return b(); }
        @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(b()); }
    }

    /** Crea (la primera vez) o carga el mundo de la cueva (si ya existe la carpeta). */
    private World cargarMundo(boolean crear) {
        World w = Bukkit.getWorld(nombreMundo());
        if (w == null) {
            if (!crear && !new File(Bukkit.getWorldContainer(), nombreMundo()).isDirectory()) return null;
            w = new WorldCreator(nombreMundo()).environment(World.Environment.NORMAL).generator(new Generador())
                    .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE)
                    .biomeProvider(new Biomas()).generateStructures(false).createWorld();
        }
        if (w == null) return null;
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.DO_INSOMNIA, false);
        w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        w.setTime(18000);
        w.setSpawnLocation(sala(w));
        mundo = w;
        return w;
    }

    /** Donde apareces al bajar: el medio de la sala de entrada, mirando al tunel. */
    private Location sala(World w) {
        Location l = d.y().getLocation("llegada");
        if (l != null && l.getWorld() != null && l.getWorld().equals(w)) return l;
        CuevaGen.Sala e = gen.entrada;
        return new Location(w, e.x + 0.5, e.piso, e.z + 0.5, 0f, 0f);
    }

    private boolean enMundo(Player p) { return mundo != null && p.getWorld().equals(mundo); }

    private double radioLinea() { return y.getDouble("linea_radio", gen.entrada.rx + 1.5); }

    /** En la sala de entrada (detras de la linea): ahi no pasa nada. */
    private boolean enSala(Location l) {
        CuevaGen.Sala e = gen.entrada;
        return Math.hypot(l.getX() - e.x, l.getZ() - e.z) < radioLinea() && Math.abs(l.getY() - e.piso) < 9;
    }

    // ================================================================= bajar y subir

    private Location bloque(String k) {
        Location l = d.y().getLocation(k);
        return l != null && l.getWorld() != null ? l : null;
    }

    /**
     * Zona (entrada o salida): de la esquina k a la esquina k+"2". Sin segunda esquina es un solo bloque (como antes).
     * Devuelve {minX, minY, minZ, maxX, maxY, maxZ} o null.
     */
    private int[] zona(String k) {
        Location a = bloque(k);
        if (a == null) return null;
        Location b = bloque(k + "2");
        if (b == null || !b.getWorld().equals(a.getWorld())) b = a;
        return new int[]{Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ())};
    }

    /** El bloque esta en la zona. */
    private boolean enZona(String k, Block bl) {
        int[] z = zona(k);
        if (z == null || !bl.getWorld().equals(bloque(k).getWorld())) return false;
        return bl.getX() >= z[0] && bl.getX() <= z[3] && bl.getY() >= z[1] && bl.getY() <= z[4] && bl.getZ() >= z[2] && bl.getZ() <= z[5];
    }

    /** Parado en la zona: con los pies adentro o pisando un bloque de la zona. */
    private boolean pisaZona(String k, Block pies) {
        return enZona(k, pies) || enZona(k, pies.getRelative(BlockFace.DOWN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pisa(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        Block pies = e.getTo().getBlock();
        if (enMundo(p)) {
            if (pisaZona("bloque_salida", pies)) { subir(p); return; }
            moverse(p, e.getTo());
            return;
        }
        if (pisaZona("entrada", pies)) bajar(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void toca(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (enZona("entrada", e.getClickedBlock())) { e.setCancelled(true); bajar(e.getPlayer()); }
        else if (enZona("bloque_salida", e.getClickedBlock())) { e.setCancelled(true); subir(e.getPlayer()); }
    }

    /** Muestra el borde de la zona con particulas durante unos segundos. */
    private void mostrarZona(Player p, String k, org.bukkit.Color color) {
        int[] z = zona(k);
        if (z == null) return;
        World w = bloque(k).getWorld();
        org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(color, 1.2f);
        double x0 = z[0], y0 = z[1], z0 = z[2], x1 = z[3] + 1, y1 = z[4] + 1, z1 = z[5] + 1;
        List<Location> pts = new ArrayList<>();
        for (double[] ar : new double[][]{{y0, z0}, {y1, z0}, {y0, z1}, {y1, z1}}) for (double x = x0; x <= x1 + 0.01; x += 0.5) pts.add(new Location(w, x, ar[0], ar[1]));
        for (double[] ar : new double[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) for (double yy = y0; yy <= y1 + 0.01; yy += 0.5) pts.add(new Location(w, ar[0], yy, ar[1]));
        for (double[] ar : new double[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) for (double zz = z0; zz <= z1 + 0.01; zz += 0.5) pts.add(new Location(w, ar[0], ar[1], zz));
        if (pts.size() > 3000) return;
        new org.bukkit.scheduler.BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || ++n > 16) { cancel(); return; }
                for (Location l : pts) p.spawnParticle(org.bukkit.Particle.DUST, l, 1, 0, 0, 0, 0, dust);
            }
        }.runTaskTimer(pl, 0L, 10L);
    }

    private final Map<UUID, Long> avisado = new HashMap<>();

    private boolean muyRapido(Player p) {
        long ahora = System.currentTimeMillis();
        if (ahora - avisado.getOrDefault(p.getUniqueId(), 0L) < 1500) return true;
        avisado.put(p.getUniqueId(), ahora);
        return false;
    }

    /** Bajar a la cueva (desde la entrada). Derrumbada (o derrumbandose) no se puede. */
    private void bajar(Player p) {
        if (muyRapido(p) || p.getGameMode() == GameMode.SPECTATOR) return;
        if (mundo == null) { error(p, "El Agujero del Infierno todavía no existe."); return; }
        long ahora = System.currentTimeMillis();
        if (estado == Estado.TERMINANDO || estado == Estado.CERRADO || estado == Estado.RECONSTRUYENDO) {
            error(p, "La cueva está derrumbada.");
            return;
        }
        if (!p.hasPermission(PERM)) {
            long ultima = d.y().getLong("ultima." + p.getUniqueId(), 0), espera = (long) (y.getDouble("espera_jugador_min", 60) * 60000);
            if (ahora - ultima < espera) {
                error(p, "Podrás volver a bajar en " + mmss(espera - (ahora - ultima)) + ".");
                return;
            }
        }
        d.set("ultima." + p.getUniqueId(), ahora);
        jugando.remove(p.getUniqueId());
        Location dst = sala(mundo);
        p.teleport(dst);
        ajustarLuego(p);
        p.playSound(dst, Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 0.7f);
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission(PERM))
            o.sendMessage(c("&4☠ &c" + p.getName() + " &7bajó al &4&lAgujero del Infierno 2.0&7."));
    }

    /** Como los errores de TOD4: ✖ en rojo y el sonido grave. */
    private static void error(Player p, String m) {
        p.sendMessage(c("&c✖ " + m));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
    }

    /** Subir (desde el bloque de salida de la sala de entrada). */
    private void subir(Player p) {
        if (muyRapido(p)) return;
        sacar(p, jugando.contains(p.getUniqueId()));
    }

    /** Lo saca afuera, a la salida. salvado = estaba jugando y se salvo. */
    private void sacar(Player p, boolean salvado) {
        jugando.remove(p.getUniqueId());
        if (barra != null) barra.removePlayer(p);
        p.stopSound(y.getString("musica", "tod4:agujero"), SoundCategory.RECORDS);
        Location s = bloque("salida");
        if (s == null) s = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(s);
        ajustarLuego(p);
        if (salvado) {
            p.showTitle(Title.title(c("&a&l¡ESCAPASTE!"), c("&7Saliste del Agujero del Infierno")));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private void moverse(Player p, Location a) {
        if (!juega(p) || enSala(a) || jugando.contains(p.getUniqueId())) return;
        // cruzo la linea
        if (estado == Estado.ABIERTO) empezar();
        if (estado == Estado.CORRIENDO) {
            jugando.add(p.getUniqueId());
            if (barra != null) barra.addPlayer(p);
            p.sendActionBar(c("&c¡Volvé a la entrada antes de que se derrumbe!"));
        }
    }

    private static boolean juega(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    private static String mmss(long ms) {
        long s = (Math.max(0, ms) + 999) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static String legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c(s));
    }

    // ================================================================= la corrida

    private double duracion() { return y.getDouble("duracion_seg", 180); }

    private double finalSeg() { ConfigurationSection cs = y.getConfigurationSection("derrumbe"); return cs == null ? 30 : cs.getDouble("final_seg", 30); }

    private void empezar() {
        estado = Estado.CORRIENDO;
        inicio = System.currentTimeMillis();
        d.set("en_curso", true);
        // la barra: solo el tiempo (con el pack, la barra de este color es invisible: ver agujero.yml)
        BarColor col;
        try { col = BarColor.valueOf(y.getString("barra_color", "WHITE").toUpperCase()); } catch (Exception ex) { col = BarColor.WHITE; }
        barra = Bukkit.createBossBar(legacy("&c&l" + mmss((long) (duracion() * 1000))), col, BarStyle.SOLID);
        finalAvisado = false;
        String mus = y.getString("musica", "tod4:agujero");
        float vol = (float) y.getDouble("musica_volumen", 1.0);
        for (Player p : mundo.getPlayers()) p.playSound(p, mus, SoundCategory.RECORDS, vol, 1f);
        mundo.playSound(sala(mundo), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 3f, 0.6f);
        mobs();
    }

    private void cada() {
        tick++;
        cartel();
        dibujarLinea();
        guardarFotoSiHace();
        if (mundo == null) return;
        long ahora = System.currentTimeMillis();
        switch (estado) {
            case CORRIENDO -> {
                double t = (ahora - inicio) / 1000.0, dur = duracion();
                barra.setProgress(Math.max(0, Math.min(1, 1 - t / dur)));
                barra.setTitle(legacy((dur - t <= finalSeg() ? "&4&l" : "&c&l") + mmss((long) ((dur - t) * 1000))));
                derrumbe(t, dur);
                if (t >= dur) {
                    estado = Estado.TERMINANDO;
                    barra.setTitle(legacy("&4&l¡SE DERRUMBÓ!"));
                    barra.setProgress(0);
                    pared();   // la salida se tapa en el momento: el que quedo del otro lado, quedo adentro
                    for (Player p : mundo.getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.5f);
                }
            }
            case TERMINANDO -> {
                double t = (ahora - inicio) / 1000.0 - duracion();
                derrumbe(duracion(), duracion());
                boolean queda = false;
                for (Player p : mundo.getPlayers()) {
                    if (!juega(p) || p.isDead() || enSala(p.getLocation())) continue;   // en la sala de entrada estas a salvo
                    queda = true;
                    if (t >= y.getDouble("espera_dano_seg", 3)) aplastar(p);
                }
                if (!queda) terminar();
            }
            case CERRADO -> { if (ahora >= cerradoHasta) reconstruir(null); }
            default -> { }
        }
    }

    /** 1,5 corazones fijos (la armadura no lo baja). Si no le alcanza la vida, muere. */
    private void aplastar(Player p) {
        double dano = y.getDouble("dano", 3.0), h = p.getHealth();
        if (h - dano > 0.0001) {
            p.damage(0.0001);
            if (!p.isDead()) p.setHealth(Math.max(0.0001, Math.min(p.getHealth(), h) - dano));
        } else {
            causa(p, p.getName() + " quedó aplastado en el Agujero del Infierno");
            p.damage(1000);
        }
    }

    /** El mensaje de muerte propio (TOD4 lo lee al morir). */
    private static void causa(Player p, String txt) {
        try {
            Class.forName("ar.tod4.core.Causas").getMethod("anotar", UUID.class, Component.class).invoke(null, p.getUniqueId(), Component.text(txt));
        } catch (Throwable ignored) { }
    }

    /**
     * Caen piedras y estalactitas del techo cerca de los que estan jugando (cada medio segundo).
     * Desde el principio pocas, y van subiendo; en los ultimos segundos (final_seg) caen muchas mas, mas lejos,
     * con temblor y ruido, para que se note que se viene abajo. Muchas de las del final se rompen al caer (no tapan todo).
     */
    private void derrumbe(double t, double dur) {
        ConfigurationSection cs = y.getConfigurationSection("derrumbe");
        double ini = cs == null ? 0.3 : cs.getDouble("por_segundo_inicio", 0.3), medio = cs == null ? 2 : cs.getDouble("por_segundo_medio", 2);
        double fin = cs == null ? 14 : cs.getDouble("por_segundo_final", 14), fseg = finalSeg();
        double punta = cs == null ? 30 : cs.getDouble("punta_chance", 30), rompen = cs == null ? 70 : cs.getDouble("final_se_rompen", 70);
        boolean esFinal = dur - t <= fseg;
        double f = Math.max(0, Math.min(1, t / Math.max(1, dur - fseg)));
        double porSeg = esFinal ? fin : ini + (medio - ini) * f * f;
        if (esFinal && !finalAvisado) {
            finalAvisado = true;
            for (Player p : mundo.getPlayers()) {
                if (!juega(p) || enSala(p.getLocation())) continue;
                p.showTitle(Title.title(c(""), c("&4&l¡SE VIENE ABAJO!"), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(500))));
                p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
        }
        int radio = esFinal ? 14 : 10;
        for (Player p : mundo.getPlayers()) {
            if (!juega(p) || enSala(p.getLocation())) continue;
            double q = porSeg / 2;   // cada medio segundo
            int n = (int) q + (rnd.nextDouble() < q - (int) q ? 1 : 0);
            for (int i = 0; i < n; i++) {
                Block b = p.getLocation().add(rnd.nextInt(radio * 2 + 1) - radio, 0, rnd.nextInt(radio * 2 + 1) - radio).getBlock();
                if (!b.isPassable()) continue;
                int k = 0;
                while (k++ < 14 && b.getRelative(BlockFace.UP).isPassable()) b = b.getRelative(BlockFace.UP);
                if (k >= 14 || b.getY() - p.getLocation().getY() < 2 || enSala(b.getLocation())) continue;
                boolean esPunta = rnd.nextDouble() * 100 < punta;
                BlockData bd = esPunta ? Bukkit.createBlockData("minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]")
                        : (rnd.nextBoolean() ? Material.GRAVEL : Material.COBBLED_DEEPSLATE).createBlockData();
                Location at = b.getLocation().add(0.5, 0, 0.5);
                @SuppressWarnings("deprecation")
                FallingBlock fb = mundo.spawnFallingBlock(at, bd);
                fb.setDropItem(false);
                fb.setHurtEntities(true);
                fb.setDamagePerBlock(esPunta ? 3f : 1f);
                fb.setMaxDamage(esPunta ? 20 : 6);
                fb.setCancelDrop(esPunta || (esFinal && rnd.nextDouble() * 100 < rompen));
                fb.addScoreboardTag(MOB);
                mundo.spawnParticle(Particle.BLOCK, at, 12, 0.4, 0.1, 0.4, bd);
            }
            if (esFinal) {
                if (rnd.nextDouble() < 0.6) p.playSound(p.getLocation(), rnd.nextBoolean() ? Sound.BLOCK_DEEPSLATE_BREAK : Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1f, 0.4f + rnd.nextFloat() * 0.3f);
                if (rnd.nextDouble() < 0.25) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, false, false));
                mundo.spawnParticle(Particle.FALLING_DUST, p.getLocation().add(0, 3, 0), 20, 6, 1, 6, Material.GRAVEL.createBlockData());
            } else if (f > 0.15 && rnd.nextDouble() < 0.2) {
                p.playSound(p.getLocation(), rnd.nextBoolean() ? Sound.BLOCK_DEEPSLATE_BREAK : Sound.BLOCK_BASALT_BREAK, SoundCategory.BLOCKS, 1f, 0.5f);
            }
        }
    }

    /** Ya no queda nadie pasando la linea: aparece la pared y empieza la cuenta para reconstruirla. */
    private void terminar() {
        estado = Estado.CERRADO;
        d.set("en_curso", false);
        cerradoHasta = System.currentTimeMillis() + (long) (y.getDouble("cerrado_min", 30) * 60000);
        guardar();
        if (barra != null) { barra.removeAll(); barra = null; }
        jugando.clear();
        for (Player p : mundo.getPlayers()) p.stopSound(y.getString("musica", "tod4:agujero"), SoundCategory.RECORDS);
        for (Entity en : mundo.getEntities()) if (en.getScoreboardTags().contains(MOB)) en.remove();
        pared();
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission(PERM))
            o.sendMessage(c("&4☠ &7El Agujero del Infierno se derrumbó. Se reconstruye en &f" + mmss(cerradoHasta - System.currentTimeMillis()) + "&7."));
    }

    /** Tapa la linea: una pared de escombros justo afuera de la sala de entrada. */
    private void pared() {
        CuevaGen.Sala e = gen.entrada;
        double r0 = radioLinea(), r1 = r0 + 2.5;
        Material[] esc = {Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF, Material.COBBLESTONE, Material.ANDESITE};
        int R = (int) Math.ceil(r1) + 1;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double h = Math.hypot(dx + 0.5, dz + 0.5);
            if (h < r0 || h > r1) continue;
            for (int dy = -3; dy <= 12; dy++) {
                Block b = mundo.getBlockAt((int) Math.floor(e.x) + dx, (int) Math.floor(e.piso) + dy, (int) Math.floor(e.z) + dz);
                if (b.isPassable() && !protegido(b)) b.setType(esc[rnd.nextInt(esc.length)], false);
            }
        }
        mundo.playSound(sala(mundo), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 2f, 0.4f);
        mundo.spawnParticle(Particle.EXPLOSION, sala(mundo).add(0, 2, 6), 6, 2, 2, 2);
    }

    // ================================================================= mobs

    private static Method SPAWN;
    private static Object HELPER;

    private Entity mm(String tipo, Location l) {
        try {
            if (SPAWN == null && Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
                Class<?> k = Class.forName("io.lumine.mythic.bukkit.BukkitAPIHelper");
                if (HELPER == null) HELPER = k.getConstructor().newInstance();
                SPAWN = k.getMethod("spawnMythicMob", String.class, Location.class);
            }
            return SPAWN == null ? null : (Entity) SPAWN.invoke(HELPER, tipo, l);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Los mobs al empezar: "cantidad" repartidos por toda la cueva (tipos) y, aparte, los de cada zona (mobs.zonas):
     * el lago de lava (solo ahi hay blazes), la catedral, las columnas...
     * Tipos: mm:<MythicMobs>, creeper:<random|mini|calamar|void|nuclear|flash> (los de TOD4) o minecraft:<mob>.
     */
    private void mobs() {
        ConfigurationSection cs = y.getConfigurationSection("mobs.tipos");
        int cant = y.getInt("mobs.cantidad", 16);
        if (cs != null && cant > 0) {
            List<CuevaGen.Punto> pts = new ArrayList<>();
            for (CuevaGen.Punto p : gen.puntos()) if (p.tipo.equals("mob")) pts.add(p);
            Collections.shuffle(pts, rnd);
            for (int i = 0; i < cant && i < pts.size(); i++) {
                String tipo = sortear(cs);
                if (tipo == null) continue;
                Location l = piso(new Location(mundo, pts.get(i).x + 0.5, pts.get(i).y, pts.get(i).z + 0.5));
                if (l != null) mob(tipo, l);
            }
        }
        ConfigurationSection zs = y.getConfigurationSection("mobs.zonas");
        if (zs == null) return;
        for (String zona : zs.getKeys(false)) {
            CuevaGen.Sala sala = null;
            boolean isla = zona.equalsIgnoreCase("isla");   // el medio del lago de lava
            for (CuevaGen.Sala sl : gen.salas()) if (sl.tipo.equalsIgnoreCase(isla ? "lago" : zona)) sala = sl;
            ConfigurationSection tz = zs.getConfigurationSection(zona + ".tipos");
            if (sala == null || tz == null) { pl.getLogger().warning("[Agujero] Zona de mobs rara: " + zona); continue; }
            int n = zs.getInt(zona + ".cantidad", 0);
            for (int i = 0; i < n; i++) {
                String tipo = sortear(tz);
                if (tipo == null) continue;
                Location l = isla ? piso(new Location(mundo, sala.x + rnd.nextInt(5) - 2 + 0.5, gen.nivelLava() + 1, sala.z + rnd.nextInt(5) - 2 + 0.5))
                        : lugarEnSala(sala, tipo.toLowerCase().contains("blaze"));
                if (l != null) mob(tipo, l);
            }
        }
    }

    private String sortear(ConfigurationSection cs) {
        double tot = 0;
        for (String k : cs.getKeys(false)) tot += Math.max(0, cs.getDouble(k));
        double r = rnd.nextDouble() * tot;
        for (String k : cs.getKeys(false)) { r -= Math.max(0, cs.getDouble(k)); if (r < 0) return k; }
        return null;
    }

    /** Un lugar al azar dentro de la sala: en el piso, o (volador) en el aire (en el lago: arriba de la lava). */
    private Location lugarEnSala(CuevaGen.Sala s, boolean vuela) {
        for (int k = 0; k < 30; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 0.85;
            double x = s.x + Math.cos(ang) * s.rx * d, z = s.z + Math.sin(ang) * s.rz * d;
            if (vuela) {
                double yy = (s.tipo.equals("lago") ? gen.nivelLava() : s.piso) + 3 + rnd.nextInt(5);
                Block b = mundo.getBlockAt((int) Math.floor(x), (int) Math.floor(yy), (int) Math.floor(z));
                if (b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.UP).isPassable()) return b.getLocation().add(0.5, 0, 0.5);
                continue;
            }
            double y0 = s.tipo.equals("lago") ? gen.nivelLava() + 1 : s.piso;
            Location l = piso(new Location(mundo, x, y0, z));
            if (l != null && !l.getBlock().getRelative(BlockFace.DOWN).isLiquid()) return l;
        }
        return null;
    }

    private Method CREEPER;
    private Object CREEPERS;

    /** Un creeper de TOD4 (random, calamar, nuclear...). */
    private Entity creeper(String tipo, Location l) {
        try {
            if (CREEPER == null) {
                org.bukkit.plugin.Plugin t = Bukkit.getPluginManager().getPlugin("TOD4");
                if (t == null) return null;
                CREEPERS = t.getClass().getMethod("creepers").invoke(t);
                CREEPER = CREEPERS.getClass().getMethod("aparecer", String.class, Location.class);
            }
            return (Entity) CREEPER.invoke(CREEPERS, tipo, l);
        } catch (Throwable ex) {
            CREEPER = null;
            return null;
        }
    }

    private void mob(String tipo, Location l) {
        Entity en;
        if (tipo.startsWith("mm:")) {
            en = mm(tipo.substring(3), l);
            if (en == null) { pl.getLogger().warning("[Agujero] MythicMobs no hizo aparecer " + tipo.substring(3) + " (¿existe con ese nombre?)"); return; }
            if (tipo.equalsIgnoreCase("mm:BombaVoladora")) en.teleport(l.clone().add(0, 3, 0));
        } else if (tipo.equalsIgnoreCase("zombie_tanque")) {
            en = tanque(l);
        } else if (tipo.startsWith("creeper:")) {
            en = creeper(tipo.substring(8), l);
            if (en == null) { pl.getLogger().warning("[Agujero] TOD4 no hizo aparecer el creeper " + tipo.substring(8)); return; }
        } else {
            EntityType t = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(tipo.contains(":") ? tipo : "minecraft:" + tipo));
            if (t == null || !t.isSpawnable()) { pl.getLogger().warning("[Agujero] No existe el mob " + tipo); return; }
            en = mundo.spawnEntity(l, t);
        }
        en.addScoreboardTag(MOB);
        if (tipo.equalsIgnoreCase("mm:BreezeOld")) en.addScoreboardTag("breeze_old");
        if (en instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
    }

    /** Zombi para tanquear: mucha vida, armadura de hierro, casi no lo empujas. No suelta la armadura. */
    private Entity tanque(Location l) {
        return mundo.spawn(l, org.bukkit.entity.Zombie.class, z -> {
            z.setAdult();
            var hp = z.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(y.getDouble("zombie_tanque.vida", 50));
            z.setHealth(y.getDouble("zombie_tanque.vida", 50));
            var kb = z.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(0.8);
            var eq = z.getEquipment();
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
            eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
            for (org.bukkit.inventory.EquipmentSlot sl : org.bukkit.inventory.EquipmentSlot.values()) {
                try { eq.setDropChance(sl, 0f); } catch (Exception ignored) { }
            }
            z.setShouldBurnInDay(false);
        });
    }

    // ================================================================= ni perlas ni cargas de viento

    private static boolean prohibido(ItemStack it) {
        return it != null && (it.getType() == Material.ENDER_PEARL || it.getType() == Material.WIND_CHARGE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void tira(PlayerInteractEvent e) {
        if (!enMundo(e.getPlayer()) || e.getAction() == Action.PHYSICAL || !prohibido(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(c("&cEn el Agujero del Infierno no se pueden usar perlas ni cargas de viento."));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void lanza(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (mundo == null || !e.getEntity().getWorld().equals(mundo)) return;
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl || (e.getEntity() instanceof org.bukkit.entity.WindCharge && e.getEntity().getShooter() instanceof Player))
            e.setCancelled(true);
    }

    /** Un lugar parado cerca de ese punto: aire con piso firme abajo (busca 5 arriba y abajo). */
    private Location piso(Location l) {
        Block b = l.getBlock();
        for (int k = 0; k <= 10; k++) {
            int dy = (k % 2 == 0) ? k / 2 : -(k + 1) / 2;
            Block q = b.getRelative(0, dy, 0);
            if (q.isPassable() && !q.isLiquid() && q.getRelative(BlockFace.UP).isPassable() && q.getRelative(BlockFace.DOWN).getType().isSolid())
                return q.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    // ================================================================= loot

    private List<ItemStack> tirar(List<String> lineas) {
        List<ItemStack> out = new ArrayList<>();
        for (String linea : lineas) {
            try {
                String[] p = linea.trim().split("\\s+");
                if (p.length == 0 || p[0].isEmpty()) continue;
                double chance = 100;
                String cant = "1";
                for (int i = 1; i < p.length; i++) {
                    if (p[i].endsWith("%")) chance = Double.parseDouble(p[i].substring(0, p[i].length() - 1).replace(',', '.'));
                    else cant = p[i];
                }
                if (rnd.nextDouble() * 100 >= chance) continue;
                int n = cantidad(cant);
                if (n <= 0) continue;
                ItemStack it = item(p[0]);
                if (it == null) { pl.getLogger().warning("[Agujero] No existe el item " + p[0]); continue; }
                int max = Math.max(1, it.getMaxStackSize());
                while (n > 0) {
                    ItemStack c = it.clone();
                    c.setAmount(Math.min(max, n));
                    n -= c.getAmount();
                    out.add(c);
                }
            } catch (Exception ex) {
                pl.getLogger().warning("[Agujero] Renglon raro en el loot: \"" + linea + "\"");
            }
        }
        return out;
    }

    private int cantidad(String s) {
        if (s.contains(":")) {
            List<int[]> op = new ArrayList<>();
            int total = 0;
            for (String x : s.split(",")) { String[] q = x.split(":"); op.add(new int[]{Integer.parseInt(q[0].trim()), Integer.parseInt(q[1].trim())}); total += op.get(op.size() - 1)[1]; }
            int r = rnd.nextInt(Math.max(1, total));
            for (int[] o : op) { if (r < o[1]) return o[0]; r -= o[1]; }
            return 0;
        }
        if (s.contains("-")) { String[] q = s.split("-"); int a = Integer.parseInt(q[0].trim()), b = Integer.parseInt(q[1].trim()); return a + rnd.nextInt(Math.max(1, b - a + 1)); }
        return Integer.parseInt(s.trim());
    }

    /** Un item del loot: minecraft:x, oraxen:x, o un nombre guardado (con /agujero item, o en el loot del Aether: chip...). */
    private ItemStack item(String id) {
        if (!id.contains(":")) {
            String b64 = d.y().getString("items." + id.toLowerCase());
            if (b64 == null) {
                File af = new File(pl.getDataFolder().getParentFile(), "TOD4Aether/loot.yml");
                if (af.exists()) b64 = YamlConfiguration.loadConfiguration(af).getString("guardados." + id.toLowerCase());
            }
            if (b64 != null) {
                try { return ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(b64)); } catch (Exception ex) { return null; }
            }
            ItemStack ox = item("oraxen:" + id);
            if (ox != null) return ox;
        }
        if (id.startsWith("oraxen:")) {
            try {
                Class<?> k = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
                Object b = k.getMethod("getItemById", String.class).invoke(null, id.substring(7));
                return b == null ? null : (ItemStack) b.getClass().getMethod("build").invoke(b);
            } catch (Throwable t) { return null; }
        }
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() || m.isAir() ? null : new ItemStack(m);
    }

    // ================================================================= reconstruir (y la foto)

    private File fotoF() { return new File(pl.getDataFolder(), "agujero_foto.bin.gz"); }

    private Map<Long, short[]> foto;
    private BlockData[] fotoBD;
    private List<String> fotoPal;            // la paleta como texto (para guardarla)
    private Map<String, Short> fotoIdx;
    private boolean fotoSucia;
    private long fotoUltimo;

    private static long clave(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** Lo que tiene que haber en un chunk: la foto guardada (si hay) o la cueva tal cual sale del generador. */
    private BlockData[] esperado(int cx, int cz, short[] out) {
        short[] f = foto == null ? null : foto.get(clave(cx, cz));
        if (f != null) { System.arraycopy(f, 0, out, 0, out.length); return fotoBD; }
        System.arraycopy(gen.chunk(cx, cz), 0, out, 0, out.length);
        return base;
    }

    private void cargarFoto() {
        if (foto != null || !fotoF().exists()) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(fotoF())))) {
            int np = in.readInt();
            BlockData[] bd = new BlockData[np];
            for (int i = 0; i < np; i++) {
                String k = in.readUTF();
                try { bd[i] = Bukkit.createBlockData(k); } catch (Exception ex) { bd[i] = Material.AIR.createBlockData(); }
            }
            int nc = in.readInt(), alto = (CuevaGen.MAX_Y - CuevaGen.MIN_Y + 1) * 256;
            Map<Long, short[]> m = new HashMap<>();
            for (int i = 0; i < nc; i++) {
                long k = in.readLong();
                short[] a = new short[alto];
                for (int j = 0; j < alto; j++) a[j] = in.readShort();
                m.put(k, a);
            }
            foto = m; fotoBD = bd;
            fotoPal = new ArrayList<>();
            fotoIdx = new HashMap<>();
            for (BlockData x : bd) { fotoIdx.putIfAbsent(x.getAsString(), (short) fotoPal.size()); fotoPal.add(x.getAsString()); }
        } catch (IOException ex) {
            pl.getLogger().warning("[Agujero] No pude leer la foto: " + ex.getMessage());
        }
    }

    /**
     * Recorre los chunks de la cueva sin trabar el server: los va cargando aparte (unos cuantos adelante, agarrados con
     * un ticket para que no se descarguen) y en cada tick trabaja como mucho ~20 ms.
     */
    private void porChunks(java.util.function.BiConsumer<Integer, Integer> hacer, Runnable fin) {
        List<int[]> l = new ArrayList<>();
        for (int cx = Math.floorDiv(CuevaGen.MIN_X, 16); cx <= Math.floorDiv(CuevaGen.MAX_X, 16); cx++)
            for (int cz = Math.floorDiv(CuevaGen.MIN_Z, 16); cz <= Math.floorDiv(CuevaGen.MAX_Z, 16); cz++) l.add(new int[]{cx, cz});
        World w = mundo;
        List<java.util.concurrent.CompletableFuture<Chunk>> fut = new ArrayList<>();
        final int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            long t0 = System.nanoTime();
            while (fut.size() < l.size() && fut.size() < i[0] + 24) {
                int[] c = l.get(fut.size());
                fut.add(w.getChunkAtAsync(c[0], c[1], true).thenApply(ch -> { ch.addPluginChunkTicket(pl); return ch; }));
            }
            while (i[0] < fut.size() && System.nanoTime() - t0 < 20_000_000L) {
                java.util.concurrent.CompletableFuture<Chunk> f = fut.get(i[0]);
                if (!f.isDone()) break;   // todavia cargando: el tick que viene
                int[] c = l.get(i[0]);
                try { hacer.accept(c[0], c[1]); } catch (Exception ex) { pl.getLogger().warning("[Agujero] Chunk " + c[0] + "," + c[1] + ": " + ex); }
                w.removePluginChunkTicket(c[0], c[1], pl);
                i[0]++;
            }
            if (i[0] >= l.size()) { task.cancel(); if (fin != null) fin.run(); }
        }, 1L, 1L);
    }

    /** Vuelve a dejar la cueva como en la foto (o como sale del generador), pone cofres nuevos y la abre. */
    void reconstruir(CommandSender quien) {
        if (mundo == null || estado == Estado.RECONSTRUYENDO) return;
        estado = Estado.RECONSTRUYENDO;
        if (barra != null) { barra.removeAll(); barra = null; }
        jugando.clear();
        d.set("en_curso", false);
        cargarFoto();
        for (Player p : mundo.getPlayers()) if (juega(p)) sacar(p, false);
        // mobs, items tirados, flechas, piedras cayendo... (los carteles de las tumbas y los clones no)
        for (Entity en : mundo.getEntities()) {
            if (en instanceof Player || en instanceof org.bukkit.entity.Display || en instanceof org.bukkit.entity.ArmorStand) continue;
            if (en.getScoreboardTags().contains(MOB) || en instanceof org.bukkit.entity.Item || en instanceof FallingBlock
                    || en instanceof org.bukkit.entity.Projectile || en instanceof org.bukkit.entity.ExperienceOrb
                    || en instanceof org.bukkit.entity.TNTPrimed || en instanceof org.bukkit.entity.Mob) en.remove();
        }
        int alto = CuevaGen.MAX_Y - CuevaGen.MIN_Y + 1;
        short[] esp = new short[alto * 256];
        final int[] cambios = {0};
        porChunks((cx, cz) -> {
            Chunk ch = mundo.getChunkAt(cx, cz);
            ChunkSnapshot s = ch.getChunkSnapshot(false, false, false);
            BlockData[] pal = esperado(cx, cz, esp);
            for (int i = 0; i < esp.length; i++) {
                int yy = i / 256 + CuevaGen.MIN_Y, lz = (i % 256) / 16, lx = i % 16;
                int x = cx * 16 + lx, z = cz * 16 + lz;
                if (x < CuevaGen.MIN_X || x > CuevaGen.MAX_X || z < CuevaGen.MIN_Z || z > CuevaGen.MAX_Z) continue;
                BlockData quiere = pal[esp[i]];
                if (s.getBlockData(lx, yy, lz).equals(quiere)) continue;
                Block b = ch.getBlock(lx, yy, lz);
                if (protegido(b)) continue;   // las tumbas de los que murieron adentro no se tocan
                b.setBlockData(quiere, false);
                cambios[0]++;
            }
        }, () -> {
            int puestos = cofres();
            estado = Estado.ABIERTO;
            cerradoHasta = 0;
            guardar();
            linea = null;
            String msg = "&aAgujero del Infierno reconstruido &7(" + cambios[0] + " bloques, " + puestos + " cofres). Está abierto.";
            if (quien != null) TOD4Eventos.ok(quien, msg);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.hasPermission(PERM) && o != quien) o.sendMessage(c("&4☠ " + msg));
                o.sendMessage(c("&4☠ &cSe reconstruyó el &4&lAgujero del Infierno 2.0&c."));
            }
        });
    }

    private Object revivir;
    private Method protegidoM;

    private boolean protegido(Block b) {
        try {
            if (protegidoM == null) {
                org.bukkit.plugin.Plugin t = Bukkit.getPluginManager().getPlugin("TOD4");
                if (t == null) return false;
                revivir = t.getClass().getMethod("revivir").invoke(t);
                protegidoM = revivir.getClass().getMethod("protegido", Block.class);
            }
            return (Boolean) protegidoM.invoke(revivir, b);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Saca la foto de como esta la cueva ahora (despues de decorarla): eso es lo que vuelve en cada reconstruccion. */
    void sacarFoto(CommandSender s) {
        if (mundo == null) { TOD4Eventos.err(s, "Primero crea la cueva: &f/tod4eventos:agujero crear"); return; }
        if (estado != Estado.ABIERTO && estado != Estado.CERRADO) { TOD4Eventos.err(s, "Esperá a que no haya nadie jugando."); return; }
        Map<String, Short> idx = new HashMap<>();
        List<String> pal = new ArrayList<>();
        Map<Long, short[]> m = new HashMap<>();
        int alto = CuevaGen.MAX_Y - CuevaGen.MIN_Y + 1;
        TOD4Eventos.info(s, "Sacando la foto de la cueva (unos segundos)...");
        porChunks((cx, cz) -> {
            ChunkSnapshot sn = mundo.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
            short[] a = new short[alto * 256];
            for (int i = 0; i < a.length; i++) {
                int yy = i / 256 + CuevaGen.MIN_Y, lz = (i % 256) / 16, lx = i % 16;
                BlockData bd = sn.getBlockData(lx, yy, lz);
                // los cofres del agujero no van en la foto (se ponen nuevos cada vez)
                String k = esCofre(bd.getMaterial()) && cofresPuestos.contains((cx * 16 + lx) + "," + yy + "," + (cz * 16 + lz)) ? "minecraft:air" : bd.getAsString();
                Short id = idx.get(k);
                if (id == null) { id = (short) pal.size(); pal.add(k); idx.put(k, id); }
                a[i] = id;
            }
            m.put(clave(cx, cz), a);
        }, () -> {
            try {
                escribirFoto(fotoF(), pal, m);
            } catch (IOException ex) {
                TOD4Eventos.err(s, "No pude guardar la foto: " + ex.getMessage());
                return;
            }
            foto = null;
            fotoSucia = false;
            cargarFoto();
            TOD4Eventos.ok(s, "Foto guardada (" + pal.size() + " tipos de bloque). Cada reconstrucción deja la cueva así.");
        });
    }

    private static void escribirFoto(File f, List<String> pal, Map<Long, short[]> m) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(pal.size());
            for (String p : pal) out.writeUTF(p);
            out.writeInt(m.size());
            for (Map.Entry<Long, short[]> e : m.entrySet()) {
                out.writeLong(e.getKey());
                for (short v : e.getValue()) out.writeShort(v);
            }
        }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ================================================================= lo que cambia un admin queda en la foto

    /** Un admin en creativo edita la cueva: cada bloque que pone o rompe queda en la foto (la reconstruccion lo respeta). */
    private boolean editor(Player p) { return p.hasPermission(PERM) && p.getGameMode() == GameMode.CREATIVE; }

    private short idxFoto(String k) {
        Short i = fotoIdx.get(k);
        if (i != null) return i;
        short n = (short) fotoPal.size();
        fotoPal.add(k);
        fotoIdx.put(k, n);
        BlockData bd;
        try { bd = Bukkit.createBlockData(k); } catch (Exception ex) { bd = Material.AIR.createBlockData(); }
        fotoBD = java.util.Arrays.copyOf(fotoBD == null ? new BlockData[0] : fotoBD, n + 1);
        fotoBD[n] = bd;
        return n;
    }

    /** Anota en la foto como esta ahora ese bloque (aire = siempre aire, para los lugares de cofre). */
    private void anotar(Block b, boolean aire) {
        if (mundo == null || !b.getWorld().equals(mundo)) return;
        int x = b.getX(), yy = b.getY(), z = b.getZ();
        if (x < CuevaGen.MIN_X || x > CuevaGen.MAX_X || z < CuevaGen.MIN_Z || z > CuevaGen.MAX_Z || yy < CuevaGen.MIN_Y || yy > CuevaGen.MAX_Y) return;
        cargarFoto();
        if (foto == null) {   // todavia no hay foto: arranca de la cueva generada
            foto = new HashMap<>();
            fotoPal = new ArrayList<>();
            fotoIdx = new HashMap<>();
            fotoBD = new BlockData[0];
        }
        int cx = x >> 4, cz = z >> 4;
        short[] a = foto.get(clave(cx, cz));
        if (a == null) {
            short[] g = gen.chunk(cx, cz);
            short[] map = new short[base.length];
            for (int i = 0; i < base.length; i++) map[i] = idxFoto(base[i].getAsString());
            a = new short[g.length];
            for (int i = 0; i < g.length; i++) a[i] = map[g[i]];
            foto.put(clave(cx, cz), a);
        }
        a[(yy - CuevaGen.MIN_Y) * 256 + (z & 15) * 16 + (x & 15)] = idxFoto(aire ? "minecraft:air" : b.getBlockData().getAsString());
        fotoSucia = true;
        fotoUltimo = System.currentTimeMillis();
    }

    private Map<Long, short[]> copiaFoto() {
        Map<Long, short[]> m = new HashMap<>();
        for (Map.Entry<Long, short[]> e : foto.entrySet()) m.put(e.getKey(), e.getValue().clone());
        return m;
    }

    /** Guarda la foto (en otro hilo) un rato despues del ultimo cambio. */
    private void guardarFotoSiHace() {
        if (!fotoSucia || foto == null || System.currentTimeMillis() - fotoUltimo < 3000) return;
        fotoSucia = false;
        List<String> pal = new ArrayList<>(fotoPal);
        Map<Long, short[]> m = copiaFoto();
        File f = fotoF();
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try { escribirFoto(f, pal, m); } catch (IOException ex) { pl.getLogger().warning("[Agujero] No pude guardar la foto: " + ex.getMessage()); }
        });
    }

    private void guardarFotoYa() {
        if (!fotoSucia || foto == null) return;
        fotoSucia = false;
        try { escribirFoto(fotoF(), fotoPal, foto); } catch (IOException ex) { pl.getLogger().warning("[Agujero] No pude guardar la foto: " + ex.getMessage()); }
    }

    private static boolean esCofre(Material m) { return m == Material.CHEST || m == Material.TRAPPED_CHEST; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rompe(org.bukkit.event.block.BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!enMundo(p)) return;
        if (!editor(p)) { e.setCancelled(true); p.sendActionBar(c("&cEn el Agujero del Infierno no se puede romper nada.")); return; }
        Block b = e.getBlock();
        if (esCofre(b.getType())) {
            String t = quitarLugar(b);
            cofresPuestos.remove(b.getX() + "," + b.getY() + "," + b.getZ());
            if (t != null) TOD4Eventos.ok(p, "Lugar de cofre &f" + t + " &aquitado.");
        }
        // un tick despues: el bloque y lo que se cayo con el (flores, puertas...)
        Block arriba = b.getRelative(BlockFace.UP), abajo = b.getRelative(BlockFace.DOWN);
        BlockData ar = arriba.getBlockData(), ab = abajo.getBlockData();
        Bukkit.getScheduler().runTask(pl, () -> {
            anotar(b, false);
            if (!arriba.getBlockData().equals(ar)) anotar(arriba, false);
            if (!abajo.getBlockData().equals(ab)) anotar(abajo, false);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pone(org.bukkit.event.block.BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!enMundo(p)) return;
        if (!editor(p)) { e.setCancelled(true); p.sendActionBar(c("&cEn el Agujero del Infierno no se puede poner nada.")); return; }
        Block b = e.getBlockPlaced();
        if (esCofre(b.getType())) {
            // poner un cofre = lugar de cofre (cofre normal: facil, cofre trampa: dificil). Se llena ya, y en la foto queda aire.
            String tipo = b.getType() == Material.TRAPPED_CHEST ? "dificil" : "facil";
            String dir = b.getBlockData() instanceof Directional dd ? dd.getFacing().name() : "NORTH";
            quitarLugar(b);
            List<String> l = d.y().getStringList("cofres_extra." + tipo);
            l.add(b.getX() + "," + b.getY() + "," + b.getZ() + "," + dir);
            d.set("cofres_extra." + tipo, l);
            cofresPuestos.add(b.getX() + "," + b.getY() + "," + b.getZ());
            Bukkit.getScheduler().runTask(pl, () -> {
                if (b.getState() instanceof Chest ch) {
                    ch.getBlockInventory().clear();
                    llenar(ch, tipo);
                }
                anotar(b, true);
            });
            TOD4Eventos.ok(p, "Lugar de cofre &f" + (tipo.equals("facil") ? "fácil" : "difícil") + " &a(" + l.size() + " " + tipo + "s). "
                    + "&7Cofre normal = fácil, cofre trampa = difícil. Rompelo para sacar el lugar.");
            return;
        }
        List<Block> bs = new ArrayList<>();
        if (e instanceof org.bukkit.event.block.BlockMultiPlaceEvent mp) for (org.bukkit.block.BlockState st : mp.getReplacedBlockStates()) bs.add(st.getBlock());
        else bs.add(b);
        Bukkit.getScheduler().runTask(pl, () -> { for (Block x : bs) anotar(x, false); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void baldeVacia(org.bukkit.event.player.PlayerBucketEmptyEvent e) { balde(e, e.getBlockClicked().getRelative(e.getBlockFace())); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void baldeLlena(org.bukkit.event.player.PlayerBucketFillEvent e) { balde(e, e.getBlockClicked()); }

    private void balde(org.bukkit.event.player.PlayerBucketEvent e, Block b) {
        Player p = e.getPlayer();
        if (!enMundo(p)) return;
        if (!editor(p)) { e.setCancelled(true); return; }
        Block cl = e.getBlockClicked();
        Bukkit.getScheduler().runTask(pl, () -> { anotar(b, false); anotar(cl, false); });
    }

    /** Marcos, cuadros y soportes: solo los admins en creativo. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void cuadro(org.bukkit.event.hanging.HangingBreakByEntityEvent e) {
        if (mundo == null || !e.getEntity().getWorld().equals(mundo)) return;
        if (!(e.getRemover() instanceof Player p) || !editor(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void tocaEntidad(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!enMundo(e.getPlayer()) || editor(e.getPlayer())) return;
        if (e.getRightClicked() instanceof org.bukkit.entity.Hanging || e.getRightClicked() instanceof org.bukkit.entity.ArmorStand) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pegaEntidad(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (mundo == null || !e.getEntity().getWorld().equals(mundo)) return;
        if (!(e.getEntity() instanceof org.bukkit.entity.Hanging || e.getEntity() instanceof org.bukkit.entity.ArmorStand)) return;
        if (!(e.getDamager() instanceof Player p) || !editor(p)) e.setCancelled(true);
    }

    /** Las explosiones no rompen la sala de entrada (ahi estan los carteles). El resto se arregla al reconstruir. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explota(org.bukkit.event.entity.EntityExplodeEvent e) {
        if (mundo == null || !e.getLocation().getWorld().equals(mundo)) return;
        e.blockList().removeIf(b -> enSala(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explotaBloque(org.bukkit.event.block.BlockExplodeEvent e) {
        if (mundo == null || !e.getBlock().getWorld().equals(mundo)) return;
        e.blockList().removeIf(b -> enSala(b.getLocation()));
    }

    /** Saca el lugar de cofre que este en ese bloque. Devuelve su tipo o null. */
    private String quitarLugar(Block b) {
        String pre = b.getX() + "," + b.getY() + "," + b.getZ();
        for (String t : new String[]{"facil", "dificil"}) {
            List<String> l = d.y().getStringList("cofres_extra." + t);
            if (l.removeIf(x -> x.equals(pre) || x.startsWith(pre + ","))) { d.set("cofres_extra." + t, l); return t; }
        }
        return null;
    }

    // ================================================================= cofres

    private final Set<String> cofresPuestos = new HashSet<>();

    /**
     * Pone los cofres. Con cofres.generados: false (lo normal) van en TODOS los lugares que pusiste vos
     * (poniendo un cofre en creativo, o /agujero cofre). Con true, ademas usa lugares de la cueva generada, al azar.
     */
    private int cofres() {
        cofresPuestos.clear();
        int puestos = 0;
        boolean generados = y.getBoolean("cofres.generados", false);
        BlockFace[] caras = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (String tipo : new String[]{"facil", "dificil"}) {
            List<String[]> pts = new ArrayList<>();
            for (String l : d.y().getStringList("cofres_extra." + tipo)) pts.add(l.split(","));
            int cant = pts.size();
            if (generados) {
                for (CuevaGen.Punto p : gen.puntos()) if (p.tipo.equals(tipo)) pts.add(new String[]{"" + p.x, "" + p.y, "" + p.z});
                Collections.shuffle(pts, rnd);
                cant = y.getInt("cofres." + tipo, tipo.equals("facil") ? 8 : 5);
            }
            for (int i = 0, n = 0; i < pts.size() && n < cant; i++) {
                String[] q = pts.get(i);
                Block b;
                try { b = mundo.getBlockAt(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])); } catch (Exception ex) { continue; }
                if (!b.isPassable() && !esCofre(b.getType())) {
                    Location l = piso(b.getLocation().add(0.5, 0, 0.5));
                    if (l == null) continue;
                    b = l.getBlock();
                }
                b.setType(Material.CHEST, false);
                if (b.getBlockData() instanceof Directional dir) {
                    BlockFace f = caras[rnd.nextInt(4)];
                    if (q.length > 3) try { f = BlockFace.valueOf(q[3]); } catch (Exception ignored) { }
                    dir.setFacing(f);
                    b.setBlockData(dir, false);
                }
                if (b.getState() instanceof Chest ch) llenar(ch, tipo);
                cofresPuestos.add(b.getX() + "," + b.getY() + "," + b.getZ());
                n++; puestos++;
            }
        }
        return puestos;
    }

    private void llenar(Chest ch, String tipo) {
        List<ItemStack> items = tirar(y.getStringList("loot." + tipo));
        List<Integer> slots = new ArrayList<>();
        for (int k = 0; k < 27; k++) slots.add(k);
        Collections.shuffle(slots, rnd);
        for (int k = 0; k < items.size() && k < 27; k++) ch.getBlockInventory().setItem(slots.get(k), items.get(k));
    }

    // ================================================================= cartel en la entrada

    /** Arriba de la entrada: el titulo y, si esta derrumbada, otro cartel con "Reconstruyendo" y el tiempo. */
    private void cartel() {
        int[] z = zona("entrada");
        if (z == null) {
            if (cartel != null) { cartel.remove(); cartel = null; }
            if (cartel2 != null) { cartel2.remove(); cartel2 = null; }
            return;
        }
        Location b = new Location(bloque("entrada").getWorld(), (z[0] + z[3]) / 2.0, z[4], (z[2] + z[5]) / 2.0);   // + 0.5 = el centro de la zona
        if (!b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4)) return;
        if (cartel == null || !cartel.isValid() || cartel2 == null || !cartel2.isValid()) {
            for (Entity en : b.getWorld().getNearbyEntities(b, 3, 5, 3)) if (en.getScoreboardTags().contains(CARTEL)) en.remove();
            cartel = holo(b.clone().add(0.5, 3.0, 0.5), 1.4f);
            cartel2 = holo(b.clone().add(0.5, 2.1, 0.5), 1.0f);
            cartel.text(c("&4&lAGUJERO DEL INFIERNO 2.0"));
        }
        long ahora = System.currentTimeMillis();
        String linea = switch (estado) {
            case ABIERTO -> "";
            case CORRIENDO -> "&c¡Se está derrumbando!\n&f" + mmss((long) ((duracion() - (ahora - inicio) / 1000.0) * 1000));
            case TERMINANDO -> "&4¡Se derrumbó!";
            case CERRADO -> "&cReconstruyendo\n&f" + mmss(cerradoHasta - ahora);
            case RECONSTRUYENDO -> "&cReconstruyendo...";
        };
        cartel2.text(c(linea));
    }

    private TextDisplay holo(Location l, float escala) {
        return l.getWorld().spawn(l, TextDisplay.class, t -> {
            t.addScoreboardTag(CARTEL);
            t.setPersistent(false);
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(escala, escala, escala), new org.joml.AxisAngle4f()));
        });
    }

    // ================================================================= la linea, marcada con particulas

    private List<Location> linea;

    /** Puntos del piso sobre la linea (el borde de la sala de entrada). */
    private List<Location> linea() {
        if (linea != null || mundo == null) return linea;
        List<Location> l = new ArrayList<>();
        CuevaGen.Sala e = gen.entrada;
        double r = radioLinea();
        int n = (int) Math.ceil(2 * Math.PI * r * 2);   // cada medio bloque
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n, x = e.x + Math.cos(a) * r, z = e.z + Math.sin(a) * r;
            for (int dy = 4; dy >= -5; dy--) {
                Block b = mundo.getBlockAt((int) Math.floor(x), (int) Math.floor(e.piso) + dy, (int) Math.floor(z));
                if (b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    l.add(new Location(mundo, x, b.getY() + 0.15, z));
                    break;
                }
            }
        }
        linea = l;
        return l;
    }

    private static final Particle.DustOptions ROJO = new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 20, 20), 1.1f);

    private void dibujarLinea() {
        if (mundo == null || estado == Estado.CERRADO || estado == Estado.RECONSTRUYENDO) return;
        List<Location> l = linea();
        if (l == null || l.isEmpty()) return;
        Location centro = sala(mundo);
        for (Player p : mundo.getPlayers()) {
            if (p.getLocation().distanceSquared(centro) > 40 * 40) continue;
            for (Location x : l) p.spawnParticle(Particle.DUST, x, 1, 0.05, 0, 0.05, 0, ROJO);
            if (tick % 4 == 0) for (int i = 0; i < l.size(); i += 6) p.spawnParticle(Particle.SMALL_FLAME, l.get(i), 1, 0.05, 0.05, 0.05, 0.005);
        }
    }

    // ================================================================= modo aventura adentro

    private org.bukkit.NamespacedKey kAventura;

    /**
     * Adentro de la cueva los jugadores estan en aventura (no pueden romper ni poner nada, ni bugearla); al salir vuelven
     * a supervivencia. Solo se toca a los que estaban en supervivencia (no a admins, espectadores ni creativos).
     */
    private void ajustarModo(Player p) {
        if (mundo == null || !p.isOnline()) return;
        if (kAventura == null) kAventura = new org.bukkit.NamespacedKey(pl, "agujero_aventura");
        var pdc = p.getPersistentDataContainer();
        if (p.getWorld().equals(mundo)) {
            if (p.hasPermission(PERM) || p.getGameMode() != GameMode.SURVIVAL) return;
            p.setGameMode(GameMode.ADVENTURE);
            pdc.set(kAventura, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        } else if (pdc.has(kAventura)) {
            pdc.remove(kAventura);
            if (p.getGameMode() == GameMode.ADVENTURE) p.setGameMode(GameMode.SURVIVAL);
        }
    }

    /** Un poco despues (otros plugins, como Multiverse, cambian el modo al cambiar de mundo) y otra vez por las dudas. */
    private void ajustarLuego(Player p) {
        Bukkit.getScheduler().runTaskLater(pl, () -> ajustarModo(p), 2L);
        Bukkit.getScheduler().runTaskLater(pl, () -> ajustarModo(p), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void cambiaMundo(org.bukkit.event.player.PlayerChangedWorldEvent e) { ajustarLuego(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void reaparece(org.bukkit.event.player.PlayerRespawnEvent e) { ajustarLuego(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void cambiaModo(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        // si algo lo pasa a supervivencia estando adentro, vuelve a aventura
        if (e.getNewGameMode() == GameMode.SURVIVAL && enMundo(e.getPlayer())) ajustarLuego(e.getPlayer());
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
        ajustarLuego(e.getPlayer());
        // si quedo pasando la linea de una corrida que ya termino (se desconecto), sale
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || !enMundo(p) || !juega(p) || p.hasPermission(PERM) || enSala(p.getLocation())) return;
            if (estado != Estado.CORRIENDO && estado != Estado.TERMINANDO) sacar(p, false);
        }, 20L);
    }

    // ================================================================= comando

    void comando(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "estado";
        Player p = s instanceof Player pp ? pp : null;
        switch (m) {
            case "crear" -> {
                if (mundo != null) { TOD4Eventos.info(s, "La cueva ya existe. &f/tod4eventos:agujero tp &7para ir."); return; }
                TOD4Eventos.info(s, "Creando el mundo de la cueva...");
                World w = cargarMundo(true);
                if (w == null) { TOD4Eventos.err(s, "No pude crear el mundo."); return; }
                d.set("forma_version", CuevaGen.VERSION);
                TOD4Eventos.ok(s, "Cueva creada en el mundo &f" + w.getName() + "&a. Andá con &f/tod4eventos:agujero tp&a.");
                // genera la cueva de a poco (2 chunks por tick) y pone los cofres
                Bukkit.getScheduler().runTaskLater(pl, () -> { estado = Estado.CERRADO; reconstruir(s); }, 20L);
            }
            case "tp" -> {
                if (p == null || mundo == null) { TOD4Eventos.err(s, "Primero &f/tod4eventos:agujero crear &c(y desde el juego)."); return; }
                p.teleport(sala(mundo));
            }
            case "entrada", "bloquesalida" -> {
                // sin numero: un solo bloque. "1" y "2": las dos esquinas de la zona (todos los bloques entre las dos).
                if (p == null) return;
                Block b = p.getTargetBlockExact(6);
                if (b == null) { TOD4Eventos.err(s, "Mirá el bloque (a menos de 6 bloques)."); return; }
                boolean ent = m.equals("entrada");
                if (!ent && !enMundo(p)) { TOD4Eventos.err(s, "La salida va adentro, en la sala de entrada de la cueva."); return; }
                String k = ent ? "entrada" : "bloque_salida";
                String que = ent ? "Entrada" : "Salida";
                String esq = a.length > 1 ? a[1] : "";
                if (esq.equals("2")) {
                    if (bloque(k) == null) { TOD4Eventos.err(s, "Primero la esquina 1: &f/tod4eventos:agujero " + m + " 1"); return; }
                    if (!bloque(k).getWorld().equals(b.getWorld())) { TOD4Eventos.err(s, "Las dos esquinas tienen que estar en el mismo mundo."); return; }
                    d.set(k + "2", b.getLocation());
                } else {
                    d.set(k, b.getLocation());
                    d.set(k + "2", null);
                }
                if (cartel != null) { cartel.remove(); cartel = null; }
                int[] z = zona(k);
                int cant = (z[3] - z[0] + 1) * (z[4] - z[1] + 1) * (z[5] - z[2] + 1);
                if (esq.equals("1")) TOD4Eventos.ok(s, que + ": esquina 1 en &f" + b.getX() + " " + b.getY() + " " + b.getZ() + "&a. Ahora mirá la otra punta y &f/tod4eventos:agujero " + m + " 2");
                else TOD4Eventos.ok(s, que + ": &f" + cant + (cant == 1 ? " bloque" : " bloques") + " &7(" + z[0] + " " + z[1] + " " + z[2] + " → " + z[3] + " " + z[4] + " " + z[5] + ")&a. "
                        + "Pisar o tocar cualquiera " + (ent ? "baja a la cueva." : "sube afuera.") + " &7Se ve el borde unos segundos.");
                mostrarZona(p, k, ent ? org.bukkit.Color.RED : org.bukkit.Color.LIME);
            }
            case "ver" -> {
                if (p == null) return;
                boolean algo = false;
                for (String k : new String[]{"entrada", "bloque_salida"}) {
                    Location l = bloque(k);
                    if (l != null && l.getWorld().equals(p.getWorld())) { mostrarZona(p, k, k.equals("entrada") ? org.bukkit.Color.RED : org.bukkit.Color.LIME); algo = true; }
                }
                if (enMundo(p)) {
                    // los lugares de cofre: verde = facil, violeta = dificil
                    for (String t : new String[]{"facil", "dificil"}) for (String x : d.y().getStringList("cofres_extra." + t)) {
                        String[] q = x.split(",");
                        Location l = new Location(mundo, Integer.parseInt(q[0]) + 0.5, Integer.parseInt(q[1]) + 0.5, Integer.parseInt(q[2]) + 0.5);
                        Particle.DustOptions col = new Particle.DustOptions(t.equals("facil") ? org.bukkit.Color.LIME : org.bukkit.Color.PURPLE, 2f);
                        new org.bukkit.scheduler.BukkitRunnable() {
                            int n = 0;
                            @Override public void run() { if (!p.isOnline() || ++n > 16) { cancel(); return; } p.spawnParticle(Particle.DUST, l, 8, 0.25, 0.4, 0.25, 0, col); }
                        }.runTaskTimer(pl, 0L, 10L);
                        algo = true;
                    }
                }
                if (algo) TOD4Eventos.ok(s, "Mostrando las zonas (roja = entrada, verde = salida) y los lugares de cofre (verde = fácil, violeta = difícil).");
                else TOD4Eventos.err(s, "No hay ninguna zona en este mundo.");
            }
            case "salida" -> {
                if (p == null) return;
                d.set("salida", p.getLocation());
                TOD4Eventos.ok(s, "Los que suben (o terminan) aparecen acá.");
            }
            case "llegada" -> {
                if (p == null || !enMundo(p)) { TOD4Eventos.err(s, "Usalo adentro de la cueva, parado donde querés que aparezcan al bajar."); return; }
                d.set("llegada", p.getLocation());
                TOD4Eventos.ok(s, "Al bajar aparecen acá (y mirando para donde mirás).");
            }
            case "foto" -> sacarFoto(s);
            case "recargar" -> { recargar(); TOD4Eventos.ok(s, "agujero.yml recargado."); }
            case "reconstruir", "abrir" -> { TOD4Eventos.info(s, "Reconstruyendo..."); cerradoHasta = 0; if (estado != Estado.RECONSTRUYENDO) { estado = Estado.CERRADO; reconstruir(s); } }
            case "cerrar" -> {
                if (estado == Estado.CORRIENDO || estado == Estado.TERMINANDO) { TOD4Eventos.err(s, "Hay alguien jugando: esperá a que termine (o &f/tod4eventos:agujero terminar&c)."); return; }
                double min = a.length > 1 ? parse(a[1], 30) : y.getDouble("cerrado_min", 30);
                estado = Estado.CERRADO;
                cerradoHasta = System.currentTimeMillis() + (long) (min * 60000);
                guardar();
                if (mundo != null) pared();
                TOD4Eventos.ok(s, "Cerrada (con la pared) por &f" + mmss(cerradoHasta - System.currentTimeMillis()) + "&a.");
            }
            case "terminar" -> {
                if (estado != Estado.CORRIENDO && estado != Estado.TERMINANDO) { TOD4Eventos.err(s, "No hay ninguna corrida."); return; }
                inicio = System.currentTimeMillis() - (long) (duracion() * 1000) - 10000;
                estado = Estado.TERMINANDO;
                TOD4Eventos.ok(s, "Terminada: a los que siguen pasando la línea les empieza el daño.");
            }
            case "cofre" -> {
                if (p == null || a.length < 2 || !(a[1].equalsIgnoreCase("facil") || a[1].equalsIgnoreCase("dificil"))) {
                    TOD4Eventos.info(s, "Uso: &f/tod4eventos:agujero cofre <facil|dificil> &7(parado donde puede ir un cofre)"); return;
                }
                if (!enMundo(p)) { TOD4Eventos.err(s, "Usalo adentro de la cueva."); return; }
                String t = a[1].toLowerCase();
                List<String> l = d.y().getStringList("cofres_extra." + t);
                Location lo = p.getLocation();
                l.add(lo.getBlockX() + "," + lo.getBlockY() + "," + lo.getBlockZ() + "," + p.getFacing().getOppositeFace().name());
                d.set("cofres_extra." + t, l);
                TOD4Eventos.ok(s, "Lugar de cofre &f" + t + " &aagregado (" + l.size() + " agregados a mano).");
            }
            case "quitarcofre" -> {
                if (p == null || !enMundo(p)) { TOD4Eventos.err(s, "Usalo adentro de la cueva, al lado del lugar."); return; }
                for (String t : new String[]{"facil", "dificil"}) {
                    List<String> l = d.y().getStringList("cofres_extra." + t);
                    if (l.removeIf(x -> { String[] q = x.split(","); return p.getLocation().distance(new Location(mundo, Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]))) < 3; })) {
                        d.set("cofres_extra." + t, l);
                        TOD4Eventos.ok(s, "Lugar de cofre quitado.");
                        return;
                    }
                }
                TOD4Eventos.err(s, "No hay ningún lugar agregado a mano a menos de 3 bloques.");
            }
            case "item" -> {
                // guarda el item de la mano con un nombre, para usarlo en el loot de agujero.yml
                if (p == null || a.length < 2) { TOD4Eventos.info(s, "Uso: &f/tod4eventos:agujero item <nombre> &7con el item en la mano (ej: tinchoin). En el loot: &f\"tinchoin 1 1%\""); return; }
                ItemStack mano = p.getInventory().getItemInMainHand();
                if (mano.getType().isAir()) { TOD4Eventos.err(s, "Tené el item en la mano."); return; }
                ItemStack uno = mano.clone();
                uno.setAmount(1);
                d.set("items." + a[1].toLowerCase(), java.util.Base64.getEncoder().encodeToString(uno.serializeAsBytes()));
                TOD4Eventos.ok(s, "Guardado como &f" + a[1].toLowerCase() + "&a. En el loot: &f\"" + a[1].toLowerCase() + " 1-3\"");
            }
            case "espera" -> {
                if (a.length < 2) { TOD4Eventos.info(s, "Uso: &f/tod4eventos:agujero espera <jugador> &7(le saca la hora de espera)"); return; }
                org.bukkit.OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (o == null) { TOD4Eventos.err(s, "No encuentro a " + a[1] + "."); return; }
                d.set("ultima." + o.getUniqueId(), null);
                TOD4Eventos.ok(s, o.getName() + " ya puede volver a bajar.");
            }
            default -> {
                TOD4Eventos.info(s, "Agujero del Infierno 2.0: &f" + estado + (estado == Estado.CERRADO ? " &7(se reconstruye en " + mmss(cerradoHasta - System.currentTimeMillis()) + ")" : "")
                        + " &7| mundo: &f" + (mundo == null ? "sin crear" : mundo.getName()) + " &7| foto: &f" + (fotoF().exists() ? "sí" : "no (la cueva generada)"));
                TOD4Eventos.info(s, "Entrada: &f" + (bloque("entrada") == null ? "sin poner" : "puesta") + " &7| salida de la cueva: &f" + (bloque("bloque_salida") == null ? "sin poner" : "puesta")
                        + " &7| salida: &f" + (bloque("salida") == null ? "sin poner (spawn)" : "puesta"));
                TOD4Eventos.info(s, "&f/tod4eventos:agujero crear|tp|entrada [1|2]|bloquesalida [1|2]|ver|salida|llegada|foto|abrir|cerrar [min]|terminar|cofre <facil|dificil>|quitarcofre|espera <jugador>|recargar");
            }
        }
    }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }

    List<String> tab(String[] a) {
        if (a.length == 1) return List.of("crear", "tp", "entrada", "bloquesalida", "ver", "item", "salida", "llegada", "foto", "abrir", "cerrar", "terminar", "cofre", "quitarcofre", "espera", "recargar", "estado");
        if (a.length == 2 && a[0].equalsIgnoreCase("cofre")) return List.of("facil", "dificil");
        if (a.length == 2 && (a[0].equalsIgnoreCase("entrada") || a[0].equalsIgnoreCase("bloquesalida"))) return List.of("1", "2");
        if (a.length == 2 && a[0].equalsIgnoreCase("espera")) { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
        return List.of();
    }
}
