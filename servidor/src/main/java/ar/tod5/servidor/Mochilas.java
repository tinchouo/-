package ar.tod5.servidor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Mochilas: 4 niveles (18, 27, 36 y 45 espacios). Se tiñen en la mesa como el cuero y se renombran en el yunque.
 *  - Lo de adentro va guardado en la misma mochila (si la tirás, sigue llena; no entra una mochila dentro de otra).
 *  - Te la "ponés" (casilla nueva del inventario, o la tecla N del mod): queda guardada en el jugador y se ve en tu espalda.
 *  - Solo se abre la que tenés puesta (tecla B del mod). Al morir se cae con todo, como la armadura.
 *  - Por ahora se sacan del creativo (pestaña Trucofdeath): cuando exista la tienda se compran ahí.
 * Al mod le manda (canal tod5:mochilas) la mochila que tenés puesta y el color de la de cada jugador (para dibujarla en la espalda).
 */
final class Mochilas implements Listener {
	static final String CANAL = "tod5:mochilas";
	static final int[] ESPACIOS = { 18, 27, 36, 45 };
	private static final NamespacedKey NIVEL = new NamespacedKey("tod5", "mochila"), CONTENIDO = new NamespacedKey("tod5", "mochila_contenido"),
		PUESTA = new NamespacedKey("tod5", "mochila_puesta");
	private final TOD5Servidor plugin;

	Mochilas(TOD5Servidor plugin) { this.plugin = plugin; }

