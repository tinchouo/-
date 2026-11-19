package ar.tod5.servidor;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * El boton "Ordenar" del mod. Ordena por grupo (bloques, minerales, herramientas/armas/armadura, comida, pociones, el resto),
 * dentro de cada grupo por nombre, y junta las pilas. Solo en lugares donde se guardan cosas; el inventario propio sin la barra de abajo.
 */
final class Ordenar {
	private final Map<UUID, Long> ultimo = new HashMap<>();

	boolean accion(Player p, JsonObject o) {
		long ahora = System.currentTimeMillis();
		Long u = ultimo.get(p.getUniqueId());
		if (u != null && ahora - u < 400) return true;   // que no lo spameen
		ultimo.put(p.getUniqueId(), ahora);
		boolean inventario = o.has("donde") && o.get("donde").getAsString().equals("inventario");
		if (inventario) { ordenar(p.getInventory(), 9, 36); return true; }
		Inventory arriba = p.getOpenInventory().getTopInventory();
		InventoryType t = arriba.getType();
		boolean vale = t == InventoryType.CHEST || t == InventoryType.BARREL || t == InventoryType.SHULKER_BOX || t == InventoryType.ENDER_CHEST
			|| arriba.getHolder() instanceof Mochilas.Ventana;
		if (vale) ordenar(arriba, 0, arriba.getSize());
		return true;
	}

	private static void ordenar(Inventory inv, int desde, int hasta) {
		List<ItemStack> l = new ArrayList<>();
		for (int i = desde; i < hasta; i++) { ItemStack it = inv.getItem(i); if (it != null && !it.getType().isAir()) l.add(it.clone()); }
		// juntar pilas iguales
		List<ItemStack> juntos = new ArrayList<>();
		for (ItemStack it : l) {
			int resta = it.getAmount();
			for (ItemStack j : juntos) {
				if (resta <= 0) break;
				if (j.isSimilar(it) && j.getAmount() < j.getMaxStackSize()) { int m = Math.min(resta, j.getMaxStackSize() - j.getAmount()); j.setAmount(j.getAmount() + m); resta -= m; }
			}
			if (resta > 0) { ItemStack n = it.clone(); n.setAmount(resta); juntos.add(n); }
		}
		juntos.sort(Comparator.comparingInt((ItemStack it) -> grupo(it.getType())).thenComparing(it -> it.getType().name()).thenComparing((ItemStack it) -> -it.getAmount()));
		for (int i = desde; i < hasta; i++) inv.setItem(i, null);
		int k = desde;
		for (ItemStack it : juntos) { if (k >= hasta) break; inv.setItem(k++, it); }
	}

	private static int grupo(Material m) {
		String n = m.name();
		if (n.endsWith("_ORE") || n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.equals("DIAMOND") || n.equals("EMERALD")
			|| n.equals("LAPIS_LAZULI") || n.equals("REDSTONE") || n.equals("QUARTZ") || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("AMETHYST_SHARD")
			|| n.equals("NETHERITE_SCRAP") || n.equals("ANCIENT_DEBRIS")) return 1;
		if (m.getMaxDurability() > 0 || n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) return 2;
		if (m.isEdible()) return 3;
		if (n.endsWith("POTION") || n.equals("TIPPED_ARROW")) return 4;
		if (m.isBlock()) return 0;
		return 5;
	}
}
