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
    private TextDisplay cartel;

    Agujero(TOD4Eventos pl) {
        this.pl = pl;
        d = new Store(pl, "agujero_datos");
        recargar();
        cerradoHasta = d.y().getLong("cerrado_hasta", 0);
        if (cerradoHasta > System.currentTimeMillis()) estado = Estado.CERRADO;
        // se apago el server con una corrida en curso: se reconstruye enseguida
        if (d.y().getBoolean("en_curso", false)) { estado = Estado.CERRADO; cerradoHasta = System.currentTimeMillis() + 20000; d.set("en_curso", false); }
        Bukkit.getScheduler().runTask(pl, () -> cargarMundo(false));
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada, 40L, 10L);
    }

    void recargar() {
        File f = new File(pl.getDataFolder(), "agujero.yml");
        if (!f.exists()) pl.saveResource("agujero.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pisa(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        Block pies = e.getTo().getBlock();
        if (enMundo(p)) {
            Location s = bloque("bloque_salida");
            if (s != null && (igual(pies, s) || igual(pies.getRelative(BlockFace.DOWN), s))) { subir(p); return; }
            moverse(p, e.getTo());
            return;
        }
        Location b = bloque("entrada");
        if (b != null && b.getWorld().equals(p.getWorld()) && (igual(pies, b) || igual(pies.getRelative(BlockFace.DOWN), b))) bajar(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void toca(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Location b = bloque("entrada"), s = bloque("bloque_salida");
        if (b != null && igual(e.getClickedBlock(), b)) { e.setCancelled(true); bajar(e.getPlayer()); }
        else if (s != null && igual(e.getClickedBlock(), s)) { e.setCancelled(true); subir(e.getPlayer()); }
    }

    private static boolean igual(Block a, Location b) {
        return a.getWorld().equals(b.getWorld()) && a.getX() == b.getBlockX() && a.getY() == b.getBlockY() && a.getZ() == b.getBlockZ();
    }

    private final Map<UUID, Long> avisado = new HashMap<>();

    private boolean muyRapido(Player p) {
        long ahora = System.currentTimeMillis();
        if (ahora - avisado.getOrDefault(p.getUniqueId(), 0L) < 1500) return true;
        avisado.put(p.getUniqueId(), ahora);
        return false;
    }

    /** Bajar a la cueva (desde el bloque de entrada). */
    private void bajar(Player p) {
        if (muyRapido(p) || p.getGameMode() == GameMode.SPECTATOR) return;
        if (mundo == null) { TOD4Eventos.err(p, "El Agujero del Infierno todavía no existe."); return; }
        long ahora = System.currentTimeMillis();
        if (estado == Estado.TERMINANDO || estado == Estado.RECONSTRUYENDO) {
            p.sendActionBar(c(estado == Estado.TERMINANDO ? "&c¡La cueva se está derrumbando!" : "&7Reconstruyendo la cueva..."));
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.5f);
            return;
        }
        // derrumbada: se puede bajar a la sala, pero la linea esta tapada (no gasta la hora de espera)
        boolean cerrada = estado == Estado.CERRADO;
        if (!cerrada && !p.hasPermission(PERM)) {
            long ultima = d.y().getLong("ultima." + p.getUniqueId(), 0), espera = (long) (y.getDouble("espera_jugador_min", 60) * 60000);
            if (ahora - ultima < espera) {
                p.sendActionBar(c("&cPodrás volver a bajar en &f" + mmss(espera - (ahora - ultima))));
                return;
            }
        }
        if (!cerrada) d.set("ultima." + p.getUniqueId(), ahora);
        jugando.remove(p.getUniqueId());
        Location dst = sala(mundo);
        p.teleport(dst);
        p.playSound(dst, Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 0.7f);
        String sub = cerrada ? "&7Está derrumbada. Se reconstruye en &f" + mmss(cerradoHasta - ahora)
                : estado == Estado.CORRIENDO ? "&c¡Ya se está derrumbando!" : "&7Cuando cruces, empieza a derrumbarse";
        p.showTitle(Title.title(c("&4&lAGUJERO DEL INFIERNO 2.0"), c(sub)));
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission(PERM)) {
            o.sendMessage(c("&4☠ &c" + p.getName() + " &7bajó al &4&lAgujero del Infierno 2.0&7" + (cerrada ? " (está derrumbada)." : ".")));
            o.playSound(o.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 1f);
        }
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

    private double duracion() { return y.getDouble("duracion_seg", 150); }

    private void empezar() {
        estado = Estado.CORRIENDO;
        inicio = System.currentTimeMillis();
        d.set("en_curso", true);
        barra = Bukkit.createBossBar(legacy("&4&lAGUJERO DEL INFIERNO 2.0"), BarColor.RED, BarStyle.SEGMENTED_10);
        String mus = y.getString("musica", "tod4:agujero");
        float vol = (float) y.getDouble("musica_volumen", 1.0);
        for (Player p : mundo.getPlayers()) p.playSound(p, mus, SoundCategory.RECORDS, vol, 1f);
        mundo.playSound(sala(mundo), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 3f, 0.6f);
        mobs();
    }

    private void cada() {
        tick++;
        cartel();
        if (mundo == null) return;
        long ahora = System.currentTimeMillis();
        switch (estado) {
            case CORRIENDO -> {
                double t = (ahora - inicio) / 1000.0, dur = duracion();
                barra.setProgress(Math.max(0, Math.min(1, 1 - t / dur)));
                barra.setTitle(legacy("&4&lAGUJERO DEL INFIERNO 2.0  &f" + mmss((long) ((dur - t) * 1000))));
                derrumbe(t / dur);
                if (t >= dur) {
                    estado = Estado.TERMINANDO;
                    barra.setTitle(legacy("&4&l¡SE DERRUMBÓ!"));
                    barra.setProgress(0);
                    for (Player p : mundo.getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.5f);
                }
            }
            case TERMINANDO -> {
                double t = (ahora - inicio) / 1000.0 - duracion();
                derrumbe(1);
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

    /** Caen piedras y estalactitas del techo cerca de los que estan jugando; cuanto mas avanzado, mas. */
    private void derrumbe(double f) {
        if (tick % 2 != 0) return;   // una vez por segundo
        ConfigurationSection cs = y.getConfigurationSection("derrumbe");
        double ini = cs == null ? 1 : cs.getDouble("bloques_inicio", 1), fin = cs == null ? 6 : cs.getDouble("bloques_fin", 6);
        double punta = cs == null ? 30 : cs.getDouble("punta_chance", 30);
        int n = (int) Math.round(ini + (fin - ini) * f * f);
        for (Player p : mundo.getPlayers()) {
            if (!juega(p) || enSala(p.getLocation())) continue;
            for (int i = 0; i < n; i++) {
                Block b = p.getLocation().add(rnd.nextInt(21) - 10, 0, rnd.nextInt(21) - 10).getBlock();
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
                fb.setCancelDrop(esPunta);
                fb.addScoreboardTag(MOB);
                mundo.spawnParticle(Particle.BLOCK, at, 12, 0.4, 0.1, 0.4, bd);
            }
            if (f > 0.15 && rnd.nextDouble() < 0.4)
                p.playSound(p.getLocation(), rnd.nextBoolean() ? Sound.BLOCK_DEEPSLATE_BREAK : Sound.BLOCK_BASALT_BREAK, SoundCategory.BLOCKS, 1f, 0.5f);
            if (f > 0.8 && rnd.nextDouble() < 0.3) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false));
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

    private void mobs() {
        ConfigurationSection cs = y.getConfigurationSection("mobs.tipos");
        int cant = y.getInt("mobs.cantidad", 16);
        if (cs == null || cant <= 0) return;
        List<CuevaGen.Punto> pts = new ArrayList<>();
        for (CuevaGen.Punto p : gen.puntos()) if (p.tipo.equals("mob")) pts.add(p);
        Collections.shuffle(pts, rnd);
        double tot = 0;
        for (String k : cs.getKeys(false)) tot += Math.max(0, cs.getDouble(k));
        for (int i = 0; i < cant && i < pts.size(); i++) {
            double r = rnd.nextDouble() * tot;
            String tipo = null;
            for (String k : cs.getKeys(false)) { r -= Math.max(0, cs.getDouble(k)); if (r < 0) { tipo = k; break; } }
            if (tipo == null) continue;
            Location l = piso(new Location(mundo, pts.get(i).x + 0.5, pts.get(i).y, pts.get(i).z + 0.5));
            if (l == null) continue;
            Entity en;
            if (tipo.startsWith("mm:")) {
                en = mm(tipo.substring(3), l);
                if (en == null) { pl.getLogger().warning("[Agujero] MythicMobs no hizo aparecer " + tipo.substring(3)); continue; }
                if (tipo.equalsIgnoreCase("mm:BombaVoladora")) en.teleport(l.clone().add(0, 3, 0));
            } else {
                EntityType t = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(tipo.contains(":") ? tipo : "minecraft:" + tipo));
                if (t == null || !t.isSpawnable()) { pl.getLogger().warning("[Agujero] No existe el mob " + tipo); continue; }
                en = mundo.spawnEntity(l, t);
            }
            en.addScoreboardTag(MOB);
            if (tipo.equalsIgnoreCase("mm:BreezeOld")) en.addScoreboardTag("breeze_old");
            if (en instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
        }
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

    private static ItemStack item(String id) {
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
        } catch (IOException ex) {
            pl.getLogger().warning("[Agujero] No pude leer la foto: " + ex.getMessage());
        }
    }

    /** Recorre los chunks de la cueva de a 2 por tick. */
    private void porChunks(java.util.function.BiConsumer<Integer, Integer> hacer, Runnable fin) {
        List<int[]> l = new ArrayList<>();
        for (int cx = Math.floorDiv(CuevaGen.MIN_X, 16); cx <= Math.floorDiv(CuevaGen.MAX_X, 16); cx++)
            for (int cz = Math.floorDiv(CuevaGen.MIN_Z, 16); cz <= Math.floorDiv(CuevaGen.MAX_Z, 16); cz++) l.add(new int[]{cx, cz});
        final int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            for (int k = 0; k < 2 && i[0] < l.size(); k++, i[0]++) hacer.accept(l.get(i[0])[0], l.get(i[0])[1]);
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
            String msg = "&aAgujero del Infierno reconstruido &7(" + cambios[0] + " bloques, " + puestos + " cofres). Está abierto.";
            if (quien != null) TOD4Eventos.ok(quien, msg);
            for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission(PERM) && o != quien) o.sendMessage(c("&4☠ " + msg));
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
                String k = bd.getMaterial() == Material.CHEST && cofresPuestos.contains((cx * 16 + lx) + "," + yy + "," + (cz * 16 + lz)) ? "minecraft:air" : bd.getAsString();
                Short id = idx.get(k);
                if (id == null) { id = (short) pal.size(); pal.add(k); idx.put(k, id); }
                a[i] = id;
            }
            m.put(clave(cx, cz), a);
        }, () -> {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(fotoF())))) {
                out.writeInt(pal.size());
                for (String p : pal) out.writeUTF(p);
                out.writeInt(m.size());
                for (Map.Entry<Long, short[]> e : m.entrySet()) {
                    out.writeLong(e.getKey());
                    for (short v : e.getValue()) out.writeShort(v);
                }
            } catch (IOException ex) {
                TOD4Eventos.err(s, "No pude guardar la foto: " + ex.getMessage());
                return;
            }
            foto = null;
            cargarFoto();
            TOD4Eventos.ok(s, "Foto guardada (" + pal.size() + " tipos de bloque). Cada reconstrucción deja la cueva así.");
        });
    }

    // ================================================================= cofres

    private final Set<String> cofresPuestos = new HashSet<>();

    /** Pone los cofres faciles y dificiles en lugares al azar (de los puntos de la cueva y los que agregaste). */
    private int cofres() {
        cofresPuestos.clear();
        int puestos = 0;
        for (String tipo : new String[]{"facil", "dificil"}) {
            List<int[]> pts = new ArrayList<>();
            for (CuevaGen.Punto p : gen.puntos()) if (p.tipo.equals(tipo)) pts.add(new int[]{p.x, p.y, p.z});
            for (String l : d.y().getStringList("cofres_extra." + tipo)) {
                String[] q = l.split(",");
                pts.add(new int[]{Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])});
            }
            Collections.shuffle(pts, rnd);
            int cant = y.getInt("cofres." + tipo, tipo.equals("facil") ? 8 : 5);
            List<String> lineas = y.getStringList("loot." + tipo);
            for (int i = 0, n = 0; i < pts.size() && n < cant; i++) {
                Location l = piso(new Location(mundo, pts.get(i)[0] + 0.5, pts.get(i)[1], pts.get(i)[2] + 0.5));
                if (l == null) continue;
                Block b = l.getBlock();
                b.setType(Material.CHEST, false);
                if (b.getBlockData() instanceof Directional dir) {
                    dir.setFacing(new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}[rnd.nextInt(4)]);
                    b.setBlockData(dir, false);
                }
                if (b.getState() instanceof Chest ch) {
                    List<ItemStack> items = tirar(lineas);
                    List<Integer> slots = new ArrayList<>();
                    for (int k = 0; k < 27; k++) slots.add(k);
                    Collections.shuffle(slots, rnd);
                    for (int k = 0; k < items.size() && k < 27; k++) ch.getBlockInventory().setItem(slots.get(k), items.get(k));
                }
                cofresPuestos.add(b.getX() + "," + b.getY() + "," + b.getZ());
                n++; puestos++;
            }
        }
        return puestos;
    }

    // ================================================================= cartel en la entrada

    private void cartel() {
        Location b = bloque("entrada");
        if (b == null) { if (cartel != null) { cartel.remove(); cartel = null; } return; }
        if (!b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4)) return;
        if (cartel == null || !cartel.isValid()) {
            for (Entity en : b.getWorld().getNearbyEntities(b, 3, 4, 3)) if (en.getScoreboardTags().contains(CARTEL)) en.remove();
            cartel = b.getWorld().spawn(b.clone().add(0.5, 2.6, 0.5), TextDisplay.class, t -> {
                t.addScoreboardTag(CARTEL);
                t.setPersistent(false);
                t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                t.setShadowed(true);
            });
        }
        long ahora = System.currentTimeMillis();
        String linea = switch (estado) {
            case ABIERTO -> "&aAbierto";
            case CORRIENDO -> "&c¡Se está derrumbando! &f" + mmss((long) ((duracion() - (ahora - inicio) / 1000.0) * 1000));
            case TERMINANDO -> "&4¡Se derrumbó!";
            case CERRADO -> "&7Será reconstruida en &f" + mmss(cerradoHasta - ahora);
            case RECONSTRUYENDO -> "&7Reconstruyendo...";
        };
        cartel.text(c("&4&lAGUJERO DEL INFIERNO 2.0\n" + linea));
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
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
                TOD4Eventos.ok(s, "Cueva creada en el mundo &f" + w.getName() + "&a. Andá con &f/tod4eventos:agujero tp&a.");
                // genera la cueva de a poco (2 chunks por tick) y pone los cofres
                Bukkit.getScheduler().runTaskLater(pl, () -> { estado = Estado.CERRADO; reconstruir(s); }, 20L);
            }
            case "tp" -> {
                if (p == null || mundo == null) { TOD4Eventos.err(s, "Primero &f/tod4eventos:agujero crear &c(y desde el juego)."); return; }
                p.teleport(sala(mundo));
            }
            case "entrada", "bloquesalida" -> {
                if (p == null) return;
                Block b = p.getTargetBlockExact(6);
                if (b == null) { TOD4Eventos.err(s, "Mirá el bloque (a menos de 6 bloques)."); return; }
                boolean ent = m.equals("entrada");
                if (!ent && !enMundo(p)) { TOD4Eventos.err(s, "El bloque de salida va adentro, en la sala de entrada de la cueva."); return; }
                d.set(ent ? "entrada" : "bloque_salida", b.getLocation());
                if (cartel != null) { cartel.remove(); cartel = null; }
                TOD4Eventos.ok(s, (ent ? "Bloque de entrada" : "Bloque de salida") + ": &f" + b.getType().name().toLowerCase() + " &aen " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + "&a. Pisarlo o tocarlo " + (ent ? "baja a la cueva." : "sube afuera."));
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
                l.add(lo.getBlockX() + "," + lo.getBlockY() + "," + lo.getBlockZ());
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
                TOD4Eventos.info(s, "Entrada: &f" + (bloque("entrada") == null ? "sin poner" : "puesta") + " &7| bloque de salida: &f" + (bloque("bloque_salida") == null ? "sin poner" : "puesto")
                        + " &7| salida: &f" + (bloque("salida") == null ? "sin poner (spawn)" : "puesta"));
                TOD4Eventos.info(s, "&f/tod4eventos:agujero crear|tp|entrada|bloquesalida|salida|llegada|foto|abrir|cerrar [min]|terminar|cofre <facil|dificil>|quitarcofre|espera <jugador>|recargar");
            }
        }
    }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }

    List<String> tab(String[] a) {
        if (a.length == 1) return List.of("crear", "tp", "entrada", "bloquesalida", "salida", "llegada", "foto", "abrir", "cerrar", "terminar", "cofre", "quitarcofre", "espera", "recargar", "estado");
        if (a.length == 2 && a[0].equalsIgnoreCase("cofre")) return List.of("facil", "dificil");
        if (a.length == 2 && a[0].equalsIgnoreCase("espera")) { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
        return List.of();
    }
}