	// ================= el item =================
	ItemStack crear(int nivel) {
		nivel = Math.max(1, Math.min(4, nivel));
		ItemStack it = new ItemStack(Material.LEATHER_HORSE_ARMOR);
		it.unsetData(DataComponentTypes.EQUIPPABLE);          // que no se le pueda poner a un caballo
		it.unsetData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
		ItemMeta m = it.getItemMeta();
		m.itemName(Component.text("Mochila"));
		m.lore(List.of(Component.text("Nivel " + nivel + " · " + ESPACIOS[nivel - 1] + " espacios", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Póntela (tecla N o su casilla del inventario)", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("y ábrela con la tecla B.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
		m.setItemModel(new NamespacedKey("tod5", "mochila"));
		m.addItemFlags(ItemFlag.HIDE_DYE, ItemFlag.HIDE_ATTRIBUTES);
		m.getPersistentDataContainer().set(NIVEL, PersistentDataType.INTEGER, nivel);
		it.setItemMeta(m);
		return it;
	}
	static boolean es(ItemStack it) { return it != null && !it.getType().isAir() && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(NIVEL, PersistentDataType.INTEGER); }
	static int nivel(ItemStack it) { return es(it) ? Math.max(1, Math.min(4, it.getItemMeta().getPersistentDataContainer().get(NIVEL, PersistentDataType.INTEGER))) : 0; }
	static int color(ItemStack it) {
		if (it.getItemMeta() instanceof LeatherArmorMeta lm && it.hasData(DataComponentTypes.DYED_COLOR)) return lm.getColor().asRGB();
		return 0x8F7A3A;   // el marron de siempre
	}

	// ================= lo de adentro (guardado en la mochila) =================
	static ItemStack[] contenido(ItemStack mochila) {
		ItemStack[] r = new ItemStack[ESPACIOS[nivel(mochila) - 1]];
		String s = mochila.getItemMeta().getPersistentDataContainer().get(CONTENIDO, PersistentDataType.STRING);
		if (s == null || s.isEmpty()) return r;
		try {
			JsonArray a = JsonParser.parseString(s).getAsJsonArray();
			for (int i = 0; i < a.size() && i < r.length; i++) {
				String b = a.get(i).getAsString();
				if (!b.isEmpty()) r[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(b));
			}
		} catch (Exception e) { plugin().getLogger().warning("Mochila: no pude leer lo de adentro (" + e.getMessage() + ")"); }
		return r;
	}
	static void guardarContenido(ItemStack mochila, ItemStack[] cosas) {
		JsonArray a = new JsonArray();
		for (ItemStack c : cosas) a.add(c == null || c.getType().isAir() ? "" : Base64.getEncoder().encodeToString(c.serializeAsBytes()));
		ItemMeta m = mochila.getItemMeta();
		m.getPersistentDataContainer().set(CONTENIDO, PersistentDataType.STRING, a.toString());
		mochila.setItemMeta(m);
	}
	private static TOD5Servidor plugin() { return TOD5Servidor.getPlugin(TOD5Servidor.class); }

	// ================= la que tenés puesta (guardada en el jugador) =================
	ItemStack puesta(Player p) {
		byte[] b = p.getPersistentDataContainer().get(PUESTA, PersistentDataType.BYTE_ARRAY);
		if (b == null) return null;
		try { return ItemStack.deserializeBytes(b); } catch (Exception e) { return null; }
	}
	private void poner(Player p, ItemStack it) {
		if (it == null || it.getType().isAir()) p.getPersistentDataContainer().remove(PUESTA);
		else p.getPersistentDataContainer().set(PUESTA, PersistentDataType.BYTE_ARRAY, it.serializeAsBytes());
		mandarTodos();
	}

	/** La ventana de la mochila abierta. */
	static final class Ventana implements InventoryHolder {
		Inventory inv;
		@Override public Inventory getInventory() { return inv; }
	}
	private static boolean abierta(Player p) { return p.getOpenInventory().getTopInventory().getHolder() instanceof Ventana; }

	void abrir(Player p) {
		ItemStack m = puesta(p);
		if (m == null) { p.sendActionBar(Component.text("No tienes una mochila puesta.", NamedTextColor.YELLOW)); return; }
		Ventana v = new Ventana();
		int n = ESPACIOS[nivel(m) - 1];
		Component titulo = m.getItemMeta().hasCustomName() ? m.getItemMeta().customName() : Component.text("Mochila");
		v.inv = Bukkit.createInventory(v, n, titulo);
		v.inv.setContents(contenido(m));
		p.openInventory(v.inv);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 1.2f);
	}
	/** Guarda lo de la ventana en la mochila puesta. */
	private void guardarVentana(Player p, Inventory inv) {
		ItemStack m = puesta(p);
		if (m == null) return;
		guardarContenido(m, inv.getContents());
		p.getPersistentDataContainer().set(PUESTA, PersistentDataType.BYTE_ARRAY, m.serializeAsBytes());
	}
	@EventHandler
	public void alCerrar(InventoryCloseEvent e) {
		if (e.getInventory().getHolder() instanceof Ventana && e.getPlayer() instanceof Player p) { guardarVentana(p, e.getInventory()); p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 0.9f); }
	}
	/** No entra una mochila dentro de otra. */
	@EventHandler(ignoreCancelled = true)
	public void alClic(InventoryClickEvent e) {
		if (!(e.getView().getTopInventory().getHolder() instanceof Ventana)) return;
		boolean arriba = e.getClickedInventory() == e.getView().getTopInventory();
		ItemStack metiendo = null;
		if (arriba) {
			if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) metiendo = e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
			else if (e.getClick() == ClickType.SWAP_OFFHAND) metiendo = e.getWhoClicked().getInventory().getItemInOffHand();
			else metiendo = e.getCursor();
		} else if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) metiendo = e.getCurrentItem();
		if (es(metiendo)) { e.setCancelled(true); avisoNoEntra(e.getWhoClicked()); }
	}
	@EventHandler(ignoreCancelled = true)
	public void alArrastrar(InventoryDragEvent e) {
		if (!(e.getView().getTopInventory().getHolder() instanceof Ventana) || !es(e.getOldCursor())) return;
		int tope = e.getView().getTopInventory().getSize();
		for (int s : e.getRawSlots()) if (s < tope) { e.setCancelled(true); avisoNoEntra(e.getWhoClicked()); return; }
	}
	private static void avisoNoEntra(org.bukkit.entity.HumanEntity h) { if (h instanceof Player p) p.sendActionBar(Component.text("Una mochila no entra dentro de otra.", NamedTextColor.YELLOW)); }

	// ================= ponérsela / sacársela =================
	/**
	 * Tecla N del mod. casilla = la del inventario del jugador que tenía el mouse encima (0..40), o -1 = la mano.
	 * Si ahí hay una mochila: te la ponés (y la que tenías puesta va a ese lugar). Si está vacío: te sacás la puesta y queda ahí.
	 */
	void ponerDesde(Player p, int casilla) {
		if (abierta(p)) return;
		var inv = p.getInventory();
		int i = casilla < 0 ? inv.getHeldItemSlot() : casilla;
		if (i < 0 || i > 40) return;
		ItemStack ahi = inv.getItem(i), vieja = puesta(p);
		boolean vacio = ahi == null || ahi.getType().isAir();
		if (!vacio && !es(ahi)) { p.sendActionBar(Component.text("Eso no es una mochila.", NamedTextColor.YELLOW)); return; }
		if (vacio && vieja == null) return;
		inv.setItem(i, vieja);
		poner(p, vacio ? null : ahi);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1f);
	}
	/** La casilla de la mochila en el inventario: cambia lo que tenés agarrado con el mouse por la puesta. */
	void casilla(Player p) {
		if (abierta(p)) return;
		ItemStack mano = p.getItemOnCursor(), vieja = puesta(p);
		boolean vacio = mano == null || mano.getType().isAir();
		if (!vacio && !es(mano)) return;
		if (vacio && vieja == null) return;
		p.setItemOnCursor(vieja);
		poner(p, vacio ? null : mano);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1f);
	}

	/** Al morir se cae con todo (salvo que el mundo tenga keepInventory). */
	@EventHandler(priority = EventPriority.HIGH)
	public void alMorir(PlayerDeathEvent e) {
		Player p = e.getEntity();
		if (abierta(p)) guardarVentana(p, p.getOpenInventory().getTopInventory());
		if (e.getKeepInventory()) return;
		ItemStack m = puesta(p);
		if (m == null) return;
		e.getDrops().add(m);
		p.getPersistentDataContainer().remove(PUESTA);
		mandarTodos();
	}

	/** Acciones del mod: mochila_abrir, mochila_poner (casilla), mochila_casilla. */
	boolean accion(Player p, String a, JsonObject o) {
		switch (a) {
			case "mochila_abrir" -> abrir(p);
			case "mochila_poner" -> ponerDesde(p, o.has("casilla") ? o.get("casilla").getAsInt() : -1);
			case "mochila_casilla" -> casilla(p);
			default -> { return false; }
		}
		return true;
	}

	// ================= lo que va al mod =================
	void mandar(Player p) {
		JsonObject o = new JsonObject();
		ItemStack m = puesta(p);
		o.add("puesta", m == null ? com.google.gson.JsonNull.INSTANCE : Crafteos.itemJson(m));
		JsonObject esp = new JsonObject();
		for (Player otro : Bukkit.getOnlinePlayers()) { ItemStack x = puesta(otro); if (x != null) esp.addProperty(otro.getUniqueId().toString(), color(x)); }
		o.add("espaldas", esp);
		p.sendPluginMessage(plugin, CANAL, o.toString().getBytes(StandardCharsets.UTF_8));
	}
	void mandarTodos() { for (Player p : Bukkit.getOnlinePlayers()) mandar(p); }
	@EventHandler public void alEntrar(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, this::mandarTodos, 40L); }
	@EventHandler public void alSalir(PlayerQuitEvent e) { Bukkit.getScheduler().runTaskLater(plugin, this::mandarTodos, 2L); }

	/** Para la pestaña Trucofdeath del creativo: una de cada nivel. */
	List<JsonObject> itemsParaTablet() {
		List<JsonObject> l = new ArrayList<>();
		for (int n = 1; n <= 4; n++) { JsonObject j = new JsonObject(); j.addProperty("id", "mochila_" + n); j.add("item", Crafteos.itemJson(crear(n))); l.add(j); }
		return l;
	}
}
