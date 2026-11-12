package ar.tod5.servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.potion.PotionMix;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Los crafteos del evento, armados desde la tablet (modo admin > Crafteos) sin tocar archivos.
 *
 * Cada crafteo: nombre, categoria, dia, nota, estado (oculto / sombra / activo) y donde se hace:
 *   mesa      3x3; cada casilla puede pedir CANTIDAD (ej. 4 diamantes en una casilla); forma exacta o libre.
 *             Lo manejo yo (no la receta de Minecraft): asi las cantidades y el shift+clic andan bien siempre.
 *   horno, fundicion, ahumador   1 ingrediente -> resultado
 *   herreria  plantilla + base + agregado -> resultado
 *   pociones  ingrediente + pocion base -> pocion resultado
 * Cualquier item sirve (tambien los custom: se guardan enteros, con nombre, encantamientos, etc.).
 *
 * La receta se arma en una "mesa" (un inventario): clic con un item en el cursor pone una COPIA en la casilla
 * (clic derecho: de a uno), clic sin nada la vacia; shift desde tu inventario copia la pila. Nada se pierde ni se duplica.
 * Tambien los items propios del evento (modo admin > Items): aparecen en la pestaña Trucofdeath del creativo.
 *
 * Archivo: plugins/TOD5/datos/crafteos.json
 */
final class Crafteos implements Listener {
	static final List<String> TIPOS = List.of("mesa", "horno", "fundicion", "ahumador", "herreria", "pociones");
	static final List<String> ESTADOS = List.of("oculto", "sombra", "activo");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class Receta {
		String id, nombre = "Crafteo nuevo", categoria = "General", nota = "", estado = "oculto", tipo = "mesa";
		int dia = 1; boolean formaExacta = true; float xp = 0.5f; int tiempo = 200;
		ItemStack[] entrada = new ItemStack[9];
		ItemStack resultado;
	}

	private final TOD5Servidor plugin;
	private final Tablero tablero;
	final Map<String, Receta> recetas = new LinkedHashMap<>();
	final Map<String, ItemStack> items = new LinkedHashMap<>();   // los items propios del evento
	private final NamespacedKey marcaItem;
	private int proximo = 1;

