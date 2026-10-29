package ar.tod4.aether;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

import ar.tod4.aether.agujero.CuevaGen;
import ar.tod4.aether.util.Ext;
import ar.tod4.aether.util.Style;
import ar.tod4.aether.util.Txt;

/**
 * AGUJERO DEL INFIERNO 2.0 (opciones en plugins/TOD4Aether/agujero.yml).
 *
 * Una cueva en su propio mundo (la forma sale de CuevaGen). Se entra tocando o pisando el bloque de la entrada.
 *  - Al salir de la sala de entrada (la "linea") arranca: suena la musica para todos los que estan en el mundo, una barra
 *    con el tiempo (solo para los de adentro) y la cueva se empieza a derrumbar (caen piedras y estalactitas).
 *  - Se termina la cancion: 3 segundos y a los que siguen adentro les saca 1,5 corazones cada medio segundo (fijo).
 *  - Para salvarte: volver a la sala de entrada antes (te saca afuera).
 *  - Despues queda derrumbada 30 minutos y se reconstruye sola (vuelve a quedar como la foto guardada) con los cofres
 *    en lugares nuevos (faciles y dificiles). Cada jugador puede entrar una vez por hora.
 *  - Cuando alguien entra, a los admins les avisa en el chat con un sonido de cueva.
 */
final class Agujero implements Listener {

    private static final String MOB = "agujero_mob", CARTEL = "agujero_cartel";

    private final TOD4Aether pl;
    private final Loot loot;
    private final Random rnd = new Random();
    private YamlConfiguration y, d;
    private File fy, fd;
    private CuevaGen gen;
    private BlockData[] base;           // la paleta de CuevaGen como BlockData
    private World mundo;

    enum Estado { ABIERTO, CORRIENDO, TERMINANDO, CERRADO, RECONSTRUYENDO }
    private Estado estado = Estado.ABIERTO;
    private long inicio, cerradoHasta;
    private final Set<UUID> cruzaron = new HashSet<>();
    private BossBar barra;
    private int tick;
    private TextDisplay cartel;

