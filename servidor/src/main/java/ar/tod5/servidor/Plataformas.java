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
import org.bukkit.entity.BlockDisplay;
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
 *  - "parpadeo" (lo normal): al llegar parpadean, desaparecen, y a los 0,75 s vuelven a aparecer en la salida
 *  - "achica": antes de llegar se van achicando hasta desaparecer (Minecraft no deja hacer bloques transparentes de a poco), y vuelven igual
 * Se ven con bloques de display (uno por bloque). Lo solido lo pone el mod: el server le manda a cada uno cual es el bloque "raiz" de cada
 * plataforma y su tamaño (canal tod5:plataformas), y el mod hace que se pueda pisar y que te lleve.
 * Como el server no sabe que estas parado en algo, no echa por "volar" a quien este arriba de una plataforma.
 */
final class Plataformas implements Listener {
	static final String CANAL = "tod5:plataformas";
	static final class Plataforma {
		String id, mundo, modo = "parpadeo", bloque = "minecraft:quartz_block";
		int ax, ay, az, bx, by, bz, ancho = 3, alto = 1, largo = 3;
		double segundos = 4;
		Location salida(World w) { return new Location(w, ax, ay, az); }
	}
	private static final class Viva {
		final List<BlockDisplay> bloques = new ArrayList<>();
		Interaction toque;
		int t;                // ticks desde que salio
		int fase;             // 0 = viajando, 1 = parpadeando, 2 = no esta (esperando)
		double x, y, z;       // donde esta ahora (la esquina)
	}
	private static final int PARPADEO = 10, ESPERA = 15;   // 0,5 s parpadeando, 0,75 s sin estar
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
					if (p != null && p.id != null) plataformas.put(p.id, p);
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
			if (v == null || v.fase == 2 || v.bloques.isEmpty() || !p.mundo.equals(pl.getWorld().getName())) continue;
			JsonObject j = new JsonObject();
			j.addProperty("id", p.id); j.addProperty("raiz", v.bloques.get(0).getUniqueId().toString());
			j.addProperty("ancho", p.ancho); j.addProperty("alto", p.alto); j.addProperty("largo", p.largo);
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
			if (v == null || v.fase == 2 || !p.mundo.equals(e.getPlayer().getWorld().getName())) continue;
			if (caja.overlaps(new BoundingBox(v.x, v.y, v.z, v.x + p.ancho, v.y + p.alto, v.z + p.largo))) { e.setCancelled(true); return; }
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
		if (v.toque != null) v.toque.remove();
	}
	void apagar() { for (String id : new ArrayList<>(vivas.keySet())) sacar(id); }
	/** Vuelve a empezar desde la salida (despues de cambiarle algo). */
	private void reiniciar(Plataforma p) { sacar(p.id); mandarTodos(); }

	private BlockData bloque(Plataforma p) {
		try { return Bukkit.createBlockData(p.bloque); } catch (IllegalArgumentException e) { return Material.QUARTZ_BLOCK.createBlockData(); }
	}
	private boolean alguienCerca(Plataforma p, World w) {
		Location l = p.salida(w), f = new Location(w, p.bx, p.by, p.bz);
		for (Player pl : w.getPlayers()) if (pl.getLocation().distanceSquared(l) < 64 * 64 || pl.getLocation().distanceSquared(f) < 64 * 64) return true;
		return false;
	}
	private int duracion(Plataforma p) { return Math.max(4, (int) Math.round(p.segundos * 20)); }

	private void aparecer(Plataforma p, Viva v, World w) {
		v.bloques.forEach(Entity::remove); v.bloques.clear();
		BlockData bd = bloque(p);
		for (int i = 0; i < p.ancho; i++) for (int j = 0; j < p.alto; j++) for (int k = 0; k < p.largo; k++) {
			final int fi = i, fj = j, fk = k;
			v.bloques.add(w.spawn(new Location(w, p.ax + i, p.ay + j, p.az + k), BlockDisplay.class, d -> {
				d.setBlock(bd); d.setPersistent(false); d.setTeleportDuration(1);
				d.getPersistentDataContainer().set(marca, PersistentDataType.STRING, p.id + ":" + fi + ":" + fj + ":" + fk);
			}));
		}
		v.t = 0; v.fase = 0; v.x = p.ax; v.y = p.ay; v.z = p.az;
		mandarTodos();
	}
	private void escala(Viva v, float s) {
		Transformation tr = new Transformation(new Vector3f((1 - s) / 2, (1 - s) / 2, (1 - s) / 2), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
		for (BlockDisplay d : v.bloques) { d.setInterpolationDelay(0); d.setInterpolationDuration(s == 0 || s == 1 ? 0 : 1); d.setTransformation(tr); }
	}

	private void cadaTick() {
		for (Plataforma p : plataformas.values()) {
			World w = Bukkit.getWorld(p.mundo);
			Viva v = vivas.get(p.id);
			if (w == null || !alguienCerca(p, w)) { if (v != null) { sacar(p.id); mandarTodos(); } continue; }
			if (v == null) { v = new Viva(); vivas.put(p.id, v); aparecer(p, v, w); }
			// el cuadro para configurarla (en la salida, solo lo usan los editores)
			if (v.toque == null || !v.toque.isValid()) {
				final String id = p.id;
				v.toque = w.spawn(new Location(w, p.ax + p.ancho / 2.0, p.ay, p.az + p.largo / 2.0), Interaction.class, i -> {
					i.setInteractionWidth(0.6f); i.setInteractionHeight(0.6f); i.setResponsive(false); i.setPersistent(false);
					i.getPersistentDataContainer().set(marca, PersistentDataType.STRING, id);
				});
			}
			int dur = duracion(p);
			v.t++;
			if (v.fase == 0) {
				double f = Math.min(1.0, v.t / (double) dur);
				v.x = p.ax + (p.bx - p.ax) * f; v.y = p.ay + (p.by - p.ay) * f; v.z = p.az + (p.bz - p.az) * f;
				for (int n = 0; n < v.bloques.size(); n++) {
					BlockDisplay d = v.bloques.get(n);
					int i = n / (p.alto * p.largo), j = (n / p.largo) % p.alto, k = n % p.largo;
					d.teleport(new Location(w, v.x + i, v.y + j, v.z + k));
				}
				if (p.modo.equals("achica") && f > 0.7) escala(v, (float) Math.max(0, (1 - f) / 0.3));
				if (v.t >= dur) {
					v.t = 0;
					if (p.modo.equals("achica")) { v.fase = 2; v.bloques.forEach(Entity::remove); v.bloques.clear(); mandarTodos(); }
					else v.fase = 1;
				}
			} else if (v.fase == 1) {
				escala(v, (v.t / 2) % 2 == 0 ? 0f : 1f);   // prende y apaga cada 2 ticks
				if (v.t >= PARPADEO) { v.t = 0; v.fase = 2; v.bloques.forEach(Entity::remove); v.bloques.clear(); mandarTodos(); }
			} else if (v.t >= ESPERA) {
				aparecer(p, v, w);
			}
		}
	}

	/** La salida de cada plataforma, marcada con particulas (las aristas de la caja). */
	private void marcarSalidas() {
		for (Plataforma p : plataformas.values()) {
			World w = Bukkit.getWorld(p.mundo);
			if (w == null) continue;
			Particle.DustOptions polvo = new Particle.DustOptions(Color.fromRGB(120, 230, 255), 0.7f);
			double x0 = p.ax, y0 = p.ay, z0 = p.az, x1 = p.ax + p.ancho, y1 = p.ay + p.alto, z1 = p.az + p.largo;
			List<double[]> pts = new ArrayList<>();
			for (double x = x0; x <= x1 + 1e-6; x += 0.5) for (double[] yz : new double[][] { { y0, z0 }, { y0, z1 }, { y1, z0 }, { y1, z1 } }) pts.add(new double[] { x, yz[0], yz[1] });
			for (double z = z0; z <= z1 + 1e-6; z += 0.5) for (double[] xy : new double[][] { { x0, y0 }, { x1, y0 }, { x0, y1 }, { x1, y1 } }) pts.add(new double[] { xy[0], xy[1], z });
			for (double y = y0; y <= y1 + 1e-6; y += 0.5) for (double[] xz : new double[][] { { x0, z0 }, { x1, z0 }, { x0, z1 }, { x1, z1 } }) pts.add(new double[] { xz[0], y, xz[1] });
			for (Player pl : w.getPlayers()) {
				if (pl.getLocation().distanceSquared(new Location(w, x0, y0, z0)) > 48 * 48) continue;
				for (double[] q : pts) pl.spawnParticle(Particle.DUST, q[0], q[1], q[2], 1, 0, 0, 0, 0, polvo);
				if (poniendoFinal.containsValue(p.id) || plugin.tablero().esEditor(pl) && plugin.varita.es(pl.getInventory().getItemInMainHand())) {
					Particle.DustOptions fin = new Particle.DustOptions(Color.fromRGB(255, 160, 40), 0.9f);
					pl.spawnParticle(Particle.DUST, p.bx + p.ancho / 2.0, p.by + p.alto + 0.3, p.bz + p.largo / 2.0, 4, p.ancho / 3.0, 0.1, p.largo / 3.0, 0, fin);
				}
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

	private static final int ANCHO = 10, LARGO = 11, ALTO = 12, BLOQUE = 14, VEL = 15, MODO = 16, FINAL = 20, REINICIAR = 22, BORRAR = 24;
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
		m.inv.setItem(ALTO, cantidad(Varita.boton(Material.BIRCH_PLANKS, "Alto: " + p.alto, NamedTextColor.AQUA, ayuda), p.alto));
		Material mb = Bukkit.createBlockData(p.bloque).getMaterial();
		m.inv.setItem(BLOQUE, Varita.boton(mb.isItem() ? mb : Material.QUARTZ_BLOCK, "Bloque: " + p.bloque.replace("minecraft:", ""), NamedTextColor.WHITE,
			"Clic con un bloque agarrado (en el cursor): usar ese"));
		m.inv.setItem(VEL, Varita.boton(Material.SUGAR, "Tarda " + p.segundos + " s en llegar", NamedTextColor.YELLOW,
			"Clic izquierdo: +0,5 s (más lenta)   Clic derecho: -0,5 s (más rápida)", "Shift + clic: de a 0,1 s"));
		m.inv.setItem(MODO, Varita.boton(p.modo.equals("parpadeo") ? Material.REDSTONE_LAMP : Material.GLASS, "Al llegar: " + (p.modo.equals("parpadeo") ? "parpadea y desaparece" : "se va achicando antes de llegar"), NamedTextColor.LIGHT_PURPLE,
			"Clic: cambiar", "Después de 0,75 s vuelve a salir."));
		m.inv.setItem(FINAL, Varita.boton(Material.TARGET, "Poner el final", NamedTextColor.GOLD,
			"Cerrá el menú y hacé clic derecho en un bloque con la varita:", "ahí llega la plataforma (los puntos naranjas).", "Ahora: " + p.bx + " " + p.by + " " + p.bz));
		m.inv.setItem(REINICIAR, Varita.boton(Material.CLOCK, "Volver a empezar", NamedTextColor.GREEN, "Sale de nuevo desde la salida."));
		m.inv.setItem(BORRAR, Varita.boton(Material.BARRIER, m.confirmarBorrar ? "¿Seguro? Clic de nuevo para borrar" : "Borrar", NamedTextColor.RED));
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
			case ANCHO -> p.ancho = limitar(p.ancho + d, p.alto, p.largo, 0);
			case LARGO -> p.largo = limitar(p.largo + d, p.ancho, p.alto, 1);
			case ALTO -> p.alto = limitar(p.alto + d, p.ancho, p.largo, 2);
			case BLOQUE -> {
				ItemStack c = e.getCursor();
				if (c == null || !c.getType().isBlock() || c.getType().isAir()) { pl.sendMessage(Component.text("Agarrá un bloque con el mouse y hacé clic acá.", NamedTextColor.YELLOW)); return; }
				p.bloque = c.getType().createBlockData().getAsString();
			}
			case VEL -> p.segundos = Math.max(0.2, Math.min(120, Math.round((p.segundos + d * (e.isShiftClick() ? 0.1 : 0.5)) * 10) / 10.0));
			case MODO -> p.modo = p.modo.equals("parpadeo") ? "achica" : "parpadeo";
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
	private static int limitar(int n, int o1, int o2, int cual) { n = Math.max(1, Math.min(16, n)); while (n > 1 && n * o1 * o2 > 64) n--; return n; }
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
				for (Plataforma p : plataformas.values()) s.sendMessage(Component.text(" " + p.id + " · " + p.ancho + "x" + p.alto + "x" + p.largo + " · " + p.ax + " " + p.ay + " " + p.az + " -> " + p.bx + " " + p.by + " " + p.bz + " · " + p.segundos + " s · " + p.modo, NamedTextColor.GRAY));
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