	Crafteos(TOD5Servidor plugin, Tablero tablero) { this.plugin = plugin; this.tablero = tablero; this.marcaItem = new NamespacedKey(plugin, "item"); }

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "crafteos.json"); }
	private static String b64(ItemStack it) { return it == null || it.getType().isAir() ? null : Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
	private static ItemStack deB64(JsonElement e) { return e == null || e.isJsonNull() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(e.getAsString())); }

	void cargar() {
		for (String id : recetas.keySet()) desregistrar(id);
		recetas.clear(); items.clear();
		File f = archivo();
		if (f.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
			for (JsonElement e : o.getAsJsonArray("recetas")) {
				JsonObject j = e.getAsJsonObject(); Receta r = new Receta();
				r.id = j.get("id").getAsString(); r.nombre = j.get("nombre").getAsString(); r.categoria = j.get("categoria").getAsString();
				r.nota = j.get("nota").getAsString(); r.estado = j.get("estado").getAsString(); r.tipo = j.get("tipo").getAsString();
				r.dia = j.get("dia").getAsInt(); r.formaExacta = j.get("forma").getAsBoolean();
				if (j.has("xp")) r.xp = j.get("xp").getAsFloat(); if (j.has("tiempo")) r.tiempo = j.get("tiempo").getAsInt();
				JsonArray en = j.getAsJsonArray("entrada"); r.entrada = new ItemStack[9];
				for (int i = 0; i < en.size() && i < 9; i++) r.entrada[i] = deB64(en.get(i));
				r.resultado = deB64(j.get("resultado"));
				recetas.put(r.id, r);
			}
			if (o.has("items")) for (JsonElement e : o.getAsJsonArray("items")) { JsonObject j = e.getAsJsonObject(); items.put(j.get("id").getAsString(), deB64(j.get("item"))); }
		} catch (Exception e) { plugin.getLogger().severe("No pude leer crafteos.json: " + e.getMessage()); }
		else migrarViejo();
		for (Receta r : recetas.values()) if (r.estado.equals("activo")) registrar(r);
		plugin.getLogger().info("Crafteos: " + recetas.size() + " (" + recetas.values().stream().filter(r -> r.estado.equals("activo")).count() + " activos), items propios: " + items.size());
	}
	void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray();
		for (Receta r : recetas.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", r.id); j.addProperty("nombre", r.nombre); j.addProperty("categoria", r.categoria); j.addProperty("nota", r.nota);
			j.addProperty("estado", r.estado); j.addProperty("tipo", r.tipo); j.addProperty("dia", r.dia); j.addProperty("forma", r.formaExacta);
			j.addProperty("xp", r.xp); j.addProperty("tiempo", r.tiempo);
			JsonArray en = new JsonArray(); for (ItemStack it : r.entrada) { String s = b64(it); if (s == null) en.add(JsonNull.INSTANCE); else en.add(s); }
			j.add("entrada", en);
			String res = b64(r.resultado); if (res != null) j.addProperty("resultado", res);
			a.add(j);
		}
		o.add("recetas", a);
		JsonArray ia = new JsonArray();
		items.forEach((id, it) -> { JsonObject j = new JsonObject(); j.addProperty("id", id); j.addProperty("item", b64(it)); ia.add(j); });
		o.add("items", ia);
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar crafteos.json: " + e.getMessage()); }
	}
	/** La primera vez: paso los crafteos viejos de crafteos.yml (la billetera) al sistema nuevo. */
	private void migrarViejo() {
		File viejo = new File(plugin.getDataFolder(), "crafteos.yml");
		if (!viejo.exists()) plugin.saveResource("crafteos.yml", false);   // server nuevo: arranca con la billetera
		YamlConfiguration cy = YamlConfiguration.loadConfiguration(viejo);
		YamlConfiguration est = YamlConfiguration.loadConfiguration(new File(new File(plugin.getDataFolder(), "datos"), "estado.yml"));
		List<String> activos = est.getStringList("crafteos_activos");
		ConfigurationSection cs = cy.getConfigurationSection("crafteos");
		if (cs == null) return;
		for (String id : cs.getKeys(false)) try {
			ConfigurationSection c = cs.getConfigurationSection(id);
			Receta r = new Receta(); r.id = id; r.nombre = c.getString("nombre", id); r.categoria = cap(c.getString("categoria", "general"));
			r.dia = c.getInt("dia", 1); r.nota = c.getString("nota", ""); r.estado = activos.contains(id) || c.getBoolean("activo") ? "activo" : "oculto";
			List<String> forma = c.getStringList("forma");
			for (int f = 0; f < 3 && f < forma.size(); f++) for (int k = 0; k < 3 && k < forma.get(f).length(); k++) {
				String m = c.getString("ingredientes." + forma.get(f).charAt(k));
				if (m != null) r.entrada[f * 3 + k] = new ItemStack(Material.matchMaterial(m));
			}
			ItemStack res = new ItemStack(Material.matchMaterial(c.getString("resultado", "stone")), Math.max(1, c.getInt("cantidad", 1)));
			ItemMeta m = res.getItemMeta(); m.itemName(Component.text(r.nombre)); res.setItemMeta(m);
			r.resultado = res;
			recetas.put(id, r);
		} catch (Exception e) { plugin.getLogger().warning("No pude pasar el crafteo viejo " + id + ": " + e.getMessage()); }
		guardar();
	}
	private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

	// ================= registrar en Minecraft (hornos, herreria, pociones) =================
	private NamespacedKey clave(String id) { return new NamespacedKey(plugin, "c_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")); }
	private static RecipeChoice exacto(ItemStack it) { ItemStack c = it.clone(); c.setAmount(1); return new RecipeChoice.ExactChoice(c); }

	private void registrar(Receta r) {
		desregistrar(r.id);
		if (r.resultado == null) return;
		NamespacedKey k = clave(r.id);
		try {
			switch (r.tipo) {
				case "horno" -> { if (r.entrada[0] != null) Bukkit.addRecipe(new FurnaceRecipe(k, r.resultado.clone(), exacto(r.entrada[0]), r.xp, r.tiempo), true); }
				case "fundicion" -> { if (r.entrada[0] != null) Bukkit.addRecipe(new BlastingRecipe(k, r.resultado.clone(), exacto(r.entrada[0]), r.xp, Math.max(1, r.tiempo / 2)), true); }
				case "ahumador" -> { if (r.entrada[0] != null) Bukkit.addRecipe(new SmokingRecipe(k, r.resultado.clone(), exacto(r.entrada[0]), r.xp, Math.max(1, r.tiempo / 2)), true); }
				case "herreria" -> {
					if (r.entrada[0] != null && r.entrada[1] != null && r.entrada[2] != null)
						Bukkit.addRecipe(new SmithingTransformRecipe(k, r.resultado.clone(), exacto(r.entrada[0]), exacto(r.entrada[1]), exacto(r.entrada[2])), true);
				}
				case "pociones" -> {
					if (r.entrada[0] != null && r.entrada[1] != null)
						Bukkit.getPotionBrewer().addPotionMix(new PotionMix(k, r.resultado.clone(), exacto(r.entrada[1]), exacto(r.entrada[0])));
				}
				default -> {}   // mesa: la manejo yo (ver alPreparar)
			}
		} catch (Exception e) { plugin.getLogger().warning("No pude registrar el crafteo " + r.id + ": " + e.getMessage()); }
	}
	private void desregistrar(String id) {
		NamespacedKey k = clave(id);
		try { Bukkit.removeRecipe(k, true); } catch (Exception ignored) {}
		try { Bukkit.getPotionBrewer().removePotionMix(k); } catch (Exception ignored) {}
	}

	// ================= la mesa 3x3 (con cantidades) =================
	/** Como se encaja una receta en la grilla: para cada casilla de la grilla, cuanto se gasta (0 = nada). */
	private static int[] encaje(Receta r, ItemStack[] grilla) {
		if (!r.tipo.equals("mesa") || r.resultado == null || grilla.length != 9) return null;
		int[] gasto = new int[9];
		if (r.formaExacta) {
			int[] bR = caja(r.entrada), bG = caja(grilla);
			if (bR == null || bG == null || bR[2] - bR[0] != bG[2] - bG[0] || bR[3] - bR[1] != bG[3] - bG[1]) return null;
			for (int f = 0; f < 3; f++) for (int c = 0; c < 3; c++) {
				int fr = f - bG[1] + bR[1], cr = c - bG[0] + bR[0];
				ItemStack g = grilla[f * 3 + c];
				ItemStack q = (fr >= 0 && fr < 3 && cr >= 0 && cr < 3) ? r.entrada[fr * 3 + cr] : null;
				if (vacio(q) != vacio(g)) return null;
				if (vacio(q)) continue;
				if (!g.isSimilar(q) || g.getAmount() < q.getAmount()) return null;
				gasto[f * 3 + c] = q.getAmount();
			}
			return gasto;
		}
		// forma libre: cada ingrediente en alguna casilla distinta, y nada de mas
		List<ItemStack> pide = new ArrayList<>(); for (ItemStack q : r.entrada) if (!vacio(q)) pide.add(q);
		boolean[] usada = new boolean[9]; int ocupadas = 0;
		for (ItemStack g : grilla) if (!vacio(g)) ocupadas++;
		if (ocupadas != pide.size()) return null;
		pide.sort((a, b) -> b.getAmount() - a.getAmount());
		for (ItemStack q : pide) {
			int elegida = -1;
			for (int i = 0; i < 9; i++) if (!usada[i] && !vacio(grilla[i]) && grilla[i].isSimilar(q) && grilla[i].getAmount() >= q.getAmount()) { elegida = i; break; }
			if (elegida < 0) return null;
			usada[elegida] = true; gasto[elegida] = q.getAmount();
		}
		return gasto;
	}
	private static boolean vacio(ItemStack it) { return it == null || it.getType().isAir() || it.getAmount() <= 0; }
	/** minX, minY, maxX, maxY de lo ocupado en una grilla 3x3. */
	private static int[] caja(ItemStack[] g) {
		int x0 = 3, y0 = 3, x1 = -1, y1 = -1;
		for (int i = 0; i < 9; i++) if (!vacio(g[i])) { int x = i % 3, y = i / 3; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
		return x1 < 0 ? null : new int[] { x0, y0, x1, y1 };
	}
	private Receta buscar(ItemStack[] grilla) {
		for (Receta r : recetas.values()) if (r.estado.equals("activo") && encaje(r, grilla) != null) return r;
		return null;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void alPreparar(PrepareItemCraftEvent e) {
		CraftingInventory inv = e.getInventory();
		if (inv.getType() != InventoryType.WORKBENCH) return;
		Receta r = buscar(inv.getMatrix());
		if (r != null) inv.setResult(r.resultado.clone());
	}

	/**
	 * Lo hago yo: gasta exactamente lo que pide cada casilla, y con shift fabrica todas las veces que alcance.
	 * El clic de Minecraft se cancela y el crafteo se hace en el tick siguiente, con el inventario ya quieto
	 * (si se toca la grilla adentro del evento, Minecraft la pisa al cancelar y aparecen items de mas).
	 */
	private final Set<UUID> crafteando = new HashSet<>();

	@EventHandler(priority = EventPriority.HIGH)
	public void alCraftear(CraftItemEvent e) {
		CraftingInventory inv = e.getInventory();
		if (inv.getType() != InventoryType.WORKBENCH || !(e.getWhoClicked() instanceof Player p)) return;
		if (buscar(inv.getMatrix()) == null) return;
		e.setCancelled(true);
		ClickType ct = e.getClick();
		if (!ct.isShiftClick() && ct != ClickType.LEFT && ct != ClickType.RIGHT) return;   // teclas numericas, tirar: no
		if (!crafteando.add(p.getUniqueId())) return;                                       // ya hay uno en camino
		Bukkit.getScheduler().runTask(plugin, () -> {
			crafteando.remove(p.getUniqueId());
			if (p.getOpenInventory().getTopInventory() != inv) return;
			hacer(p, inv, ct.isShiftClick());
		});
	}
	private void hacer(Player p, CraftingInventory inv, boolean shift) {
		ItemStack[] grilla = inv.getMatrix();
		Receta r = buscar(grilla);
		if (r == null) { inv.setResult(null); p.updateInventory(); return; }
		int[] gasto = encaje(r, grilla);
		int veces = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) if (gasto[i] > 0) veces = Math.min(veces, grilla[i].getAmount() / gasto[i]);
		if (veces <= 0 || veces == Integer.MAX_VALUE) return;
		ItemStack res = r.resultado.clone();
		if (shift) {
			int entran = cuantasEntran(p.getInventory(), res);
			veces = Math.min(veces, Math.max(0, entran / res.getAmount()));
			if (veces <= 0) return;
			for (int v = 0; v < veces; v++) p.getInventory().addItem(res.clone()).values().forEach(s -> p.getWorld().dropItem(p.getLocation(), s));
		} else {
			ItemStack cursor = p.getItemOnCursor();
			if (vacio(cursor)) p.setItemOnCursor(res);
			else if (cursor.isSimilar(res) && cursor.getAmount() + res.getAmount() <= cursor.getMaxStackSize()) { cursor.setAmount(cursor.getAmount() + res.getAmount()); p.setItemOnCursor(cursor); }
			else return;
			veces = 1;
		}
		ItemStack[] nueva = new ItemStack[9];
		for (int i = 0; i < 9; i++) {
			if (vacio(grilla[i])) continue;
			ItemStack g = grilla[i].clone();
			int queda = g.getAmount() - gasto[i] * veces;
			Material resto = gasto[i] > 0 && queda <= 0 ? g.getType().getCraftingRemainingItem() : null;
			if (resto != null && !resto.isAir()) nueva[i] = new ItemStack(resto);   // balde de leche -> balde, etc.
			else if (queda > 0) { g.setAmount(queda); nueva[i] = g; }
		}
		inv.setMatrix(nueva);
		Receta otra = buscar(nueva);
		inv.setResult(otra == null ? null : otra.resultado.clone());
		p.updateInventory();
	}
	private static int cuantasEntran(PlayerInventory inv, ItemStack it) {
		int n = 0, max = it.getMaxStackSize();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (vacio(s)) n += max; else if (s.isSimilar(it)) n += Math.max(0, max - s.getAmount());
		}
		return n;
	}

	// ================= acciones (desde la tablet) =================
	String nuevoId() { return "c" + (proximo++); }

	Receta guardarDesdeTablet(JsonObject j) {
		String id = j.has("id") && !j.get("id").getAsString().isBlank() ? j.get("id").getAsString() : nuevoId();
		Receta r = recetas.computeIfAbsent(id, k -> { Receta n = new Receta(); n.id = k; return n; });
		if (j.has("nombre")) r.nombre = j.get("nombre").getAsString().trim().isEmpty() ? "Crafteo" : j.get("nombre").getAsString().trim();
		if (j.has("categoria")) r.categoria = j.get("categoria").getAsString().trim().isEmpty() ? "General" : j.get("categoria").getAsString().trim();
		if (j.has("nota")) r.nota = j.get("nota").getAsString();
		if (j.has("dia")) r.dia = Math.max(1, j.get("dia").getAsInt());
		if (j.has("forma")) r.formaExacta = j.get("forma").getAsBoolean();
		if (j.has("tipo") && TIPOS.contains(j.get("tipo").getAsString())) {
			String t = j.get("tipo").getAsString();
			if (!t.equals(r.tipo)) { r.tipo = t; if (r.estado.equals("activo")) registrar(r); }
		}
		guardar();
		return r;
	}
	/** Cambia el estado. Devuelve true si paso a activo (para avisar en la tablet de los jugadores). */
	boolean estado(String id, String estado) {
		Receta r = recetas.get(id);
		if (r == null || !ESTADOS.contains(estado)) return false;
		boolean antes = r.estado.equals("activo");
		r.estado = estado;
		if (estado.equals("activo")) registrar(r); else desregistrar(id);
		guardar();
		return !antes && estado.equals("activo");
	}
	void borrar(String id) { if (recetas.remove(id) != null) { desregistrar(id); guardar(); } }
	Receta duplicar(String id) {
		Receta o = recetas.get(id); if (o == null) return null;
		Receta r = new Receta(); r.id = nuevoId(); r.nombre = o.nombre + " (copia)"; r.categoria = o.categoria; r.nota = o.nota; r.tipo = o.tipo;
		r.dia = o.dia; r.formaExacta = o.formaExacta; r.xp = o.xp; r.tiempo = o.tiempo;
		for (int i = 0; i < 9; i++) r.entrada[i] = o.entrada[i] == null ? null : o.entrada[i].clone();
		r.resultado = o.resultado == null ? null : o.resultado.clone();
		recetas.put(r.id, r); guardar();
		return r;
	}

	// ---- items propios ----
	/** Crea o cambia un item del evento: de que item parte, nombre (con color), descripcion y si brilla. */
	String guardarItem(JsonObject j) {
		String id = j.has("id") && !j.get("id").getAsString().isBlank() ? j.get("id").getAsString() : "i" + (proximo++);
		Material base = Material.matchMaterial(j.has("base") ? j.get("base").getAsString() : "paper");
		if (base == null || !base.isItem() || base.isAir()) base = Material.PAPER;
		ItemStack it = new ItemStack(base);
		ItemMeta m = it.getItemMeta();
		int color = j.has("color") ? j.get("color").getAsInt() : 0xFFFFFF;
		String nombre = j.has("nombre") ? j.get("nombre").getAsString() : "Item";
		m.itemName(Component.text(nombre, TextColor.color(color)));
		List<Component> lore = new ArrayList<>();
		if (j.has("desc")) for (String l : j.get("desc").getAsString().split("\n")) if (!l.isBlank()) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		m.lore(lore.isEmpty() ? null : lore);
		m.setEnchantmentGlintOverride(j.has("brillo") && j.get("brillo").getAsBoolean() ? Boolean.TRUE : null);
		m.getPersistentDataContainer().set(marcaItem, PersistentDataType.STRING, id);
		it.setItemMeta(m);
		items.put(id, it); guardar();
		return id;
	}
	/** Guarda como item del evento el que tiene en la mano (por si lo armaste con un yunque o comandos). */
	String itemDeLaMano(Player p) {
		ItemStack mano = p.getInventory().getItemInMainHand();
		if (vacio(mano)) return null;
		ItemStack it = mano.clone(); it.setAmount(1);
		String id = "i" + (proximo++);
		ItemMeta m = it.getItemMeta(); m.getPersistentDataContainer().set(marcaItem, PersistentDataType.STRING, id); it.setItemMeta(m);
		items.put(id, it); guardar();
		return id;
	}
	void borrarItem(String id) { if (items.remove(id) != null) guardar(); }

	// ================= lo que va a la tablet =================
	@SuppressWarnings("deprecation")
	static JsonElement itemJson(ItemStack it) {
		if (vacio(it)) return JsonNull.INSTANCE;
		try { return Bukkit.getUnsafe().serializeItemAsJson(it); } catch (Throwable t) { JsonObject o = new JsonObject(); o.addProperty("id", it.getType().getKey().toString()); o.addProperty("count", it.getAmount()); return o; }
	}
	JsonArray paraTablet(boolean editor) {
		JsonArray a = new JsonArray();
		for (Receta r : recetas.values()) {
			boolean activo = r.estado.equals("activo"), sombra = r.estado.equals("sombra");
			if (!editor && !activo && !sombra) continue;    // oculto: ni se manda
			JsonObject j = new JsonObject();
			j.addProperty("id", r.id); j.addProperty("estado", r.estado); j.addProperty("tipo", r.tipo); j.addProperty("categoria", r.categoria);
			j.add("icono", itemJson(r.resultado));
			if (activo || editor) {                          // la sombra sola no dice nada mas
				j.addProperty("nombre", r.nombre); j.addProperty("nota", r.nota); j.addProperty("dia", r.dia); j.addProperty("forma", r.formaExacta);
				JsonArray en = new JsonArray(); for (ItemStack it : r.entrada) en.add(itemJson(it));
				j.add("entrada", en); j.add("resultado", itemJson(r.resultado));
			}
			a.add(j);
		}
		return a;
	}
	JsonArray itemsParaTablet() {
		JsonArray a = new JsonArray();
		items.forEach((id, it) -> { JsonObject j = new JsonObject(); j.addProperty("id", id); j.add("item", itemJson(it)); a.add(j); });
		return a;
	}

	// ================= la mesa para armar la receta =================
	/** Casillas que se pueden tocar en el cofre, segun el tipo (entrada..., resultado al final). */
	static int[] casillas(String tipo) {
		return switch (tipo) {
			case "mesa" -> new int[] { 10, 11, 12, 19, 20, 21, 28, 29, 30, 24 };
			case "herreria" -> new int[] { 19, 20, 21, 24 };
			case "pociones" -> new int[] { 11, 29, 24 };     // ingrediente arriba, pocion base abajo
			default -> new int[] { 20, 24 };                 // hornos
		};
	}
	private static final int GUARDAR = 49, CANCELAR = 45;

	private static final class Mesa implements InventoryHolder {
		final String id; Inventory inv; boolean guardado;
		Mesa(String id) { this.id = id; }
		@Override public Inventory getInventory() { return inv; }
	}

	void abrirMesa(Player p, String id) {
		Receta r = recetas.get(id);
		if (r == null) { p.sendMessage(Component.text("Ese crafteo no existe.", NamedTextColor.RED)); return; }
		Mesa m = new Mesa(id);
		String donde = switch (r.tipo) { case "mesa" -> "Mesa"; case "horno" -> "Horno"; case "fundicion" -> "Horno de fundición"; case "ahumador" -> "Ahumador"; case "herreria" -> "Herrería"; default -> "Pociones"; };
		Inventory inv = Bukkit.createInventory(m, 54, Component.text(donde + ": " + r.nombre));
		m.inv = inv;
		ItemStack vidrio = deco(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
		for (int i = 0; i < 54; i++) inv.setItem(i, vidrio);
		int[] cs = casillas(r.tipo);
		for (int i = 0; i < cs.length - 1; i++) inv.setItem(cs[i], r.entrada[i] == null ? null : r.entrada[i].clone());
		inv.setItem(cs[cs.length - 1], r.resultado == null ? null : r.resultado.clone());
		inv.setItem(23, deco(Material.ARROW, "→", NamedTextColor.WHITE));
		String ayuda = switch (r.tipo) {
			case "mesa" -> "Poné los ingredientes en el 3x3 (con la cantidad que pide cada casilla) y el resultado a la derecha.";
			case "herreria" -> "Plantilla, base y agregado (en ese orden), y el resultado a la derecha.";
			case "pociones" -> "Arriba el ingrediente, abajo la poción base, y la poción que sale a la derecha.";
			default -> "Lo que se mete en el horno y lo que sale a la derecha.";
		};
		inv.setItem(53, deco(Material.BOOK, "Cómo se arma", NamedTextColor.YELLOW, ayuda));
		inv.setItem(GUARDAR, deco(Material.LIME_STAINED_GLASS_PANE, "Guardar receta", NamedTextColor.GREEN));
		inv.setItem(CANCELAR, deco(Material.RED_STAINED_GLASS_PANE, "Cancelar", NamedTextColor.RED));
		p.openInventory(inv);
	}
	private static ItemStack deco(Material mat, String nombre, NamedTextColor color, String... lore) {
		ItemStack it = new ItemStack(mat); ItemMeta m = it.getItemMeta();
		m.itemName(Component.text(nombre, color));
		if (lore.length > 0) m.lore(Arrays.stream(lore).map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		m.setHideTooltip(nombre.isBlank());
		it.setItemMeta(m); return it;
	}

	@EventHandler
	public void alClicMesa(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Mesa m)) return;
		Receta r = recetas.get(m.id);
		if (r == null) { e.setCancelled(true); return; }
		int[] cs = casillas(r.tipo);
		int slot = e.getRawSlot();
		if (slot >= 54) {   // el inventario del jugador: libre, pero el shift solo va a las casillas de la receta
			if (e.isShiftClick()) {
				e.setCancelled(true);
				ItemStack it = e.getCurrentItem(); if (vacio(it)) return;
				for (int c : cs) if (vacio(m.inv.getItem(c))) { m.inv.setItem(c, it.clone()); break; }
			}
			return;
		}
		boolean editable = Arrays.stream(cs).anyMatch(c -> c == slot);
		e.setCancelled(true);
		if (editable) {
			// casillas "fantasma": se pone una COPIA de lo que tenes en el cursor (no se pierde ni se duplica nada)
			ItemStack cur = e.getCursor(), en = m.inv.getItem(slot);
			if (!vacio(cur)) {
				if (e.isRightClick()) {   // clic derecho: de a uno
					if (!vacio(en) && en.isSimilar(cur)) { en.setAmount(Math.min(en.getMaxStackSize(), en.getAmount() + 1)); m.inv.setItem(slot, en); }
					else { ItemStack c = cur.clone(); c.setAmount(1); m.inv.setItem(slot, c); }
				} else m.inv.setItem(slot, cur.clone());
			} else if (!vacio(en)) {
				if (e.isRightClick() && en.getAmount() > 1) { en.setAmount(en.getAmount() - 1); m.inv.setItem(slot, en); }
				else m.inv.setItem(slot, null);
			}
			return;
		}
		if (slot == GUARDAR) {
			for (int i = 0; i < 9; i++) r.entrada[i] = null;
			for (int i = 0; i < cs.length - 1; i++) { ItemStack it = m.inv.getItem(cs[i]); r.entrada[i] = vacio(it) ? null : it.clone(); }
			ItemStack res = m.inv.getItem(cs[cs.length - 1]); r.resultado = vacio(res) ? null : res.clone();
			if (r.estado.equals("activo")) registrar(r);
			guardar(); m.guardado = true;
			e.getWhoClicked().closeInventory();
			e.getWhoClicked().sendMessage(Component.text("Receta de \"" + r.nombre + "\" guardada.", NamedTextColor.GREEN));
			tablero.sincronizarTodos();
		} else if (slot == CANCELAR) e.getWhoClicked().closeInventory();
	}
	@EventHandler
	public void alArrastrarMesa(InventoryDragEvent e) {
		if (!(e.getInventory().getHolder() instanceof Mesa)) return;
		for (int s : e.getRawSlots()) if (s < 54) { e.setCancelled(true); return; }   // arrastrar sobre la mesa no: de a una casilla
	}
	/** Al cerrar, los items que pusiste en la mesa no se pierden: son copias (los tuyos quedan en tu inventario). */
	@EventHandler
	public void alCerrarMesa(InventoryCloseEvent e) {
		if (!(e.getInventory().getHolder() instanceof Mesa m)) return;
		HumanEntity h = e.getPlayer();
		if (!m.guardado) h.sendMessage(Component.text("No se guardó la receta (apretá el vidrio verde para guardar).", NamedTextColor.GRAY));
	}
}
