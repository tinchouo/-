package ar.tod4.aether;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ar.tod4.TOD4;
import ar.tod4.aether.Estado.Puente;
import ar.tod4.aether.Estado.Trabajo;
import ar.tod4.aether.Piezas.Pieza;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.title.Title;

/**
 * El Aether (antes aether.sk): mapa de islas que se va armando a medida que los jugadores exploran,
 * pocion para entrar, caida al vacio para volver, musica en loop, animales y viento.
 * Las piezas (.nbt) siguen en el datapack aether_v7 y la dimension/biomas en aether_dimension.
 */
public final class Aether implements Listener {

    private static final String[] ESPECIALES = {"village", "windmill", "coliseum", "mushroom_island"};

    private final TOD4 pl;
    private final File archivo;
    private final Gson gson = new GsonBuilder().create();
    private final Random rnd = new Random();
    private Conf k;
    private Estado e;
    private Plan plan;

    private final Map<String, Structure> estructuras = new HashMap<>();
    private boolean ocupado;
    private long ocupadoDesde;
    private boolean guardarPendiente;

    private final Map<UUID, Long> sinCaida = new HashMap<>();
    private final Map<UUID, Long> musica = new HashMap<>();
    private final Map<UUID, Long> aviso = new HashMap<>();
    private final Set<UUID> condenados = new HashSet<>();
    private int tick;
    private int fillRestantes;

