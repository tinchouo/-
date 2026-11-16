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
 * La varita (verde) de los editores: una sola cosa para poner y configurar todo lo del mapa.
 *  - clic derecho en un bloque: pone lo elegido al lado de ese bloque (en la cara que tocaste)
 *  - shift + clic derecho (sin apuntar a nada puesto): elegis que poner
 *  - clic izquierdo en algo puesto (flecha, orbe, pad): abre su menu
 */
final class Varita implements Listener {
	static final List<String> COSAS = List.of("flecha", "orbe_amarilla", "orbe_roja", "pad_amarilla", "pad_roja");
	private static final Map<String, String> NOMBRES = Map.of("flecha", "Flecha de gravedad", "orbe_amarilla", "Orbe amarilla", "orbe_roja", "Orbe roja",
		"pad_amarilla", "Pad amarillo", "pad_roja", "Pad rojo");
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
			Component.text("Clic derecho en un bloque: poner lo elegido.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Shift + clic derecho: elegir qué poner.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Clic izquierdo en algo puesto: configurarlo.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || !es(e.getItem())) return;
		Action a = e.getAction();
		if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) { if (a == Action.LEFT_CLICK_BLOCK) e.setCancelled(true); return; }
		if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		if (!plugin.tablero().esEditor(p)) { p.sendMessage(Component.text("La varita es solo para los editores.", NamedTextColor.RED)); return; }
		if (p.isSneaking()) { abrirElegir(p); return; }
		if (a != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
			p.sendActionBar(Component.text("Apuntá a un bloque para poner: " + NOMBRES.get(elegido(p)), NamedTextColor.YELLOW));
			return;
		}
		BlockFace cara = e.getBlockFace();
		Location celda = e.getClickedBlock().getRelative(cara).getLocation();
		String c = elegido(p);
		if (c.equals("flecha")) {
			Location l = celda.clone().add(0.5, 0.5, 0.5); l.setYaw(p.getLocation().getYaw());
			plugin.flechas.crear(l);
		} else {
			String[] t = c.split("_");
			plugin.saltos.crear(celda, t[0], t[1]);
		}
		p.sendActionBar(Component.text("Puesto: " + NOMBRES.get(c) + "  (clic izquierdo en él: configurarlo)", NamedTextColor.GREEN));
	}

	// ================= elegir que poner =================
	private static final class Elegir implements InventoryHolder {
		Inventory inv;
		@Override public Inventory getInventory() { return inv; }
	}
	private void abrirElegir(Player p) {
		Elegir m = new Elegir();
		m.inv = Bukkit.createInventory(m, 9, Component.text("¿Qué pongo con la varita?"));
		String sel = elegido(p);
		Material[] mats = { Material.ARROW, Material.YELLOW_DYE, Material.RED_DYE, Material.YELLOW_CARPET, Material.RED_CARPET };
		for (int i = 0; i < COSAS.size(); i++) {
			String c = COSAS.get(i);
			m.inv.setItem(i, boton(mats[i], NOMBRES.get(c) + (c.equals(sel) ? "  ✔" : ""), c.equals(sel) ? NamedTextColor.GREEN : NamedTextColor.WHITE, "Clic: elegir"));
		}
		p.openInventory(m.inv);
	}
	@EventHandler
	public void alClic(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Elegir)) return;
		e.setCancelled(true);
		if (e.getClickedInventory() != e.getInventory() || !(e.getWhoClicked() instanceof Player p)) return;
		int i = e.getSlot();
		if (i < 0 || i >= COSAS.size()) return;
		elegido.put(p.getUniqueId(), COSAS.get(i));
		p.closeInventory();
		p.sendActionBar(Component.text("Varita: " + NOMBRES.get(COSAS.get(i)), NamedTextColor.GREEN));
	}
	@EventHandler
	public void alArrastrar(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Elegir) e.setCancelled(true); }

	void comando(org.bukkit.command.CommandSender s) {
		if (s instanceof Player p) { p.getInventory().addItem(item()); s.sendMessage(Component.text("Te di la varita. Shift + clic derecho para elegir qué poner.", NamedTextColor.GREEN)); }
		else s.sendMessage(Component.text("Solo un jugador.", NamedTextColor.YELLOW));
	}
	/** Para la pestaña Trucofdeath del creativo. */
	JsonObject itemParaTablet() {
		JsonObject j = new JsonObject(); j.addProperty("id", "varita"); j.add("item", Crafteos.itemJson(item()));
		return j;
	}
}
