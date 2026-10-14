package ar.tod4.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import ar.tod4.hook.Ext;
import net.kyori.adventure.text.Component;

/**
 * Objetivos de misiones y momentos, guardados como texto:
 *  mat:DIAMOND            item / bloque vanilla
 *  ox:mena_truco          item / bloque de Oraxen
 *  tag:minecraft:diamond_ores   grupo de bloques (todos los diamantes, etc.)
 *  ent:ZOMBIE             mob vanilla
 *  mm:EnderZombie         mob de MythicMobs
 *  adv:minecraft:story/mine_diamond   logro
 *  dim:world_nether       mundo / dimension
 */
public final class Obj {
    private Obj() {}

    /** Grupos rapidos para "Picar". */
    public static final Map<String, String> GRUPOS = new LinkedHashMap<>();
    static {
        GRUPOS.put("minecraft:diamond_ores", "Diamante (todas las menas)");
        GRUPOS.put("minecraft:gold_ores", "Oro (todas las menas)");
        GRUPOS.put("minecraft:iron_ores", "Hierro (todas las menas)");
        GRUPOS.put("minecraft:copper_ores", "Cobre (todas las menas)");
        GRUPOS.put("minecraft:coal_ores", "Carbón (todas las menas)");
        GRUPOS.put("minecraft:emerald_ores", "Esmeralda (todas las menas)");
        GRUPOS.put("minecraft:lapis_ores", "Lapislázuli (todas las menas)");
        GRUPOS.put("minecraft:redstone_ores", "Redstone (todas las menas)");
        GRUPOS.put("minecraft:logs", "Troncos (todos)");
        GRUPOS.put("minecraft:leaves", "Hojas (todas)");
    }

    public static Material grupoIcono(String key) {
        switch (key) {
            case "minecraft:diamond_ores": return Material.DIAMOND_ORE;
            case "minecraft:gold_ores": return Material.GOLD_ORE;
            case "minecraft:iron_ores": return Material.IRON_ORE;
            case "minecraft:copper_ores": return Material.COPPER_ORE;
            case "minecraft:coal_ores": return Material.COAL_ORE;
            case "minecraft:emerald_ores": return Material.EMERALD_ORE;
            case "minecraft:lapis_ores": return Material.LAPIS_ORE;
            case "minecraft:redstone_ores": return Material.REDSTONE_ORE;
            case "minecraft:logs": return Material.OAK_LOG;
            default: return Material.OAK_LEAVES;
        }
    }

