package ar.tod4.momento;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import ar.tod4.TOD4;
import ar.tod4.avisos.Paleta;
import ar.tod4.data.Store;
import ar.tod4.hook.Ext;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.bossbar.BossBar;

/**
 * Momentos Truco: ilimitados, cada uno con objetivo, modo, duracion, consecuencias y recompensa.
 * Objetivos: tocar un bloque, estar en una dimension, arriba/abajo de una altura, cerca de un punto,
 * tener un item, matar mobs. Las consecuencias se aplican a todos los que fallaron al mismo tiempo.
 */
public final class Momentos implements Listener {

    public enum Tipo {
        TOCAR("Tocar un bloque", Material.RED_BED),
        DIMENSION("Estar en una dimensión", Material.ENDER_EYE),
        ALTURA("Arriba / abajo de una altura", Material.LADDER),
        CERCA("Estar cerca de un punto", Material.COMPASS),
        TENER("Tener un ítem", Material.CHEST),
        MATAR("Matar mobs", Material.IRON_SWORD);

        public final String label;
        public final Material icono;
        Tipo(String l, Material i) { label = l; icono = i; }
    }

    final TOD4 pl;
    final Store st;

    // ---- estado del momento en curso
    private String activo;
    private final Set<UUID> participantes = new HashSet<>();
    private final Set<UUID> salvados = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Long> congelados = new HashMap<>();
    private int restante, total;
    private BossBar barra;
    private BukkitTask tarea;
    private boolean preparando;

    public Momentos(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "momentos");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public Store store() { return st; }

    ConfigurationSection m(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("momentos." + id);
        return s != null ? s : st.y().createSection("momentos." + id);
    }

