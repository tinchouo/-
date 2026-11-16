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
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * Las flechas de gravedad (como en Mario Galaxy).
 *
 * Una flecha esta en un lugar (puede estar en el aire). Se le pega con clic izquierdo o derecho:
 *   suena ON, la flecha gira (suena MOVE ~1 s), para (STOP) y recien ahi cambia la gravedad (SWITCHED).
 * Cada jugador ve SU flecha: apunta a su gravedad de ahora, verde si le quedan usos, roja si ya la gasto (suena NG).
 *
 * Se configura con shift + clic derecho (solo editores), en un menu chico:
 *   - modo: fija (siempre a la direccion 1) o alterna (entre la 1 y la 2)
 *   - usos por jugador: 1 (por defecto), un numero o infinito
 *   - reactivar (vuelve a verde para todos) y borrar
 * Se pone con el item "Flecha de gravedad" (pestaña Trucofdeath del creativo o /tod flecha) o con /tod flecha crear [x y z].
 *
 * Archivos: datos/flechas.json (las flechas) y datos/flechas_usos.json (cuantas veces la uso cada uno).
 */
final class Flechas implements Listener {
	static final class Flecha {
		String id, mundo, modo = "alterna", a = "abajo", b = "arriba";
		double x, y, z;
		int usos = 1;   // 0 = infinito
		Location lugar() { World w = Bukkit.getWorld(mundo); return w == null ? null : new Location(w, x, y, z); }
	}
	/** Lo que esta en el mundo para una flecha: el cuadro para pegarle (uno para todos) y la flecha que ve cada jugador. */
	private static final class Vivo {
		Interaction golpe;
		final Map<UUID, ItemDisplay> vista = new HashMap<>();
		final Map<UUID, String> estado = new HashMap<>();   // lo que muestra cada vista ("abajo:verde"), para no mandar de nuevo
	}

	private static final int GIRO = 20;            // ticks que dura el giro (1 s)
	private static final double CERCA = 48;         // a esta distancia aparece
	private static final float TAM = 1.0f;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final TOD5Servidor plugin;
	private final NamespacedKey marcaFlecha, marcaItem;
	final Map<String, Flecha> flechas = new LinkedHashMap<>();
	private final Map<String, Map<UUID, Integer>> usados = new HashMap<>();
	private final Map<String, Vivo> vivos = new HashMap<>();
	private final Set<String> girando = new HashSet<>();   // "flecha:jugador" mientras gira (no se le puede pegar de nuevo)
	private int proximo = 1;

	Flechas(TOD5Servidor plugin) {
		this.plugin = plugin;
		this.marcaFlecha = new NamespacedKey(plugin, "flecha");
		this.marcaItem = new NamespacedKey(plugin, "flecha_item");
		Bukkit.getScheduler().runTaskTimer(plugin, this::cadaRato, 10, 10);
	}

