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
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Plataformas que se mueven: aparecen en su lugar de salida (marcado con particulas), van derecho hasta el final en el tiempo configurado y:
 * Como en Mario Galaxy 2: medio bloque de alto, un color de la ruleta, borde oscuro y esquinas redondeadas; la salida es un cuadrado brillante.
 * Arranca y frena suave. Al aparecer crece desde el cuadrado.
 *  - "parpadeo" (lo normal): llega, se queda un ratito (configurable), parpadea en blanco y desaparece; a los 0,75 s vuelve a salir
 *  - "desvanece": los ultimos N segundos (configurable, como un fade out) se va haciendo transparente; al llegar ya no esta (ni se pisa)
 * Cada bloque es una pieza (item display) con el borde segun que lados son de afuera; el color va por tinte. Lo solido lo pone el mod:
 * el server le manda a cada uno la pieza "raiz" de cada plataforma y su tamaño (canal tod5:plataformas), y el mod hace que se pueda pisar y que te lleve.
 * Como el server no sabe que estas parado en algo, no echa por "volar" a quien este arriba de una plataforma.
 */
final class Plataformas implements Listener {
	static final String CANAL = "tod5:plataformas";
	static final class Plataforma {
		String id, mundo, modo = "parpadeo", color = "amarillo";
		int ax, ay, az, bx, by, bz, ancho = 3, largo = 3;
		double segundos = 4, espera = 1, fade = 1.5;   // espera: el ratito al final (parpadeo); fade: cuanto dura el desvanecido (desvanece)
		Location salida(World w) { return new Location(w, ax, ay, az); }
	}
	private static final class Viva {
		final List<ItemDisplay> bloques = new ArrayList<>();
		final List<ItemDisplay> marco = new ArrayList<>();
		Interaction toque;
		int t;                // ticks desde que salio
		int fase;             // 0 = viajando, 1 = quieta al final, 2 = parpadeando, 3 = no esta (esperando)
		int alfa = 7;         // 7 = entera ... 0 = casi invisible
		boolean blanca;
		double x, y, z;       // donde esta ahora (la esquina)
	}
	private static final int PARPADEO = 10, ESPERA = 15, CRECE = 6;   // 0,5 s parpadeando, 0,75 s sin estar, 0,3 s creciendo
	static final double ALTO = 0.5;
	/** Los colores de la ruleta. */
	static final LinkedHashMap<String, Integer> COLORES = new LinkedHashMap<>();
	static {
		COLORES.put("verde", 0x55FF55); COLORES.put("celeste", 0x55FFFF); COLORES.put("azul", 0x4678FF); COLORES.put("violeta", 0xB45AFF);
		COLORES.put("rosa", 0xFF78C8); COLORES.put("rojo", 0xFF3C3C); COLORES.put("naranja", 0xFF9600); COLORES.put("amarillo", 0xFFE63C);
	}
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final TOD5Servidor plugin;
	private final NamespacedKey marca;
	final Map<String, Plataforma> plataformas = new LinkedHashMap<>();
	private final Map<String, Viva> vivas = new HashMap<>();
	private final Map<UUID, String> poniendoFinal = new HashMap<>();   // editor que esta eligiendo el final de una plataforma con la varita
	private int proximo = 1;

