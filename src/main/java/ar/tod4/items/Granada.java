package ar.tod4.items;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import ar.tod4.TOD4;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Granada de Impulso (como la del Fortnite): se tira, queda "espera_seg" en el piso pitando y explota sin romper nada
 * ni hacer dano. Empuja a todos los que esten en el radio (al que la tiro tambien, y a los mobs si afecta_mobs) hacia
 * AFUERA desde donde exploto: si estas arriba, para arriba ("altura_bloques"); si estas al costado, para el costado
 * ("empuje_bloques"). Mas cerca, mas fuerte. El dano de caida queda (no se toca).
 * config.yml -> granada_impulso.  /tod4 granada [cantidad] [jugador]
 */
public final class Granada implements Listener {

    private final TOD4 pl;
    private final NamespacedKey kItem, kTirada;

    public Granada(TOD4 pl) {
        this.pl = pl;
        this.kItem = new NamespacedKey(pl, "granada_impulso");
        this.kTirada = new NamespacedKey(pl, "granada_tirada");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private double d(String k, double def) { return pl.getConfig().getDouble("granada_impulso." + k, def); }

    public ItemStack item(int cant) {
        ItemStack it = new ItemStack(Material.SNOWBALL, Math.max(1, cant));
        ItemMeta m = it.getItemMeta();
        m.displayName(Txt.c(pl.getConfig().getString("granada_impulso.nombre", "<#7FE7FF>Granada de Impulso")).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Txt.c("&7Tírala: al rato explota y empuja").decoration(TextDecoration.ITALIC, false),
                Txt.c("&7a todos los que estén cerca.").decoration(TextDecoration.ITALIC, false),
                Txt.c("&8No hace daño, pero la caída sí.").decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("tod4", "granada_impulso"));
        m.getPersistentDataContainer().set(kItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean es(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kItem, PersistentDataType.BYTE);
    }

    private boolean bloqueado(World w) {
        List<String> l = pl.getConfig().contains("granada_impulso.mundos_bloqueados")
                ? pl.getConfig().getStringList("granada_impulso.mundos_bloqueados") : List.of("agujero_infierno");
        return l.stream().anyMatch(n -> n.equalsIgnoreCase(w.getName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void tira(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Snowball s) || !es(s.getItem())) return;
        if (!pl.getConfig().getBoolean("granada_impulso.activa", true) || bloqueado(s.getWorld())) {
            e.setCancelled(true);
            if (s.getShooter() instanceof Player p) p.sendActionBar(Txt.c("&cLa granada no funciona acá."));
            return;
        }
        s.getPersistentDataContainer().set(kTirada, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler
    public void pega(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball s) || !s.getPersistentDataContainer().has(kTirada, PersistentDataType.BYTE)) return;
        e.setCancelled(true);   // no empuja ni lastima a lo que toca: la explosion hace todo
        Location l = s.getLocation();
        if (e.getHitBlockFace() != null) l.add(e.getHitBlockFace().getDirection().multiply(0.15));
        else if (e.getHitEntity() != null) l = e.getHitEntity().getLocation().add(0, 0.2, 0);
        s.remove();
        armar(l);
    }

    /** Queda en el piso pitando y despues explota. */
    private void armar(Location l) {
        World w = l.getWorld();
        ItemDisplay d = w.spawn(l, ItemDisplay.class, x -> {
            x.setItemStack(item(1));
            x.setPersistent(false);
            x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f()));
            x.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        });
        int espera = Math.max(1, (int) Math.round(d("espera_seg", 0.75) * 20));
        Bukkit.getScheduler().runTaskTimer(pl, new java.util.function.Consumer<org.bukkit.scheduler.BukkitTask>() {
            int t = 0;
            @Override public void accept(org.bukkit.scheduler.BukkitTask task) {
                if (t % 4 == 0) {
                    w.playSound(l, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.8f, 1.6f + t * 0.02f);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, l, 4, 0.15, 0.15, 0.15, 0.05);
                }
                if (++t >= espera) {
                    task.cancel();
                    d.remove();
                    explotar(l);
                }
            }
        }, 0L, 1L);
    }

    private void explotar(Location l) {
        World w = l.getWorld();
        double r = Math.max(1, d("radio", 4));
        w.spawnParticle(Particle.GUST_EMITTER_LARGE, l, 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.ELECTRIC_SPARK, l, 40, r * 0.3, r * 0.3, r * 0.3, 0.3);
        w.playSound(l, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.5f, 0.8f);
        w.playSound(l, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1.2f, 1.2f);
        double vyMax = velocidadParaAltura(d("altura_bloques", 5.5));
        double vhMax = d("empuje_bloques", 6) * (1 - 0.91);   // en el aire se frena un 9% por tick
        boolean mobs = pl.getConfig().getBoolean("granada_impulso.afecta_mobs", true);
        int n = 0;
        for (Entity e : w.getNearbyEntities(l, r, r, r)) {
            if (!(e instanceof LivingEntity le) || e instanceof org.bukkit.entity.ArmorStand) continue;
            if (!(e instanceof Player) && !mobs) continue;
            if (e instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE && p.isFlying())) continue;
            Location c = le.getLocation().add(0, le.getHeight() * 0.5, 0);
            Vector dir = c.toVector().subtract(l.toVector());
            double dist = dir.length();
            if (dist > r) continue;
            if (dist < 1e-3) dir = new Vector(0, 1, 0); else dir.multiply(1 / dist);
            double fuerza = 1 - (dist / r) * 0.6;            // al borde, 40% de la fuerza
            Vector h = dir.clone().setY(0);
            double hl = h.length();
            // arriba: si estas encima, todo; al costado, un saltito igual (si no, se arrastra por el piso)
            double arriba = Math.max(dir.getY(), d("minimo_arriba", 0.4));
            Vector v = (hl > 1e-3 ? h.multiply(1 / hl).multiply(vhMax * hl) : new Vector()).add(new Vector(0, vyMax * arriba, 0)).multiply(fuerza);
            le.setVelocity(v);
            n++;
            if (pl.getConfig().getBoolean("granada_impulso.debug", false))
                pl.getLogger().info("[Granada] empujo a " + e.getName() + " (a " + String.format("%.1f", dist) + " bloques): " + String.format("%.2f %.2f %.2f", v.getX(), v.getY(), v.getZ()));
            if (e instanceof Player p) p.setFallDistance(0);   // la caida cuenta desde donde lo tiro (no lo de antes)
        }
    }

    /** La velocidad para arriba que hace subir "h" bloques (con la gravedad y el freno del aire de Minecraft). */
    private static double velocidadParaAltura(double h) {
        double lo = 0, hi = 4;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2, y = 0, v = mid;
            while (v > 0) { y += v; v = (v - 0.08) * 0.98; }
            if (y < h) lo = mid; else hi = mid;
        }
        return hi;
    }

    /** /tod4 granada [cantidad] [jugador] */
    public void cmd(CommandSender s, String[] a) {
        int n = 1;
        if (a.length > 1) try { n = Math.max(1, Math.min(64, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
        Player p = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pp ? pp : null);
        if (p == null) { Style.err(s, "Uso: &e/tod4 granada [cantidad] [jugador]"); return; }
        for (ItemStack sobra : p.getInventory().addItem(item(n)).values()) p.getWorld().dropItem(p.getLocation(), sobra);
        Style.ok(s, n + " granada(s) de impulso para " + p.getName() + ".");
    }
}
