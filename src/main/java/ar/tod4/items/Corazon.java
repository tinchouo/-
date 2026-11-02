package ar.tod4.items;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Corazon Rojo: se come y da un corazon de vida extra para siempre (sin tope).
 * Al morir se pierden todos los corazones extra (eso no lo dice el item, a proposito).
 * Corazon Amarillo: 2 corazones amarillos (absorcion) para siempre; los golpes los gastan primero y no vuelven.
 * Corazon Morado: deja la vida maxima en 10 corazones (lo normal) y te cura entero, tengas mas o menos.
 * Con el cambio d10.solo_corazones, sin equipo no se puede comer ninguno de los tres.
 * Texturas: tod4:corazon, tod4:corazon_amarillo, tod4:corazon_morado (TOD4_extras.zip).
 */
public final class Corazon implements Listener {
    private final TOD4 pl;
    private final NamespacedKey kItem, kCuenta, kMod, kAmarillo, kMorado, kAmarillos, kModAbs, kRobados, kModRobo;

    public enum Tipo { ROJO, AMARILLO, MORADO }

    public Corazon(TOD4 pl) {
        this.pl = pl;
        this.kItem = new NamespacedKey(pl, "corazon_rojo");
        this.kCuenta = new NamespacedKey(pl, "corazones_extra");
        this.kMod = new NamespacedKey(pl, "corazones_extra");
        this.kAmarillo = new NamespacedKey(pl, "corazon_amarillo");
        this.kMorado = new NamespacedKey(pl, "corazon_morado");
        this.kAmarillos = new NamespacedKey(pl, "corazones_amarillos");
        this.kModAbs = new NamespacedKey(pl, "corazones_amarillos");
        this.kRobados = new NamespacedKey(pl, "corazones_robados");
        this.kModRobo = new NamespacedKey(pl, "corazones_robados");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public ItemStack item(int cant) { return item(Tipo.ROJO, cant); }

    public ItemStack item(Tipo t, int cant) {
        ItemStack it = new ItemStack(Material.APPLE, Math.max(1, cant));
        ItemMeta meta = it.getItemMeta();
        switch (t) {
            case AMARILLO -> {
                meta.displayName(Txt.c("&e&lCorazón Amarillo").decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Txt.c("&7Comerlo te da &e2 corazones amarillos").decoration(TextDecoration.ITALIC, false),
                        Txt.c("&fpermanentes&7. Si te pegan, se pierden.").decoration(TextDecoration.ITALIC, false)));
                meta.setItemModel(new NamespacedKey("tod4", "corazon_amarillo"));
                meta.getPersistentDataContainer().set(kAmarillo, PersistentDataType.BYTE, (byte) 1);
            }
            case MORADO -> {
                meta.displayName(Txt.c("&5&lCorazón Morado").decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Txt.c("&7Comerlo deja tu vida en &d10 corazones").decoration(TextDecoration.ITALIC, false),
                        Txt.c("&7y te &fcura entero&7.").decoration(TextDecoration.ITALIC, false)));
                meta.setItemModel(new NamespacedKey("tod4", "corazon_morado"));
                meta.getPersistentDataContainer().set(kMorado, PersistentDataType.BYTE, (byte) 1);
            }
            default -> {
                meta.displayName(Txt.c("&c&lCorazón Rojo").decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Txt.c("&7Comerlo te da un &ccorazón &7de vida").decoration(TextDecoration.ITALIC, false),
                        Txt.c("&7extra &fpermanente&7.").decoration(TextDecoration.ITALIC, false)));
                meta.setItemModel(new NamespacedKey("tod4", "corazon"));
                meta.getPersistentDataContainer().set(kItem, PersistentDataType.BYTE, (byte) 1);
            }
        }
        FoodComponent food = meta.getFood();
        food.setCanAlwaysEat(true);
        meta.setFood(food);
        it.setItemMeta(meta);
        return it;
    }

    private Tipo tipo(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (pdc.has(kItem, PersistentDataType.BYTE)) return Tipo.ROJO;
        if (pdc.has(kAmarillo, PersistentDataType.BYTE)) return Tipo.AMARILLO;
        if (pdc.has(kMorado, PersistentDataType.BYTE)) return Tipo.MORADO;
        return null;
    }

    private boolean esCorazon(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kItem, PersistentDataType.BYTE);
    }

    public int extra(Player p) { return p.getPersistentDataContainer().getOrDefault(kCuenta, PersistentDataType.INTEGER, 0); }

    /** d10.solo_corazones: sin equipo de verdad, los corazones rojos no se pueden comer y los que ya tiene no cuentan. */
    private boolean bloqueado(Player p) {
        return pl.cambios() != null && pl.cambios().on("d10.solo_corazones") && pl.equipos() != null && pl.equipos().solo(p);
    }

    /**
     * Pone el modificador de vida segun cuantos corazones extra tiene (2 de vida = 1 corazon).
     * Sin equipo (con el cambio d10.solo_corazones) no cuentan, pero no se pierden: vuelven al entrar a un equipo.
     */
    public void aplicar(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return;
        int n = bloqueado(p) ? 0 : extra(p);
        AttributeModifier ya = ai.getModifier(kMod);
        if (ya != null && ya.getAmount() == n * 2.0) return;
        if (ya != null) ai.removeModifier(kMod);
        if (n > 0) ai.addModifier(new AttributeModifier(kMod, n * 2.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        if (p.getHealth() > ai.getValue()) p.setHealth(Math.max(0.5, ai.getValue()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void come(PlayerItemConsumeEvent e) {
        Tipo t = tipo(e.getItem());
        if (t == null) return;
        Player p = e.getPlayer();
        if (bloqueado(p)) {
            e.setCancelled(true);
            Style.err(p, "Sin equipo no puedes usar Corazones " + (t == Tipo.ROJO ? "Rojos" : t == Tipo.AMARILLO ? "Amarillos" : "Morados") + ".");
            return;
        }
        switch (t) {
            case ROJO -> {
                p.getPersistentDataContainer().set(kCuenta, PersistentDataType.INTEGER, extra(p) + 1);
                aplicar(p);
                Bukkit.getScheduler().runTask(pl, () -> {
                    AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
                    if (ai != null) p.setHealth(Math.min(ai.getValue(), p.getHealth() + 2));
                });
                p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 8, 0.4, 0.3, 0.4, 0);
            }
            case AMARILLO -> {
                Bukkit.getScheduler().runTask(pl, () -> {
                    double n = amarillos(p) + 4;   // 2 corazones
                    p.getPersistentDataContainer().set(kAmarillos, PersistentDataType.DOUBLE, n);
                    aplicarAmarillos(p);
                    p.setAbsorptionAmount(Math.min(topeAbs(p), p.getAbsorptionAmount() + 4));
                });
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1.2, 0), 25, 0.4, 0.5, 0.4, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 210, 40), 1.3f));
            }
            case MORADO -> {
                Bukkit.getScheduler().runTask(pl, () -> morado(p));
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1.2, 0), 25, 0.4, 0.5, 0.4, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(170, 60, 230), 1.3f));
            }
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.8f, 0.8f);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, t == Tipo.MORADO ? 1.0f : 1.6f);
    }

    // ================================================================= amarillos

    /** Cuanta absorcion (en vida, 2 = 1 corazon) le queda de los corazones amarillos. */
    public double amarillos(Player p) { return p.getPersistentDataContainer().getOrDefault(kAmarillos, PersistentDataType.DOUBLE, 0.0); }

    private double topeAbs(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_ABSORPTION);
        return ai == null ? 0 : ai.getValue();
    }

    /** El tope de absorcion sube lo que quedan de amarillos (si no, el juego los borra). */
    private void aplicarAmarillos(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (ai == null) return;
        double n = amarillos(p);
        AttributeModifier ya = ai.getModifier(kModAbs);
        if (ya != null && ya.getAmount() == n) return;
        if (ya != null) ai.removeModifier(kModAbs);
        if (n > 0) ai.addModifier(new AttributeModifier(kModAbs, n, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    /** Despues de un golpe: los amarillos que se gastaron ya no vuelven. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golpe(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || amarillos(p) <= 0) return;
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!p.isOnline() || p.isDead()) return;
            double n = amarillos(p), abs = p.getAbsorptionAmount();
            if (abs >= n) return;
            if (abs <= 0.01) p.getPersistentDataContainer().remove(kAmarillos);
            else p.getPersistentDataContainer().set(kAmarillos, PersistentDataType.DOUBLE, abs);
            aplicarAmarillos(p);
        });
    }

    // ================================================================= corazones robados (murcielagos, etc.)

    /** Cuantos corazones normales (de los 10) le robaron. Vuelven al morir o con el Corazon Morado. */
    public int robados(Player p) { return p.getPersistentDataContainer().getOrDefault(kRobados, PersistentDataType.INTEGER, 0); }

    private void aplicarRobo(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return;
        int n = robados(p);
        AttributeModifier ya = ai.getModifier(kModRobo);
        if (ya != null && ya.getAmount() == -n * 2.0) return;
        if (ya != null) ai.removeModifier(kModRobo);
        if (n > 0) ai.addModifier(new AttributeModifier(kModRobo, -n * 2.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        if (p.getHealth() > ai.getValue()) p.setHealth(Math.max(0.5, ai.getValue()));
    }

    /**
     * Le roba corazones de la vida maxima (no de la vida): primero los rojos, despues los normales.
     * Los amarillos no lo protegen. Si no le queda ninguno, muere (causa = el mensaje de muerte).
     * @return true si murio
     */
    public boolean robar(Player p, int cuantos, net.kyori.adventure.text.Component causa) {
        for (int i = 0; i < cuantos; i++) {
            AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
            double max = ai == null ? 20 : ai.getValue();
            if (max <= 2.0001) {   // era el ultimo corazon
                if (causa != null) ar.tod4.core.Causas.anotar(p.getUniqueId(), causa);
                p.setHealth(0);
                return true;
            }
            if (!bloqueado(p) && extra(p) > 0) {
                int n = extra(p) - 1;
                if (n <= 0) p.getPersistentDataContainer().remove(kCuenta);
                else p.getPersistentDataContainer().set(kCuenta, PersistentDataType.INTEGER, n);
                aplicar(p);
            } else {
                p.getPersistentDataContainer().set(kRobados, PersistentDataType.INTEGER, robados(p) + 1);
                aplicarRobo(p);
            }
        }
        return false;
    }

    // ================================================================= morado

    /** Vida maxima en 10 corazones (sin rojos, sin lo que le hayan sacado) y la vida llena. */
    public void morado(Player p) {
        p.getPersistentDataContainer().remove(kCuenta);
        p.getPersistentDataContainer().remove(kRobados);
        aplicar(p);
        aplicarRobo(p);
        if (pl.momentos() != null) pl.momentos().olvidarQuitados(p.getUniqueId());
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return;
        ai.setBaseValue(20.0);
        p.setHealth(Math.max(0.5, ai.getValue()));
    }

    /** Al morir se pierden (los rojos y los amarillos). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (amarillos(p) > 0) { p.getPersistentDataContainer().remove(kAmarillos); aplicarAmarillos(p); }
        if (robados(p) > 0) { p.getPersistentDataContainer().remove(kRobados); aplicarRobo(p); }
        if (extra(p) == 0) return;
        p.getPersistentDataContainer().remove(kCuenta);
        aplicar(p);
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) { aplicar(e.getPlayer()); aplicarAmarillos(e.getPlayer()); aplicarRobo(e.getPlayer()); }

    @EventHandler
    public void reaparece(PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(pl, () -> { aplicar(e.getPlayer()); aplicarAmarillos(e.getPlayer()); aplicarRobo(e.getPlayer()); }); }

    /** /tod4 corazon [rojo|amarillo|morado] [jugador] [cantidad] */
    public void cmd(CommandSender s, String[] a) {
        Tipo t = Tipo.ROJO;
        int i = 1;
        if (a.length > 1) {
            for (Tipo x : Tipo.values()) if (x.name().equalsIgnoreCase(a[1])) { t = x; i = 2; }
        }
        Player dest = s instanceof Player p ? p : null;
        int cant = 1;
        if (a.length > i) {
            Player o = Bukkit.getPlayerExact(a[i]);
            if (o != null) dest = o;
            else try { cant = Integer.parseInt(a[i]); } catch (NumberFormatException ex) { Style.err(s, "No encontré a " + a[i] + "."); return; }
        }
        if (a.length > i + 1) try { cant = Integer.parseInt(a[i + 1]); } catch (NumberFormatException ignored) { }
        if (dest == null) { Style.err(s, "Uso: &e/tod4 corazon [rojo|amarillo|morado] [jugador] [cantidad]"); return; }
        for (ItemStack sobra : dest.getInventory().addItem(item(t, cant)).values()) dest.getWorld().dropItem(dest.getLocation(), sobra);
        String nom = t == Tipo.ROJO ? "Corazón Rojo" : t == Tipo.AMARILLO ? "Corazón Amarillo" : "Corazón Morado";
        Style.ok(s, "Le di " + cant + " " + nom + " a " + dest.getName() + ".");
    }
}
