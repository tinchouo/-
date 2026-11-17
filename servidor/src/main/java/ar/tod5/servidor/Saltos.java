package ar.tod5.servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Orbes y pads (como en Geometry Dash).
 *  - pad: se pone en cualquier cara de un bloque (piso, pared o techo); lo tocas y salis disparado para donde apunta. Un cuarto de bloque de alto.
 *  - orbe: estando en el aire y adentro de su bloque, apretas espacio y saltas. Cada jugador la gasta hasta que toca el piso
 *    (salvo que tenga "se puede repetir en el aire"); las particulas de una orbe gastada no las ve ese jugador.
 *  - amarillo y rojo, con una altura cada uno (en bloques), configurable. El salto reemplaza al que tenias (no se suma).
 *  - despues de un salto no hay daño de caida hasta que tocas el piso.
 * El salto lo hace el mod (en la compu de cada uno, asi es instantaneo): el server le manda la lista (canal tod5:saltos) y el mod le avisa
 * cuando usa uno (canal tod5:salto), para las particulas y el daño de caida.
 */
final class Saltos implements Listener {
	static final String CANAL = "tod5:saltos", CANAL_USO = "tod5:salto";
	static final class Salto {
		String id, mundo, tipo = "orbe", color = "amarilla", cara = "up";   // cara: para donde apunta el pad (up, down, north, south, east, west)
		double x, y, z, altura;
		boolean repetir;
		Location lugar() { World w = Bukkit.getWorld(mundo); return w == null ? null : new Location(w, x, y, z); }
	}
	private static final class Vivo { ItemDisplay vista; Interaction toque; }
	static double alturaNormal(String tipo, String color) {
		if (tipo.equals("pad")) return color.equals("roja") ? 18 : 8;
		return color.equals("roja") ? 6 : 2.5;   // la amarilla: el doble de un salto normal (1,25); la roja: 6 bloques
	}

	private static final double CERCA = 48;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final TOD5Servidor plugin;
	private final NamespacedKey marca;
	final Map<String, Salto> saltos = new LinkedHashMap<>();
	private final Map<String, Vivo> vivos = new HashMap<>();
	private final Map<UUID, Set<String>> gastadas = new HashMap<>();   // orbes gastadas por cada uno (hasta que toca el piso)
	private final Map<UUID, Long> lanzado = new HashMap<>();           // salio volando (tick): sin daño de caida hasta tocar el piso
	private int proximo = 1;
	private long tick;

