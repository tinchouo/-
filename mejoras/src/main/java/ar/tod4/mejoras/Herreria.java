package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Mesa de herreria: mejora (izquierda) + pieza permitida (medio) + nada (derecha) = la misma pieza (encantamientos,
 * durabilidad y nombre intactos) con el adorno de Silencio del color de la mejora y su linea en la descripcion.
 * El resultado lo entrega el plugin (sin receta de Minecraft), asi la derecha puede ir vacia.
 * Los ornamentos de Minecraft no se pueden usar.
 */
final class Herreria implements Listener {
    private final TOD4Mejoras pl;

    Herreria(TOD4Mejoras pl) { this.pl = pl; }

    private static boolean esOrnamento(ItemStack it) {
        return it != null && it.getType().name().endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE");
    }

    /** El resultado (o null si no corresponde). */
    private ItemStack resultado(SmithingInventory inv) {
        ItemStack tpl = inv.getInputTemplate(), base = inv.getInputEquipment(), extra = inv.getInputMineral();
        Mejora m = pl.mejoraDe(tpl);
        if (m == null || base == null || !pl.piezas.contains(base.getType())) return null;
        if (extra != null && !extra.getType().isAir()) return null;   // la derecha tiene que ir vacia
        ItemStack out = base.clone();
        out.setAmount(1);
        ItemMeta meta = out.getItemMeta();
        if (meta instanceof ArmorMeta am) am.setTrim(new ArmorTrim(m.adorno, TrimPattern.SILENCE));
        meta.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM);
        // la linea de la mejora anterior (si tenia) se saca; la nueva va primera
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(l -> {
            String t = PlainTextComponentSerializer.plainText().serialize(l);
            for (Mejora x : pl.mejoras.values()) if (t.equals(x.nombre)) return true;
            return false;
        });
        lore.add(0, TOD4Mejoras.c(m.nombre, m.color));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(pl.K_MEJORA, PersistentDataType.STRING, m.id);
        out.setItemMeta(meta);
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepara(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack tpl = inv.getInputTemplate();
        if (pl.mejoraDe(tpl) == null) {
            // un ornamento de Minecraft (no una mejora): no se puede usar
            if (pl.cfg.getBoolean("bloquear_ornamentos_originales", true) && esOrnamento(tpl)) e.setResult(null);
            return;
        }
        e.setResult(resultado(inv));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void saca(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof SmithingInventory inv)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() != 3) {
            // cualquier cambio en los huecos (en cualquier orden): se recalcula al tick siguiente
            recalcular(p, inv);
            return;
        }
        Mejora m = pl.mejoraDe(inv.getInputTemplate());
        if (m == null) return;
        e.setCancelled(true);
        ItemStack res = resultado(inv);
        if (res == null) { inv.setResult(null); return; }
        switch (e.getClick()) {
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (p.getInventory().firstEmpty() < 0) return;
                p.getInventory().addItem(res);
            }
            case LEFT, RIGHT -> {
                ItemStack cur = p.getItemOnCursor();
                if (cur != null && !cur.getType().isAir()) return;
                p.setItemOnCursor(res);
            }
            default -> { return; }
        }
        // se gastan la mejora y la pieza vieja
        ItemStack tpl = inv.getInputTemplate();
        if (tpl != null) { tpl.setAmount(tpl.getAmount() - 1); inv.setItem(0, tpl.getAmount() <= 0 ? null : tpl); }
        inv.setItem(1, null);
        inv.setResult(null);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
        Bukkit.getScheduler().runTask(pl, p::updateInventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void arrastra(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory() instanceof SmithingInventory inv)) return;
        if (e.getRawSlots().contains(3)) { e.setCancelled(true); return; }
        if (e.getWhoClicked() instanceof Player p) recalcular(p, inv);
    }

    /** Un tick despues (cuando los items ya estan en su lugar): si hay una mejora, su resultado; si no, lo de Minecraft. */
    private void recalcular(Player p, SmithingInventory inv) {
        Bukkit.getScheduler().runTask(pl, () -> {
            if (p.getOpenInventory().getTopInventory() != inv) return;
            ItemStack tpl = inv.getInputTemplate();
            if (pl.mejoraDe(tpl) != null) inv.setResult(resultado(inv));
            else if (pl.cfg.getBoolean("bloquear_ornamentos_originales", true) && esOrnamento(tpl)) inv.setResult(null);
        });
    }

    /** Crafteo apagado desde /mejoras: la mesa de crafteo no da nada. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void craftea(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !pl.esReceta(k.getKey())) return;
        Mejora m = pl.mejoraDe(e.getRecipe().getResult());
        if (m != null && !pl.crafteoOn(m)) e.getInventory().setResult(null);
    }
}
