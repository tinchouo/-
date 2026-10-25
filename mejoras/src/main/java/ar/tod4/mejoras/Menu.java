package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** /mejoras: todas las mejoras; clic en una para prender/apagar su crafteo y cada efecto, o darte una. */
final class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> acciones = new HashMap<>();

    private Menu(int filas, Component titulo) { inv = Bukkit.createInventory(this, filas * 9, titulo); }

    @Override public Inventory getInventory() { return inv; }

    private void set(int slot, ItemStack it, Consumer<InventoryClickEvent> a) { inv.setItem(slot, it); if (a != null) acciones.put(slot, a); }

    private static ItemStack boton(Material m, Component nombre, Component... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(nombre.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private void relleno() {
        ItemStack v = boton(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, v);
    }

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    static void principal(TOD4Mejoras pl, Player p) {
        Menu m = new Menu(6, Component.text("Mejoras de armadura", NamedTextColor.DARK_GRAY));
        int i = 0;
        for (Mejora mj : pl.mejoras.values()) {
            if (i >= SLOTS.length) break;
            ItemStack it = pl.item(mj, 1);
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(estado("Crafteo", pl.crafteoOn(mj)));
            lore.add(Component.text("▶ Clic para configurar", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            it.setItemMeta(meta);
            m.set(SLOTS[i++], it, e -> detalle(pl, p, mj));
        }
        m.set(48, boton(Material.BOOK, Component.text("Cómo funciona", NamedTextColor.GOLD),
                TOD4Mejoras.gris("Mesa de herrería: la mejora a la izquierda,"),
                TOD4Mejoras.gris("la pieza de netherite en el medio y la derecha vacía."),
                TOD4Mejoras.gris("Crafteo: plantilla de netherite en el medio"),
                TOD4Mejoras.gris("y 8 del ingrediente alrededor."),
                TOD4Mejoras.gris("Los efectos no se suman entre piezas.")), null);
        m.set(49, boton(Material.BARRIER, Component.text("Cerrar", NamedTextColor.RED)), e -> p.closeInventory());
        m.set(50, boton(Material.COMPARATOR, Component.text("Recargar mejoras.yml", NamedTextColor.AQUA)), e -> {
            pl.recargar();
            principal(pl, p);
        });
        m.relleno();
        p.openInventory(m.inv);
    }

    private static Component estado(String que, boolean on) {
        return Component.text(que + ": ", NamedTextColor.GRAY).append(on
                ? Component.text("habilitado", NamedTextColor.GREEN) : Component.text("deshabilitado", NamedTextColor.RED))
                .decoration(TextDecoration.ITALIC, false);
    }

    static void detalle(TOD4Mejoras pl, Player p, Mejora mj) {
        Menu m = new Menu(4, TOD4Mejoras.c(mj.nombre, mj.color));
        m.set(4, pl.item(mj, 1), null);
        boolean on = pl.crafteoOn(mj);
        m.set(10, boton(on ? Material.LIME_DYE : Material.GRAY_DYE, estado("Crafteo", on),
                TOD4Mejoras.gris("8 × " + mj.ingrediente.name().toLowerCase().replace('_', ' ')),
                TOD4Mejoras.gris("+ plantilla de mejora de netherite"),
                Component.empty(),
                Component.text("▶ Clic para " + (on ? "deshabilitar" : "habilitar"), NamedTextColor.YELLOW)), e -> {
            pl.setCrafteo(mj, !on);
            ding(p);
            detalle(pl, p, mj);
        });
        int slot = 12;
        for (Mejora.Efecto ef : mj.efectos.values()) {
            if (slot > 16) break;
            boolean eon = pl.efectoOn(mj, ef);
            m.set(slot++, boton(eon ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    estado("Efecto " + ef.id, eon), TOD4Mejoras.gris(ef.texto), Component.empty(),
                    Component.text("▶ Clic para " + (eon ? "apagar" : "prender"), NamedTextColor.YELLOW)), e -> {
                pl.setEfecto(mj, ef, !eon);
                ding(p);
                detalle(pl, p, mj);
            });
        }
        m.set(22, boton(Material.CHEST, Component.text("Darme una", NamedTextColor.GOLD), TOD4Mejoras.gris("Para probarla.")), e -> {
            p.getInventory().addItem(pl.item(mj, 1)).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
            ding(p);
        });
        m.set(27, boton(Material.ARROW, Component.text("◀ Volver", NamedTextColor.GRAY)), e -> principal(pl, p));
        m.relleno();
        p.openInventory(m.inv);
    }

    private static void ding(Player p) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f); }

    static final class Listen implements Listener {
        @EventHandler
        public void click(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() != m.inv) return;
            Consumer<InventoryClickEvent> a = m.acciones.get(e.getRawSlot());
            if (a != null) a.accept(e);
        }

        @EventHandler
        public void drag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
        }
    }
}