	Saltos(TOD5Servidor plugin) {
		this.plugin = plugin;
		this.marca = new NamespacedKey(plugin, "salto");
		Bukkit.getScheduler().runTaskTimer(plugin, this::cadaRato, 10, 10);
		Bukkit.getScheduler().runTaskTimer(plugin, this::aterrizajes, 4, 2);
	}

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "saltos.json"); }
	void cargar() {
		saltos.clear();
		try {
			File f = archivo();
			if (f.exists()) {
				JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
				proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
				for (JsonElement e : o.getAsJsonArray("saltos")) {
					JsonObject j = e.getAsJsonObject(); Salto s = new Salto();
					s.id = j.get("id").getAsString(); s.mundo = j.get("mundo").getAsString();
					s.tipo = j.get("tipo").getAsString(); s.color = j.get("color").getAsString();
					s.x = j.get("x").getAsDouble(); s.y = j.get("y").getAsDouble(); s.z = j.get("z").getAsDouble();
					s.altura = j.has("altura") ? j.get("altura").getAsDouble() : alturaNormal(s.tipo, s.color);
					s.repetir = j.has("repetir") && j.get("repetir").getAsBoolean();
					s.cara = j.has("cara") ? j.get("cara").getAsString() : "up";
					saltos.put(s.id, s);
				}
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer los saltos: " + e.getMessage()); }
		plugin.getLogger().info("Orbes y pads: " + saltos.size());
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray();
		for (Salto s : saltos.values()) a.add(json(s, true));
		o.add("saltos", a);
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar los saltos: " + e.getMessage()); }
	}
	private static JsonObject json(Salto s, boolean conMundo) {
		JsonObject j = new JsonObject();
		j.addProperty("id", s.id); if (conMundo) j.addProperty("mundo", s.mundo);
		j.addProperty("tipo", s.tipo); j.addProperty("color", s.color);
		j.addProperty("x", s.x); j.addProperty("y", s.y); j.addProperty("z", s.z);
		j.addProperty("altura", s.altura); j.addProperty("repetir", s.repetir); j.addProperty("cara", s.cara);
		return j;
	}

	// ================= avisarle al mod =================
	/** Le manda al mod los saltos de su mundo. */
	void mandar(Player p) {
		JsonArray a = new JsonArray();
		for (Salto s : saltos.values()) if (s.mundo.equals(p.getWorld().getName())) a.add(json(s, false));
		p.sendPluginMessage(plugin, CANAL, a.toString().getBytes(StandardCharsets.UTF_8));
	}
	private void mandarTodos() { for (Player p : Bukkit.getOnlinePlayers()) mandar(p); }
	@EventHandler public void alEntrar(PlayerJoinEvent e) { Player p = e.getPlayer(); Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) mandar(p); }, 40); }
	@EventHandler public void alCambiarMundo(PlayerChangedWorldEvent e) { mandar(e.getPlayer()); }
	@EventHandler public void alSalir(PlayerQuitEvent e) { gastadas.remove(e.getPlayer().getUniqueId()); lanzado.remove(e.getPlayer().getUniqueId()); }

	/** El mod avisa que uso uno: "id". */
	void usado(Player p, String id) {
		Salto s = saltos.get(id.trim());
		if (s == null) return;
		lanzado.put(p.getUniqueId(), tick);
		p.setFallDistance(0);
		if (s.tipo.equals("orbe") && !s.repetir) gastadas.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(s.id);
		plugin.getLogger().info(p.getName() + " uso " + (s.tipo.equals("pad") ? "el pad " : "la orbe ") + s.id + " (" + s.color + ", " + s.altura + " bloques)");
	}
	@EventHandler(ignoreCancelled = true)
	public void alCaer(EntityDamageEvent e) {
		if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
		if (lanzado.containsKey(p.getUniqueId())) { e.setCancelled(true); lanzado.remove(p.getUniqueId()); }
	}

	// ================= poner / sacar =================
	/** celda: donde va; cara: (los pads) para donde apuntan: el pad queda pegado a la cara de enfrente de esa celda. */
	Salto crear(Location celda, String tipo, String color, org.bukkit.block.BlockFace cara) {
		Salto s = new Salto();
		s.id = (tipo.equals("pad") ? "p" : "o") + (proximo++); s.mundo = celda.getWorld().getName(); s.tipo = tipo; s.color = color;
		s.x = celda.getBlockX() + 0.5; s.y = celda.getBlockY() + 0.5; s.z = celda.getBlockZ() + 0.5;
		if (tipo.equals("pad")) {
			s.cara = cara.name().toLowerCase(Locale.ROOT);
			s.x -= cara.getModX() * 0.5; s.y -= cara.getModY() * 0.5; s.z -= cara.getModZ() * 0.5;   // en la cara del bloque que tocaste
		}
		s.altura = alturaNormal(tipo, color);
		saltos.put(s.id, s); guardar(); mandarTodos();
		plugin.getLogger().info("Salto " + s.id + " (" + tipo + " " + color + ") en " + s.mundo + " " + s.x + " " + s.y + " " + s.z);
		cadaRato();
		return s;
	}
	void borrar(String id) { sacar(id); saltos.remove(id); guardar(); mandarTodos(); }
	private void sacar(String id) {
		Vivo v = vivos.remove(id);
		if (v == null) return;
		if (v.vista != null) v.vista.remove();
		if (v.toque != null) v.toque.remove();
	}
	void apagar() { for (String id : new ArrayList<>(vivos.keySet())) sacar(id); }

	private static ItemStack modelo(Salto s) {
		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta m = it.getItemMeta();
		m.setItemModel(new NamespacedKey("tod5", s.tipo + "_" + (s.color.equals("roja") ? "rojo" : "amarillo")));
		it.setItemMeta(m);
		return it;
	}
	/** Cada medio segundo: aparecen si hay alguien cerca. */
	private void cadaRato() {
		for (Salto s : saltos.values()) {
			Location l = s.lugar();
			boolean alguien = false;
			if (l != null) for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < CERCA * CERCA) { alguien = true; break; }
			Vivo v = vivos.get(s.id);
			if (!alguien) { if (v != null) sacar(s.id); continue; }
			if (v == null) { v = new Vivo(); vivos.put(s.id, v); }
			boolean orbe = s.tipo.equals("orbe");
			if (v.vista == null || !v.vista.isValid()) {
				v.vista = l.getWorld().spawn(l, ItemDisplay.class, e -> {
					e.setPersistent(false);
					e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
					e.setBillboard(orbe ? Display.Billboard.CENTER : Display.Billboard.FIXED);
					e.setBrightness(new Display.Brightness(15, 15));
					// el pad esta hecho acostado en el piso: lo giro para que apunte a su cara y lo apoyo
					if (!orbe) e.setTransformation(transPad(s.cara));
				});
			}
			ItemStack mod = modelo(s);
			if (!mod.equals(v.vista.getItemStack())) v.vista.setItemStack(mod);
			if (v.toque == null || !v.toque.isValid()) {
				org.bukkit.util.Vector n = normal(s.cara);
				Location centro = orbe ? l : l.clone().add(n.clone().multiply(0.12));
				float lado = orbe ? 1.0f : 0.5f;   // la orbe: todo su bloque
				v.toque = l.getWorld().spawn(centro.clone().add(0, -lado / 2, 0), Interaction.class, i -> {
					i.setInteractionWidth(lado); i.setInteractionHeight(lado); i.setResponsive(false); i.setPersistent(false);
					i.getPersistentDataContainer().set(marca, PersistentDataType.STRING, s.id);
				});
			}
		}
	}

	static org.bukkit.util.Vector normal(String cara) {
		return switch (cara) {
			case "down" -> new org.bukkit.util.Vector(0, -1, 0); case "north" -> new org.bukkit.util.Vector(0, 0, -1); case "south" -> new org.bukkit.util.Vector(0, 0, 1);
			case "east" -> new org.bukkit.util.Vector(1, 0, 0); case "west" -> new org.bukkit.util.Vector(-1, 0, 0); default -> new org.bukkit.util.Vector(0, 1, 0);
		};
	}
	/** El pad (hecho acostado, con la base en el piso del bloque) girado para que apunte a su cara, con la base apoyada en el punto. */
	private static Transformation transPad(String cara) {
		float r = (float) (Math.PI / 2);
		AxisAngle4f giro = switch (cara) {
			case "down" -> new AxisAngle4f((float) Math.PI, 1, 0, 0);
			case "north" -> new AxisAngle4f(-r, 1, 0, 0); case "south" -> new AxisAngle4f(r, 1, 0, 0);
			case "east" -> new AxisAngle4f(-r, 0, 0, 1); case "west" -> new AxisAngle4f(r, 0, 0, 1);
			default -> new AxisAngle4f();
		};
		org.bukkit.util.Vector n = normal(cara);
		return new Transformation(new Vector3f((float) n.getX() * 0.5f, (float) n.getY() * 0.5f, (float) n.getZ() * 0.5f), giro, new Vector3f(1, 1, 1), new AxisAngle4f());
	}

	/** Al tocar el piso: se le vuelven a cargar las orbes y ya puede recibir daño de caida (las particulas las hace el mod). */
	private void aterrizajes() {
		tick += 2;
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!p.isOnGround()) continue;
			UUID u = p.getUniqueId();
			gastadas.remove(u);
			Long t = lanzado.get(u);
			if (t != null && tick - t > 6) lanzado.remove(u);   // los primeros ticks todavia estaba en el piso del pad
		}
	}

	// ================= el menu =================
	private Salto deEntidad(Entity e) {
		if (!(e instanceof Interaction)) return null;
		String id = e.getPersistentDataContainer().get(marca, PersistentDataType.STRING);
		return id == null ? null : saltos.get(id);
	}
	@EventHandler(ignoreCancelled = false)
	public void alPegar(PrePlayerAttackEntityEvent e) {
		Salto s = deEntidad(e.getAttacked());
		if (s == null) return;
		e.setCancelled(true);
	}
	@EventHandler
	public void alTocar(PlayerInteractEntityEvent e) {
		Salto s = deEntidad(e.getRightClicked());
		if (s == null) return;
		e.setCancelled(true);
		if (e.getHand() == EquipmentSlot.HAND && plugin.varita.es(e.getPlayer().getInventory().getItemInMainHand()) && plugin.tablero().esEditor(e.getPlayer())) abrirMenu(e.getPlayer(), s);
	}

	private static final int COLOR = 10, ALTURA = 12, REPETIR = 14, BORRAR = 16;
	private static final class Menu implements InventoryHolder {
		final String id; Inventory inv; boolean confirmarBorrar;
		Menu(String id) { this.id = id; }
		@Override public Inventory getInventory() { return inv; }
	}
	void abrirMenu(Player p, Salto s) {
		Menu m = new Menu(s.id);
		m.inv = Bukkit.createInventory(m, 27, Component.text(s.tipo.equals("pad") ? "Pad" : "Orbe"));
		pintar(m, s);
		p.openInventory(m.inv);
	}
	private void pintar(Menu m, Salto s) {
		ItemStack vidrio = Varita.boton(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
		for (int i = 0; i < 27; i++) m.inv.setItem(i, vidrio);
		boolean roja = s.color.equals("roja");
		m.inv.setItem(COLOR, Varita.boton(roja ? Material.RED_DYE : Material.YELLOW_DYE, "Color: " + (roja ? "rojo" : "amarillo"), roja ? NamedTextColor.RED : NamedTextColor.YELLOW,
			"Clic: cambiar (vuelve a la altura de ese color)"));
		ItemStack h = Varita.boton(Material.FEATHER, "Altura: " + fmt(s.altura) + " bloques", NamedTextColor.AQUA,
			"Clic izquierdo: +1   Clic derecho: -1", "Shift + clic: de a 0,25", "Normal: " + fmt(alturaNormal(s.tipo, s.color)));
		h.setAmount((int) Math.max(1, Math.min(64, Math.ceil(s.altura))));
		m.inv.setItem(ALTURA, h);
		if (s.tipo.equals("orbe")) m.inv.setItem(REPETIR, Varita.boton(s.repetir ? Material.LIME_DYE : Material.GRAY_DYE, "Se puede repetir en el aire: " + (s.repetir ? "sí" : "no"), NamedTextColor.GREEN,
			s.repetir ? "Se usa todas las veces que quieras (cada 0,1 s)." : "Se gasta hasta que tocás el piso.", "Clic: cambiar"));
		m.inv.setItem(BORRAR, Varita.boton(Material.BARRIER, m.confirmarBorrar ? "¿Seguro? Clic de nuevo para borrar" : "Borrar", NamedTextColor.RED));
	}
	private static String fmt(double d) { return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d); }
	@EventHandler
	public void alClicMenu(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (e.getClickedInventory() != e.getInventory() || !(e.getWhoClicked() instanceof Player p)) return;
		Salto s = saltos.get(m.id);
		if (s == null) { p.closeInventory(); return; }
		switch (e.getSlot()) {
			case COLOR -> { s.color = s.color.equals("roja") ? "amarilla" : "roja"; s.altura = alturaNormal(s.tipo, s.color); }
			case ALTURA -> {
				double paso = e.isShiftClick() ? 0.25 : 1;
				s.altura = Math.max(0.25, Math.min(64, s.altura + (e.isRightClick() ? -paso : paso)));
			}
			case REPETIR -> { if (s.tipo.equals("orbe")) s.repetir = !s.repetir; }
			case BORRAR -> {
				if (!m.confirmarBorrar) { m.confirmarBorrar = true; pintar(m, s); return; }
				borrar(s.id); p.closeInventory(); p.sendMessage(Component.text("Borrado.", NamedTextColor.YELLOW)); return;
			}
			default -> { return; }
		}
		m.confirmarBorrar = false;
		guardar(); mandarTodos(); cadaRato();
		pintar(m, s);
	}
	@EventHandler
	public void alArrastrarMenu(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

	// ================= los items para ponerlos (pestaña Trucofdeath del creativo) =================
	private NamespacedKey marcaItem() { return new NamespacedKey(plugin, "salto_item"); }
	ItemStack item(String tipo, String color) {
		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta m = it.getItemMeta();
		boolean roja = color.equals("roja"), pad = tipo.equals("pad");
		m.itemName(Component.text((pad ? "Pad " : "Orbe ") + (roja ? "roj" : "amarill") + (pad ? "o" : "a"), roja ? NamedTextColor.RED : NamedTextColor.YELLOW));
		m.lore(List.of(Component.text(pad ? "Clic derecho en una cara de un bloque: ponerlo (apunta para afuera)." : "Clic derecho en un bloque: ponerla al lado.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
			Component.text("Para configurarlo: clic derecho con la varita.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
		m.setItemModel(new NamespacedKey("tod5", tipo + "_" + (roja ? "rojo" : "amarillo")));
		m.getPersistentDataContainer().set(marcaItem(), PersistentDataType.STRING, tipo + ":" + color);
		it.setItemMeta(m);
		return it;
	}
	@EventHandler
	public void alUsarItem(org.bukkit.event.player.PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null || !e.getItem().hasItemMeta()) return;
		String q = e.getItem().getItemMeta().getPersistentDataContainer().get(marcaItem(), PersistentDataType.STRING);
		if (q == null) return;
		e.setCancelled(true);
		if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		Player p = e.getPlayer();
		if (!plugin.tablero().esEditor(p)) { p.sendMessage(Component.text("Solo los editores pueden ponerlos.", NamedTextColor.RED)); return; }
		String[] t = q.split(":");
		crear(e.getClickedBlock().getRelative(e.getBlockFace()).getLocation(), t[0], t[1], e.getBlockFace());
	}
	List<JsonObject> itemsParaTablet() {
		List<JsonObject> l = new ArrayList<>();
		for (String t : List.of("orbe", "pad")) for (String c : List.of("amarilla", "roja")) {
			JsonObject j = new JsonObject(); j.addProperty("id", t + "_" + c); j.add("item", Crafteos.itemJson(item(t, c))); l.add(j);
		}
		return l;
	}

	// ================= /tod salto =================
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "crear" -> {   // /tod salto crear <orbe|pad> <amarilla|roja> x y z
				if (args.length < 7) { s.sendMessage(Component.text("Uso: /tod salto crear <orbe|pad> <amarilla|roja> <x> <y> <z> [up|down|north|south|east|west]", NamedTextColor.YELLOW)); return; }
				World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
				org.bukkit.block.BlockFace cara = args.length > 7 ? org.bukkit.block.BlockFace.valueOf(args[7].toUpperCase(Locale.ROOT)) : org.bukkit.block.BlockFace.UP;
				Salto n = crear(new Location(w, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6])), args[2].equals("pad") ? "pad" : "orbe", args[3].startsWith("roj") ? "roja" : "amarilla", cara);
				s.sendMessage(Component.text("Creado " + n.id + ".", NamedTextColor.GREEN));
			}
			case "lista" -> {
				s.sendMessage(Component.text("Orbes y pads: " + saltos.size(), NamedTextColor.YELLOW));
				for (Salto x : saltos.values()) s.sendMessage(Component.text(" " + x.id + " · " + x.tipo + " " + x.color + " · " + x.mundo + " " + x.x + " " + x.y + " " + x.z + " · " + fmt(x.altura) + " bloques" + (x.repetir ? " · se repite" : ""), NamedTextColor.GRAY));
			}
			case "borrar" -> {
				if (args.length < 3 || !saltos.containsKey(args[2])) { s.sendMessage(Component.text("Uso: /tod salto borrar <id> (mirá /tod salto lista)", NamedTextColor.YELLOW)); return; }
				borrar(args[2]); s.sendMessage(Component.text("Borrado.", NamedTextColor.YELLOW));
			}
			default -> s.sendMessage(Component.text("Uso: /tod salto [crear | lista | borrar]  (se ponen desde la pestaña Trucofdeath del creativo; se editan con la varita)", NamedTextColor.YELLOW));
		}
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "lista", "borrar");
		if (args.length == 3 && args[1].equalsIgnoreCase("borrar")) return new ArrayList<>(saltos.keySet());
		if (args.length == 3 && args[1].equalsIgnoreCase("crear")) return List.of("orbe", "pad");
		if (args.length == 4 && args[1].equalsIgnoreCase("crear")) return List.of("amarilla", "roja");
		return List.of();
	}
}
