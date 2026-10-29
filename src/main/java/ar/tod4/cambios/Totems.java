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
 * /tod4:totems: dos listas de totems.
 *  - Sin ceguera: no dan la ceguera del cambio d14.totem al salvarte.
 *  - Pueden fallar: con el cambio d14.totem_falla, tienen una chance (1% de entrada) de no salvarte.
 * Se compara por el id de Oraxen (si es de Oraxen) o por el item igual (sin contar la cantidad).
 */
public final class Totems {
    private Totems() {}

    private static final String SIN_CEGUERA = "totems_sin_ceguera", FALLAN = "totems_fallan", CHANCE = "totems_falla_chance";
    private static final int SLOTS = 18;
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");

    private static List<ItemStack> lista(TOD4 pl, String clave) {
        List<?> l = pl.datos().y().getList(clave);
        List<ItemStack> out = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof ItemStack it && !it.getType().isAir()) out.add(it);
        return out;
    }

    private static String oraxen(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING);
    }

    private static boolean esta(TOD4 pl, String clave, ItemStack usado) {
        if (usado == null || usado.getType().isAir()) return false;
        String ox = oraxen(usado);
        for (ItemStack it : lista(pl, clave)) {
            String o2 = oraxen(it);
            if (ox != null || o2 != null) { if (ox != null && ox.equals(o2)) return true; continue; }
            if (it.isSimilar(usado)) return true;
        }
        return false;
    }

    /** true = ese totem no da ceguera. */
    public static boolean libre(TOD4 pl, ItemStack usado) { return esta(pl, SIN_CEGUERA, usado); }

    /** true = ese totem puede fallar. */
    public static boolean falla(TOD4 pl, ItemStack usado) { return esta(pl, FALLAN, usado); }

    /** % de que falle un totem de la lista "pueden fallar". */
    public static double chance(TOD4 pl) { return pl.datos().y().getDouble(CHANCE, 1.0); }

    private static String pct(double v) {
        return (v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v)) + "%";
    }

    /** El menu principal: las dos listas y la chance de fallar. */
    public static void abrir(TOD4 pl, Player p) {
        Menu m = new Menu(3, "&8Tótems");
        m.set(11, Items.of(Material.TOTEM_OF_UNDYING, "&e&lSin ceguera", "",
                "&7Los tótems que &fNO &7dan ceguera", "&7al salvarte (día 14).", "",
                "&7Tótems: &f" + lista(pl, SIN_CEGUERA).size(), "", "&e▶ Click para editar"),
                e -> { Style.click(p); lista(pl, p, SIN_CEGUERA, "&8Tótems sin ceguera", "&7NO dan ceguera al salvarte."); });
        m.set(13, Items.of(Material.SKELETON_SKULL, "&c&lPueden fallar", "",
                "&7Con el cambio &fTótems que fallan&7,", "&7estos tienen " + pct(chance(pl)) + " de no salvarte.", "",
                "&7Tótems: &f" + lista(pl, FALLAN).size(), "", "&e▶ Click para editar"),
                e -> { Style.click(p); lista(pl, p, FALLAN, "&8Tótems que pueden fallar", "&7pueden fallar (" + pct(chance(pl)) + ")."); });
        m.set(15, Items.of(Material.CLOCK, "&6&lChance de fallar: &f" + pct(chance(pl)), "",
                "&eClick: &f+1%   &eClick derecho: &f-1%", "&eShift: &f±0,1%"), e -> {
            double paso = e.isShiftClick() ? 0.1 : 1;
            double v = chance(pl) + (e.isRightClick() ? -paso : paso);
            v = Math.max(0, Math.min(100, Math.round(v * 10) / 10.0));
            pl.datos().set(CHANCE, v);
            Style.click(p);
            abrir(pl, p);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private static void lista(TOD4 pl, Player p, String clave, String titulo, String que) {
        Menu m = new Menu(3, titulo);
        for (int i = 0; i < SLOTS; i++) m.editable(i);
        List<ItemStack> l = lista(pl, clave);
        for (int i = 0; i < l.size() && i < SLOTS; i++) m.getInventory().setItem(i, l.get(i).clone());
        for (int i = SLOTS; i < 27; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(22, Items.of(Material.TOTEM_OF_UNDYING, "&e&lInfo", "", "&7Pon arriba los tótems que", que, "",
                "&7Se guarda al cerrar. Quedan guardados", "&7acá: si sacas uno, deja la lista."));
        m.onClose(e -> {
            List<ItemStack> nueva = new ArrayList<>();
            for (int i = 0; i < SLOTS; i++) {
                ItemStack it = e.getInventory().getItem(i);
                if (it != null && !it.getType().isAir()) nueva.add(it.clone());
            }
            pl.datos().set(clave, nueva);
            Style.ok(p, "Tótems guardados: &f" + nueva.size() + "&a.");
        });
        m.open(p);
    }
}
