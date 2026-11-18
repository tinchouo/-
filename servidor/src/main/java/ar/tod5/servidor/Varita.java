package ar.tod5.servidor;

import com.google.gson.JsonObject;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * La varita (verde) de los editores: sirve para editar. Clic derecho en algo puesto (flecha, orbe, pad, plataforma): abre su menu.
 * (Las cosas se ponen con sus items, desde la pestaña Trucofdeath del creativo.)
 */
final class Varita implements Listener {
	static final List<String> COSAS = List.of("flecha", "orbe_amarilla", "orbe_roja", "pad_amarilla", "pad_roja", "plataforma");
	private static final Map<String, String> NOMBRES = Map.of("flecha", "Flecha de gravedad", "orbe_amarilla", "Orbe amarilla", "orbe_roja", "Orbe roja",
		"pad_amarilla", "Pad amarillo", "pad_roja", "Pad rojo", "plataforma", "Plataforma");
	private final TOD5Servidor plugin;
	private final NamespacedKey marca;
	private final Map<UUID, String> elegido = new HashMap<>();

	Varita(TOD5Servidor plugin) { this.plugin = plugin; this.marca = new NamespacedKey(plugin, "varita"); }

	static ItemStack boton(Material m, String nombre, NamedTextColor c, String... lore) {
		ItemStack it = new ItemStack(m);
		ItemMeta im = it.getItemMeta();
		im.itemName(Component.text(nombre, c));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		im.lore(l);
		it.setItemMeta(im);
		return it;
	}

	ItemStack item() {
		ItemStack it = new ItemStack(Material.STICK);
		ItemMeta m = it.getItemMeta();
		m.itemName(Component.text("Varita TOD5", NamedTextColor.GREEN));
		m.lore(List.of(
			Component.text("Clic derecho en una flecha, orbe, pad o plataforma: configurarla.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
		m.setItemModel(new NamespacedKey("tod5", "varita"));
		m.setEnchantmentGlintOverride(false);
		m.getPersistentDataContainer().set(marca, PersistentDataType.BYTE, (byte) 1);
		it.setItemMeta(m);
		return it;
	}
	boolean es(ItemStack it) {
		return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(marca, PersistentDataType.BYTE);
	}
	private String elegido(Player p) { return elegido.getOrDefault(p.getUniqueId(), "flecha"); }

	/** Con la varita en la mano no se rompe ni se usa nada; clic derecho en un bloque solo sirve para poner el final de una plataforma. */
	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || !es(e.getItem())) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		boolean bloque = e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_BLOCK;
		if (bloque && e.getClickedBlock() != null && plugin.plataformas.esperandoFinal(p))
			plugin.plataformas.elegirEsquina(p, e.getClickedBlock().getRelative(e.getBlockFace()).getLocation(), e.getAction() == Action.LEFT_CLICK_BLOCK);
	}

	void comando(org.bukkit.command.CommandSender s) {
		if (s instanceof Player p) { p.getInventory().addItem(item()); s.sendMessage(Component.text("Te di la varita: clic derecho en algo puesto para configurarlo.", NamedTextColor.GREEN)); }
		else s.sendMessage(Component.text("Solo un jugador.", NamedTextColor.YELLOW));
	}
	/** Para la pestaña Trucofdeath del creativo. */
	JsonObject itemParaTablet() {
		JsonObject j = new JsonObject(); j.addProperty("id", "varita"); j.add("item", Crafteos.itemJson(item()));
		return j;
	}
}