	// ================= archivos =================
	private File archivo(String n) { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, n); }
	private static String str(JsonObject o, String k, String def) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; }

	void cargar() {
		flechas.clear(); usados.clear();
		try {
			File f = archivo("flechas.json");
			if (f.exists()) {
				JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
				proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
				for (JsonElement e : o.getAsJsonArray("flechas")) {
					JsonObject j = e.getAsJsonObject(); Flecha fl = new Flecha();
					fl.id = str(j, "id", "f" + proximo++); fl.mundo = str(j, "mundo", "world");
					fl.x = j.get("x").getAsDouble(); fl.y = j.get("y").getAsDouble(); fl.z = j.get("z").getAsDouble();
					fl.modo = str(j, "modo", "alterna"); fl.a = str(j, "a", "abajo"); fl.b = str(j, "b", "arriba");
					fl.usos = j.has("usos") ? j.get("usos").getAsInt() : 1;
					flechas.put(fl.id, fl);
				}
			}
			File u = archivo("flechas_usos.json");
			if (u.exists()) {
				JsonObject o = JsonParser.parseString(Files.readString(u.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
				for (String id : o.keySet()) {
					Map<UUID, Integer> m = new HashMap<>();
					JsonObject j = o.getAsJsonObject(id);
					for (String k : j.keySet()) m.put(UUID.fromString(k), j.get(k).getAsInt());
					usados.put(id, m);
				}
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer las flechas: " + e.getMessage()); }
		plugin.getLogger().info("Flechas de gravedad: " + flechas.size());
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray();
		for (Flecha f : flechas.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", f.id); j.addProperty("mundo", f.mundo); j.addProperty("x", f.x); j.addProperty("y", f.y); j.addProperty("z", f.z);
			j.addProperty("modo", f.modo); j.addProperty("a", f.a); j.addProperty("b", f.b); j.addProperty("usos", f.usos);
			a.add(j);
		}
		o.add("flechas", a);
		JsonObject u = new JsonObject();
		usados.forEach((id, m) -> { JsonObject j = new JsonObject(); m.forEach((k, v) -> j.addProperty(k.toString(), v)); u.add(id, j); });
		try {
			Files.writeString(archivo("flechas.json").toPath(), GSON.toJson(o), StandardCharsets.UTF_8);
			Files.writeString(archivo("flechas_usos.json").toPath(), GSON.toJson(u), StandardCharsets.UTF_8);
		} catch (Exception e) { plugin.getLogger().severe("No pude guardar las flechas: " + e.getMessage()); }
	}
	/** Al apagar: saco todo lo que hay en el mundo (igual no se guarda, pero por las dudas). */
	void apagar() { for (String id : new ArrayList<>(vivos.keySet())) sacar(id); }

	// ================= poner / sacar =================
	Flecha crear(Location l) {
		Flecha f = new Flecha();
		f.id = "f" + (proximo++); f.mundo = l.getWorld().getName();
		f.x = Math.round(l.getX() * 2) / 2.0; f.y = Math.round(l.getY() * 2) / 2.0; f.z = Math.round(l.getZ() * 2) / 2.0;   // a medio bloque: queda prolija
		flechas.put(f.id, f); guardar();
		plugin.getLogger().info("Flecha de gravedad " + f.id + " en " + f.mundo + " " + f.x + " " + f.y + " " + f.z);
		return f;
	}
	void borrar(String id) { sacar(id); flechas.remove(id); usados.remove(id); guardar(); }
	private void sacar(String id) {
		Vivo v = vivos.remove(id);
		if (v == null) return;
		if (v.golpe != null) v.golpe.remove();
		v.vista.values().forEach(Entity::remove);
	}

	ItemStack item() {
		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta m = it.getItemMeta();
		m.itemName(Component.text("Flecha de gravedad"));
		m.lore(List.of(Component.text("Clic derecho: la ponés acá (solo editores).", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Shift + clic derecho en la flecha: configurarla.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
		m.setItemModel(new NamespacedKey("tod5", "flecha_verde"));
		m.getPersistentDataContainer().set(marcaItem, PersistentDataType.BYTE, (byte) 1);
		it.setItemMeta(m);
		return it;
	}
	private boolean esItem(ItemStack it) {
		return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(marcaItem, PersistentDataType.BYTE);
	}

	// ================= lo que ve cada uno =================
	private int usos(String id, UUID u) { return usados.getOrDefault(id, Map.of()).getOrDefault(u, 0); }
	private boolean gastada(Flecha f, UUID u) { return f.usos > 0 && usos(f.id, u) >= f.usos; }
	/** A donde la manda esta flecha si le pega ahora. */
	private Gravedad.Dir destino(Flecha f, Gravedad.Dir actual) {
		Gravedad.Dir a = Gravedad.Dir.de(f.a), b = Gravedad.Dir.de(f.b);
		if (a == null) a = Gravedad.Dir.ABAJO;
		if (b == null) b = Gravedad.Dir.ARRIBA;
		if (f.modo.equals("fija")) return a;
		return actual == a ? b : a;
	}
	private static float angulo(Gravedad.Dir d) {
		return switch (d) { case ARRIBA -> (float) Math.PI; case IZQUIERDA -> (float) (-Math.PI / 2); case DERECHA -> (float) (Math.PI / 2); default -> 0f; };
	}
	private static Transformation trans(float ang) {
		return new Transformation(new Vector3f(), new AxisAngle4f(ang, 0, 0, 1), new Vector3f(TAM, TAM, TAM), new AxisAngle4f());
	}
	private ItemStack modelo(boolean roja) {
		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta m = it.getItemMeta();
		m.setItemModel(new NamespacedKey("tod5", roja ? "flecha_roja" : "flecha_verde"));
		it.setItemMeta(m);
		return it;
	}

	/** Cada medio segundo: aparece/desaparece segun quien esta cerca, y cada vista apunta a la gravedad de su jugador. */
	private void cadaRato() {
		for (Flecha f : flechas.values()) {
			Location l = f.lugar();
			Vivo v = vivos.get(f.id);
			List<Player> cerca = new ArrayList<>();
			if (l != null) for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < CERCA * CERCA) cerca.add(p);
			if (cerca.isEmpty()) { if (v != null) sacar(f.id); continue; }
			if (v == null) { v = new Vivo(); vivos.put(f.id, v); }
			if (v.golpe == null || !v.golpe.isValid()) {
				v.golpe = l.getWorld().spawn(l.clone().add(0, -0.6, 0), Interaction.class, i -> {
					i.setInteractionWidth(0.9f); i.setInteractionHeight(1.2f); i.setResponsive(true); i.setPersistent(false);
					i.getPersistentDataContainer().set(marcaFlecha, PersistentDataType.STRING, f.id);
				});
			}
			Set<UUID> estan = new HashSet<>();
			for (Player p : cerca) {
				estan.add(p.getUniqueId());
				ItemDisplay d = v.vista.get(p.getUniqueId());
				if (d == null || !d.isValid()) {
					d = l.getWorld().spawn(l, ItemDisplay.class, e -> {
						e.setVisibleByDefault(false); e.setPersistent(false);
						e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
						e.setBillboard(Display.Billboard.VERTICAL);
						e.setBrightness(new Display.Brightness(15, 15));
					});
					p.showEntity(plugin, d);
					v.vista.put(p.getUniqueId(), d);
					v.estado.remove(p.getUniqueId());
				}
				if (girando.contains(f.id + ":" + p.getUniqueId())) continue;   // mientras gira no la toco
				Gravedad.Dir g = plugin.gravedad.de(p);
				boolean roja = gastada(f, p.getUniqueId());
				String est = g.id + ":" + roja;
				if (!est.equals(v.estado.get(p.getUniqueId()))) {
					boolean primera = !v.estado.containsKey(p.getUniqueId());
					d.setItemStack(modelo(roja));
					d.setInterpolationDelay(0); d.setInterpolationDuration(primera ? 0 : 6);
					d.setTransformation(trans(angulo(g)));
					v.estado.put(p.getUniqueId(), est);
				}
			}
			for (Iterator<Map.Entry<UUID, ItemDisplay>> it = v.vista.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, ItemDisplay> e = it.next();
				if (!estan.contains(e.getKey())) { e.getValue().remove(); v.estado.remove(e.getKey()); it.remove(); }
			}
		}
	}

	// ================= pegarle =================
	private Flecha deEntidad(Entity e) {
		if (!(e instanceof Interaction)) return null;
		String id = e.getPersistentDataContainer().get(marcaFlecha, PersistentDataType.STRING);
		return id == null ? null : flechas.get(id);
	}
	@EventHandler(ignoreCancelled = false)
	public void alPegar(PrePlayerAttackEntityEvent e) {
		Flecha f = deEntidad(e.getAttacked());
		if (f == null) return;
		e.setCancelled(true);
		golpe(e.getPlayer(), f);
	}
	@EventHandler
	public void alTocar(PlayerInteractEntityEvent e) {
		Flecha f = deEntidad(e.getRightClicked());
		if (f == null) return;
		e.setCancelled(true);
		if (e.getHand() != EquipmentSlot.HAND) return;
		Player p = e.getPlayer();
		if (p.isSneaking() && plugin.tablero().esEditor(p)) { abrirMenu(p, f); return; }
		golpe(p, f);
	}

	private void sonar(Player p, Location l, String s) { p.playSound(l, "tod5:" + s, SoundCategory.BLOCKS, 1f, 1f); }

	void golpe(Player p, Flecha f) {
		String clave = f.id + ":" + p.getUniqueId();
		if (girando.contains(clave)) return;   // ya esta girando: el segundo clic (o el clic doble del juego) no hace nada
		Location l = f.lugar();
		Gravedad.Dir actual = plugin.gravedad.de(p), dest = destino(f, actual);
		if (gastada(f, p.getUniqueId()) || dest == actual || !dest.lista()) {
			sonar(p, l, "flecha_ng");
			plugin.getLogger().info(p.getName() + " le pego a la flecha " + f.id + ": no hace nada (" + (gastada(f, p.getUniqueId()) ? "gastada" : "ya esta en " + dest.id) + ")");
			return;
		}
		Vivo v = vivos.get(f.id);
		ItemDisplay d = v == null ? null : v.vista.get(p.getUniqueId());
		girando.add(clave);
		usados.computeIfAbsent(f.id, k -> new HashMap<>()).merge(p.getUniqueId(), 1, Integer::sum);
		guardar();
		plugin.getLogger().info(p.getName() + " le pego a la flecha " + f.id + ": " + actual.id + " -> " + dest.id);
		sonar(p, l, "flecha_on");
		sonar(p, l, "flecha_mueve");
		// el giro en dos mitades (un giro de 180 de una sola vez no sabe para que lado ir)
		float a0 = angulo(actual), a1 = angulo(dest);
		float medio = a0 + (float) (Math.PI / 2) * Math.signum(a1 - a0 == 0 ? 1 : a1 - a0);
		if (d != null) { d.setInterpolationDelay(0); d.setInterpolationDuration(GIRO / 2); d.setTransformation(trans(medio)); }
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (d != null && d.isValid()) { d.setInterpolationDelay(0); d.setInterpolationDuration(GIRO / 2); d.setTransformation(trans(a1)); }
		}, GIRO / 2);
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			girando.remove(clave);
			if (!p.isOnline()) return;
			sonar(p, l, "flecha_para");
			plugin.gravedad.poner(p, dest);
			p.playSound(p.getLocation(), "tod5:gravedad_cambio", SoundCategory.MASTER, 1f, 1f);
			if (v != null) v.estado.remove(p.getUniqueId());   // la proxima vuelta le pone el color (roja si se gasto)
		}, GIRO);
	}

	// ================= ponerla con el item =================
	@EventHandler
	public void alUsarItem(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || !esItem(e.getItem())) return;
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		if (!plugin.tablero().esEditor(p)) { p.sendMessage(Component.text("Solo los editores pueden poner flechas.", NamedTextColor.RED)); return; }
		Location l;
		if (e.getClickedBlock() != null && e.getBlockFace() != null) l = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0.5, 0.5);
		else l = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(3));
		Flecha f = crear(l);
		p.sendMessage(Component.text("Flecha puesta. Shift + clic derecho en ella para configurarla.", NamedTextColor.GREEN));
		cadaRato();
	}

	// ================= el menu (shift + clic derecho) =================
	private static final int MODO = 10, DIR_A = 12, DIR_B = 13, USOS = 14, REACTIVAR = 16, BORRAR = 22;
	private static final class Menu implements InventoryHolder {
		final String id; Inventory inv; boolean confirmarBorrar;
		Menu(String id) { this.id = id; }
		@Override public Inventory getInventory() { return inv; }
	}
	private static ItemStack boton(Material m, String nombre, NamedTextColor c, String... lore) {
		ItemStack it = new ItemStack(m);
		ItemMeta im = it.getItemMeta();
		im.itemName(Component.text(nombre, c));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		im.lore(l);
		it.setItemMeta(im);
		return it;
	}
	private static String nombre(String dir) { return dir.substring(0, 1).toUpperCase() + dir.substring(1); }
	void abrirMenu(Player p, Flecha f) {
		Menu m = new Menu(f.id);
		m.inv = Bukkit.createInventory(m, 27, Component.text("Flecha de gravedad"));
		pintar(m, f);
		p.openInventory(m.inv);
	}
	private void pintar(Menu m, Flecha f) {
		ItemStack vidrio = boton(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
		for (int i = 0; i < 27; i++) m.inv.setItem(i, vidrio);
		boolean fija = f.modo.equals("fija");
		m.inv.setItem(MODO, boton(fija ? Material.REPEATER : Material.COMPARATOR, "Modo: " + (fija ? "fija" : "alterna"), NamedTextColor.YELLOW,
			fija ? "Siempre manda a la dirección 1." : "Cada golpe cambia entre la dirección 1 y la 2.", "Clic: cambiar"));
		m.inv.setItem(DIR_A, boton(Material.ARROW, "Dirección 1: " + nombre(f.a), NamedTextColor.GREEN, "Clic: cambiar", "(izquierda y derecha: más adelante)"));
		if (!fija) m.inv.setItem(DIR_B, boton(Material.SPECTRAL_ARROW, "Dirección 2: " + nombre(f.b), NamedTextColor.GREEN, "Clic: cambiar"));
		ItemStack u = boton(Material.CLOCK, "Usos por jugador: " + (f.usos == 0 ? "infinitos" : f.usos), NamedTextColor.AQUA,
			"Clic izquierdo: +1   Clic derecho: -1", "Shift + clic: infinitos / 1", "Cada jugador tiene sus usos.");
		if (f.usos > 0) u.setAmount(Math.min(64, f.usos));
		m.inv.setItem(USOS, u);
		m.inv.setItem(REACTIVAR, boton(Material.LIME_DYE, "Reactivar", NamedTextColor.GREEN, "Vuelve a verde para todos (borra los usos)."));
		m.inv.setItem(BORRAR, m.confirmarBorrar ? boton(Material.TNT, "¿Borrar? Clic de nuevo", NamedTextColor.RED, "Se borra la flecha.")
			: boton(Material.BARRIER, "Borrar flecha", NamedTextColor.RED, "Clic: borrar (te pide confirmar)"));
	}
	@EventHandler
	public void alClicMenu(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		Flecha f = flechas.get(m.id);
		if (f == null || e.getRawSlot() >= 27) return;
		Player p = (Player) e.getWhoClicked();
		if (!plugin.tablero().esEditor(p)) { p.closeInventory(); return; }
		boolean bor = false;
		switch (e.getRawSlot()) {
			case MODO -> f.modo = f.modo.equals("fija") ? "alterna" : "fija";
			case DIR_A -> f.a = f.a.equals("abajo") ? "arriba" : "abajo";
			case DIR_B -> { if (!f.modo.equals("fija")) f.b = f.b.equals("abajo") ? "arriba" : "abajo"; }
			case USOS -> {
				if (e.isShiftClick()) f.usos = f.usos == 0 ? 1 : 0;
				else if (e.isRightClick()) f.usos = f.usos <= 1 ? 1 : f.usos - 1;
				else if (f.usos > 0) f.usos = Math.min(999, f.usos + 1);
			}
			case REACTIVAR -> { usados.remove(f.id); p.sendMessage(Component.text("Flecha reactivada para todos.", NamedTextColor.GREEN)); }
			case BORRAR -> {
				if (m.confirmarBorrar) { borrar(f.id); p.closeInventory(); p.sendMessage(Component.text("Flecha borrada.", NamedTextColor.YELLOW)); return; }
				bor = true;
			}
			default -> { return; }
		}
		m.confirmarBorrar = bor;
		guardar();
		Vivo v = vivos.get(f.id); if (v != null) v.estado.clear();   // que todos la vuelvan a pintar
		pintar(m, f);
		p.playSound(p.getLocation(), "tod5:tablet_tocar", SoundCategory.MASTER, 0.8f, 1f);
	}
	@EventHandler
	public void alArrastrarMenu(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
	@EventHandler
	public void alSalir(PlayerQuitEvent e) {
		UUID u = e.getPlayer().getUniqueId();
		for (Vivo v : vivos.values()) { ItemDisplay d = v.vista.remove(u); if (d != null) d.remove(); v.estado.remove(u); }
	}

	// ================= /tod flecha =================
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "crear" -> {
				Location l;
				if (args.length >= 5 && s instanceof Player p) l = new Location(p.getWorld(), Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
				else if (args.length >= 5) l = new Location(Bukkit.getWorlds().get(0), Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
				else if (s instanceof Player p) l = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(3));
				else { s.sendMessage(Component.text("Uso: /tod flecha crear <x> <y> <z>", NamedTextColor.YELLOW)); return; }
				Flecha f = crear(l);
				s.sendMessage(Component.text("Flecha " + f.id + " creada.", NamedTextColor.GREEN));
				cadaRato();
			}
			case "lista" -> {
				s.sendMessage(Component.text("Flechas: " + flechas.size(), NamedTextColor.YELLOW));
				for (Flecha f : flechas.values()) s.sendMessage(Component.text(" " + f.id + " · " + f.mundo + " " + f.x + " " + f.y + " " + f.z + " · " + f.modo + " " + f.a + (f.modo.equals("fija") ? "" : "/" + f.b) + " · usos " + (f.usos == 0 ? "∞" : f.usos), NamedTextColor.GRAY));
			}
			case "borrar" -> {
				if (args.length < 3 || !flechas.containsKey(args[2])) { s.sendMessage(Component.text("Uso: /tod flecha borrar <id> (mirá /tod flecha lista)", NamedTextColor.YELLOW)); return; }
				borrar(args[2]); s.sendMessage(Component.text("Flecha borrada.", NamedTextColor.YELLOW));
			}
			case "pegar" -> {   // para las pruebas: le pega a una flecha como si fuera el jugador
				if (args.length < 4) return;
				Player p = Bukkit.getPlayerExact(args[3]); Flecha f = flechas.get(args[2]);
				if (p != null && f != null) golpe(p, f);
			}
			default -> {
				if (s instanceof Player p) { p.getInventory().addItem(item()); s.sendMessage(Component.text("Te di una flecha de gravedad: clic derecho para ponerla.", NamedTextColor.GREEN)); }
				else s.sendMessage(Component.text("Uso: /tod flecha [crear x y z | lista | borrar id]", NamedTextColor.YELLOW));
			}
		}
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "lista", "borrar");
		if (args.length == 3 && args[1].equalsIgnoreCase("borrar")) return new ArrayList<>(flechas.keySet());
		return List.of();
	}
	/** Para la pestaña Trucofdeath del creativo (los editores la sacan de ahi). */
	JsonObject itemParaTablet() {
		JsonObject j = new JsonObject(); j.addProperty("id", "flecha_gravedad"); j.add("item", Crafteos.itemJson(item()));
		return j;
	}
}
