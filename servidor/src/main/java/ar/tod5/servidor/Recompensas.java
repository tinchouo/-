package ar.tod5.servidor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * La bandeja de recompensas: lo que ganas en una zona de monedas (y despues en desafios, anuncios...)
 * NO entra directo al inventario: queda guardado a tu nombre en el server hasta que lo reclamas.
 * (Las misiones no usan esto: tienen su propio "Reclamar".)
 *
 *  - Cada premio es un "paquete" (hasta 27 items) con un titulo ("Zona: cueva").
 *  - En la tablet aparece la pestaña Recompensas (solo si tenes algo) y un aviso.
 *  - Se reclama con "Reclamar todo" (lo que no entra queda esperando) o abriendo el cofre y sacando de a uno.
 *  - El cofre es SOLO PARA SACAR: no se puede meter nada (no sirve de cofre extra).
 *  - Si cerras el cofre con un item del premio en la mano, vuelve al paquete (no se cae al piso).
 *  - Si te desconectas o se reinicia el server, sigue ahi (plugins/TOD5/datos/recompensas.json).
 */
final class Recompensas implements Listener {
	static final int FILAS = 3, CASILLAS = FILAS * 9, RECLAMAR = CASILLAS + 4, TAM = CASILLAS + 9;

	static final class Paquete {
		String id, titulo;
		final List<ItemStack> items = new ArrayList<>();
		final List<ItemStack> original = new ArrayList<>();   // lo que se dio (para saber que puede volver al cerrar)
	}

	private final TOD5Servidor plugin;
	private final Map<UUID, List<Paquete>> bandejas = new HashMap<>();
	private int proximo = 1;

