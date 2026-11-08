package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

/**
 * Armadura Nightmare (mejoras.yml, seccion "nightmare"):
 *
 *   R R R      R = Resina Reforzada (Oraxen)
 *   C P C      P = pieza de netherite (casco, pechera, pantalones o botas)   C = obsidiana llorosa
 *   S S S      S = Nightmare Sculk (Oraxen)
 *
 * Da la misma pieza de la armadura Nightmare (Oraxen) con los MISMOS encantamientos, la mejora que tenia
 * (y su adorno), lo de los demas plugins guardado en la pieza, y la durabilidad al maximo.
 *
 * Los materiales los hace el plugin (no hace falta pack ni Oraxen): se ven como el bloque normal, encantado.
 *   Nightmare Sculk     = 4 fragmentos de eco en cuadrado (2x2)
 *   Resina Reforzada    = 9 bloques de resina (3x3)
 */
final class Nightmare implements Listener {

    private final TOD4Mejoras pl;
    private final NamespacedKey clave, kSculk, kResina, kMaterial;
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");

    Nightmare(TOD4Mejoras pl) {
        this.pl = pl;
        this.clave = new NamespacedKey(pl, "nightmare");
        this.kSculk = new NamespacedKey(pl, "nightmare_sculk");
        this.kResina = new NamespacedKey(pl, "resina_reforzada");
        this.kMaterial = new NamespacedKey(pl, "material");
        registrar();
    }

    private ConfigurationSection c() { return pl.cfg.getConfigurationSection("nightmare"); }

    private String cfg(String k, String def) { return c() == null ? def : c().getString(k, def); }

    private boolean activa() { return c() == null || c().getBoolean("activada", true); }

    void registrar() {
        Bukkit.removeRecipe(clave);
        Bukkit.removeRecipe(kSculk);
        Bukkit.removeRecipe(kResina);
        if (!activa()) return;
        ShapedRecipe sc = new ShapedRecipe(kSculk, material("nightmare_sculk"));
        sc.shape("EE", "EE");
        sc.setIngredient('E', Material.ECHO_SHARD);
        Bukkit.addRecipe(sc);
        ShapedRecipe re = new ShapedRecipe(kResina, material("resina_reforzada"));
        re.shape("RRR", "RRR", "RRR");
        re.setIngredient('R', Material.RESIN_BLOCK);
        Bukkit.addRecipe(re);
        ShapedRecipe r = new ShapedRecipe(clave, muestra());
        r.shape("RRR", "CPC", "SSS");
        r.setIngredient('R', new RecipeChoice.MaterialChoice(Material.PAPER));
        r.setIngredient('S', new RecipeChoice.MaterialChoice(Material.PAPER));
        r.setIngredient('C', Material.CRYING_OBSIDIAN);
        r.setIngredient('P', new RecipeChoice.MaterialChoice(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS));
        Bukkit.addRecipe(r);
    }

    /** El material: papel que se ve como el bloque normal (sculk o resina), encantado, con su nombre y su marca. */
    ItemStack material(String tipo) {
        boolean sculk = tipo.equals("nightmare_sculk");
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.minecraft(sculk ? "sculk" : "resin_block"));
        m.setEnchantmentGlintOverride(true);
        m.itemName(sculk ? TOD4Mejoras.c("Nightmare Sculk", "#c77dff").decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
                : TOD4Mejoras.c("Resina Reforzada", "#ffb347").decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        m.lore(List.of(TOD4Mejoras.gris(sculk ? "Sculk cargado de pesadillas." : "Nueve bloques de resina en uno."),
                Component.text("Sirve para la armadura Nightmare.", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(kMaterial, PersistentDataType.STRING, tipo);
        it.setItemMeta(m);
        return it;
    }

    /** Si el item es ese material (el del plugin o, si hay, el de Oraxen con ese nombre). */
    private boolean es(ItemStack it, String tipo, String oraxenId) {
        if (it == null || !it.hasItemMeta()) return false;
        String t = it.getItemMeta().getPersistentDataContainer().get(kMaterial, PersistentDataType.STRING);
        return tipo.equals(t) || oraxenId.equals(idOraxen(it));
    }

    private ItemStack muestra() {
        ItemStack it = oraxen(cfg("pechera", "green_pro_chestplate"));
        return it != null ? it : new ItemStack(Material.NETHERITE_CHESTPLATE);
    }

    private static String idOraxen(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING);
    }

    static ItemStack oraxen(String id) {
        try {
            Class<?> k = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Object b = k.getMethod("getItemById", String.class).invoke(null, id);
            return b == null ? null : (ItemStack) b.getClass().getMethod("build").invoke(b);
        } catch (Throwable t) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepara(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !k.getKey().equals(clave)) return;
        ItemStack[] m = e.getInventory().getMatrix();
        e.getInventory().setResult(m.length == 9 ? resultado(m) : null);
    }

    /** La pieza Nightmare, o null si algo no va (ingredientes que no son los de Oraxen, pieza que ya es de Oraxen...). */
    private ItemStack resultado(ItemStack[] m) {
        String resina = cfg("resina", "resina_reforzada"), sculk = cfg("sculk", "nightmare_sculk");
        for (int i : new int[]{0, 1, 2}) if (!es(m[i], "resina_reforzada", resina)) return null;
        for (int i : new int[]{6, 7, 8}) if (!es(m[i], "nightmare_sculk", sculk)) return null;
        ItemStack vieja = m[4];
        if (vieja == null || idOraxen(vieja) != null) return null;   // solo netherite normal (no una que ya sea Nightmare)
        String id = switch (vieja.getType()) {
            case NETHERITE_HELMET -> cfg("casco", "green_pro_helmet");
            case NETHERITE_CHESTPLATE -> cfg("pechera", "green_pro_chestplate");
            case NETHERITE_LEGGINGS -> cfg("pantalones", "green_pro_leggings");
            case NETHERITE_BOOTS -> cfg("botas", "green_pro_boots");
            default -> null;
        };
        if (id == null) return null;
        ItemStack nueva = oraxen(id);
        if (nueva == null) return null;
        ItemMeta de = vieja.getItemMeta(), a = nueva.getItemMeta();
        // encantamientos
        for (Map.Entry<Enchantment, Integer> en : de.getEnchants().entrySet()) a.addEnchant(en.getKey(), en.getValue(), true);
        // todo lo guardado por los plugins (la mejora, el libro indestructible, etc.); el id de Oraxen queda el de la Nightmare
        de.getPersistentDataContainer().copyTo(a.getPersistentDataContainer(), true);
        a.getPersistentDataContainer().set(ORAXEN, PersistentDataType.STRING, id);
        // la mejora: su adorno y su renglon arriba de todo
        if (de instanceof ArmorMeta va && a instanceof ArmorMeta na && va.hasTrim()) {
            na.setTrim(va.getTrim());
            a.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM);
        }
        Mejora mj = pl.mejoraDe(vieja);
        if (mj != null) {
            List<Component> lore = a.lore() == null ? new ArrayList<>() : new ArrayList<>(a.lore());
            lore.add(0, TOD4Mejoras.c(mj.nombre, mj.color));
            a.lore(lore);
        }
        if (de.isUnbreakable()) a.setUnbreakable(true);
        if (de instanceof Repairable rv && a instanceof Repairable rn) rn.setRepairCost(rv.getRepairCost());
        if (a instanceof Damageable dn) dn.setDamage(0);   // durabilidad al maximo
        nueva.setItemMeta(a);
        nueva.setAmount(1);
        return nueva;
    }
}
