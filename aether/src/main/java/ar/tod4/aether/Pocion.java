package ar.tod4.aether;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Pocion del Aether: Levitacion muy fuerte. Se escribe como las pociones normales (el efecto en azul, como uno bueno).
 * Lleva la marca tod4:aether_pocion, asi se puede usar en recetas (plugins de crafteo) y el plugin la reconoce.
 */
final class Pocion {

    private static NamespacedKey KEY;

    private Pocion() { }

    /** La marca es tod4:aether_pocion (la misma de cuando estaba en TOD4: las pociones que ya existen siguen andando). */
    static void init(Plugin pl) { KEY = new NamespacedKey("tod4", "aether_pocion"); }

    static ItemStack crear(int nivel, int segundos) {
        ItemStack it = new ItemStack(Material.POTION);
        PotionMeta m = (PotionMeta) it.getItemMeta();
        m.setColor(Color.fromRGB(0xA6E4FF));
        m.addCustomEffect(new PotionEffect(PotionEffectType.LEVITATION, segundos * 20, nivel - 1, false, true, true), true);
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        // en las pociones el juego ignora item_name y escribe "Poción no fabricable": va como nombre propio, en celeste
        m.displayName(Component.text("Poción del Aether", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(linea("Levitación " + romano(nivel) + " (" + tiempo(segundos) + ")", NamedTextColor.BLUE));
        lore.add(Component.empty());
        lore.add(linea("Bébela para ascender al reino de los cielos.", NamedTextColor.GRAY));
        lore.add(linea("Nunca la bebas sin los pies sobre la tierra.", NamedTextColor.RED));
        m.lore(lore);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    static boolean es(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    /** Tiene el texto nuevo. */
    static boolean alDia(ItemStack it) {
        if (!it.getItemMeta().hasDisplayName()) return false;
        List<Component> l = it.getItemMeta().lore();
        if (l == null) return false;
        for (Component c : l) if (net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).startsWith("Nunca la bebas")) return true;
        return false;
    }

    private static Component linea(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static String tiempo(int s) { return (s / 60) + ":" + String.format("%02d", s % 60); }

    static String romano(int n) {
        int[] v = {100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] r = {"C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { b.append(r[i]); n -= v[i]; }
        return b.toString();
    }
}
