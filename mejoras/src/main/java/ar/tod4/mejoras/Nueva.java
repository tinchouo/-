package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

/**
 * La armadura nueva (mejoras.yml, seccion "armadura_nueva"), en la mesa de crafteo:
 *
 *   P P P      N = pieza de la armadura Nightmare (casco, pechera, pantalones o botas)
 *   P N P      P = piel de conejo: "cantidad" (8) en CADA casilla -> 64 en total
 *   P P P
 *
 * Da la misma pieza de la armadura nueva, sin encantamientos ni mejoras. Las 8 de cada casilla las descuenta el
 * plugin (la mesa normal solo gasta 1 por casilla): si hay mas, sobran y quedan. El shift-click hace una por vez.
 * La armadura nueva acepta 2 mejoras en la mesa de herreria (ver Herreria).
 */
final class Nueva implements Listener {

    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");
    private static final String[] PIEZAS = {"casco", "pechera", "pantalones", "botas"};
    // por si mejoras.yml no tiene la seccion (los nombres de Oraxen de la armadura nueva)
    static final String[] NUEVA_DEF = {"todarmor_helmet", "todarmor_chestplate", "todarmor_leggings", "todarmor_boots"};

    private final TOD4Mejoras pl;
    private final NamespacedKey clave;

    Nueva(TOD4Mejoras pl) {
        this.pl = pl;
        this.clave = new NamespacedKey(pl, "armadura_nueva");
        registrar();
    }

    private static ConfigurationSection c(TOD4Mejoras pl) { return pl.cfg.getConfigurationSection("armadura_nueva"); }

    private static String cfg(TOD4Mejoras pl, String k, String def) { ConfigurationSection c = c(pl); return c == null ? def : c.getString(k, def); }

    private boolean activa() { ConfigurationSection c = c(pl); return c == null || c.getBoolean("activada", true); }

    private int cantidad() { ConfigurationSection c = c(pl); return c == null ? 8 : Math.max(1, Math.min(64, c.getInt("cantidad", 8))); }

    private Material material() {
        Material m = Material.matchMaterial(cfg(pl, "material", "RABBIT_HIDE"));
        return m != null ? m : Material.RABBIT_HIDE;
    }

    /** Los nombres de Oraxen de la armadura nueva: casco, pechera, pantalones, botas. */
    static List<String> ids(TOD4Mejoras pl) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) l.add(cfg(pl, PIEZAS[i], NUEVA_DEF[i]));
        return l;
    }

    /** Los de la Nightmare (seccion "nightmare"). */
    private List<String> nightmare() {
        ConfigurationSection n = pl.cfg.getConfigurationSection("nightmare");
        String[] def = {"green_pro_helmet", "green_pro_chestplate", "green_pro_leggings", "green_pro_boots"};
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) l.add(n == null ? def[i] : n.getString(PIEZAS[i], def[i]));
        return l;
    }

    void registrar() {
        Bukkit.removeRecipe(clave);
        if (!activa()) return;
        List<Material> armaduras = new ArrayList<>();
        for (Material m : Material.values())
            if (!m.isLegacy() && m.isItem() && (m.name().endsWith("_HELMET") || m.name().endsWith("_CHESTPLATE")
                    || m.name().endsWith("_LEGGINGS") || m.name().endsWith("_BOOTS"))) armaduras.add(m);
        ItemStack muestra = Nightmare.oraxen(ids(pl).get(1));
        ShapedRecipe r = new ShapedRecipe(clave, muestra != null ? muestra : new ItemStack(Material.NETHERITE_CHESTPLATE));
        r.shape("PPP", "PNP", "PPP");
        r.setIngredient('P', material());
        r.setIngredient('N', new RecipeChoice.MaterialChoice(armaduras));
        Bukkit.addRecipe(r);
    }

    private static String idOraxen(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING);
    }

    /** La pieza nueva, o null si no va (no es Nightmare, o alguna casilla tiene menos de 8). */
    private ItemStack resultado(ItemStack[] m) {
        if (m == null || m.length != 9) return null;
        int n = cantidad();
        Material mat = material();
        for (int i = 0; i < 9; i++) {
            if (i == 4) continue;
            ItemStack x = m[i];
            if (x == null || x.getType() != mat || x.getAmount() < n) return null;
            if (x.hasItemMeta() && idOraxen(x) != null) return null;   // la piel de conejo normal
        }
        int pieza = nightmare().indexOf(idOraxen(m[4]));
        if (pieza < 0) return null;
        String id = ids(pl).get(pieza);
        if (id == null || id.isBlank() || id.contains("?")) return null;
        ItemStack nueva = Nightmare.oraxen(id);
        if (nueva == null) {
            pl.getLogger().warning("Oraxen no tiene el item \"" + id + "\" (mejoras.yml -> armadura_nueva -> " + PIEZAS[pieza] + ").");
            return null;
        }
        nueva.setAmount(1);
        return nueva;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepara(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !k.getKey().equals(clave)) return;
        e.getInventory().setResult(resultado(e.getInventory().getMatrix()));
    }

    /** Sacar el resultado: lo hace el plugin (asi descuenta 8 de cada casilla y no se buguea). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void craftea(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !k.getKey().equals(clave)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        CraftingInventory inv = e.getInventory();
        ItemStack[] m = inv.getMatrix();
        ItemStack res = resultado(m);
        if (res == null) { inv.setResult(null); return; }
        ClickType ct = e.getClick();
        if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
            if (p.getInventory().firstEmpty() == -1) return;
            p.getInventory().addItem(res);
        } else if (ct == ClickType.LEFT || ct == ClickType.RIGHT) {
            ItemStack cur = p.getItemOnCursor();
            if (cur != null && !cur.getType().isAir()) return;
            p.setItemOnCursor(res);
        } else return;   // numeros, Q, etc.: nada
        int n = cantidad();
        for (int i = 0; i < 9; i++) {
            ItemStack x = m[i];
            if (x == null) continue;
            int queda = x.getAmount() - (i == 4 ? 1 : n);
            if (queda <= 0) m[i] = null; else x.setAmount(queda);
        }
        inv.setMatrix(m);
        inv.setResult(resultado(m));
        Bukkit.getScheduler().runTask(pl, p::updateInventory);
    }
}