    public Aether(TOD4 pl) {
        this.pl = pl;
        this.archivo = new File(pl.getDataFolder(), "aether_estado.json.gz");
        Pocion.init(pl);
        recargar();
        e = cargar();
        nuevoPlan();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaTick, 40L, 1L);
        World w = mundo();
        if (e.listo && w != null) pl.getLogger().info("Aether listo en el mundo " + w.getName() + ".");
    }

    public void recargar() {
        File f = new File(pl.getDataFolder(), "aether.yml");
        if (!f.exists()) pl.saveResource("aether.yml", false);
        k = Conf.de(YamlConfiguration.loadConfiguration(f));
        if (e != null) nuevoPlan();
    }

    private void nuevoPlan() {
        plan = new Plan(k, e, this::cerca, m -> pl.getLogger().info("[Aether] " + m));
    }

    // ================================================================= mundo

    /** El mundo del Aether: el que se ligo con /tod4:aether_init, el de aether.yml, o la dimension aether:aether. */
    public World mundo() {
        if (e != null && e.mundo != null) {
            World w = Bukkit.getWorld(e.mundo);
            if (w != null) return w;
        }
        if (k.mundo != null && !k.mundo.isEmpty()) {
            World w = Bukkit.getWorld(k.mundo);
            if (w != null) return w;
        }
        NamespacedKey key = new NamespacedKey("aether", "aether");
        for (World w : Bukkit.getWorlds()) if (key.equals(w.getKey())) return w;
        for (World w : Bukkit.getWorlds()) if (w.getName().toLowerCase().contains("aether")) return w;
        return null;
    }

    private World principal() {
        World w = Bukkit.getWorld(k.mundoPrincipal);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public boolean enAether(Player p) {
        World w = mundo();
        return w != null && p.getWorld().equals(w);
    }

    private boolean cerca(int x, int z) {
        World w = mundo();
        if (w == null) return false;
        double r2 = (double) k.radioExpansion * k.radioExpansion;
        for (Player p : w.getPlayers()) {
            double dx = x - p.getLocation().getX(), dz = z - p.getLocation().getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    private Location spawn() {
        World w = mundo();
        if (w == null || e.spawn == null) return null;
        return new Location(w, e.spawn[0], e.spawn[1], e.spawn[2]);
    }

    // ================================================================= tareas

    private void cadaTick() {
        tick++;
        if (tick % 2 == 0) procesarCola();
        if (tick % 3 == 0) vacio();
        if (tick % 4 == 0) entrada();
        if (tick % 5 == 0) musica();
        if (tick % 20 == 0 && e.listo && !e.borrando && mundo() != null) {
            int antes = e.cola.size();
            plan.crecer(k.expansionesPorSeg);
            if (e.cola.size() != antes) guardarPendiente = true;
        }
        if (tick % 5 == 0 && fillRestantes > 0) fill();
        if (tick % 40 == 0 && k.viento) {
            World w = mundo();
            if (w != null) for (Player p : w.getPlayers()) if (rnd.nextInt(100) < 40) Vida.viento(p, rnd);
        }
        if (tick % 6000 == 0 && guardarPendiente) guardar();
    }

    // ================================================================= cola de piezas

    private Structure estructura(String n) {
        Structure s = estructuras.get(n);
        if (s == null) {
            try {
                NamespacedKey key = new NamespacedKey("aether", n);
                s = Bukkit.getStructureManager().getStructure(key);
                if (s == null) s = Bukkit.getStructureManager().loadStructure(key);
            } catch (Exception ex) {
                s = null;
            }
            if (s != null) estructuras.put(n, s);
        }
        return s;
    }

    private void procesarCola() {
        if (ocupado) {
            if (System.currentTimeMillis() - ocupadoDesde < 120_000) return;
            ocupado = false;
        }
        if (e.cola.isEmpty()) return;
        World w = mundo();
        if (w == null) return;
        Trabajo j = e.cola.get(0);
        ocupado = true;
        ocupadoDesde = System.currentTimeMillis();
        switch (j.tipo) {
            case "E" -> borrarCaja(w, j);
            case "R" -> finBorrado(j);
            default -> pegar(w, j);
        }
    }

    private void listo(Trabajo j) {
        e.cola.remove(j);
        ocupado = false;
        guardarPendiente = true;
    }

    private List<int[]> chunks(Trabajo j) {
        List<int[]> l = new ArrayList<>();
        for (int cx = j.x0 >> 4; cx <= j.x1 >> 4; cx++)
            for (int cz = j.z0 >> 4; cz <= j.z1 >> 4; cz++) l.add(new int[]{cx, cz});
        return l;
    }

    /** Carga los chunks sin trabar el server, pega la pieza y suelta los chunks. */
    private void pegar(World w, Trabajo j) {
        List<int[]> cs = chunks(j);
        List<CompletableFuture<Chunk>> fs = new ArrayList<>();
        for (int[] c : cs) fs.add(w.getChunkAtAsync(c[0], c[1], true));
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(pl, () -> {
                    for (int[] c : cs) w.addPluginChunkTicket(c[0], c[1], pl);
                    try {
                        colocar(w, j);
                    } catch (Exception err) {
                        pl.getLogger().warning("[Aether] No pude pegar " + j.n + ": " + err);
                        listo(j);
                    }
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        for (int[] c : cs) w.removePluginChunkTicket(c[0], c[1], pl);
                    }, 60L);
                }));
    }

    private void colocar(World w, Trabajo j) {
        Structure s = estructura(j.n);
        if (s == null) {
            pl.getLogger().warning("[Aether] Falta la pieza aether:" + j.n + " (esta el datapack aether_v7 en world/datapacks?)");
            listo(j);
            return;
        }
        StructureRotation rot = j.rot == null ? StructureRotation.NONE : StructureRotation.valueOf(j.rot);
        int px = j.x0, pz = j.z0;
        if (rot == StructureRotation.CLOCKWISE_90) px = j.x1;
        if (rot == StructureRotation.CLOCKWISE_180) { px = j.x1; pz = j.z1; }
        if (rot == StructureRotation.COUNTERCLOCKWISE_90) pz = j.z1;
        Location at = new Location(w, px, j.y0, pz);
        s.place(at, false, rot, Mirror.NONE, 0, 1f, rnd);
        if (Piezas.esPuente(j.n)) {
            e.puentes.add(new Puente(j.n, j.x0, j.y0, j.z0));
            e.hechos.add(new int[]{j.x0, j.y0, j.z0, j.x1, j.y0 + 17, j.z1});
            listo(j);
            return;
        }
        Pieza pz2 = Piezas.get(j.n);
        int mx = Math.floorDiv(j.x0 + j.x1, 2), mz = Math.floorDiv(j.z0 + j.z1, 2);
        if (pz2 != null && !hayBloques(w, mx, mz, j.y0 + pz2.ylo(), j.y0 + pz2.yhi())) {
            // a veces la pieza no aparece entera la primera vez: se vuelve a pegar una vez
            pl.getLogger().info("[Aether] La pieza " + j.n + " no aparecio en (" + j.x0 + ", " + j.y0 + ", " + j.z0 + "); reintentando.");
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                s.place(at, false, rot, Mirror.NONE, 0, 1f, rnd);
                terminarIsla(w, j, pz2);
            }, 20L);
            return;
        }
        terminarIsla(w, j, pz2);
    }

    private boolean hayBloques(World w, int x, int z, int y0, int y1) {
        for (int y = y0; y <= y1; y += 2) if (!w.getBlockAt(x, y, z).getType().isAir()) return true;
        return false;
    }

    private void terminarIsla(World w, Trabajo j, Pieza p) {
        int ylo = p == null ? 0 : p.ylo(), yhi = p == null ? 17 : p.yhi();
        // bioma (nombre en el F3, color del cielo y particulas)
        int fy0 = Math.max(w.getMinHeight(), j.y0 + ylo - 8), fy1 = Math.min(w.getMaxHeight() - 1, j.y0 + yhi + 40);
        consola("execute in " + w.getKey().asString() + " run fillbiome " + j.x0 + " " + fy0 + " " + j.z0 + " "
                + j.x1 + " " + fy1 + " " + j.z1 + " aether:" + Piezas.bioma(j.n));
        if (j.n.equals("windmill")) {
            int[] f = Piezas.MARCO_WINDMILL;
            consola("execute in " + w.getKey().asString() + " run summon minecraft:glow_item_frame " + (j.x0 + f[0]) + " "
                    + (j.y0 + f[1]) + " " + (j.z0 + f[2]) + " {Facing:3b,Item:{id:\"minecraft:trident\",count:1,components:{"
                    + "\"minecraft:custom_name\":'{\"text\":\"Tridente del Aether\",\"color\":\"aqua\",\"italic\":false}',"
                    + "\"minecraft:custom_data\":{aether:1b}}}}");
        }
        if (k.animales && p != null) {
            int cx = Math.floorDiv(j.x0 + j.x1, 2), cz = Math.floorDiv(j.z0 + j.z1, 2);
            try {
                Vida.poblar(w, j.n, cx, j.y0 + Piezas.DECK, cz, p.rax(), rnd);
            } catch (Exception ex) {
                pl.getLogger().warning("[Aether] Animales: " + ex);
            }
        }
        e.hechos.add(new int[]{j.x0, j.y0 + ylo, j.z0, j.x1, j.y0 + yhi, j.z1});
        listo(j);
    }

    private void consola(String cmd) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception ex) {
            pl.getLogger().warning("[Aether] Fallo el comando: " + cmd);
        }
    }

    // ================================================================= borrar

    private void borrarCaja(World w, Trabajo j) { borrarChunk(w, j, chunks(j), 0); }

    private void borrarChunk(World w, Trabajo j, List<int[]> cs, int i) {
        if (i >= cs.size()) { listo(j); return; }
        int[] c = cs.get(i);
        ocupadoDesde = System.currentTimeMillis();
        w.getChunkAtAsync(c[0], c[1], true).whenComplete((ch, ex) -> Bukkit.getScheduler().runTask(pl, () -> {
            int ax0 = Math.max(j.x0, c[0] << 4), ax1 = Math.min(j.x1, (c[0] << 4) + 15);
            int az0 = Math.max(j.z0, c[1] << 4), az1 = Math.min(j.z1, (c[1] << 4) + 15);
            int y0 = Math.max(w.getMinHeight(), j.y0), y1 = Math.min(w.getMaxHeight() - 1, j.y1);
            for (int x = ax0; x <= ax1; x++)
                for (int z = az0; z <= az1; z++)
                    for (int y = y0; y <= y1; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            BoundingBox bb = new BoundingBox(ax0, y0 - 2, az0, ax1 + 1, y1 + 3, az1 + 1);
            for (Entity en : w.getNearbyEntities(bb)) if (!(en instanceof Player)) en.remove();
            Bukkit.getScheduler().runTaskLater(pl, () -> borrarChunk(w, j, cs, i + 1), 1L);
        }));
    }

    private void finBorrado(Trabajo j) {
        String mundo = e.mundo;
        e.cola.clear();
        ocupado = false;
        Estado nuevo = new Estado();
        nuevo.mundo = mundo;
        e = nuevo;
        nuevoPlan();
        boolean reiniciar = "init".equals(j.n);
        if (reiniciar) plan.iniciar(new double[]{0.5, k.baseY + 1, 0.5});
        guardar();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPermission("tod4.admin"))
                Style.ok(p, reiniciar ? "Aether borrado y reiniciado. Cuando aparezca el spawn usa &e/tod4:aether_tp&a."
                        : "Aether borrado. Para empezar de nuevo: &e/tod4:aether_init&a.");
    }

    // ================================================================= entrar y salir

    /** Cualquier jugador del Overworld que llega a Y >= entrar_y aparece en el spawn del Aether, y se le saca la levitacion. */
    private void entrada() {
        World ow = principal();
        for (Player p : ow.getPlayers()) {
            if (p.getLocation().getY() < k.entrarY) continue;
            Location sp = spawn();
            if (!e.listo || sp == null || mundo() == null || mundo().equals(ow)) {
                Long a = aviso.get(p.getUniqueId());
                if (a == null || System.currentTimeMillis() - a > 15_000) {
                    aviso.put(p.getUniqueId(), System.currentTimeMillis());
                    Style.err(p, "El Aether todavía no está listo. Avísale a un admin.");
                }
                continue;
            }
            sinCaida.put(p.getUniqueId(), System.currentTimeMillis());
            p.removePotionEffect(PotionEffectType.LEVITATION);
            sp.setYaw(p.getLocation().getYaw());
            p.teleport(sp);
            p.removePotionEffect(PotionEffectType.LEVITATION);
            p.setFallDistance(0);
            p.setVelocity(new Vector());
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
            p.showTitle(Title.title(Txt.c("&b&lAether"), Txt.c("&7Bienvenido a las islas del cielo"),
                    Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(1))));
        }
    }

    /** Caer al vacio del Aether te devuelve al cielo del Overworld (caes normal, sin dano). */
    private void vacio() {
        World w = mundo();
        if (w == null || e.borrando) return;
        for (Player p : w.getPlayers()) {
            double y = p.getLocation().getY();
            if (condenados.contains(p.getUniqueId())) {
                if (y > w.getMaxHeight() - 8) p.damage(1_000_000, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
                continue;
            }
            if (y < k.volverY) volver(p);
        }
    }

    private void volver(Player p) {
        sinCaida.put(p.getUniqueId(), System.currentTimeMillis());
        condenados.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.LEVITATION);
        p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        World ow = principal();
        Location s = ow.getSpawnLocation();
        p.teleport(new Location(ow, s.getBlockX() + 0.5, k.volverCieloY, s.getBlockZ() + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch()));
        p.setFallDistance(0);
        p.setVelocity(new Vector());
        p.sendActionBar(Txt.c("&dCaíste del Aether..."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void dano(EntityDamageEvent ev) {
        if (!(ev.getEntity() instanceof Player p)) return;
        if (ev.getCause() == EntityDamageEvent.DamageCause.VOID && enAether(p) && !condenados.contains(p.getUniqueId())) {
            ev.setCancelled(true);
            volver(p);
            return;
        }
        if (ev.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Long t = sinCaida.get(p.getUniqueId());
            if (t != null && System.currentTimeMillis() - t < k.sinCaidaSeg * 1000L) ev.setCancelled(true);
        }
    }

    /** Tomarla dentro del Aether: sube hasta el techo del mundo y muere como en el vacio. */
    @EventHandler(ignoreCancelled = true)
    public void toma(PlayerItemConsumeEvent ev) {
        if (!Pocion.es(ev.getItem()) || !enAether(ev.getPlayer())) return;
        condenados.add(ev.getPlayer().getUniqueId());
        ev.getPlayer().sendActionBar(Txt.c("&cEl Aether rechaza la poción..."));
    }

    @EventHandler
    public void muere(PlayerDeathEvent ev) { condenados.remove(ev.getEntity().getUniqueId()); }

    @EventHandler
    public void sale(PlayerQuitEvent ev) {
        UUID u = ev.getPlayer().getUniqueId();
        condenados.remove(u);
        musica.remove(u);
        aviso.remove(u);
    }

    // ================================================================= musica

    /** Tu cancion en loop en todo el Aether (categoria master), sin la musica de Minecraft. */
    private void musica() {
        long ahora = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (enAether(p)) {
                p.stopSound(SoundCategory.MUSIC);
                Long t = musica.get(u);
                if (t == null) {
                    musica.put(u, ahora);
                    p.playSound(p, k.musicaIntro, SoundCategory.MASTER, 1f, 1f);
                } else if (ahora - t > (long) (k.musicaVuelve * 1000)) {
                    musica.put(u, ahora);
                    p.playSound(p, k.musica, SoundCategory.MASTER, 1f, 1f);
                }
            } else if (musica.remove(u) != null) {
                p.stopSound(k.musica, SoundCategory.MASTER);
                p.stopSound(k.musicaIntro, SoundCategory.MASTER);
            }
        }
    }

    // ================================================================= guardar

    private Estado cargar() {
        if (!archivo.exists()) return new Estado();
        try (Reader r = new InputStreamReader(new GZIPInputStream(new FileInputStream(archivo)), StandardCharsets.UTF_8)) {
            Estado x = gson.fromJson(r, Estado.class);
            return x == null ? new Estado() : x;
        } catch (Exception ex) {
            pl.getLogger().warning("[Aether] No pude leer " + archivo.getName() + ": " + ex.getMessage());
            return new Estado();
        }
    }

    public void guardar() {
        guardarPendiente = false;
        try {
            pl.getDataFolder().mkdirs();
            File tmp = new File(pl.getDataFolder(), "aether_estado.tmp");
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp)), StandardCharsets.UTF_8)) {
                gson.toJson(e, w);
            }
            if (archivo.exists()) archivo.delete();
            tmp.renameTo(archivo);
        } catch (Exception ex) {
            pl.getLogger().warning("[Aether] No pude guardar: " + ex.getMessage());
        }
    }

    public void apagar() {
        guardar();
        World w = mundo();
        if (w != null) for (Player p : w.getPlayers()) { p.stopSound(k.musica, SoundCategory.MASTER); p.stopSound(k.musicaIntro, SoundCategory.MASTER); }
    }

    // ================================================================= comandos (/tod4:aether_...)

    public List<String> nombres() { return List.of(ESPECIALES); }

    public boolean comando(CommandSender s, String c, String[] a) {
        Player p = s instanceof Player pp ? pp : null;
        switch (c) {
            case "aether_pocion" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
                if (t == null) { Style.err(s, a.length > 0 ? "Jugador no encontrado." : "Uso: &e/tod4:aether_pocion <jugador> [cantidad]"); return true; }
                int n = 1;
                if (a.length > 1) try { n = Math.max(1, Math.min(64, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
                ItemStack it = Pocion.crear(k.pocionNivel, k.pocionSeg);
                for (int i = 0; i < n; i++) for (ItemStack sob : t.getInventory().addItem(it.clone()).values()) t.getWorld().dropItem(t.getLocation(), sob);
                Style.ok(s, "Poción del Aether entregada a &f" + t.getName() + (n > 1 ? " &7x" + n : "") + "&a.");
                return true;
            }
            case "aether_init" -> { init(s, p); return true; }
            case "aether_reset" -> { reset(s, p, a); return true; }
            case "aether_tp" -> {
                if (p == null) return true;
                Location sp = spawn();
                if (!e.listo || sp == null) { Style.err(s, "Todavía no hay spawn. Usa &e/tod4:aether_init&c primero."); return true; }
                p.setGameMode(GameMode.CREATIVE);
                p.teleport(sp);
                return true;
            }
            case "aether_locate" -> {
                if (a.length == 0) { Style.info(s, "Estructuras: &fvillage, windmill, coliseum, mushroom_island&7. Uso: &e/tod4:aether_locate <nombre>"); return true; }
                List<int[]> l = e.loc.get(a[0].toLowerCase());
                if (l == null || l.isEmpty()) { Style.err(s, "Todavía no se generó ninguna &f" + a[0] + "&c. Explora para que aparezcan."); return true; }
                for (int i = 0; i < l.size(); i++) Style.raw(s, "&7" + a[0] + " #" + (i + 1) + ": &fX " + l.get(i)[0] + "  Y " + l.get(i)[1] + "  Z " + l.get(i)[2]);
                return true;
            }
            case "aether_goto" -> {
                if (p == null) return true;
                World w = mundo();
                if (w == null || !e.listo) { Style.err(s, "El Aether no está listo."); return true; }
                if (a.length == 0) { Style.info(s, "Uso: &e/tod4:aether_goto <nombre|spawn> [n]"); return true; }
                if (a[0].equalsIgnoreCase("spawn")) { p.teleport(spawn()); return true; }
                int n = 1;
                if (a.length > 1) try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) { }
                List<int[]> l = e.loc.get(a[0].toLowerCase());
                if (l == null || n < 1 || n > l.size()) { Style.err(s, "No existe &f" + a[0] + " #" + n + "&c. Usa &e/tod4:aether_locate&c."); return true; }
                int[] q = l.get(n - 1);
                p.setGameMode(GameMode.CREATIVE);
                p.teleport(new Location(w, q[0] + 0.5, q[1] + 40, q[2] + 0.5));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 400, 0));
                Style.ok(s, "Teletransportado a &f" + a[0] + " #" + n + "&a.");
                return true;
            }
            case "aether_fill" -> {
                if (!e.listo) { Style.err(s, "El Aether no está listo. Usa &e/tod4:aether_init&c."); return true; }
                int n = 50;
                if (a.length > 0) try { n = Integer.parseInt(a[0]); } catch (NumberFormatException ignored) { }
                fillRestantes = Math.max(1, Math.min(400, n));
                Style.ok(s, "Generando &f" + fillRestantes + "&a expansiones sin caminar. Puede tardar unos minutos.");
                return true;
            }
            case "aether_test" -> { test(s, p, a); return true; }
            case "aether_force" -> {
                if (a.length == 0 || !List.of(ESPECIALES).contains(a[0].toLowerCase())) {
                    Style.info(s, "Uso: &e/tod4:aether_force <village|windmill|coliseum|mushroom_island>");
                    return true;
                }
                e.force = a[0].toLowerCase();
                Style.ok(s, "La próxima rama con estructura cerca de un jugador termina en &f" + e.force + "&a. Después: &e/tod4:aether_goto " + e.force);
                return true;
            }
            case "aether_music" -> {
                if (p == null) return true;
                if (a.length > 0 && a[0].equalsIgnoreCase("stop")) {
                    p.stopSound(k.musica, SoundCategory.MASTER);
                    p.stopSound(k.musicaIntro, SoundCategory.MASTER);
                    Style.info(s, "Música detenida (si estás en el Aether vuelve sola).");
                } else {
                    p.playSound(p, k.musicaIntro, SoundCategory.MASTER, 1f, 1f);
                    Style.info(s, "Reproduciendo la prueba. Si no escuchas nada, falta el pack con la música.");
                }
                return true;
            }
            default -> { return false; }
        }
    }

    private void fill() {
        if (!e.listo || e.borrando) { fillRestantes = 0; return; }
        if (e.cola.size() >= k.colaMax) return;
        if (!plan.expandirAlguno()) {
            fillRestantes = 0;
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("tod4.admin")) Style.err(p, "Aether: no quedan puertos abiertos.");
            return;
        }
        guardarPendiente = true;
        if (--fillRestantes == 0)
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("tod4.admin"))
                Style.ok(p, "Aether: expansiones listas. Cuando termine la cola usa &e/tod4:aether_test&a.");
    }

    private void init(CommandSender s, Player p) {
        if (e.listo) { Style.err(s, "El Aether ya está iniciado. Para empezar de nuevo: &e/tod4:aether_reset"); return; }
        if (e.borrando) { Style.err(s, "Se está borrando. Espera a que termine."); return; }
        World w = null;
        if (p != null && !p.getWorld().equals(principal())) w = p.getWorld();
        if (w == null) w = mundo();
        if (w == null || w.equals(principal())) {
            Style.err(s, "No encuentro el mundo del Aether (la dimensión aether:aether). Entra a ese mundo y usa &e/tod4:aether_init&c.");
            return;
        }
        e.mundo = w.getName();
        w.setGameRule(GameRule.COMMAND_MODIFICATION_BLOCK_LIMIT, 50_000_000);
        nuevoPlan();
        plan.iniciar(new double[]{0.5, k.baseY + 1, 0.5});
        guardar();
        if (p != null && p.getWorld().equals(w)) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 600, 0));
        Style.ok(s, "Aether iniciado en el mundo &f" + w.getName() + "&a. El spawn y los 4 caminos se van pegando de a poco. Para ir: &e/tod4:aether_tp");
    }

    private void reset(CommandSender s, Player p, String[] a) {
        String modo = a.length > 0 ? a[0].toLowerCase() : "";
        if (modo.equals("status") || modo.equals("estado")) {
            World w = mundo();
            Style.raw(s, "&7Mundo: &f" + (w == null ? "-" : w.getName()) + " &7| iniciado: &f" + e.listo + " &7| borrando: &f" + e.borrando);
            Style.raw(s, "&7Puertos abiertos: &f" + e.puertos.size() + " &7| cola: &f" + e.cola.size() + " &7| piezas pegadas: &f" + e.hechos.size());
            Style.raw(s, "&7Caminos: &f" + e.ntr + " &7(4 principales + " + (e.ntr - 4) + " azules)");
            return;
        }
        if (modo.equals("forget") || modo.equals("olvidar")) {
            String mundo = e.mundo;
            e = new Estado();
            e.mundo = mundo;
            nuevoPlan();
            guardar();
            Style.ok(s, "Estado olvidado (los bloques NO se borraron). Para empezar: &e/tod4:aether_init");
            return;
        }
        if (!modo.isEmpty() && !modo.equals("clear") && !modo.equals("borrar")) {
            Style.info(s, "Uso: &e/tod4:aether_reset &7(borra todo y reinicia) &8| &eclear &7(solo borra) &8| &eforget &7(solo olvida) &8| &estatus");
            return;
        }
        World w = mundo();
        if (w == null || !e.listo) { Style.err(s, "El Aether todavía no fue iniciado."); return; }
        if (e.borrando) { Style.err(s, "Ya se está borrando. Espera a que termine."); return; }
        if (ocupado) { Style.err(s, "Se está pegando una pieza ahora mismo. Prueba de nuevo en unos segundos."); return; }
        World ow = principal();
        for (Player q : w.getPlayers()) if (q != p) q.teleport(ow.getSpawnLocation());
        if (p != null && p.getWorld().equals(w)) p.setGameMode(GameMode.SPECTATOR);
        e.listo = false;
        e.borrando = true;
        e.cola.clear();
        for (int[] b : e.hechos) {
            Trabajo t = new Trabajo();
            t.tipo = "E";
            t.x0 = b[0]; t.y0 = b[1]; t.z0 = b[2]; t.x1 = b[3]; t.y1 = b[4]; t.z1 = b[5];
            e.cola.add(t);
        }
        Trabajo r = new Trabajo();
        r.tipo = "R";
        r.n = modo.isEmpty() ? "init" : "stop";
        e.cola.add(r);
        guardar();
        Style.ok(s, "Borrando &f" + e.hechos.size() + "&a piezas... te aviso cuando termine.");
    }

    private void test(CommandSender s, Player p, String[] a) {
        if (p == null || !e.listo) { Style.err(s, "El Aether no está listo."); return; }
        World w = mundo();
        int r = 500;
        if (a.length > 0) try { r = Integer.parseInt(a[0]); } catch (NumberFormatException ignored) { }
        Location l = p.getLocation();
        int chk = 0, bad = 0;
        for (Puente b : e.puentes) {
            double dd = Math.hypot(b.x - l.getX(), b.z - l.getZ());
            if (dd > r) continue;
            String[] sp = b.n.split("_");
            int len = Integer.parseInt(sp[1]);
            int c = sp[0].startsWith("g") || sp[0].startsWith("t") ? 5 : 3;
            boolean ejeZ = sp[0].length() > 1 && sp[0].charAt(1) == 'z';
            int ex1 = b.x - 1, ez1 = b.z + c, ex2 = b.x + len, ez2 = ez1;
            if (ejeZ) { ex1 = b.x + c; ez1 = b.z - 1; ex2 = ex1; ez2 = b.z + len; }
            chk++;
            boolean ok1 = false, ok2 = false;
            for (int yy = b.y - 4; yy <= b.y + 14; yy++) {
                if (!w.getBlockAt(ex1, yy, ez1).getType().isAir()) ok1 = true;
                if (!w.getBlockAt(ex2, yy, ez2).getType().isAir()) ok2 = true;
            }
            if (!ok1 && ++bad <= 12) Style.raw(s, "&cPuente " + b.n + " sin isla al principio: X " + ex1 + " Y " + b.y + " Z " + ez1);
            if (!ok2 && ++bad <= 12) Style.raw(s, "&cPuente " + b.n + " sin isla al final: X " + ex2 + " Y " + b.y + " Z " + ez2);
        }
        Style.raw(s, "&ePuentes revisados (radio " + r + "): " + chk + " | extremos sin isla: " + bad);
        Style.raw(s, "&7Puertos abiertos: " + e.puertos.size() + " | cola: " + e.cola.size());
        for (String n : ESPECIALES) Style.raw(s, "&7" + n + ": " + (e.loc.containsKey(n) ? e.loc.get(n).size() : 0) + " generadas");
    }
}
