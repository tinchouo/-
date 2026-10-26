package ar.tod4.items;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CartographyInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;

/**
 * Los items de Oraxen (monedas, mochilas, etc.) son papel u otros items de Minecraft por dentro.
 * Esto evita que se usen como ingrediente de algo de Minecraft: libros, mapas, el crafter, venderlos a un aldeano...
 * Las recetas de Oraxen y de los plugins siguen andando. No se toca ningun item.
 */
public final class SoloOraxen implements Listener {
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");

    public SoloOraxen(TOD4 pl) {
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private static boolean esOraxen(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ORAXEN, PersistentDataType.STRING);
    }

    private static boolean hayOraxen(ItemStack[] items) {
        if (items == null) return false;
        for (ItemStack it : items) if (esOraxen(it)) return true;
        return false;
    }

    /** Receta de Minecraft (no de Oraxen ni de otro plugin). */
    private static boolean deMinecraft(Recipe r) {
        return r instanceof Keyed k && k.getKey().getNamespace().equals(NamespacedKey.MINECRAFT);
    }

    // mesa de crafteo (y el inventario de 2x2)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void craftea(PrepareItemCraftEvent e) {
        if (!deMinecraft(e.getRecipe())) return;
        if (hayOraxen(e.getInventory().getMatrix())) e.getInventory().setResult(null);
    }

    // crafter automatico
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void crafter(CrafterCraftEvent e) {
        if (!deMinecraft(e.getRecipe())) return;
        if (e.getBlock().getState() instanceof Crafter c && hayOraxen(c.getInventory().getContents())) e.setCancelled(true);
    }

    // mesa de cartografia (mapas con papel)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void cartografia(PrepareInventoryResultEvent e) {
        if (e.getInventory() instanceof CartographyInventory inv && hayOraxen(inv.getContents())) e.setResult(null);
    }

    // aldeanos: no se paga con items de Oraxen
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void aldeano(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof MerchantInventory inv) || e.getRawSlot() != 2) return;
        if (esOraxen(inv.getItem(0)) || esOraxen(inv.getItem(1))) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendActionBar(Txt.c("&cLos aldeanos no aceptan ese ítem."));
        }
    }
}
