package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;

/**
 * /tod4:totems: un menu donde se ponen los totems que NO dan ceguera al salvarte (cambio d14.totem).
 * Se compara por el id de Oraxen (si es de Oraxen) o por el item igual (sin contar la cantidad).
 */
public final class Totems {
    private Totems() {}

    private static final String CLAVE = "totems_sin_ceguera";
    private static final int SLOTS = 18;
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");

    @SuppressWarnings("unchecked")
    private static List<ItemStack> lista(TOD4 pl) {
        List<?> l = pl.datos().y().getList(CLAVE);
        List<ItemStack> out = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof ItemStack it && !it.getType().isAir()) out.add(it);
        return out;
    }

    private static String oraxen(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING);
    }

    /** true = ese totem esta en la lista (no da ceguera). */
    public static boolean libre(TOD4 pl, ItemStack usado) {
        if (usado == null || usado.getType().isAir()) return false;
        String ox = oraxen(usado);
        for (ItemStack it : lista(pl)) {
            String o2 = oraxen(it);
            if (ox != null || o2 != null) { if (ox != null && ox.equals(o2)) return true; continue; }
            if (it.isSimilar(usado)) return true;
        }
        return false;
    }

    public static void abrir(TOD4 pl, Player p) {
        Menu m = new Menu(3, "&8Tótems sin ceguera");
        for (int i = 0; i < SLOTS; i++) m.editable(i);
        List<ItemStack> l = lista(pl);
        for (int i = 0; i < l.size() && i < SLOTS; i++) m.getInventory().setItem(i, l.get(i).clone());
        for (int i = SLOTS; i < 27; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(22, Items.of(Material.TOTEM_OF_UNDYING, "&e&lTótems sin ceguera", "",
                "&7Pon arriba los tótems que", "&7NO dan ceguera al salvarte", "&7(cambio del día 14).", "",
                "&7Se guarda al cerrar. Quedan guardados", "&7acá: si sacas uno, deja la lista."));
        m.onClose(e -> {
            List<ItemStack> nueva = new ArrayList<>();
            for (int i = 0; i < SLOTS; i++) {
                ItemStack it = e.getInventory().getItem(i);
                if (it != null && !it.getType().isAir()) nueva.add(it.clone());
            }
            // quedan guardados en el menu (si sacas uno, deja de estar en la lista y es tuyo)
            pl.datos().set(CLAVE, nueva);
            Style.ok(p, "Tótems sin ceguera guardados: &f" + nueva.size() + "&a.");
        });
        m.open(p);
    }
}