    private static Tag<Material> tag(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, k, Material.class);
    }

    /** Texto del objetivo a partir de un item (Oraxen primero). */
    public static String deItem(ItemStack it) {
        String ox = Items.oraxenId(it);
        if (ox != null) return "ox:" + ox;
        String n = it.getType().name();
        if (n.endsWith("_SPAWN_EGG")) {
            try { return "ent:" + EntityType.valueOf(n.substring(0, n.length() - 10)).name(); } catch (IllegalArgumentException ignored) { }
        }
        return "mat:" + n;
    }

    // ------------------------------------------------------------------ coincidencias

    public static boolean item(String obj, ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (obj.startsWith("ox:")) return obj.substring(3).equals(Items.oraxenId(it));
        if (obj.startsWith("mat:")) return Items.oraxenId(it) == null && it.getType().name().equals(obj.substring(4));
        if (obj.startsWith("tag:")) { Tag<Material> t = tag(obj.substring(4)); return t != null && t.isTagged(it.getType()); }
        return false;
    }

    public static boolean item(List<String> objs, ItemStack it) {
        for (String o : objs) if (item(o, it)) return true;
        return false;
    }

    /** Bloque roto: material vanilla o id de Oraxen (oxId puede ser null). */
    public static boolean bloque(String obj, Material m, String oxId) {
        if (obj.startsWith("ox:")) return oxId != null && obj.substring(3).equals(oxId);
        if (oxId != null) return false;
        if (obj.startsWith("mat:")) return m.name().equals(obj.substring(4));
        if (obj.startsWith("tag:")) { Tag<Material> t = tag(obj.substring(4)); return t != null && t.isTagged(m); }
        return false;
    }

    public static boolean mob(String obj, Entity e, String mythic) {
        if (obj.startsWith("mm:")) return mythic != null && mythic.equalsIgnoreCase(obj.substring(3));
        if (obj.startsWith("ent:")) return mythic == null && e.getType().name().equals(obj.substring(4));
        return false;
    }

    public static boolean mundo(String obj, World w) {
        if (!obj.startsWith("dim:")) return false;
        String n = obj.substring(4);
        return w.getName().equalsIgnoreCase(n) || w.getKey().toString().equalsIgnoreCase(n);
    }

    /** Cuantos items de estos objetivos hay en el inventario (+ cursor). */
    public static int contar(PlayerInventory inv, List<String> objs) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (item(objs, it)) n += it.getAmount();
        if (item(objs, inv.getItemInOffHand())) n += inv.getItemInOffHand().getAmount();
        return n;
    }

    /** Saca 'cant' items de estos objetivos del inventario. */
    public static void sacar(PlayerInventory inv, List<String> objs, int cant) {
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length && cant > 0; i++) {
            if (!item(objs, c[i])) continue;
            int q = Math.min(cant, c[i].getAmount());
            c[i].setAmount(c[i].getAmount() - q);
            if (c[i].getAmount() <= 0) c[i] = null;
            cant -= q;
        }
        inv.setStorageContents(c);
        if (cant > 0 && item(objs, inv.getItemInOffHand())) {
            ItemStack o = inv.getItemInOffHand();
            o.setAmount(Math.max(0, o.getAmount() - cant));
            inv.setItemInOffHand(o.getAmount() <= 0 ? null : o);
        }
    }

    // ------------------------------------------------------------------ nombres (en el idioma de cada jugador)

    public static Component nombre(String obj) {
        try {
            if (obj.startsWith("mat:")) {
                Material m = Material.valueOf(obj.substring(4));
                return Component.translatable(m.isItem() ? m.getItemTranslationKey() : m.getBlockTranslationKey());
            }
            if (obj.startsWith("ox:")) {
                ItemStack it = Ext.oraxenItem(obj.substring(3));
                if (it != null) {
                    ItemMeta im = it.getItemMeta();
                    if (im != null && im.hasDisplayName()) return im.displayName();
                    if (im != null && im.hasItemName()) return im.itemName();
                }
                return Component.text(bonito(obj.substring(3)));
            }
            if (obj.equals("tag:minecraft:beds")) return Component.text("Cualquier cama");
            if (obj.startsWith("tag:")) return Component.text(GRUPOS.getOrDefault(obj.substring(4), bonito(obj.substring(4))));
            if (obj.startsWith("ent:")) return Component.translatable(EntityType.valueOf(obj.substring(4)).translationKey());
            if (obj.startsWith("mm:")) return Component.text(bonito(obj.substring(3)));
            if (obj.startsWith("adv:")) {
                NamespacedKey k = NamespacedKey.fromString(obj.substring(4));
                Advancement a = k == null ? null : Bukkit.getAdvancement(k);
                if (a != null) return a.displayName();
                return Component.text(obj.substring(4));
            }
            if (obj.startsWith("dim:")) {
                World w = Bukkit.getWorld(obj.substring(4));
                return Component.text(Txt.strip(w != null ? ar.tod4.sidebar.Sidebar.mundo(ar.tod4.TOD4.getPlugin(ar.tod4.TOD4.class), w) : obj.substring(4)));
            }
        } catch (Throwable ignored) { }
        return Component.text(obj);
    }

    /** Icono para mostrar el objetivo en un menu. */
    public static Material icono(String obj) {
        try {
            if (obj.startsWith("mat:")) {
                Material m = Material.valueOf(obj.substring(4));
                return m.isItem() ? m : Material.STONE;
            }
            if (obj.startsWith("ox:")) { ItemStack it = Ext.oraxenItem(obj.substring(3)); return it != null ? it.getType() : Material.NOTE_BLOCK; }
            if (obj.equals("tag:minecraft:beds")) return Material.RED_BED;
            if (obj.startsWith("tag:")) return grupoIcono(obj.substring(4));
            if (obj.startsWith("ent:")) { Material egg = Material.matchMaterial(obj.substring(4) + "_SPAWN_EGG"); return egg != null ? egg : Material.ZOMBIE_HEAD; }
            if (obj.startsWith("mm:")) return Material.WITHER_SKELETON_SKULL;
            if (obj.startsWith("adv:")) return Material.KNOWLEDGE_BOOK;
            if (obj.startsWith("dim:")) return Material.ENDER_EYE;
        } catch (Throwable ignored) { }
        return Material.PAPER;
    }

    public static String bonito(String s) {
        String n = s.replace('_', ' ').replace(':', ' ');
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** Bloque roto -> id de Oraxen o null. */
    public static String oxDe(Block b) { return Ext.oraxenBlock(b); }
}
