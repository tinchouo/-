package ar.tod4.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Items;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Palo de Retorno: un admin le pega (o click derecho) a un jugador y lo manda a su cama / punto de reaparicion.
 * Sin cama: al spawn del mundo principal. Funciona en todos los mundos (click derecho anda aunque no haya PvP).
 */
public final class Palo implements Listener {

    private final TOD4 pl;
    private final NamespacedKey KEY;

    public Palo(TOD4 pl) {
        this.pl = pl;
        this.KEY = new NamespacedKey(pl, "palo_retorno");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public ItemStack item() {
        ItemStack it = Items.of(Material.STICK, "&6&lPalo de Retorno", "&7Pegale o hacé click derecho a un", "&7jugador para mandarlo a su spawn.");
        ItemMeta m = it.getItemMeta();
        m.addEnchant(Enchantment.UNBREAKING, 1, true);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean es(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    private void mandar(Player admin, Player v) {
        Location dest = v.getRespawnLocation();
        if (dest == null) {
            World w = Bukkit.getWorld(pl.getConfig().getString("palo.mundo_principal", "world"));
            if (w == null) w = Bukkit.getWorlds().get(0);
            dest = w.getSpawnLocation().add(0.5, 0, 0.5);
            Style.err(admin, "&e" + v.getName() + " &cno tenía cama: fue al spawn del mundo principal.");
        } else {
            Style.ok(admin, "Enviaste a &f" + v.getName() + " &aa su punto de reaparición.");
        }
        v.teleport(dest);
        v.sendActionBar(Txt.c("&aSe te ha teletransportado a tu último spawn."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void click(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player v)) return;
        Player a = e.getPlayer();
        if (!a.hasPermission("tod4.admin") || !es(a.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        mandar(a, v);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void pega(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a) || !(e.getEntity() instanceof Player v)) return;
        if (!a.hasPermission("tod4.admin") || !es(a.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        mandar(a, v);
    }

    @EventHandler(ignoreCancelled = true)
    public void craft(CraftItemEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (es(it)) { e.setCancelled(true); return; }
    }
}
