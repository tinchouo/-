package ar.tod4.mejoras;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Pocion de Suerte I (5 minutos, la de Minecraft) en la mesa de crafteo:
 *
 *   B R B      B = bloque de esmeralda    R = pata de conejo
 *   G P G      G = manzana dorada         P = pocion rara (awkward)
 *   B R B
 */
final class Suerte implements Listener {

    private final NamespacedKey clave;

    Suerte(TOD4Mejoras pl) {
        this.clave = new NamespacedKey(pl, "pocion_suerte");
        Bukkit.removeRecipe(clave);
        ShapedRecipe r = new ShapedRecipe(clave, pocion());
        r.shape("BRB", "GPG", "BRB");
        r.setIngredient('B', Material.EMERALD_BLOCK);
        r.setIngredient('R', Material.RABBIT_FOOT);
        r.setIngredient('G', Material.GOLDEN_APPLE);
        r.setIngredient('P', Material.POTION);
        Bukkit.addRecipe(r);
    }

    NamespacedKey clave() { return clave; }

    static ItemStack pocion() {
        ItemStack it = new ItemStack(Material.POTION);
        PotionMeta m = (PotionMeta) it.getItemMeta();
        m.setBasePotionType(PotionType.LUCK);
        it.setItemMeta(m);
        return it;
    }

    /** El del medio tiene que ser una pocion rara (no agua ni otra pocion). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepara(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof org.bukkit.Keyed k) || !k.getKey().equals(clave)) return;
        ItemStack[] m = e.getInventory().getMatrix();
        boolean ok = m.length == 9 && m[4] != null && m[4].getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() == PotionType.AWKWARD;
        e.getInventory().setResult(ok ? pocion() : null);
    }
}
