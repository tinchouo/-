package ar.tod4.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Menu de cofre. Cada menu es duenio de sus clicks: ningun otro script/plugin los pisa.
 * Los slots "editables" dejan poner y sacar items (y el inventario del jugador se puede usar normal).
 */
public class Menu implements InventoryHolder {

    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private Consumer<InventoryCloseEvent> onClose;

    public Menu(int rows, String title) {
        this.inv = Bukkit.createInventory(this, rows * 9, Txt.c(title));
    }

    @Override
    public Inventory getInventory() { return inv; }

    public Menu fill(Material pane) {
        ItemStack p = Items.pane(pane);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, p);
        return this;
    }

    public Menu set(int slot, ItemStack it, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, it);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
        return this;
    }

    public Menu set(int slot, ItemStack it) { return set(slot, it, null); }

    public Menu editable(int slot) {
        editable.add(slot);
        inv.setItem(slot, null);
        return this;
    }

    public Menu onClose(Consumer<InventoryCloseEvent> c) { this.onClose = c; return this; }

    public void open(Player p) { p.openInventory(inv); }

    // ------------------------------------------------------------------ listener comun

    public static final class Listen implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void click(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            Inventory clicked = e.getClickedInventory();
            boolean top = clicked != null && clicked.equals(e.getView().getTopInventory());
            if (top) {
                if (m.editable.contains(e.getSlot())) {
                    e.setCancelled(false);
                    return;
                }
                e.setCancelled(true);
                Consumer<InventoryClickEvent> a = m.actions.get(e.getSlot());
                if (a != null) a.accept(e);
                return;
            }
            // inventario del jugador: libre, salvo que intente mandar algo a un slot no editable
            if (e.isShiftClick() || e.getAction().name().contains("COLLECT")) {
                if (m.editable.isEmpty()) e.setCancelled(true);
                else e.setCancelled(false);
            } else {
                e.setCancelled(false);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void drag(InventoryDragEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            int top = e.getView().getTopInventory().getSize();
            for (int raw : e.getRawSlots()) {
                if (raw < top && !m.editable.contains(raw)) { e.setCancelled(true); return; }
            }
            e.setCancelled(false);
        }

        @EventHandler
        public void close(InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof Menu m && m.onClose != null) m.onClose.accept(e);
        }
    }
}
