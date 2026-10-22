package ar.tod4.eventos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Invasion Goblin: como una raid de Minecraft, con mobs de MythicMobs.
 *  - Empieza con una chance baja al tradear con un aldeano (no los de Shopkeepers) o seguro al pegarle al "Aldeano Goblin".
 *  - Barra arriba "Invasion Goblin" (texto blanco, barra azul): entre oleadas se llena sola; durante la oleada muestra la vida
 *    que les queda a los goblins; con pocos dice "Goblins restantes: N". Al final "Victoria" o "Derrota".
 *  - Cuerno de raid en cada oleada. Los goblins no se queman con el sol y van a por el jugador mas cercano.
 */
final class Goblins implements Listener {

    private enum Estado { ESPERA, OLEADA, FIN }

    private final TOD4Eventos pl;
    private final NamespacedKey K_GOBLIN;
    private final Store st;

    // ---- raid en curso (una a la vez)
    private Estado estado;
    private Location centro;
    private int oleada, totalOleadas, tick, sinJugadores;
    private Location punto;
    private final Set<UUID> mobs = new HashSet<>();
    private double vidaTotal;
    private final Set<UUID> jugadores = new HashSet<>();
    private BossBar barra;
    private boolean ganada;

    Goblins(TOD4Eventos pl) {
        this.pl = pl;
        this.K_GOBLIN = new NamespacedKey(pl, "goblin_raid");
        this.st = new Store(pl, "goblins");
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaSegundo, 20L, 20L);
        Bukkit.getScheduler().runTask(pl, this::borrarSueltos);
    }

    private FileConfiguration cfg() { return pl.getConfig(); }
    private int seg(String k, int d) { return cfg().getInt("goblins." + k, d); }

    boolean activa() { return estado != null; }

    // ================================================================= empezar / terminar

    boolean empezar(Location c) {
        if (activa()) return false;
        estado = Estado.ESPERA;
        centro = c.clone();
        oleada = 0;
        totalOleadas = Math.max(1, cfg().getStringList("goblins.oleadas").size());
        tick = seg("espera_inicio_seg", 15);
        sinJugadores = 0;
        ganada = false;
        mobs.clear();
        jugadores.clear();
        barra = BossBar.bossBar(titulo(null), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        actualizarJugadores();
        sonar("event.raid.horn", centro, 1f);
        return true;
    }

    private void terminar(boolean victoria) {
        estado = Estado.FIN;
        ganada = victoria;
        tick = 6;
        st.set("cooldown_hasta", System.currentTimeMillis() + seg("cooldown_seg", 1800) * 1000L);
        st.save();
        barra.name(titulo(victoria ? "Victoria" : "Derrota"));
        barra.progress(victoria ? 1f : 0f);
        if (victoria) {
            for (Player p : participantes()) {
                p.playSound(p.getLocation(), "ui.toast.challenge_complete", SoundCategory.MASTER, 1f, 1f);
                for (String cmd : cfg().getStringList("goblins.recompensas"))
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        } else {
            borrarMobs();
        }
    }

    void detener() {
        if (barra != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(barra);
        borrarMobs();
        estado = null;
        barra = null;
        jugadores.clear();
    }

    void apagar() { detener(); }

    private Component titulo(String extra) {
        String t = cfg().getString("goblins.titulo", "Invasión Goblin");
        return Component.text(extra == null ? t : t + " - " + extra, NamedTextColor.WHITE);
    }

    // ================================================================= bucle

    private void cadaSegundo() {
        if (!activa()) return;
        actualizarJugadores();
        if (estado == Estado.FIN) {
            if (--tick <= 0) detener();
            return;
        }
        // sin nadie cerca durante un minuto: derrota
        if (jugadores.isEmpty()) {
            if (++sinJugadores >= 60) terminar(false);
            return;
        }
        sinJugadores = 0;
        if (estado == Estado.ESPERA) {
            int total = oleada == 0 ? seg("espera_inicio_seg", 15) : seg("espera_entre_seg", 15);
            tick--;
            barra.name(titulo(null));
            barra.progress(clamp(1f - tick / (float) Math.max(1, total)));
            if (tick <= 0) empezarOleada();
            return;
        }
        // oleada
        tick++;
        List<LivingEntity> vivos = vivos();
        if (vivos.isEmpty()) {
            if (tick >= 3) {
                if (oleada >= totalOleadas) terminar(true);
                else { estado = Estado.ESPERA; tick = seg("espera_entre_seg", 15); barra.progress(0f); }
            }
            return;
        }
        double vida = 0;
        for (LivingEntity e : vivos) vida += e.getHealth();
        barra.progress(clamp((float) (vida / Math.max(1, vidaTotal))));
        int pocos = seg("pocos_restantes", 2);
        if (vivos.size() <= pocos) {
            barra.name(titulo("Goblins restantes: " + vivos.size()));
            for (LivingEntity e : vivos) e.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false));
        } else {
            barra.name(titulo(null));
        }
        if (tick % 2 == 0) perseguir(vivos);
        if (tick > seg("rezagados_seg", 180) && tick % 20 == 0) rescatar(vivos);
    }

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    private void empezarOleada() {
        oleada++;
        estado = Estado.OLEADA;
        tick = 0;
        mobs.clear();
        vidaTotal = 0;
        punto = puntoAparicion();
        int k = Math.max(1, jugadores.size());
        double mult = Math.min(cfg().getDouble("goblins.mult_max", 2.5), 1 + cfg().getDouble("goblins.mult_por_jugador", 0.35) * (k - 1));
        List<String> ol = cfg().getStringList("goblins.oleadas");
        if (ol.isEmpty()) { terminar(false); return; }
        String linea = ol.get(Math.min(oleada, ol.size()) - 1);
        for (String parte : linea.split(",")) {
            String[] kv = parte.trim().split(":");
            if (kv.length < 2) continue;
            int n;
            try { n = Integer.parseInt(kv[1].trim()); } catch (NumberFormatException ex) { continue; }
            // los jefes (goblins.sin_multiplicar) salen siempre en la cantidad escrita
            boolean fijo = cfg().getStringList("goblins.sin_multiplicar").contains(kv[0].trim());
            int cant = fijo ? n : (int) Math.ceil(n * mult);
            for (int i = 0; i < cant; i++) aparecer(kv[0].trim());
        }
        for (LivingEntity e : vivos()) {
            AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
            vidaTotal += a != null ? a.getValue() : e.getHealth();
        }
        barra.progress(1f);
        // el cuerno suena desde donde vienen
        for (Player p : participantes()) {
            org.bukkit.util.Vector dir = punto.toVector().subtract(p.getLocation().toVector());
            Location desde = dir.lengthSquared() < 1 || p.getWorld() != punto.getWorld() ? p.getLocation() : p.getLocation().add(dir.normalize().multiply(16));
            p.playSound(desde, "event.raid.horn", SoundCategory.HOSTILE, 64f, 1f);
        }
        if (mobs.isEmpty()) {
            pl.getLogger().warning("Invasion Goblin: no aparecio ningun goblin (revisa los nombres de MythicMobs en goblins.oleadas).");
            terminar(false);
        }
    }

    // ================================================================= mobs

    private static Method SPAWN;
    private static Object HELPER;

    /** MythicMobs (por su id) o, si no existe, un mob de Minecraft (para probar: "PILLAGER", "ZOMBIE"...). */
    private void aparecer(String tipo) {
        Location l = suelo(punto.clone().add(ThreadLocalRandom.current().nextDouble(-4, 4), 0, ThreadLocalRandom.current().nextDouble(-4, 4)));
        Entity e = null;
        try {
            if (HELPER == null && Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
                Class<?> c = Class.forName("io.lumine.mythic.bukkit.BukkitAPIHelper");
                HELPER = c.getConstructor().newInstance();
                SPAWN = c.getMethod("spawnMythicMob", String.class, Location.class);
            }
            if (SPAWN != null) e = (Entity) SPAWN.invoke(HELPER, tipo, l);
        } catch (Throwable ex) {
            e = null;
        }
        if (e == null) {
            try { e = l.getWorld().spawnEntity(l, EntityType.valueOf(tipo.toUpperCase())); }
            catch (IllegalArgumentException ex) {
                pl.getLogger().warning("Invasion Goblin: no existe el mob '" + tipo + "' (ni en MythicMobs ni en Minecraft).");
                return;
            }
        }
        e.getPersistentDataContainer().set(K_GOBLIN, PersistentDataType.BYTE, (byte) 1);
        e.addScoreboardTag("gr_mob");
        if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
        mobs.add(e.getUniqueId());
    }

    private List<LivingEntity> vivos() {
        List<LivingEntity> out = new ArrayList<>();
        for (UUID u : mobs) {
            Entity e = Bukkit.getEntity(u);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) out.add(le);
        }
        return out;
    }

    private boolean esGoblin(Entity e) { return e.getPersistentDataContainer().has(K_GOBLIN, PersistentDataType.BYTE); }

    private void borrarMobs() {
        for (UUID u : mobs) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        mobs.clear();
        borrarSueltos();
    }

    /** Goblins que quedaron de una invasion anterior (por ejemplo, si se apago el server en el medio). */
    private void borrarSueltos() {
        if (activa() && estado != Estado.FIN) return;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (esGoblin(e)) e.remove();
    }

    private void perseguir(List<LivingEntity> vivos) {
        for (LivingEntity e : vivos) {
            if (!(e instanceof Mob m) || (m.getTarget() != null && m.getTarget().isValid())) continue;
            Player p = cercano(e.getLocation());
            if (p != null && p.getLocation().distanceSquared(e.getLocation()) < 90 * 90) m.setTarget(p);
        }
    }

    /** Los que quedaron trabados o muy lejos se acercan (como en las raids de Minecraft). */
    private void rescatar(List<LivingEntity> vivos) {
        for (LivingEntity e : vivos) {
            Player p = cercano(e.getLocation());
            if (p == null || p.getLocation().distanceSquared(e.getLocation()) < 40 * 40) continue;
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            e.teleport(suelo(p.getLocation().add(Math.cos(a) * 20, 0, Math.sin(a) * 20)));
        }
    }

    private Player cercano(Location l) {
        Player mejor = null;
        double d = Double.MAX_VALUE;
        for (Player p : participantes()) {
            if (p.getWorld() != l.getWorld()) continue;
            double x = p.getLocation().distanceSquared(l);
            if (x < d) { d = x; mejor = p; }
        }
        return mejor;
    }

    // ================================================================= lugares

    /** Un punto en un anillo alrededor del centro, sin agua ni copas de arboles. */
    private Location puntoAparicion() {
        double min = cfg().getDouble("goblins.distancia_min", 24), max = cfg().getDouble("goblins.distancia_max", 32);
        for (int i = 0; i < 20; i++) {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = ThreadLocalRandom.current().nextDouble(min, max);
            Location l = suelo(centro.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            Block abajo = l.getBlock().getRelative(0, -1, 0);
            if (!l.getBlock().isLiquid() && !abajo.isLiquid() && !abajo.getType().name().endsWith("LEAVES")) return l;
        }
        return suelo(centro.clone().add(min, 0, 0));
    }

    /** El piso mas cercano a la altura del lugar (buscando de arriba hacia abajo, sin hojas). */
    private static Location suelo(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY() + 20; y > l.getBlockY() - 40 && y > w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && !b.getType().name().endsWith("LEAVES")
                    && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable())
                return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    // ================================================================= jugadores

    private void actualizarJugadores() {
        double r = cfg().getDouble("goblins.radio", 96);
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean dentro = p.getWorld() == centro.getWorld() && p.getLocation().distanceSquared(centro) <= r * r
                    && p.getGameMode() != GameMode.SPECTATOR && !p.isDead();
            if (dentro) { ahora.add(p.getUniqueId()); p.showBossBar(barra); }
            else if (jugadores.contains(p.getUniqueId())) p.hideBossBar(barra);
        }
        jugadores.clear();
        jugadores.addAll(ahora);
    }

    private List<Player> participantes() {
        List<Player> out = new ArrayList<>();
        for (UUID u : jugadores) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    private void sonar(String s, Location l, float tono) {
        for (Player p : participantes()) p.playSound(l, s, SoundCategory.HOSTILE, 64f, tono);
    }

    // ================================================================= disparadores

    private boolean enCooldown() { return System.currentTimeMillis() < st.y().getLong("cooldown_hasta", 0); }

    /** Los vendedores de Shopkeepers (la tienda) no disparan nada. */
    private static boolean esTienda(Entity e) {
        return e.hasMetadata("shopkeeper") || e.getScoreboardTags().contains("shopkeeper");
    }

    private boolean esAldeanoGoblin(Entity e) {
        String n = e.customName() == null ? "" : PlainTextComponentSerializer.plainText().serialize(e.customName());
        return n.contains(cfg().getString("goblins.aldeano_goblin", "Aldeano Goblin"));
    }

    @EventHandler(ignoreCancelled = true)
    public void tradea(PlayerTradeEvent e) {
        if (activa() || enCooldown() || esTienda(e.getVillager())) return;
        if (cfg().getBoolean("goblins.solo_aldeano_goblin", false) && !esAldeanoGoblin(e.getVillager())) return;
        if (ThreadLocalRandom.current().nextDouble() < cfg().getDouble("goblins.probabilidad", 0.03)) empezar(e.getVillager().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void lePegan(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Villager v) || !esAldeanoGoblin(v) || esTienda(v)) return;
        Entity d = e.getDamager();
        boolean jugador = d instanceof Player || (d instanceof Projectile pr && pr.getShooter() instanceof Player);
        if (!jugador || activa() || enCooldown()) return;
        empezar(v.getLocation());
    }

    // ---- durante la invasion, los que estan en ella no pueden tradear
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void abreAldeano(PlayerInteractEntityEvent e) {
        if (!activa() || estado == Estado.FIN || !(e.getRightClicked() instanceof Villager v) || esTienda(v)) return;
        if (!jugadores.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(TOD4Eventos.c("&c¡No es momento de comerciar, hay una invasión goblin!"));
    }

    @EventHandler(ignoreCancelled = true)
    public void abreMenu(InventoryOpenEvent e) {
        if (!activa() || estado == Estado.FIN || e.getInventory().getType() != InventoryType.MERCHANT) return;
        if (e.getInventory().getHolder() instanceof Entity en && esTienda(en)) return;
        if (e.getPlayer() instanceof Player p && jugadores.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ---- los goblins no se queman con el sol (por eso antes morian todos al empezar)
    @EventHandler(ignoreCancelled = true)
    public void sol(EntityCombustEvent e) {
        if (e instanceof EntityCombustByEntityEvent || e instanceof EntityCombustByBlockEvent) return;
        if (esGoblin(e.getEntity())) e.setCancelled(true);
    }

    // ================================================================= comando /tod4eventos:goblins

    void comando(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "iniciar" -> {
                if (!(s instanceof Player p)) { TOD4Eventos.err(s, "Solo en el juego."); return; }
                if (empezar(p.getLocation())) TOD4Eventos.ok(s, "Invasión Goblin iniciada en tu posición.");
                else TOD4Eventos.err(s, "Ya hay una invasión en curso.");
            }
            case "detener" -> { detener(); TOD4Eventos.ok(s, "Invasión detenida."); }
            case "reset" -> { st.set("cooldown_hasta", 0L); st.save(); TOD4Eventos.ok(s, "Espera entre invasiones reiniciada."); }
            case "estado" -> {
                if (!activa()) {
                    long r = (st.y().getLong("cooldown_hasta", 0) - System.currentTimeMillis()) / 1000;
                    TOD4Eventos.info(s, "No hay invasión." + (r > 0 ? " Próxima posible en &f" + (r / 60) + " min&7." : ""));
                } else TOD4Eventos.info(s, "Estado: &f" + estado.name().toLowerCase() + " &7| Oleada &f" + oleada + "/" + totalOleadas
                        + " &7| Goblins: &f" + vivos().size() + " &7| Jugadores: &f" + jugadores.size());
            }
            default -> {
                TOD4Eventos.info(s, "&2Invasión Goblin:");
                TOD4Eventos.raw(s, " &e/tod4eventos:goblins iniciar &8- &7empieza en tu posición");
                TOD4Eventos.raw(s, " &e/tod4eventos:goblins detener &7/ &eestado &7/ &ereset &8(reset = sin espera)");
            }
        }
    }

    List<String> tab(String[] a) {
        return a.length == 1 ? new ArrayList<>(List.of("iniciar", "detener", "estado", "reset")) : new ArrayList<>();
    }
}
