package ar.tod5.servidor;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Las monedas de TOD5: Trucoin (amarilla), Fracoin (roja), Tinchoin (morada) y Truquita (la ficha de casino).
 * El server es Paper y no conoce items de mods: son pepitas de oro con el dibujo y el nombre del mod
 * (tod5:trucoin, item.tod5.trucoin...) y una marca propia,
 * asi no se pueden usar como pepitas en la mesa de crafteo.
 * Por ahora solo existen: para que sirve cada una se decide despues.
 *
 * /tod moneda <trucoin|fracoin|tinchoin|truquita> [cantidad] [jugador]   (solo editores, para probar)
 */
final class Monedas implements Listener {
	enum Tipo {
		TRUCOIN("trucoin", "Trucoin", 0xF2C21C),     // amarilla
		FRACOIN("fracoin", "Fracoin", 0xD8382E),     // roja
		TINCHOIN("tinchoin", "Tinchoin", 0xA050D8),  // morada
		TRUQUITA("truquita", "Truquita", 0x3AD86A);  // la ficha de casino (verde)
		final String modelo, nombre; final int color;
		Tipo(String modelo, String nombre, int color) { this.modelo = modelo; this.nombre = nombre; this.color = color; }
		static Tipo de(String s) {
			for (Tipo t : values()) if (t.name().equalsIgnoreCase(s) || t.modelo.equalsIgnoreCase(s)) return t;
			return switch (s.toLowerCase(Locale.ROOT)) { case "amarilla" -> TRUCOIN; case "roja" -> FRACOIN; case "morada" -> TINCHOIN; case "ficha", "verde" -> TRUQUITA; default -> null; };
		}
	}

	static final int MAX = 99;
	private final NamespacedKey marca;
	Monedas(TOD5Servidor plugin) { this.marca = new NamespacedKey(plugin, "moneda"); }

	ItemStack crear(Tipo t, int cantidad) {
		ItemStack it = new ItemStack(Material.GOLD_NUGGET);
		ItemMeta m = it.getItemMeta();
		m.setItemModel(new NamespacedKey("tod5", t.modelo));
		m.setMaxStackSize(MAX);   // se apilan hasta 99 (igual que las del creativo del mod)
		m.itemName(Component.translatable("item.tod5." + t.modelo));   // el nombre lo pone el mod (como un item de verdad)
		m.getPersistentDataContainer().set(marca, PersistentDataType.STRING, t.name().toLowerCase(Locale.ROOT));
		it.setItemMeta(m);
		it.setAmount(Math.max(1, Math.min(MAX, cantidad)));   // despues de subir el tope a 99
		return it;
	}
	boolean esMoneda(ItemStack it) {
		return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(marca, PersistentDataType.STRING);
	}

	/** Que no se puedan usar como pepitas de oro (9 pepitas = lingote). */
	@EventHandler
	public void alCraftear(PrepareItemCraftEvent e) {
		for (ItemStack it : e.getInventory().getMatrix()) if (esMoneda(it)) { e.getInventory().setResult(null); return; }
	}

	/** /tod moneda <tipo> [cantidad] [jugador] */
	void comando(CommandSender s, String[] args) {
		Tipo t = args.length > 1 ? Tipo.de(args[1]) : null;
		if (t == null) { s.sendMessage(Component.text("Uso: /tod moneda <trucoin|fracoin|tinchoin|truquita> [cantidad] [jugador]", NamedTextColor.YELLOW)); return; }
		int n = 1;
		if (args.length > 2) try { n = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { s.sendMessage(Component.text("La cantidad tiene que ser un número.", NamedTextColor.RED)); return; }
		Player p = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : (s instanceof Player yo ? yo : null);
		if (p == null) { s.sendMessage(Component.text("¿A quién? Poné el nombre del jugador.", NamedTextColor.RED)); return; }
		p.getInventory().addItem(crear(t, n)).values().forEach(sobra -> p.getWorld().dropItem(p.getLocation(), sobra));
		s.sendMessage(Component.text("Le di " + Math.max(1, Math.min(MAX, n)) + " × " + t.nombre + " a " + p.getName() + ".", NamedTextColor.GREEN));
	}
	static List<String> tipos() { return List.of("trucoin", "fracoin", "tinchoin", "truquita"); }
}