    Agujero(TOD4Aether pl, Loot loot) {
        this.pl = pl;
        this.loot = loot;
        recargar();
        fd = new File(pl.getDataFolder(), "agujero_datos.yml");
        d = YamlConfiguration.loadConfiguration(fd);
        cerradoHasta = d.getLong("cerrado_hasta", 0);
        if (cerradoHasta > System.currentTimeMillis()) estado = Estado.CERRADO;
        // se apago el server con una corrida en curso: se reconstruye enseguida
        if (d.getBoolean("en_curso", false)) { estado = Estado.CERRADO; cerradoHasta = System.currentTimeMillis() + 20000; d.set("en_curso", false); }
        // si el mundo ya se creo, se carga (con su generador: lo que falte se genera igual)
        if (d.getBoolean("creado", false)) Bukkit.getScheduler().runTask(pl, () -> cargarMundo(false));
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cada, 40L, 10L);
    }

    void recargar() {
        fy = new File(pl.getDataFolder(), "agujero.yml");
        if (!fy.exists()) pl.saveResource("agujero.yml", false);
        y = YamlConfiguration.loadConfiguration(fy);
        if (gen == null || y.getLong("semilla", 666) != semillaGen) {
            semillaGen = y.getLong("semilla", 666);
            gen = new CuevaGen(semillaGen);
            base = new BlockData[CuevaGen.PALETA.length];
            for (int i = 0; i < base.length; i++) base[i] = Bukkit.createBlockData("minecraft:" + CuevaGen.PALETA[i]);
        }
    }

    private long semillaGen = Long.MIN_VALUE;

    void apagar() {
        if (barra != null) barra.removeAll();
        if (cartel != null) cartel.remove();
        guardar();
    }

    private void guardar() {
        d.set("cerrado_hasta", cerradoHasta);
        try { d.save(fd); } catch (IOException ex) { pl.getLogger().warning("No pude guardar agujero_datos.yml: " + ex.getMessage()); }
    }

    private String nombreMundo() { return y.getString("mundo", "agujero_infierno"); }

    // ================================================================= el mundo

    private final class Generador extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo w, java.util.Random r, int cx, int cz, ChunkData c) {
            if (cx * 16 + 15 < CuevaGen.MIN_X || cx * 16 > CuevaGen.MAX_X || cz * 16 + 15 < CuevaGen.MIN_Z || cz * 16 > CuevaGen.MAX_Z) return;
            short[] b = gen.chunk(cx, cz);
            for (int i = 0; i < b.length; i++) {
                if (b[i] == CuevaGen.AIRE) continue;
                int yy = i / 256 + CuevaGen.MIN_Y, lz = (i % 256) / 16, lx = i % 16;
                c.setBlock(lx, yy, lz, base[b[i]]);
            }
        }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo w) { return new Biomas(); }
        @Override public Location getFixedSpawnLocation(World w, java.util.Random r) { return entradaAdentro(w); }
    }

    private final class Biomas extends BiomeProvider {
        private Biome b() {
            Biome b = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(y.getString("bioma", "dripstone_caves")));
            return b != null ? b : Biome.DRIPSTONE_CAVES;
        }
        @Override public Biome getBiome(WorldInfo w, int x, int yy, int z) { return b(); }
        @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(b()); }
    }

    /** Crea (la primera vez) o carga el mundo de la cueva. */
    private World cargarMundo(boolean crear) {
        World w = Bukkit.getWorld(nombreMundo());
        if (w == null) {
            if (!crear && !new File(Bukkit.getWorldContainer(), nombreMundo()).exists()) return null;
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
        w.setSpawnLocation(entradaAdentro(w));
        mundo = w;
        if (!d.getBoolean("creado", false)) { d.set("creado", true); guardar(); }
        return w;
    }

    private Location entradaAdentro(World w) {
        CuevaGen.Sala e = gen.entrada;
        return new Location(w, e.x + 0.5, e.piso, e.z + 0.5, 0f, 0f);   // mirando al sur (adonde va el tunel)
    }

    private boolean enMundo(Player p) { return mundo != null && p.getWorld().equals(mundo); }

    /** En la sala de entrada (detras de la linea). */
    private boolean enEntrada(Location l) {
        CuevaGen.Sala e = gen.entrada;
        return Math.hypot(l.getX() - e.x, l.getZ() - e.z) < e.rx + 1.5 && Math.abs(l.getY() - e.piso) < 8;
    }

    // ================================================================= entrar

    private Location bloqueEntrada() {
        Location l = d.getLocation("entrada");
        return l != null && l.getWorld() != null ? l : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pisa(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        if (enMundo(p)) { moverseAdentro(p, e.getTo()); return; }
        Location b = bloqueEntrada();
        if (b == null || !b.getWorld().equals(p.getWorld())) return;
        Block pies = e.getTo().getBlock();
        if (igual(pies, b) || igual(pies.getRelative(BlockFace.DOWN), b)) intentar(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void toca(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Location b = bloqueEntrada();
        if (b == null || !igual(e.getClickedBlock(), b)) return;
        e.setCancelled(true);
        intentar(e.getPlayer());
    }

    private static boolean igual(Block a, Location b) {
        return a.getWorld().equals(b.getWorld()) && a.getX() == b.getBlockX() && a.getY() == b.getBlockY() && a.getZ() == b.getBlockZ();
    }

    private final Map<UUID, Long> avisado = new HashMap<>();

    private void intentar(Player p) {
        long ahora = System.currentTimeMillis();
        if (ahora - avisado.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        avisado.put(p.getUniqueId(), ahora);
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (mundo == null) { Style.err(p, "El Agujero del Infierno todavía no existe."); return; }
        if (estado == Estado.CERRADO || estado == Estado.RECONSTRUYENDO || estado == Estado.TERMINANDO) {
            long falta = Math.max(0, cerradoHasta - ahora);
            p.sendActionBar(Txt.c(estado == Estado.CERRADO ? "&cLa cueva será reconstruida en &f" + mmss(falta) : "&cLa cueva se está derrumbando..."));
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.5f);
            return;
        }
        boolean admin = p.hasPermission("tod4.admin");
        long ultima = d.getLong("ultima." + p.getUniqueId(), 0), espera = (long) (y.getDouble("espera_jugador_min", 60) * 60000);
        if (!admin && ahora - ultima < espera) {
            p.sendActionBar(Txt.c("&cPodrás volver a entrar en &f" + mmss(espera - (ahora - ultima))));
            return;
        }
        d.set("ultima." + p.getUniqueId(), ahora);
        guardar();
        cruzaron.remove(p.getUniqueId());
        Location dst = entradaAdentro(mundo);
        dst.setYaw(0f);
        p.teleport(dst);
        p.playSound(dst, Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 0.7f);
        p.showTitle(net.kyori.adventure.title.Title.title(Txt.c("&4&lAGUJERO DEL INFIERNO 2.0"),
                Txt.c(estado == Estado.CORRIENDO ? "&c¡Ya se está derrumbando!" : "&7Cuando cruces, empieza a derrumbarse")));
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("tod4.admin")) {
            o.sendMessage(Txt.c("&4☠ &c" + p.getName() + " &7entró al &4&lAgujero del Infierno 2.0&7."));
            o.playSound(o.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.MASTER, 1f, 1f);
        }
    }

    private static String legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(Txt.c(s));
    }

    private static String mmss(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    // ================================================================= adentro

    private void moverseAdentro(Player p, Location a) {
        if (!jugando(p)) return;
        boolean entrada = enEntrada(a);
        UUID u = p.getUniqueId();
        if (!entrada && !cruzaron.contains(u)) {
            if (estado == Estado.ABIERTO) empezar();
            if (estado == Estado.CORRIENDO) {
                cruzaron.add(u);
                p.sendActionBar(Txt.c("&c¡Volvé a la entrada antes de que se derrumbe!"));
            }
        } else if (entrada && cruzaron.contains(u)) {
            sacar(p, true);
        }
    }

    private static boolean jugando(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    /** Lo saca afuera (a la salida). */
    private void sacar(Player p, boolean salvado) {
        cruzaron.remove(p.getUniqueId());
        if (barra != null) barra.removePlayer(p);
        p.stopSound(y.getString("musica", "tod4:agujero"), SoundCategory.RECORDS);
        Location s = d.getLocation("salida");
        if (s == null || s.getWorld() == null) s = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(s);
        if (salvado) {
            p.showTitle(net.kyori.adventure.title.Title.title(Txt.c("&a&l¡ESCAPASTE!"), Txt.c("&7Saliste del Agujero del Infierno")));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    // ================================================================= la corrida

    private double duracion() { return y.getDouble("duracion_seg", 150); }

    private void empezar() {
        estado = Estado.CORRIENDO;
        d.set("en_curso", true);
        guardar();
        inicio = System.currentTimeMillis();
        barra = Bukkit.createBossBar(legacy("&4&lAGUJERO DEL INFIERNO 2.0"), BarColor.RED, BarStyle.SEGMENTED_10);
        String mus = y.getString("musica", "tod4:agujero");
        float vol = (float) y.getDouble("musica_volumen", 1.0);
        for (Player p : mundo.getPlayers()) {
            p.playSound(p, mus, SoundCategory.RECORDS, vol, 1f);
            if (jugando(p)) barra.addPlayer(p);
        }
        mundo.playSound(entradaAdentro(mundo), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 3f, 0.6f);
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
                for (Player p : mundo.getPlayers()) if (jugando(p) && !barra.getPlayers().contains(p)) barra.addPlayer(p);
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
                for (Player p : new ArrayList<>(mundo.getPlayers())) {
                    if (!jugando(p) || p.isDead()) continue;
                    if (enEntrada(p.getLocation()) && !cruzaron.contains(p.getUniqueId())) { sacar(p, false); continue; }
                    queda = true;
                    if (t >= y.getDouble("espera_dano_seg", 3)) aplastar(p);
                }
                if (!queda) terminar();
            }
            case CERRADO -> {
                if (ahora >= cerradoHasta) reconstruir(null);
            }
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
            Class.forName("ar.tod4.core.Causas").getMethod("anotar", UUID.class, net.kyori.adventure.text.Component.class)
                    .invoke(null, p.getUniqueId(), net.kyori.adventure.text.Component.text(txt));
        } catch (Throwable ignored) { }
    }

    /** Caen piedras y estalactitas del techo cerca de los de adentro; cuanto mas avanzado, mas. */
    private void derrumbe(double f) {
        if (tick % 2 != 0) return;   // una vez por segundo
        ConfigurationSection c = y.getConfigurationSection("derrumbe");
        double ini = c == null ? 1 : c.getDouble("bloques_inicio", 1), fin = c == null ? 6 : c.getDouble("bloques_fin", 6);
        double punta = c == null ? 30 : c.getDouble("punta_chance", 30);
        int n = (int) Math.round(ini + (fin - ini) * f * f);
        for (Player p : mundo.getPlayers()) {
            if (!jugando(p) || enEntrada(p.getLocation())) continue;
            for (int i = 0; i < n; i++) {
                Location l = p.getLocation().add(rnd.nextInt(21) - 10, 0, rnd.nextInt(21) - 10);
                Block b = l.getBlock();
                if (!b.isPassable()) continue;
                int k = 0;
                while (k++ < 14 && b.getRelative(BlockFace.UP).isPassable()) b = b.getRelative(BlockFace.UP);
                if (k >= 14 || b.getY() - p.getLocation().getY() < 2) continue;
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
                fb.setVelocity(new org.bukkit.util.Vector(0, -0.1, 0));
                mundo.spawnParticle(Particle.BLOCK, at, 12, 0.4, 0.1, 0.4, bd);
            }
            if (f > 0.15 && rnd.nextDouble() < 0.4)
                p.playSound(p.getLocation(), rnd.nextBoolean() ? Sound.BLOCK_DEEPSLATE_BREAK : Sound.BLOCK_BASALT_BREAK, SoundCategory.BLOCKS, 1f, 0.5f);
            if (f > 0.8 && rnd.nextDouble() < 0.3) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false));
        }
    }

    /** Ya no queda nadie adentro: se cierra y empieza la cuenta para reconstruirla. */
    private void terminar() {
        estado = Estado.CERRADO;
        d.set("en_curso", false);
        cerradoHasta = System.currentTimeMillis() + (long) (y.getDouble("cerrado_min", 30) * 60000);
        guardar();
        if (barra != null) { barra.removeAll(); barra = null; }
        cruzaron.clear();
        for (Player p : mundo.getPlayers()) {
            p.stopSound(y.getString("musica", "tod4:agujero"), SoundCategory.RECORDS);
            if (jugando(p)) sacar(p, false);
        }
        for (Entity en : mundo.getEntities()) if (en.getScoreboardTags().contains(MOB)) en.remove();
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("tod4.admin"))
            o.sendMessage(Txt.c("&4☠ &7El Agujero del Infierno se derrumbó. Se reconstruye en &f" + mmss(cerradoHasta - System.currentTimeMillis()) + "&7."));
    }

    // ================================================================= mobs

    private void mobs() {
        ConfigurationSection c = y.getConfigurationSection("mobs.tipos");
        int cant = y.getInt("mobs.cantidad", 14);
        if (c == null || cant <= 0) return;
        List<CuevaGen.Punto> pts = new ArrayList<>();
        for (CuevaGen.Punto p : gen.puntos()) if (p.tipo.equals("mob")) pts.add(p);
        Collections.shuffle(pts, rnd);
        double tot = 0;
        for (String k : c.getKeys(false)) tot += Math.max(0, c.getDouble(k));
        for (int i = 0; i < cant && i < pts.size(); i++) {
            double r = rnd.nextDouble() * tot;
            String tipo = null;
            for (String k : c.getKeys(false)) { r -= Math.max(0, c.getDouble(k)); if (r < 0) { tipo = k; break; } }
            if (tipo == null) continue;
            Location l = piso(new Location(mundo, pts.get(i).x + 0.5, pts.get(i).y, pts.get(i).z + 0.5));
            if (l == null) continue;
            Entity en = null;
            if (tipo.startsWith("mm:")) en = Ext.mythicSpawn(tipo.substring(3), l);
            else {
                EntityType t = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(tipo.contains(":") ? tipo : "minecraft:" + tipo));
                if (t != null && t.isSpawnable()) en = mundo.spawnEntity(l, t);
            }
            if (en == null) continue;
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
            Block c = b.getRelative(0, dy, 0);
            if (c.isPassable() && !c.isLiquid() && c.getRelative(BlockFace.UP).isPassable() && c.getRelative(BlockFace.DOWN).getType().isSolid())
                return c.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    // ================================================================= reconstruir (y la foto)

    private File fotoF() { return new File(pl.getDataFolder(), "agujero_foto.bin.gz"); }

    private List<String> fotoPaleta;
    private Map<Long, short[]> foto;

    private int chunks0() { return Math.floorDiv(CuevaGen.MIN_X, 16); }

    /** Lo que tiene que haber en un chunk: la foto guardada (si hay) o la cueva tal cual sale del generador. */
    private BlockData[] esperado(int cx, int cz, short[] out) {
        short[] f = foto == null ? null : foto.get(clave(cx, cz));
        if (f != null) { System.arraycopy(f, 0, out, 0, out.length); return fotoBD; }
        short[] g = gen.chunk(cx, cz);
        System.arraycopy(g, 0, out, 0, out.length);
        return base;
    }

    private BlockData[] fotoBD;

    private static long clave(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void cargarFoto() {
        if (foto != null || !fotoF().exists()) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(fotoF())))) {
            int np = in.readInt();
            List<String> pal = new ArrayList<>();
            for (int i = 0; i < np; i++) pal.add(in.readUTF());
            int nc = in.readInt();
            int alto = (CuevaGen.MAX_Y - CuevaGen.MIN_Y + 1) * 256;
            Map<Long, short[]> m = new HashMap<>();
            for (int i = 0; i < nc; i++) {
                long k = in.readLong();
                short[] a = new short[alto];
                for (int j = 0; j < alto; j++) a[j] = in.readShort();
                m.put(k, a);
            }
            BlockData[] bd = new BlockData[pal.size()];
            for (int i = 0; i < bd.length; i++) {
                try { bd[i] = Bukkit.createBlockData(pal.get(i)); } catch (Exception ex) { bd[i] = Material.AIR.createBlockData(); }
            }
            fotoPaleta = pal; foto = m; fotoBD = bd;
        } catch (IOException ex) {
            pl.getLogger().warning("[Agujero] No pude leer la foto: " + ex.getMessage());
        }
    }

    /** Recorre los chunks de la cueva de a 2 por tick. */
    private void porChunks(java.util.function.BiConsumer<Integer, Integer> hacer, Runnable fin) {
        List<int[]> l = new ArrayList<>();
        for (int cx = chunks0(); cx <= Math.floorDiv(CuevaGen.MAX_X, 16); cx++)
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
        cargarFoto();
        for (Player p : mundo.getPlayers()) if (jugando(p)) sacar(p, false);
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
            if (quien != null) Style.ok(quien, msg);
            for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("tod4.admin") && o != quien) o.sendMessage(Txt.c("&4☠ " + msg));
        });
    }

    private Object revivir;
    private java.lang.reflect.Method protegidoM;

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
        if (mundo == null) { Style.err(s, "Primero crea la cueva: &e/tod4:agujero crear"); return; }
        Map<String, Short> idx = new HashMap<>();
        List<String> pal = new ArrayList<>();
        Map<Long, short[]> m = new HashMap<>();
        int alto = CuevaGen.MAX_Y - CuevaGen.MIN_Y + 1;
        Style.info(s, "Sacando la foto de la cueva (unos segundos)...");
        porChunks((cx, cz) -> {
            ChunkSnapshot sn = mundo.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
            short[] a = new short[alto * 256];
            for (int i = 0; i < a.length; i++) {
                int yy = i / 256 + CuevaGen.MIN_Y, lz = (i % 256) / 16, lx = i % 16;
                BlockData bd = sn.getBlockData(lx, yy, lz);
                // los cofres del agujero no van en la foto (se ponen nuevos cada vez)
                String k = bd.getMaterial() == Material.CHEST && cofresPuestos.contains(cx * 16 + lx + "," + yy + "," + (cz * 16 + lz)) ? "minecraft:air" : bd.getAsString();
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
                Style.err(s, "No pude guardar la foto: " + ex.getMessage());
                return;
            }
            foto = null;
            cargarFoto();
            Style.ok(s, "Foto guardada (" + pal.size() + " tipos de bloque). Cada reconstrucción deja la cueva así.");
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
            for (String l : d.getStringList("cofres_extra." + tipo)) {
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
                    List<ItemStack> items = loot.tirar(lineas, "agujero " + tipo);
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
        Location b = bloqueEntrada();
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
            case CERRADO -> "&7Será reconstruida en &f" + mmss(Math.max(0, cerradoHasta - ahora));
            case RECONSTRUYENDO -> "&7Reconstruyendo...";
        };
        cartel.text(Txt.c("&4&lAGUJERO DEL INFIERNO 2.0\n" + linea));
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
        // si quedo adentro de una corrida que ya termino (se desconecto), sale
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || !enMundo(p) || !jugando(p) || p.hasPermission("tod4.admin")) return;
            if (estado != Estado.CORRIENDO && estado != Estado.TERMINANDO) sacar(p, false);
        }, 20L);
    }

    // ================================================================= comando

    void comando(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "estado";
        Player p = s instanceof Player pp ? pp : null;
        switch (m) {
            case "crear" -> {
                if (mundo != null) { Style.info(s, "La cueva ya existe. &e/tod4:agujero tp &7para ir."); return; }
                Style.info(s, "Creando el mundo de la cueva...");
                World w = cargarMundo(true);
                if (w == null) { Style.err(s, "No pude crear el mundo."); return; }
                Style.ok(s, "Cueva creada en el mundo &f" + w.getName() + "&a. Andá con &e/tod4:agujero tp&a.");
                Bukkit.getScheduler().runTaskLater(pl, () -> { cofres(); Style.ok(s, "Cofres puestos."); }, 40L);
            }
            case "tp" -> {
                if (p == null || mundo == null) { Style.err(s, "Primero &e/tod4:agujero crear&c (y desde el juego)."); return; }
                p.teleport(entradaAdentro(mundo));
            }
            case "entrada" -> {
                if (p == null) return;
                Block b = p.getTargetBlockExact(6);
                if (b == null) { Style.err(s, "Mirá el bloque que tiene que mandar a la cueva (a menos de 6 bloques)."); return; }
                d.set("entrada", b.getLocation());
                guardar();
                if (cartel != null) { cartel.remove(); cartel = null; }
                Style.ok(s, "Entrada: &f" + b.getType().name().toLowerCase() + " &aen " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + "&a. Pisarlo o tocarlo manda a la cueva.");
            }
            case "salida" -> {
                if (p == null) return;
                d.set("salida", p.getLocation());
                guardar();
                Style.ok(s, "Salida: los que escapan (o terminan) aparecen acá.");
            }
            case "foto", "guardar" -> sacarFoto(s);
            case "reconstruir" -> { Style.info(s, "Reconstruyendo..."); estado = estado == Estado.RECONSTRUYENDO ? estado : Estado.CERRADO; reconstruir(s); }
            case "abrir" -> { cerradoHasta = 0; reconstruir(s); }
            case "cerrar" -> {
                double min = a.length > 1 ? parse(a[1], 30) : y.getDouble("cerrado_min", 30);
                if (estado == Estado.CORRIENDO || estado == Estado.TERMINANDO) { Style.err(s, "Hay alguien adentro: esperá a que termine."); return; }
                estado = Estado.CERRADO;
                cerradoHasta = System.currentTimeMillis() + (long) (min * 60000);
                guardar();
                Style.ok(s, "Cerrada por &f" + mmss(cerradoHasta - System.currentTimeMillis()) + "&a.");
            }
            case "terminar" -> {
                if (estado != Estado.CORRIENDO && estado != Estado.TERMINANDO) { Style.err(s, "No hay ninguna corrida."); return; }
                inicio = System.currentTimeMillis() - (long) (duracion() * 1000) - 10000;
                estado = Estado.TERMINANDO;
                Style.ok(s, "Terminada: a los de adentro les empieza el daño.");
            }
            case "cofre" -> {
                if (p == null || a.length < 2 || !(a[1].equalsIgnoreCase("facil") || a[1].equalsIgnoreCase("dificil"))) {
                    Style.info(s, "Uso: &e/tod4:agujero cofre <facil|dificil> &7(parado donde puede ir un cofre)"); return;
                }
                if (!enMundo(p)) { Style.err(s, "Usalo adentro de la cueva."); return; }
                String t = a[1].toLowerCase();
                List<String> l = d.getStringList("cofres_extra." + t);
                Location lo = p.getLocation();
                l.add(lo.getBlockX() + "," + lo.getBlockY() + "," + lo.getBlockZ());
                d.set("cofres_extra." + t, l);
                guardar();
                Style.ok(s, "Lugar de cofre &f" + t + " &aagregado (" + l.size() + " agregados a mano).");
            }
            case "quitarcofre" -> {
                if (p == null || !enMundo(p)) { Style.err(s, "Usalo adentro de la cueva, al lado del lugar."); return; }
                for (String t : new String[]{"facil", "dificil"}) {
                    List<String> l = d.getStringList("cofres_extra." + t);
                    if (l.removeIf(x -> { String[] q = x.split(","); return p.getLocation().distance(new Location(mundo, Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]))) < 3; })) {
                        d.set("cofres_extra." + t, l);
                        guardar();
                        Style.ok(s, "Lugar de cofre quitado.");
                        return;
                    }
                }
                Style.err(s, "No hay ningún lugar agregado a mano a menos de 3 bloques.");
            }
            case "espera" -> {
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:agujero espera <jugador> &7(le saca la hora de espera)"); return; }
                org.bukkit.OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (o == null) { Style.err(s, "No encuentro a " + a[1] + "."); return; }
                d.set("ultima." + o.getUniqueId(), null);
                guardar();
                Style.ok(s, o.getName() + " ya puede volver a entrar.");
            }
            default -> {
                Style.info(s, "Agujero del Infierno 2.0: &f" + estado + (estado == Estado.CERRADO ? " &7(se reconstruye en " + mmss(cerradoHasta - System.currentTimeMillis()) + ")" : "")
                        + " &7| mundo: &f" + (mundo == null ? "sin crear" : mundo.getName()) + " &7| foto: &f" + (fotoF().exists() ? "sí" : "no (usa la cueva generada)"));
                Location e = bloqueEntrada();
                Style.info(s, "Entrada: &f" + (e == null ? "sin poner (&e/tod4:agujero entrada&f)" : e.getBlockX() + " " + e.getBlockY() + " " + e.getBlockZ())
                        + " &7| salida: &f" + (d.getLocation("salida") == null ? "sin poner (spawn)" : "puesta"));
                Style.info(s, "&e/tod4:agujero crear|tp|entrada|salida|foto|reconstruir|abrir|cerrar [min]|terminar|cofre <facil|dificil>|quitarcofre|espera <jugador>");
            }
        }
    }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }

    List<String> subcomandos() {
        return List.of("crear", "tp", "entrada", "salida", "foto", "reconstruir", "abrir", "cerrar", "terminar", "cofre", "quitarcofre", "espera", "estado");
    }
}
