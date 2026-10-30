package ar.tod4.revivir;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Menu;
import ar.tod4.util.Txt;
import net.kyori.adventure.text.Component;

/**
 * Los items de revivir solo sirven el dia en que aparecen.
 * La primera vez que se ve un item de revivir (en el inventario, al agarrarlo del piso o al abrir un cofre)
 * se le anota el dia. Cuando el dia cambia, ese item ya no sirve en el altar y se le agrega en rojo
 * que quedo inutilizable. Si el dia vuelve atras (/tod4 dia), vuelve a servir y se le saca el aviso.
 * Se apaga con revivir.items_vencen: false.
 */
final class Vencimiento implements Listener {

    private static final String MARCA = "quedó inutilizable";

    private final TOD4 pl;
    private final Predicate<ItemStack> esItem;
    private final NamespacedKey K_DIA;

    Vencimiento(TOD4 pl, Predicate<ItemStack> esItem) {
        this.pl = pl;
        this.esItem = esItem;
        this.K_DIA = new NamespacedKey(pl, "revivir_dia");
        Bukkit.getPluginManager().registerEvents(this, pl);
        // cada segundo: los inventarios de los que estan conectados (asi el aviso aparece apenas cambia el dia)
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            if (!activo()) return;
            for (Player p : Bukkit.getOnlinePlayers()) revisar(p);
        }, 40L, 20L);
    }

    boolean activo() { return pl.getConfig().getBoolean("revivir.items_vencen", true); }

    /** El dia en que aparecio (0 = todavia no tiene). */
    int dia(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer d = it.getItemMeta().getPersistentDataContainer().get(K_DIA, PersistentDataType.INTEGER);
        return d == null ? 0 : d;
    }

    /** true = es de un dia anterior y ya no sirve. */
    boolean vencido(ItemStack it) {
        if (!activo()) return false;
        int d = dia(it);
        return d > 0 && d < pl.dia();
    }

    /** Le anota el dia si no lo tiene y pone o saca el aviso rojo. No toca los items que no son de revivir. true = cambio. */
    boolean marcar(ItemStack it) {
        if (it == null || it.getType().isAir() || !esItem.test(it)) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        boolean cambio = false;
        Integer d = m.getPersistentDataContainer().get(K_DIA, PersistentDataType.INTEGER);
        if (d == null) {
            d = pl.dia();
            m.getPersistentDataContainer().set(K_DIA, PersistentDataType.INTEGER, d);
            cambio = true;
        }
        boolean venc = d < pl.dia();
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        boolean tiene = false;
        for (Component c : lore) if (Txt.plain(c).contains(MARCA)) tiene = true;
        if (venc && !tiene) {
            lore.add(Txt.c(""));
            lore.add(Txt.c("&c&l✖ Este ítem " + MARCA + "."));
            lore.add(Txt.c("&cEra del día " + d + " y ya pasó el día."));
            m.lore(lore);
            cambio = true;
        } else if (!venc && tiene) {
            // el dia volvio atras: se saca el aviso (y el renglon vacio de arriba)
            int i = 0;
            while (i < lore.size()) {
                String t = Txt.plain(lore.get(i));
                if (t.contains(MARCA)) {
                    if (i > 0 && Txt.plain(lore.get(i - 1)).isBlank()) { lore.remove(i - 1); i--; }
                    lore.remove(i);
                    if (i < lore.size() && Txt.plain(lore.get(i)).startsWith("Era del día ")) lore.remove(i);
                    continue;
                }
                i++;
            }
            m.lore(lore.isEmpty() ? null : lore);
            cambio = true;
        }
        if (cambio) it.setItemMeta(m);
        return cambio;
    }

    private void revisar(Inventory inv) {
        if (inv == null) return;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (marcar(c[i])) inv.setItem(i, c[i]);
    }

    void revisar(Player p) {
        revisar(p.getInventory());
        ItemStack cur = p.getItemOnCursor();
        if (marcar(cur)) p.setItemOnCursor(cur);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void entra(PlayerJoinEvent e) {
        if (activo()) revisar(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void agarra(EntityPickupItemEvent e) {
        if (!activo() || !(e.getEntity() instanceof Player)) return;
        ItemStack it = e.getItem().getItemStack();
        if (marcar(it)) e.getItem().setItemStack(it);
    }

    /** Cofres, barriles, shulkers, ender chest...: los items que estaban guardados (no los menus de los plugins). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void abre(InventoryOpenEvent e) {
        if (!activo()) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menu || top.getHolder() instanceof Revivir.TumbaHolder) return;
        if (top.getHolder() == null && top.getType() != org.bukkit.event.inventory.InventoryType.ENDER_CHEST) return;
        revisar(top);
    }
}
