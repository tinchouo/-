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
 * Textura: tod4:corazon (TOD4_extras.zip).
 */
public final class Corazon implements Listener {
    private final TOD4 pl;
    private final NamespacedKey kItem, kCuenta, kMod;

    public Corazon(TOD4 pl) {
        this.pl = pl;
        this.kItem = new NamespacedKey(pl, "corazon_rojo");
        this.kCuenta = new NamespacedKey(pl, "corazones_extra");
        this.kMod = new NamespacedKey(pl, "corazones_extra");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public ItemStack item(int cant) {
        ItemStack it = new ItemStack(Material.APPLE, Math.max(1, cant));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Txt.c("&c&lCorazón Rojo").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Txt.c("&7Comerlo te da un &ccorazón &7de vida").decoration(TextDecoration.ITALIC, false),
                Txt.c("&7extra &fpermanente&7.").decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(new NamespacedKey("tod4", "corazon"));
        FoodComponent food = meta.getFood();
        food.setCanAlwaysEat(true);
        meta.setFood(food);
        meta.getPersistentDataContainer().set(kItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean esCorazon(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kItem, PersistentDataType.BYTE);
    }

    public int extra(Player p) { return p.getPersistentDataContainer().getOrDefault(kCuenta, PersistentDataType.INTEGER, 0); }

    /** Pone el modificador de vida segun cuantos corazones extra tiene (2 de vida = 1 corazon). */
    private void aplicar(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return;
        int n = extra(p);
        AttributeModifier ya = ai.getModifier(kMod);
        if (ya != null && ya.getAmount() == n * 2.0) return;
        if (ya != null) ai.removeModifier(kMod);
        if (n > 0) ai.addModifier(new AttributeModifier(kMod, n * 2.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void come(PlayerItemConsumeEvent e) {
        if (!esCorazon(e.getItem())) return;
        Player p = e.getPlayer();
        p.getPersistentDataContainer().set(kCuenta, PersistentDataType.INTEGER, extra(p) + 1);
        aplicar(p);
        Bukkit.getScheduler().runTask(pl, () -> {
            AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
            if (ai != null) p.setHealth(Math.min(ai.getValue(), p.getHealth() + 2));
        });
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.8f, 0.8f);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.6f);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 8, 0.4, 0.3, 0.4, 0);
        p.sendActionBar(Txt.c("&c&l+1 ❤ &7permanente"));
    }

    /** Al morir se pierden. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (extra(p) == 0) return;
        p.getPersistentDataContainer().remove(kCuenta);
        aplicar(p);
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) { aplicar(e.getPlayer()); }

    @EventHandler
    public void reaparece(PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(pl, () -> aplicar(e.getPlayer())); }

    /** /tod4 corazon [jugador] [cantidad] */
    public void cmd(CommandSender s, String[] a) {
        Player dest = s instanceof Player p ? p : null;
        int cant = 1;
        if (a.length > 1) {
            Player o = Bukkit.getPlayerExact(a[1]);
            if (o != null) dest = o;
            else try { cant = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { Style.err(s, "No encontré a " + a[1] + "."); return; }
        }
        if (a.length > 2) try { cant = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
        if (dest == null) { Style.err(s, "Uso: &e/tod4 corazon [jugador] [cantidad]"); return; }
        for (ItemStack sobra : dest.getInventory().addItem(item(cant)).values()) dest.getWorld().dropItem(dest.getLocation(), sobra);
        Style.ok(s, "Le di " + cant + " Corazón Rojo a " + dest.getName() + ".");
    }
}