	Recompensas(TOD5Servidor plugin) { this.plugin = plugin; }

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "recompensas.json"); }
	private static String b64(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
	private static ItemStack deB64(JsonElement e) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(e.getAsString())); }

	void cargar() {
		bandejas.clear();
		File f = archivo();
		if (!f.exists()) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
			JsonObject js = o.getAsJsonObject("jugadores");
			if (js != null) for (String u : js.keySet()) {
				List<Paquete> l = new ArrayList<>();
				for (JsonElement e : js.getAsJsonArray(u)) {
					JsonObject j = e.getAsJsonObject();
					Paquete p = new Paquete(); p.id = j.get("id").getAsString(); p.titulo = j.get("titulo").getAsString();
					for (JsonElement i : j.getAsJsonArray("items")) p.items.add(deB64(i));
					if (j.has("original")) for (JsonElement i : j.getAsJsonArray("original")) p.original.add(deB64(i));
					else p.original.addAll(p.items.stream().map(ItemStack::clone).toList());
					if (!p.items.isEmpty()) l.add(p);
				}
				if (!l.isEmpty()) bandejas.put(UUID.fromString(u), l);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer recompensas.json: " + e.getMessage()); }
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonObject js = new JsonObject();
		bandejas.forEach((u, l) -> {
			JsonArray a = new JsonArray();
			for (Paquete p : l) {
				JsonObject j = new JsonObject(); j.addProperty("id", p.id); j.addProperty("titulo", p.titulo);
				JsonArray it = new JsonArray(); p.items.forEach(i -> it.add(b64(i))); j.add("items", it);
				JsonArray or = new JsonArray(); p.original.forEach(i -> or.add(b64(i))); j.add("original", or);
				a.add(j);
			}
			if (!a.isEmpty()) js.add(u.toString(), a);
		});
		o.add("jugadores", js);
		try { Files.writeString(archivo().toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar recompensas.json: " + e.getMessage()); }
	}

	// ================= dar =================
	/** Deja un premio en la bandeja del jugador (aunque este desconectado). Los items vacios se ignoran. */
	void dar(UUID jugador, String titulo, List<ItemStack> items) {
		Paquete p = new Paquete(); p.id = "r" + (proximo++); p.titulo = titulo;
		for (ItemStack it : items) if (it != null && !it.getType().isAir()) { meter(p.items, it); p.original.add(it.clone()); }
		if (p.items.isEmpty()) return;
		bandejas.computeIfAbsent(jugador, k -> new ArrayList<>()).add(p);
		guardar();
		Player pl = Bukkit.getPlayer(jugador);
		if (pl != null) {
			plugin.tablero().sincronizar(pl);   // la tablet muestra el aviso y la pestaña
			if (!plugin.tieneElMod(pl)) pl.sendMessage(Component.text("Tenés una recompensa sin reclamar: " + titulo, NamedTextColor.GREEN));
		}
	}

	private Paquete paquete(UUID u, String id) {
		for (Paquete p : bandejas.getOrDefault(u, List.of())) if (p.id.equals(id)) return p;
		return null;
	}
	private void limpiar(UUID u) {
		List<Paquete> l = bandejas.get(u);
		if (l == null) return;
		l.removeIf(p -> p.items.isEmpty());
		if (l.isEmpty()) bandejas.remove(u);
	}

	/** Para la tablet: los paquetes que tiene este jugador. */
	JsonArray paraTablet(Player pl) {
		JsonArray a = new JsonArray();
		for (Paquete p : bandejas.getOrDefault(pl.getUniqueId(), List.of())) {
			JsonObject j = new JsonObject(); j.addProperty("id", p.id); j.addProperty("titulo", p.titulo);
			JsonArray it = new JsonArray(); p.items.forEach(i -> it.add(Crafteos.itemJson(i))); j.add("items", it);
			a.add(j);
		}
		return a;
	}

	// ================= reclamar todo =================
	/** Mete en el inventario todo lo que entra. Lo que no entra queda en el paquete. */
	void reclamarTodo(Player pl, String id) {
		Paquete p = paquete(pl.getUniqueId(), id);
		if (p == null) return;
		int antes = p.items.size();
		List<ItemStack> quedan = new ArrayList<>();
		for (ItemStack it : p.items) quedan.addAll(pl.getInventory().addItem(it.clone()).values());
		p.items.clear(); p.items.addAll(quedan);
		limpiar(pl.getUniqueId()); guardar();
		if (quedan.isEmpty()) {
			pl.playSound(pl.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, SoundCategory.MASTER, 0.8f, 1.1f);
			pl.sendActionBar(Component.text("Reclamaste " + p.titulo, NamedTextColor.GREEN));
		} else pl.sendActionBar(Component.text("No te entra todo: quedan " + quedan.size() + " de " + antes + " esperando en Recompensas.", NamedTextColor.YELLOW));
		plugin.tablero().sincronizar(pl);
	}

	// ================= el cofre (solo para sacar) =================
	private static final class Cofre implements InventoryHolder {
		final UUID dueno; final String id; Inventory inv;
		final List<ItemStack> extra = new ArrayList<>();   // si hubiera mas de 27 (no deberia), no se pierden
		Cofre(UUID dueno, String id) { this.dueno = dueno; this.id = id; }
		@Override public Inventory getInventory() { return inv; }
	}

	void abrir(Player pl, String id) {
		Paquete p = paquete(pl.getUniqueId(), id);
		if (p == null) { plugin.tablero().sincronizar(pl); return; }
		Cofre c = new Cofre(pl.getUniqueId(), id);
		Inventory inv = Bukkit.createInventory(c, TAM, Component.text("Recompensa: " + p.titulo));
		c.inv = inv;
		for (int i = 0; i < p.items.size(); i++) { if (i < CASILLAS) inv.setItem(i, p.items.get(i).clone()); else c.extra.add(p.items.get(i).clone()); }
		ItemStack vidrio = deco(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
		for (int i = CASILLAS; i < TAM; i++) inv.setItem(i, vidrio);
		inv.setItem(RECLAMAR, deco(Material.LIME_STAINED_GLASS_PANE, "Reclamar todo", NamedTextColor.GREEN,
			"Lo que no te entre queda guardado acá.", "Clic en un item: lo sacás de a uno."));
		pl.openInventory(inv);
	}
	private static ItemStack deco(Material mat, String nombre, NamedTextColor color, String... lore) {
		ItemStack it = new ItemStack(mat); ItemMeta m = it.getItemMeta();
		m.itemName(Component.text(nombre, color));
		if (lore.length > 0) m.lore(Arrays.stream(lore).map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		m.setHideTooltip(nombre.isBlank());
		it.setItemMeta(m); return it;
	}

	/** Lo que quedo en el cofre pasa al paquete (el cofre manda mientras esta abierto). */
	private void copiarDelCofre(Cofre c) {
		Paquete p = paquete(c.dueno, c.id);
		if (p == null) return;
		p.items.clear();
		for (int i = 0; i < CASILLAS; i++) { ItemStack it = c.inv.getItem(i); if (it != null && !it.getType().isAir()) p.items.add(it.clone()); }
		for (ItemStack it : c.extra) p.items.add(it.clone());
	}
	/** Agrega juntando con los que son iguales (asi no ocupa casillas de mas). */
	private static void meter(List<ItemStack> l, ItemStack nuevo) {
		ItemStack n = nuevo.clone();
		for (ItemStack it : l) {
			if (n.getAmount() <= 0) return;
			if (!it.isSimilar(n)) continue;
			int pasa = Math.min(n.getAmount(), it.getMaxStackSize() - it.getAmount());
			if (pasa > 0) { it.setAmount(it.getAmount() + pasa); n.setAmount(n.getAmount() - pasa); }
		}
		if (n.getAmount() > 0) l.add(n);
	}

	private static final Set<InventoryAction> SACAR = EnumSet.of(InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
		InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME, InventoryAction.MOVE_TO_OTHER_INVENTORY);

	@EventHandler
	public void alClic(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Cofre c)) return;
		Player pl = (Player) e.getWhoClicked();
		int slot = e.getRawSlot();
		if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }   // doble clic juntaria cosas del cofre
		if (slot >= TAM) {   // tu inventario: libre, pero nada pasa al cofre
			if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) e.setCancelled(true);
			return;
		}
		if (slot == RECLAMAR) {
			e.setCancelled(true);
			copiarDelCofre(c);
			pl.closeInventory();
			reclamarTodo(pl, c.id);
			return;
		}
		if (slot >= CASILLAS || !SACAR.contains(e.getAction())) { e.setCancelled(true); return; }   // solo se saca
		Bukkit.getScheduler().runTask(plugin, () -> { copiarDelCofre(c); guardar(); });
	}
	@EventHandler
	public void alArrastrar(InventoryDragEvent e) {
		if (!(e.getInventory().getHolder() instanceof Cofre)) return;
		for (int s : e.getRawSlots()) if (s < TAM) { e.setCancelled(true); return; }
	}
	@EventHandler
	public void alCerrar(InventoryCloseEvent e) {
		if (!(e.getInventory().getHolder() instanceof Cofre c)) return;
		Player pl = (Player) e.getPlayer();
		copiarDelCofre(c);
		Paquete p = paquete(c.dueno, c.id);
		ItemStack cur = pl.getItemOnCursor();
		// cerraste con un item del premio en la mano: vuelve al paquete (no va al piso)
		if (p != null && cur != null && !cur.getType().isAir() && p.original.stream().anyMatch(o -> o.isSimilar(cur))) {
			meter(p.items, cur);
			pl.setItemOnCursor(null);
		}
		limpiar(c.dueno); guardar();
		Bukkit.getScheduler().runTask(plugin, () -> plugin.tablero().sincronizar(pl));
	}
}
