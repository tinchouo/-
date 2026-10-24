package ar.tod4.eventos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
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
/**
 * Invasion Goblin: como una raid de Minecraft, con mobs de MythicMobs.
 *  - Empieza con una chance baja al tradear con un aldeano (no los de Shopkeepers) o seguro al pegarle al "Aldeano Goblin".
 *  - Barra arriba "Invasion Goblin" (texto blanco, barra azul): entre oleadas se llena sola; durante la oleada muestra la vida
 *    que les queda a los goblins; con pocos dice "Goblins restantes: N". Al final "Victoria" o "Derrota".
 *  - Cuerno de raid en cada oleada. Los goblins no se queman con el sol y van a por el jugador mas cercano.
 *  - Puede haber varias a la vez en lugares distintos. Cada una deja su zona (goblins.zona bloques) en espera
 *    goblins.cooldown_seg segundos; fuera de esa zona puede empezar otra.
 */
final class Goblins implements Listener {

    private enum Estado { ESPERA, OLEADA, FIN }

    private final TOD4Eventos pl;
    private final NamespacedKey K_GOBLIN;
    private final Store st;
    private final List<Raid> raids = new ArrayList<>();

    Goblins(TOD4Eventos pl) {
        this.pl = pl;
        this.K_GOBLIN = new NamespacedKey(pl, "goblin_raid");
        this.st = new Store(pl, "goblins");
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { for (Raid r : new ArrayList<>(raids)) r.cadaSegundo(); }, 20L, 20L);
        Bukkit.getScheduler().runTask(pl, this::borrarSueltos);
    }

    private FileConfiguration cfg() { return pl.getConfig(); }
    private int seg(String k, int d) { return cfg().getInt("goblins." + k, d); }

    boolean activa() { return !raids.isEmpty(); }

    private Component titulo(String extra) {
        String t = cfg().getString("goblins.titulo", "Invasión Goblin");
        return Component.text(extra == null ? t : t + " - " + extra, NamedTextColor.WHITE);
    }

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    // ================================================================= zonas en espera

    private double zona() { return cfg().getDouble("goblins.zona", 50); }

    /** Zona en espera (o con una invasion en curso) cerca de este lugar. */
    private boolean zonaOcupada(Location l) {
        double z2 = zona() * zona();
        for (Raid r : raids) if (r.centro.getWorld() == l.getWorld() && r.centro.distanceSquared(l) <= z2) return true;
        long ahora = System.currentTimeMillis();
        List<Map<?, ?>> zs = st.y().getMapList("zonas");
        for (Map<?, ?> m : zs) {
            if (((Number) m.get("hasta")).longValue() < ahora) continue;
            if (!l.getWorld().getName().equals(m.get("mundo"))) continue;
            double dx = ((Number) m.get("x")).doubleValue() - l.getX(), dz = ((Number) m.get("z")).doubleValue() - l.getZ();
            if (dx * dx + dz * dz <= z2) return true;
        }
        return false;
    }

    private void guardarZona(Location c) {
        long ahora = System.currentTimeMillis();
        List<Map<String, Object>> nuevas = new ArrayList<>();
        for (Map<?, ?> m : st.y().getMapList("zonas")) {
            if (((Number) m.get("hasta")).longValue() < ahora) continue;   // las vencidas se tiran
            Map<String, Object> k = new HashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) k.put(String.valueOf(en.getKey()), en.getValue());
            nuevas.add(k);
        }
        Map<String, Object> z = new HashMap<>();
        z.put("mundo", c.getWorld().getName());
        z.put("x", c.getX());
        z.put("z", c.getZ());
        z.put("hasta", ahora + seg("cooldown_seg", 1200) * 1000L);
        nuevas.add(z);
        st.set("zonas", nuevas);
        st.save();
    }

    // ================================================================= una invasion

    boolean empezar(Location c) {
        if (zonaOcupada(c)) return false;
        raids.add(new Raid(c));
        return true;
    }

    void detener() {
        for (Raid r : new ArrayList<>(raids)) r.detener();
        borrarSueltos();
    }

    void apagar() { detener(); }

    private Raid raidDe(UUID mob) {
        for (Raid r : raids) if (r.mobs.contains(mob)) return r;
        return null;
    }

    private boolean enAlgunaRaid(Player p) {
        for (Raid r : raids) if (r.estado != Estado.FIN && r.jugadores.contains(p.getUniqueId())) return true;
        return false;
    }

    private final class Raid {
        Estado estado;
        final Location centro;
        int oleada, totalOleadas, tick, sinJugadores;
        Location punto;
        final Set<UUID> mobs = new HashSet<>();
        /** Ultima vez que se vio cargado cada goblin (los que estan en chunks descargados no cuentan como muertos). */
        final Map<UUID, Long> visto = new HashMap<>();
        double vidaTotal;
        final Set<UUID> jugadores = new HashSet<>();
        BossBar barra;

        Raid(Location c) {
            estado = Estado.ESPERA;
            centro = c.clone();
            oleada = 0;
            totalOleadas = Math.max(1, cfg().getStringList("goblins.oleadas").size());
            tick = seg("espera_inicio_seg", 15);
            barra = BossBar.bossBar(titulo(null), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            actualizarJugadores();
            sonar("event.raid.horn", centro, 1f);
        }

        void terminar(boolean victoria) {
            estado = Estado.FIN;
            tick = 6;
            guardarZona(centro);
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
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(barra);
            borrarMobs();
            raids.remove(this);
        }

        void cadaSegundo() {
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
            if (mobs.isEmpty()) {
                if (tick >= 3) {
                    if (oleada >= totalOleadas) terminar(true);
                    else { estado = Estado.ESPERA; tick = seg("espera_entre_seg", 15); barra.progress(0f); }
                }
                return;
            }
            if (vivos.isEmpty()) return; // quedan goblins, pero en chunks descargados: se espera
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

        void empezarOleada() {
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

        /** MythicMobs (por su id) o, si no existe, un mob de Minecraft (para probar: "PILLAGER", "ZOMBIE"...). */
        void aparecer(String tipo) {
            Location l = suelo(punto.clone().add(ThreadLocalRandom.current().nextDouble(-4, 4), 0, ThreadLocalRandom.current().nextDouble(-4, 4)));
            Entity e = spawn(tipo, l);
            if (e == null) return;
            e.getPersistentDataContainer().set(K_GOBLIN, PersistentDataType.BYTE, (byte) 1);
            e.addScoreboardTag("gr_mob");
            if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
            mobs.add(e.getUniqueId());
            visto.put(e.getUniqueId(), System.currentTimeMillis());
        }

        /** Los goblins cargados y vivos. Los muertos salen de la lista; los que no se encuentran (chunk descargado)
         *  siguen contando hasta 60 s sin verlos (despues se olvidan y se borran cuando su chunk vuelva a cargar). */
        List<LivingEntity> vivos() {
            List<LivingEntity> out = new ArrayList<>();
            long ahora = System.currentTimeMillis();
            for (java.util.Iterator<UUID> it = mobs.iterator(); it.hasNext(); ) {
                UUID u = it.next();
                Entity e = Bukkit.getEntity(u);
                if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) { out.add(le); visto.put(u, ahora); continue; }
                if (e != null && (e.isDead() || (e instanceof LivingEntity l2 && l2.getHealth() <= 0))) { it.remove(); visto.remove(u); continue; }
                if (ahora - visto.getOrDefault(u, ahora) > 60_000L) { it.remove(); visto.remove(u); }
            }
            return out;
        }

        void borrarMobs() {
            for (UUID u : mobs) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
            mobs.clear();
            visto.clear();
        }

        void perseguir(List<LivingEntity> vivos) {
            for (LivingEntity e : vivos) {
                if (!(e instanceof Mob m) || (m.getTarget() != null && m.getTarget().isValid())) continue;
                Player p = cercano(e.getLocation());
                if (p != null && p.getLocation().distanceSquared(e.getLocation()) < 90 * 90) m.setTarget(p);
            }
        }

        /** Los que quedaron trabados o muy lejos se acercan (como en las raids de Minecraft). */
        void rescatar(List<LivingEntity> vivos) {
            for (LivingEntity e : vivos) {
                Player p = cercano(e.getLocation());
                if (p == null || p.getLocation().distanceSquared(e.getLocation()) < 40 * 40) continue;
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                e.teleport(suelo(p.getLocation().add(Math.cos(a) * 20, 0, Math.sin(a) * 20)));
            }
        }

        Player cercano(Location l) {
            Player mejor = null;
            double d = Double.MAX_VALUE;
            for (Player p : participantes()) {
                if (p.getWorld() != l.getWorld()) continue;
                double x = p.getLocation().distanceSquared(l);
                if (x < d) { d = x; mejor = p; }
            }
            return mejor;
        }

        /** Un punto en un anillo alrededor del centro, sin agua ni copas de arboles. */
        Location puntoAparicion() {
            double min = cfg().getDouble("goblins.distancia_min", 24), max = cfg().getDouble("goblins.distancia_max", 32);
            for (int i = 0; i < 20; i++) {
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = ThreadLocalRandom.current().nextDouble(min, max);
                Location l = suelo(centro.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                Block abajo = l.getBlock().getRelative(0, -1, 0);
                if (!l.getBlock().isLiquid() && !abajo.isLiquid() && !abajo.getType().name().endsWith("LEAVES")) return l;
            }
            return suelo(centro.clone().add(min, 0, 0));
        }

        void actualizarJugadores() {
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

        List<Player> participantes() {
            List<Player> out = new ArrayList<>();
            for (UUID u : jugadores) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
            return out;
        }

        void sonar(String s, Location l, float tono) {
            for (Player p : participantes()) p.playSound(l, s, SoundCategory.HOSTILE, 64f, tono);
        }
    }

    // ================================================================= mobs

    private static Method SPAWN;
    private static Object HELPER;

    private Entity spawn(String tipo, Location l) {
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
                return null;
            }
        }
        return e;
    }

    @EventHandler
    public void muere(EntityDeathEvent e) {
        Raid r = raidDe(e.getEntity().getUniqueId());
        if (r != null) { r.mobs.remove(e.getEntity().getUniqueId()); r.visto.remove(e.getEntity().getUniqueId()); }
    }

    /** Goblins que quedaron de antes (de una invasion que ya termino) se borran cuando su chunk carga. */
    @EventHandler
    public void cargan(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (esGoblin(en) && raidDe(en.getUniqueId()) == null) en.remove();
    }

    private boolean esGoblin(Entity e) { return e.getPersistentDataContainer().has(K_GOBLIN, PersistentDataType.BYTE); }

    /** Goblins que no son de ninguna invasion en curso (por ejemplo, si se apago el server en el medio). */
    private void borrarSueltos() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (esGoblin(e) && raidDe(e.getUniqueId()) == null) e.remove();
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

    // ================================================================= disparadores

    /** Dia actual de TOD4 (0 si no esta el plugin). */
    private static int dia() {
        try {
            org.bukkit.plugin.Plugin t = Bukkit.getPluginManager().getPlugin("TOD4");
            if (t != null) return (int) t.getClass().getMethod("dia").invoke(t);
        } catch (Throwable ignored) { }
        return 0;
    }

    /** Antes de goblins.dia_minimo no empieza sola (el comando iniciar si). 0 = cualquier dia. */
    private boolean antesDeTiempo() {
        int min = cfg().getInt("goblins.dia_minimo", 0);
        return min > 0 && dia() < min;
    }

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
        if (antesDeTiempo() || esTienda(e.getVillager()) || zonaOcupada(e.getVillager().getLocation())) return;
        if (cfg().getBoolean("goblins.solo_aldeano_goblin", false) && !esAldeanoGoblin(e.getVillager())) return;
        if (ThreadLocalRandom.current().nextDouble() < cfg().getDouble("goblins.probabilidad", 0.03)) empezar(e.getVillager().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void lePegan(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Villager v) || !esAldeanoGoblin(v) || esTienda(v)) return;
        Entity d = e.getDamager();
        boolean jugador = d instanceof Player || (d instanceof Projectile pr && pr.getShooter() instanceof Player);
        if (!jugador || antesDeTiempo() || zonaOcupada(v.getLocation())) return;
        empezar(v.getLocation());
    }

    // ---- durante la invasion, los que estan en ella no pueden tradear
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void abreAldeano(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v) || esTienda(v) || !enAlgunaRaid(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(TOD4Eventos.c("&c¡No es momento de comerciar, hay una invasión goblin!"));
    }

    @EventHandler(ignoreCancelled = true)
    public void abreMenu(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.MERCHANT) return;
        if (e.getInventory().getHolder() instanceof Entity en && esTienda(en)) return;
        if (e.getPlayer() instanceof Player p && enAlgunaRaid(p)) e.setCancelled(true);
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
                // el comando no mira la zona en espera, solo que no haya otra invasion ahi mismo
                for (Raid r : raids) if (r.centro.getWorld() == p.getWorld() && r.centro.distanceSquared(p.getLocation()) <= zona() * zona()) {
                    TOD4Eventos.err(s, "Ya hay una invasión en curso acá cerca."); return;
                }
                raids.add(new Raid(p.getLocation()));
                TOD4Eventos.ok(s, "Invasión Goblin iniciada en tu posición.");
            }
            case "detener" -> {
                // la mas cercana (si estas en el juego) o todas
                if (s instanceof Player p && a.length < 2) {
                    Raid mejor = null; double d = Double.MAX_VALUE;
                    for (Raid r : raids) if (r.centro.getWorld() == p.getWorld() && r.centro.distanceSquared(p.getLocation()) < d) { d = r.centro.distanceSquared(p.getLocation()); mejor = r; }
                    if (mejor == null) { TOD4Eventos.err(s, "No hay ninguna invasión en este mundo. Para todas: &e/tod4eventos:goblins detener todas"); return; }
                    mejor.detener(); TOD4Eventos.ok(s, "Invasión más cercana detenida.");
                } else { detener(); TOD4Eventos.ok(s, "Todas las invasiones detenidas."); }
            }
            case "reset" -> { st.set("zonas", null); st.save(); TOD4Eventos.ok(s, "Esperas de todas las zonas reiniciadas."); }
            case "estado" -> {
                if (raids.isEmpty()) TOD4Eventos.info(s, "No hay invasiones en curso.");
                for (Raid r : raids) TOD4Eventos.info(s, "&f" + r.centro.getWorld().getName() + " " + r.centro.getBlockX() + " " + r.centro.getBlockZ()
                        + " &7| " + r.estado.name().toLowerCase() + " | Oleada &f" + r.oleada + "/" + r.totalOleadas
                        + " &7| Goblins: &f" + r.vivos().size() + " &7| Jugadores: &f" + r.jugadores.size());
                long ahora = System.currentTimeMillis();
                for (Map<?, ?> m : st.y().getMapList("zonas")) {
                    long rest = (((Number) m.get("hasta")).longValue() - ahora) / 1000;
                    if (rest > 0) TOD4Eventos.info(s, "Zona en espera: &f" + m.get("mundo") + " " + Math.round(((Number) m.get("x")).doubleValue())
                            + " " + Math.round(((Number) m.get("z")).doubleValue()) + " &7(" + (rest / 60) + " min)");
                }
            }
            default -> {
                TOD4Eventos.info(s, "&2Invasión Goblin:");
                TOD4Eventos.raw(s, " &e/tod4eventos:goblins iniciar &8- &7empieza en tu posición");
                TOD4Eventos.raw(s, " &e/tod4eventos:goblins detener [todas] &7/ &eestado &7/ &ereset &8(reset = sin espera en ninguna zona)");
            }
        }
    }

    List<String> tab(String[] a) {
        return a.length == 1 ? new ArrayList<>(List.of("iniciar", "detener", "estado", "reset")) : new ArrayList<>();
    }
}
