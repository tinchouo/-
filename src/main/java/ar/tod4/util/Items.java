package ar.tod4.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

public final class Items {
    private Items() {}

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, name, Arrays.asList(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Txt.c(name));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Txt.c(s));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack pane(Material m) {
        return of(m, " ");
    }

    /** Nombre visible de un item (custom o el nombre del material). */
    public static String name(ItemStack it) {
        if (it == null) return "nada";
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return Txt.plain(meta.displayName());
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static final NamespacedKey ORAXEN_ID = new NamespacedKey("oraxen", "id");

    /** ID de Oraxen del item, o null si es vanilla. */
    public static String oraxenId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(ORAXEN_ID, PersistentDataType.STRING);
    }

    /** Mismo "tipo" de item: si es de Oraxen compara el ID de Oraxen, si no el material. */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        String ia = oraxenId(a), ib = oraxenId(b);
        if (ia != null || ib != null) return ia != null && ia.equals(ib);
        return a.getType() == b.getType();
    }
}