	Plataformas(TOD5Servidor plugin) {
		this.plugin = plugin;
		this.marca = new NamespacedKey(plugin, "plataforma");
		Bukkit.getScheduler().runTaskTimer(plugin, this::cadaTick, 20, 1);
		Bukkit.getScheduler().runTaskTimer(plugin, this::marcarSalidas, 20, 10);
	}

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "plataformas.json"); }
	void cargar() {
		plataformas.clear();
		try {
			File f = archivo();
			if (f.exists()) {
				JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
				proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
				for (JsonElement e : o.getAsJsonArray("plataformas")) {
					Plataforma p = GSON.fromJson(e, Plataforma.class);
					if (p == null || p.id == null) continue;
					if (!p.modo.equals("parpadeo")) p.modo = "desvanece";
					if (p.color == null || !COLORES.containsKey(p.color)) p.color = "amarillo";
					if (p.espera <= 0 && p.fade <= 0) { p.espera = 1; p.fade = 1.5; }
					plataformas.put(p.id, p);
				}
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer las plataformas: " + e.getMessage()); }
		plugin.getLogger().info("Plataformas: " + plataformas.size());
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray();
		for (Plataforma p : plataformas.values()) a.add(GSON.toJsonTree(p));
		o.add("plataformas", a);
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar las plataformas: " + e.getMessage()); }
	}

	// ================= avisarle al mod =================
	void mandar(Player pl) {
		JsonArray a = new JsonArray();
		for (Plataforma p : plataformas.values()) {
			Viva v = vivas.get(p.id);
			if (v == null || v.fase == 3 || v.bloques.isEmpty() || !p.mundo.equals(pl.getWorld().getName())) continue;
			JsonObject j = new JsonObject();
			j.addProperty("id", p.id); j.addProperty("raiz", v.bloques.get(0).getUniqueId().toString());
			j.addProperty("ancho", p.ancho); j.addProperty("alto", ALTO); j.addProperty("largo", p.largo);
			a.add(j);
		}
		pl.sendPluginMessage(plugin, CANAL, a.toString().getBytes(StandardCharsets.UTF_8));
	}
	private void mandarTodos() { for (Player p : Bukkit.getOnlinePlayers()) mandar(p); }
	@EventHandler public void alEntrar(PlayerJoinEvent e) { Player p = e.getPlayer(); Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) mandar(p); }, 40); }
	@EventHandler public void alCambiarMundo(PlayerChangedWorldEvent e) { mandar(e.getPlayer()); }

	/** Parado arriba de una plataforma el server cree que esta volando: no lo echo. */
	@EventHandler
	public void alEchar(PlayerKickEvent e) {
		if (!e.getCause().name().equals("FLYING_PLAYER")) return;
		BoundingBox caja = e.getPlayer().getBoundingBox().expand(1.5);
		for (Plataforma p : plataformas.values()) {
			Viva v = vivas.get(p.id);
			if (v == null || v.fase == 3 || !p.mundo.equals(e.getPlayer().getWorld().getName())) continue;
			if (caja.overlaps(new BoundingBox(v.x, v.y, v.z, v.x + p.ancho, v.y + ALTO, v.z + p.largo))) { e.setCancelled(true); return; }
		}
	}

	// ================= poner / sacar =================
	Plataforma crear(Location celda) {
		Plataforma p = new Plataforma();
		p.id = "pl" + (proximo++); p.mundo = celda.getWorld().getName();
		p.ax = celda.getBlockX() - 1; p.ay = celda.getBlockY(); p.az = celda.getBlockZ() - 1;   // la celda que tocaste queda en el medio (3x3)
		p.bx = p.ax + 8; p.by = p.ay; p.bz = p.az;
		plataformas.put(p.id, p); guardar();
		plugin.getLogger().info("Plataforma " + p.id + " en " + p.mundo + " " + p.ax + " " + p.ay + " " + p.az);
		return p;
	}
	void borrar(String id) { sacar(id); plataformas.remove(id); guardar(); mandarTodos(); }
	private void sacar(String id) {
		Viva v = vivas.remove(id);
		if (v == null) return;
		v.bloques.forEach(Entity::remove);
		v.marco.forEach(Entity::remove);
		if (v.toque != null) v.toque.remove();
	}
	void apagar() { for (String id : new ArrayList<>(vivas.keySet())) sacar(id); }
	/** Vuelve a empezar desde la salida (despues de cambiarle algo). */
	private void reiniciar(Plataforma p) { sacar(p.id); mandarTodos(); }

	/** Que lados de esta pieza son "de afuera" (N=1, E=2, S=4, W=8). */
	private static int mascara(Plataforma p, int i, int k) {
		return (k == 0 ? 1 : 0) | (i == p.ancho - 1 ? 2 : 0) | (k == p.largo - 1 ? 4 : 0) | (i == 0 ? 8 : 0);
	}
	private static ItemStack pieza(String modelo, int rgb) {
		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta m = it.getItemMeta();
		m.setItemModel(new NamespacedKey("tod5", modelo));
		CustomModelDataComponent c = m.getCustomModelDataComponent();
		c.setColors(List.of(Color.fromRGB(rgb)));
		m.setCustomModelDataComponent(c);
		it.setItemMeta(m);
		return it;
	}
	private int color(Plataforma p) { return COLORES.getOrDefault(p.color, 0xFFE63C); }
	private boolean alguienCerca(Plataforma p, World w) {
		Location l = p.salida(w), f = new Location(w, p.bx, p.by, p.bz);
		for (Player pl : w.getPlayers()) if (pl.getLocation().distanceSquared(l) < 64 * 64 || pl.getLocation().distanceSquared(f) < 64 * 64) return true;
		return false;
	}
	private int duracion(Plataforma p) { return Math.max(4, (int) Math.round(p.segundos * 20)); }

	private void aparecer(Plataforma p, Viva v, World w) {
		v.bloques.forEach(Entity::remove); v.bloques.clear();
		int rgb = color(p);
		for (int i = 0; i < p.ancho; i++) for (int k = 0; k < p.largo; k++) {
			ItemStack it = pieza("plataforma_" + mascara(p, i, k) + "_7", rgb);
			final int fi = i, fk = k;
			v.bloques.add(w.spawn(new Location(w, p.ax + i + 0.5, p.ay + 0.5, p.az + k + 0.5), ItemDisplay.class, d -> {
				d.setItemStack(it); d.setPersistent(false); d.setTeleportDuration(1);
				d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
				d.setBrightness(new Display.Brightness(15, 15));
				// crece desde el cuadrado de la salida
				d.setTransformation(new Transformation(new Vector3f(0, -0.25f, 0), new AxisAngle4f(), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
				d.getPersistentDataContainer().set(marca, PersistentDataType.STRING, p.id + ":" + fi + ":" + fk);
			}));
		}
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			for (ItemDisplay d : v.bloques) {
				d.setInterpolationDelay(0); d.setInterpolationDuration(CRECE);
				d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
			}
		}, 1);
		v.t = 0; v.fase = 0; v.x = p.ax; v.y = p.ay; v.z = p.az; v.alfa = 7; v.blanca = false;
		mandarTodos();
	}
	/** Cambia la transparencia (0..7) o el color de todas las piezas. */
	private void pintarPiezas(Plataforma p, Viva v, int alfa, boolean blanca) {
		if (alfa == v.alfa && blanca == v.blanca) return;
		v.alfa = alfa; v.blanca = blanca;
		int rgb = blanca ? 0xFFFFFF : color(p);
		for (int n = 0; n < v.bloques.size(); n++) {
			int i = n / p.largo, k = n % p.largo;
			v.bloques.get(n).setItemStack(pieza("plataforma_" + mascara(p, i, k) + "_" + alfa, rgb));
		}
	}
	private void quitar(Viva v) { v.bloques.forEach(Entity::remove); v.bloques.clear(); v.t = 0; v.fase = 3; mandarTodos(); }
	private static double suave(double x) { return x * x * (3 - 2 * x); }

	private void cadaTick() {
		for (Plataforma p : plataformas.values()) {
			World w = Bukkit.getWorld(p.mundo);
			Viva v = vivas.get(p.id);
			if (w == null || !alguienCerca(p, w)) { if (v != null) { sacar(p.id); mandarTodos(); } continue; }
			if (v == null) { v = new Viva(); vivas.put(p.id, v); aparecer(p, v, w); }
			// el cuadradito para configurarla (en el medio de la salida, solo lo usan los editores con la varita)
			if (v.toque == null || !v.toque.isValid()) {
				final String id = p.id;
				v.toque = w.spawn(new Location(w, p.ax + p.ancho / 2.0, p.ay, p.az + p.largo / 2.0), Interaction.class, i -> {
					i.setInteractionWidth(0.6f); i.setInteractionHeight(0.3f); i.setResponsive(false); i.setPersistent(false);
					i.getPersistentDataContainer().set(marca, PersistentDataType.STRING, id);
				});
			}
			if (v.marco.isEmpty() || !v.marco.get(0).isValid()) ponerMarco(p, v, w);
			int dur = duracion(p);
			v.t++;
			if (v.fase == 0) {
				double f = suave(Math.min(1.0, v.t / (double) dur));   // arranca y frena despacio
				v.x = p.ax + (p.bx - p.ax) * f; v.y = p.ay + (p.by - p.ay) * f; v.z = p.az + (p.bz - p.az) * f;
				for (int n = 0; n < v.bloques.size(); n++) {
					int i = n / p.largo, k = n % p.largo;
					v.bloques.get(n).teleport(new Location(w, v.x + i + 0.5, v.y + 0.5, v.z + k + 0.5));
				}
				if (p.modo.equals("desvanece")) {
					int fadeT = Math.max(1, (int) Math.round(p.fade * 20));
					int falta = dur - v.t;
					if (falta < fadeT) pintarPiezas(p, v, Math.max(0, (int) Math.floor(7.999 * falta / fadeT)), false);
				}
				if (v.t >= dur) {
					v.t = 0;
					if (p.modo.equals("desvanece")) quitar(v);   // ya no esta: no se pisa
					else v.fase = 1;
				}
			} else if (v.fase == 1) {
				if (v.t >= Math.round(p.espera * 20)) { v.t = 0; v.fase = 2; }
			} else if (v.fase == 2) {
				pintarPiezas(p, v, 7, (v.t / 2) % 2 == 0);   // parpadea en blanco
				if (v.t >= PARPADEO) quitar(v);
			} else if (v.t >= ESPERA) {
				aparecer(p, v, w);
			}
		}
	}

	/** El cuadrado brillante de la salida: piezas de marco en los bloques del borde. */
	private void ponerMarco(Plataforma p, Viva v, World w) {
		v.marco.forEach(Entity::remove); v.marco.clear();
		int rgb = color(p);
		for (int i = 0; i < p.ancho; i++) for (int k = 0; k < p.largo; k++) {
			int m = mascara(p, i, k);
			if (m == 0) continue;
			ItemStack it = pieza("plataforma_marco_" + m, rgb);
			v.marco.add(w.spawn(new Location(w, p.ax + i + 0.5, p.ay + 0.5 + 0.01, p.az + k + 0.5), ItemDisplay.class, d -> {
				d.setItemStack(it); d.setPersistent(false);
				d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
				d.setBrightness(new Display.Brightness(15, 15));
				d.setGlowing(false);
			}));
		}
	}

	/** Con la varita en la mano, los editores ven el final marcado (puntos naranjas). */
	private void marcarSalidas() {
		Particle.DustOptions fin = new Particle.DustOptions(Color.fromRGB(255, 160, 40), 0.9f);
		for (Plataforma p : plataformas.values()) {
			World w = Bukkit.getWorld(p.mundo);
			if (w == null) continue;
			for (Player pl : w.getPlayers()) {
				if (pl.getLocation().distanceSquared(new Location(w, p.ax, p.ay, p.az)) > 64 * 64) continue;
				if (!plugin.tablero().esEditor(pl) || !plugin.varita.es(pl.getInventory().getItemInMainHand())) continue;
				pl.spawnParticle(Particle.DUST, p.bx + p.ancho / 2.0, p.by + ALTO + 0.3, p.bz + p.largo / 2.0, 6, p.ancho / 3.0, 0.1, p.largo / 3.0, 0, fin);
			}
		}
	}

	// ================= varita: poner el final =================
	boolean esperandoFinal(Player p) { return poniendoFinal.containsKey(p.getUniqueId()); }
	/** El editor toco un bloque con la varita mientras elegia el final: la plataforma termina ahi (esa celda es la esquina como en la salida). */
	void ponerFinal(Player pl, Location celda) {
		Plataforma p = plataformas.get(poniendoFinal.remove(pl.getUniqueId()));
		if (p == null) return;
		p.bx = celda.getBlockX() - p.ancho / 2; p.by = celda.getBlockY(); p.bz = celda.getBlockZ() - p.largo / 2;
		guardar(); reiniciar(p);
		pl.sendMessage(Component.text("Final de la plataforma " + p.id + " puesto.", NamedTextColor.GREEN));
	}

	// ================= el menu =================
	private Plataforma deEntidad(Entity e) {
		if (!(e instanceof Interaction)) return null;
		String id = e.getPersistentDataContainer().get(marca, PersistentDataType.STRING);
		return id == null ? null : plataformas.get(id);
	}
	@EventHandler(ignoreCancelled = false)
	public void alPegar(PrePlayerAttackEntityEvent e) {
		Plataforma p = deEntidad(e.getAttacked());
		if (p == null) return;
		e.setCancelled(true);
	}
	@EventHandler
	public void alTocar(PlayerInteractEntityEvent e) {
		Plataforma p = deEntidad(e.getRightClicked());
		if (p == null) return;
		e.setCancelled(true);
		if (e.getHand() == EquipmentSlot.HAND && plugin.varita.es(e.getPlayer().getInventory().getItemInMainHand()) && plugin.tablero().esEditor(e.getPlayer())) abrirMenu(e.getPlayer(), p);
	}

	private static final int ANCHO = 10, LARGO = 11, COLOR = 13, VEL = 14, MODO = 15, TIEMPO = 16, FINAL = 20, REINICIAR = 22, BORRAR = 24;
	private static final class Menu implements InventoryHolder {
		final String id; Inventory inv; boolean confirmarBorrar;
		Menu(String id) { this.id = id; }
		@Override public Inventory getInventory() { return inv; }
	}
	void abrirMenu(Player pl, Plataforma p) {
		Menu m = new Menu(p.id);
		m.inv = Bukkit.createInventory(m, 27, Component.text("Plataforma " + p.id));
		pintar(m, p);
		pl.openInventory(m.inv);
	}
	private void pintar(Menu m, Plataforma p) {
		ItemStack vidrio = Varita.boton(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
		for (int i = 0; i < 27; i++) m.inv.setItem(i, vidrio);
		String[] ayuda = { "Clic izquierdo: +1   Clic derecho: -1" };
		m.inv.setItem(ANCHO, cantidad(Varita.boton(Material.OAK_PLANKS, "Ancho (este-oeste): " + p.ancho, NamedTextColor.AQUA, ayuda), p.ancho));
		m.inv.setItem(LARGO, cantidad(Varita.boton(Material.SPRUCE_PLANKS, "Largo (norte-sur): " + p.largo, NamedTextColor.AQUA, ayuda), p.largo));
		m.inv.setItem(COLOR, Varita.boton(lana(p.color), "Color: " + p.color, NamedTextColor.WHITE, "Clic izquierdo: siguiente   Clic derecho: anterior"));
		m.inv.setItem(VEL, Varita.boton(Material.SUGAR, "Tarda " + fmt(p.segundos) + " s en llegar", NamedTextColor.YELLOW,
			"Clic izquierdo: +0,5 s (más lenta)   Clic derecho: -0,5 s (más rápida)", "Shift + clic: de a 0,1 s"));
		boolean parpadeo = p.modo.equals("parpadeo");
		m.inv.setItem(MODO, Varita.boton(parpadeo ? Material.REDSTONE_LAMP : Material.GLASS, "Al llegar: " + (parpadeo ? "se queda, parpadea y se va" : "se desvanece"), NamedTextColor.LIGHT_PURPLE,
			"Clic: cambiar", "Después de 0,75 s vuelve a salir."));
		m.inv.setItem(TIEMPO, parpadeo
			? Varita.boton(Material.CLOCK, "Se queda " + fmt(p.espera) + " s antes de parpadear", NamedTextColor.GOLD, "Clic izquierdo: +0,25 s   Clic derecho: -0,25 s")
			: Varita.boton(Material.PHANTOM_MEMBRANE, "Se desvanece en los últimos " + fmt(p.fade) + " s", NamedTextColor.GOLD,
				"Como un fade out: siempre termina invisible al llegar.", "Clic izquierdo: +0,25 s   Clic derecho: -0,25 s"));
		m.inv.setItem(FINAL, Varita.boton(Material.TARGET, "Poner el final", NamedTextColor.GOLD,
			"Cerrá el menú y hacé clic derecho en un bloque con la varita:", "ahí llega la plataforma (los puntos naranjas).", "Ahora: " + p.bx + " " + p.by + " " + p.bz));
		m.inv.setItem(REINICIAR, Varita.boton(Material.COMPASS, "Volver a empezar", NamedTextColor.GREEN, "Sale de nuevo desde la salida."));
		m.inv.setItem(BORRAR, Varita.boton(Material.BARRIER, m.confirmarBorrar ? "¿Seguro? Clic de nuevo para borrar" : "Borrar", NamedTextColor.RED));
	}
	private static String fmt(double d) { return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(Math.round(d * 100) / 100.0); }
	private static Material lana(String c) {
		return switch (c) {
			case "verde" -> Material.LIME_WOOL; case "celeste" -> Material.LIGHT_BLUE_WOOL; case "azul" -> Material.BLUE_WOOL; case "violeta" -> Material.PURPLE_WOOL;
			case "rosa" -> Material.PINK_WOOL; case "rojo" -> Material.RED_WOOL; case "naranja" -> Material.ORANGE_WOOL; default -> Material.YELLOW_WOOL;
		};
	}
	private static ItemStack cantidad(ItemStack it, int n) { it.setAmount(Math.max(1, Math.min(64, n))); return it; }
	@EventHandler
	public void alClicMenu(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (e.getClickedInventory() != e.getInventory() || !(e.getWhoClicked() instanceof Player pl)) return;
		Plataforma p = plataformas.get(m.id);
		if (p == null) { pl.closeInventory(); return; }
		int d = e.isRightClick() ? -1 : 1;
		switch (e.getSlot()) {
			case ANCHO -> p.ancho = limitar(p.ancho + d, p.largo);
			case LARGO -> p.largo = limitar(p.largo + d, p.ancho);
			case COLOR -> {
				List<String> l = new ArrayList<>(COLORES.keySet());
				p.color = l.get((l.indexOf(p.color) + (d > 0 ? 1 : l.size() - 1)) % l.size());
			}
			case VEL -> p.segundos = Math.max(0.2, Math.min(120, Math.round((p.segundos + d * (e.isShiftClick() ? 0.1 : 0.5)) * 10) / 10.0));
			case MODO -> p.modo = p.modo.equals("parpadeo") ? "desvanece" : "parpadeo";
			case TIEMPO -> {
				if (p.modo.equals("parpadeo")) p.espera = Math.max(0, Math.min(30, p.espera + d * 0.25));
				else p.fade = Math.max(0.25, Math.min(p.segundos, p.fade + d * 0.25));
			}
			case FINAL -> { poniendoFinal.put(pl.getUniqueId(), p.id); pl.closeInventory(); pl.sendMessage(Component.text("Tocá un bloque con la varita: ahí termina la plataforma.", NamedTextColor.GOLD)); return; }
			case REINICIAR -> { reiniciar(p); return; }
			case BORRAR -> {
				if (!m.confirmarBorrar) { m.confirmarBorrar = true; pintar(m, p); return; }
				borrar(p.id); pl.closeInventory(); pl.sendMessage(Component.text("Plataforma borrada.", NamedTextColor.YELLOW)); return;
			}
			default -> { return; }
		}
		m.confirmarBorrar = false;
		guardar(); reiniciar(p);
		pintar(m, p);
	}
	/** Hasta 64 bloques por plataforma. */
	private static int limitar(int n, int otro) { n = Math.max(1, Math.min(16, n)); while (n > 1 && n * otro > 64) n--; return n; }
	@EventHandler
	public void alArrastrarMenu(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

	// ================= el item para ponerla (pestaña Trucofdeath del creativo) =================
	private NamespacedKey marcaItem() { return new NamespacedKey(plugin, "plataforma_item"); }
	ItemStack item() {
		ItemStack it = new ItemStack(Material.QUARTZ_SLAB);
		var m = it.getItemMeta();
		m.itemName(Component.text("Plataforma que se mueve", NamedTextColor.AQUA));
		m.lore(List.of(Component.text("Clic derecho en un bloque: ponerla (3x3) y abrir su menú.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
			Component.text("Para configurarla después: clic derecho con la varita en el cuadradito del medio de la salida.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
		m.getPersistentDataContainer().set(marcaItem(), PersistentDataType.BYTE, (byte) 1);
		it.setItemMeta(m);
		return it;
	}
	@EventHandler
	public void alUsarItem(org.bukkit.event.player.PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null || !e.getItem().hasItemMeta() || !e.getItem().getItemMeta().getPersistentDataContainer().has(marcaItem(), PersistentDataType.BYTE)) return;
		e.setCancelled(true);
		if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		Player pl = e.getPlayer();
		if (!plugin.tablero().esEditor(pl)) { pl.sendMessage(Component.text("Solo los editores pueden ponerlas.", NamedTextColor.RED)); return; }
		Plataforma p = crear(e.getClickedBlock().getRelative(e.getBlockFace()).getLocation());
		abrirMenu(pl, p);
	}
	JsonObject itemParaTablet() {
		JsonObject j = new JsonObject(); j.addProperty("id", "plataforma"); j.add("item", Crafteos.itemJson(item()));
		return j;
	}

	// ================= /tod plataforma =================
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "crear" -> {   // /tod plataforma crear x y z  x2 y2 z2  [segundos]
				if (args.length < 8) { s.sendMessage(Component.text("Uso: /tod plataforma crear <x y z de salida> <x y z de llegada> [segundos]", NamedTextColor.YELLOW)); return; }
				World w = s instanceof Player pl ? pl.getWorld() : Bukkit.getWorlds().get(0);
				Plataforma p = crear(new Location(w, Integer.parseInt(args[2]) + 1, Integer.parseInt(args[3]), Integer.parseInt(args[4]) + 1));
				p.bx = Integer.parseInt(args[5]); p.by = Integer.parseInt(args[6]); p.bz = Integer.parseInt(args[7]);
				if (args.length > 8) p.segundos = Double.parseDouble(args[8]);
				guardar(); reiniciar(p);
				s.sendMessage(Component.text("Plataforma " + p.id + " creada.", NamedTextColor.GREEN));
			}
			case "lista" -> {
				s.sendMessage(Component.text("Plataformas: " + plataformas.size(), NamedTextColor.YELLOW));
				for (Plataforma p : plataformas.values()) s.sendMessage(Component.text(" " + p.id + " · " + p.ancho + "x" + p.largo + " " + p.color + " · " + p.ax + " " + p.ay + " " + p.az + " -> " + p.bx + " " + p.by + " " + p.bz + " · " + p.segundos + " s · " + p.modo, NamedTextColor.GRAY));
			}
			case "borrar" -> {
				if (args.length < 3 || !plataformas.containsKey(args[2])) { s.sendMessage(Component.text("Uso: /tod plataforma borrar <id>", NamedTextColor.YELLOW)); return; }
				borrar(args[2]); s.sendMessage(Component.text("Plataforma borrada.", NamedTextColor.YELLOW));
			}
			default -> s.sendMessage(Component.text("Uso: /tod plataforma [crear | lista | borrar]  (o con la varita)", NamedTextColor.YELLOW));
		}
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "lista", "borrar");
		if (args.length == 3 && args[1].equalsIgnoreCase("borrar")) return new ArrayList<>(plataformas.keySet());
		return List.of();
	}
}