    public List<String> ids() {
        ConfigurationSection s = st.y().getConfigurationSection("momentos");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    public boolean existe(String id) { return st.y().isConfigurationSection("momentos." + id); }

    public String nombre(String id) { return m(id).getString("nombre", id); }

    Tipo tipo(String id) {
        try { return Tipo.valueOf(m(id).getString("objetivo.tipo", "TOCAR")); } catch (Exception e) { return Tipo.TOCAR; }
    }

    public String activo() { return activo; }

    public boolean enCurso() { return activo != null; }

    public int restante() { return restante; }

    // ================================================================= texto del objetivo

    public String objetivoCorto(String id) {
        ConfigurationSection c = m(id);
        List<String> objs = c.getStringList("objetivo.objs");
        String lista = objs.isEmpty() ? "?" : Txt.plain(Obj.nombre(objs.get(0))) + (objs.size() > 1 ? " (o similar)" : "");
        switch (tipo(id)) {
            case TOCAR: return "Tocá: " + lista;
            case DIMENSION: return "Estar en: " + lista;
            case ALTURA: return (c.getBoolean("objetivo.arriba", true) ? "Arriba de Y " : "Abajo de Y ") + c.getInt("objetivo.y", 64);
            case CERCA: return "Llegá al punto (" + c.getInt("objetivo.radio", 10) + " bloques)";
            case TENER: return "Tené " + c.getInt("objetivo.cant", 1) + "x " + lista;
            case MATAR: return "Matá " + c.getInt("objetivo.cant", 1) + "x " + lista;
            default: return "";
        }
    }

    // ================================================================= cumplir

    /** Cumple el objetivo AHORA (para los objetivos de estado). */
    private boolean cumpleAhora(Player p, String id) {
        ConfigurationSection c = m(id);
        List<String> objs = c.getStringList("objetivo.objs");
        switch (tipo(id)) {
            case DIMENSION:
                for (String o : objs) if (Obj.mundo(o, p.getWorld())) return true;
                return false;
            case ALTURA: {
                double y = p.getLocation().getY();
                int ref = c.getInt("objetivo.y", 64);
                return c.getBoolean("objetivo.arriba", true) ? y >= ref : y <= ref;
            }
            case CERCA: {
                Location l = c.getLocation("objetivo.punto");
                return l != null && l.getWorld() != null && l.getWorld().equals(p.getWorld())
                        && l.distance(p.getLocation()) <= c.getInt("objetivo.radio", 10);
            }
            case TENER: return Obj.contar(p.getInventory(), objs) >= c.getInt("objetivo.cant", 1);
            case MATAR: return kills.getOrDefault(p.getUniqueId(), 0) >= c.getInt("objetivo.cant", 1);
            default: return false;
        }
    }

    private boolean unaVez(String id) {
        Tipo t = tipo(id);
        return t == Tipo.TOCAR || t == Tipo.MATAR || !"al_final".equals(m(id).getString("modo", "una_vez"));
    }

    /** Para la sidebar: esta a salvo ahora mismo. */
    public boolean aSalvo(Player p) {
        if (activo == null) return false;
        if (salvados.contains(p.getUniqueId())) return true;
        return !unaVez(activo) && cumpleAhora(p, activo);
    }

    public boolean participa(Player p) { return participantes.contains(p.getUniqueId()); }

    private void salvar(Player p) {
        if (activo == null || !participantes.contains(p.getUniqueId()) || salvados.contains(p.getUniqueId())) return;
        salvados.add(p.getUniqueId());
        Style.ok(p, "¡Lo conseguiste! &f✔ ESTÁS A SALVO");
        p.playSound(p.getLocation(), "entity.player.levelup", SoundCategory.MASTER, 1f, 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void toca(PlayerInteractEvent e) {
        if (activo == null || tipo(activo) != Tipo.TOCAR) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        String ox = Ext.oraxenBlock(e.getClickedBlock());
        for (String o : m(activo).getStringList("objetivo.objs"))
            if (Obj.bloque(o, e.getClickedBlock().getType(), ox)) { salvar(e.getPlayer()); return; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void mata(EntityDeathEvent e) {
        if (activo == null || tipo(activo) != Tipo.MATAR) return;
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        String mm = Ext.mythic(e.getEntity());
        for (String o : m(activo).getStringList("objetivo.objs")) {
            if (Obj.mob(o, e.getEntity(), mm)) {
                kills.merge(p.getUniqueId(), 1, Integer::sum);
                if (cumpleAhora(p, activo)) salvar(p);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void congelado(PlayerMoveEvent e) {
        Long hasta = congelados.get(e.getPlayer().getUniqueId());
        if (hasta == null) return;
        if (System.currentTimeMillis() > hasta) { congelados.remove(e.getPlayer().getUniqueId()); return; }
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())
            e.setTo(e.getFrom().setDirection(e.getTo().getDirection()));
    }

    // ================================================================= iniciar / terminar

    public void iniciar(Player quien, String id) {
        if (activo != null || preparando) { Style.err(quien, "Ya hay un Momento Truco activo."); return; }
        preparando = true;
        pl.avisos().ruletaGlobal(Paleta.ROJO, () -> {
            preparando = false;
            String av = m(id).getString("aviso");
            if (av != null && pl.avisos().existe(av)) for (Player p : Bukkit.getOnlinePlayers()) pl.avisos().mostrar(av, p);
            comenzar(id);
        });
    }

    private void comenzar(String id) {
        activo = id;
        participantes.clear(); salvados.clear(); kills.clear();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) participantes.add(p.getUniqueId());
        total = restante = Math.max(5, m(id).getInt("duracion", 240));
        barra = BossBar.bossBar(Txt.c(titulo()), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(barra);
        Style.broadcast("&c⚡ &c&lMOMENTO TRUCO: &f" + objetivoCorto(id));
        tarea = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    private String titulo() {
        int mm = restante / 60, ss = restante % 60;
        return "&c&l⚡ " + nombre(activo) + " &8| &f" + objetivoCorto(activo) + " &8| &e" + String.format("%d:%02d", mm, ss);
    }

    private void tick() {
        if (activo == null) return;
        restante--;
        if (unaVez(activo)) {
            for (UUID u : participantes) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && !salvados.contains(u) && cumpleAhora(p, activo)) salvar(p);
            }
        }
        barra.name(Txt.c(titulo()));
        barra.progress(Math.max(0f, Math.min(1f, restante / (float) total)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(barra);
        if (restante <= 0) terminar(true);
    }

    public void cancelar(Player quien) {
        if (activo == null) { Style.err(quien, "No hay ningún Momento Truco activo."); return; }
        terminar(false);
        Style.broadcast("&c✖ El Momento Truco fue cancelado.");
    }

    private void terminar(boolean aplicar) {
        String id = activo;
        if (tarea != null) tarea.cancel();
        if (aplicar && id != null) {
            if (!unaVez(id)) for (UUID u : participantes) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && cumpleAhora(p, id)) salvados.add(u);
            }
            List<Player> fallaron = new ArrayList<>();
            for (UUID u : participantes) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || p.isDead()) continue;
                if (salvados.contains(u)) {
                    Style.raw(p, "&a✔ &fSobreviviste al Momento Truco.");
                    for (ItemStack it : recompensas(id)) p.getInventory().addItem(it.clone()).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
                } else {
                    fallaron.add(p);
                    Style.broadcast("&c✖ &f" + p.getName() + " &7no lo consiguió.");
                }
            }
            for (Player p : fallaron) consecuencias(p, id);
        }
        activo = null;
        BossBar b = barra;
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (b != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b); }, 40L);
        barra = null;
    }

    @SuppressWarnings("unchecked")
    List<ItemStack> recompensas(String id) {
        List<ItemStack> out = new ArrayList<>();
        List<?> l = m(id).getList("recompensas");
        if (l != null) for (Object o : l) if (o instanceof ItemStack it) out.add(it);
        return out;
    }

    private void consecuencias(Player p, String id) {
        ConfigurationSection c = m(id).getConfigurationSection("cons");
        if (c == null) return;
        int seg = c.getInt("efectos_seg", 10);
        if (c.getBoolean("tirar")) {
            for (ItemStack it : p.getInventory().getContents()) if (it != null && !it.getType().isAir()) p.getWorld().dropItemNaturally(p.getLocation(), it);
            p.getInventory().clear();
        }
        if (c.getBoolean("tp")) {
            Location l = c.getLocation("tp_pos");
            if (l != null) p.teleport(l);
        }
        if (c.getBoolean("mobs")) {
            String tipo = c.getString("mobs_tipo", "zombie");
            int n = Math.max(1, Math.min(20, c.getInt("mobs_cant", 3)));
            Location l = p.getLocation();
            if (tipo.startsWith("mythic:")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn -s " + tipo.substring(7) + " " + n + " "
                        + l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
            } else {
                try {
                    EntityType t = EntityType.valueOf(tipo.toUpperCase());
                    for (int i = 0; i < n; i++) l.getWorld().spawnEntity(l, t);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (c.getBoolean("wither")) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, seg * 20, 0));
        if (c.getBoolean("poison")) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, seg * 20, 0));
        if (c.getBoolean("slowness")) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seg * 20, 9));
        if (c.getBoolean("darkness")) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seg * 20, 0));
        if (c.getBoolean("freeze")) {
            congelados.put(p.getUniqueId(), System.currentTimeMillis() + c.getInt("freeze_seg", 5) * 1000L);
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        }
        if (c.getBoolean("corazones_on")) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                double nuevo = Math.max(2.0, a.getBaseValue() - c.getInt("corazones", 3) * 2.0);
                a.setBaseValue(nuevo);
                if (p.getHealth() > nuevo) p.setHealth(nuevo);
            }
        }
        if (c.getBoolean("rayo")) p.getWorld().strikeLightning(p.getLocation());
        if (c.getBoolean("explosion")) p.getWorld().createExplosion(p.getLocation(), (float) c.getInt("fuerza", 4), false, true);
        if (c.getBoolean("medio") && !p.isDead()) p.setHealth(Math.min(p.getHealth(), 1.0));
        if (c.getBoolean("instakill") && !p.isDead()) p.setHealth(0);
    }
}
