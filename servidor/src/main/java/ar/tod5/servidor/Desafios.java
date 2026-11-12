package ar.tod5.servidor;

import com.google.gson.Gson;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Zonas de monedas (como las monedas rojas / moradas de Mario Galaxy).
 *
 * Una zona es un lugar (centro + radio) con monedas puestas. No se "empieza" nada:
 *  - Entras al radio: aparecen las monedas (solo para vos), suena "monedas_aparecen" y sale el contador a la izquierda.
 *  - Cada jugador junta las suyas (la que agarras desaparece solo para vos).
 *  - Hasta 10 monedas: cada una suena (moneda_roja) un poco mas aguda; con la ultima suena monedas_completo.
 *  - Mas de 10: todas suenan igual (moneda_morada) y al terminar no suena nada.
 *  - Si suena una moneda y agarras otra, la anterior se corta.
 *  - Si salis de la zona sin terminar, se reinicia (la proxima vez arrancas de cero).
 *  - Las monedas no brillan ni se ven a traves de las paredes.
 *
 * Armar zonas (editores):
 *   /tod zona crear <nombre> [radio]   el centro es donde estas parado (radio por defecto 24)
 *   /tod zona editar <nombre>          te da la varita de esa zona:
 *        clic derecho en un bloque = pone una moneda arriba de ese bloque
 *        clic izquierdo mirando una moneda = la saca
 *        mientras la tenes en la mano ves todas las monedas de esa zona
 *   /tod zona radio <nombre> <radio> · lista · borrar <nombre>
 * Archivo: plugins/TOD5/datos/zonas.json
 */
final class Desafios implements Listener {
	static final String CANAL = "tod5:desafio";
	/** Hasta 10 monedas: semitonos de cada moneda (la ultima es la mas aguda). */
	private static final int[] ESCALA = { -5, -3, -1, 0, 2, 4, 5, 7, 9, 11 };
	private static final int PERIODO = 5;          // ticks entre chequeos
	private static final float TAM = 0.75f;        // tamaño de la moneda

	private static final class Zona {
		String nombre; String mundo; double x, y, z; double radio = 24;
		final List<double[]> monedas = new ArrayList<>();   // x, y, z de cada moneda
		Location centro() { World w = Bukkit.getWorld(mundo); return w == null ? null : new Location(w, x, y, z); }
	}
	private static final class Estado {
		Zona zona; int juntadas; boolean terminada;
		final Map<Integer, ItemDisplay> visibles = new HashMap<>();   // indice de moneda -> entidad (solo para este jugador)
	}

	private final TOD5Servidor plugin;
	private final NamespacedKey marcaVarita;
	private final Map<String, Zona> zonas = new LinkedHashMap<>();
	private final Map<UUID, Estado> estados = new HashMap<>();
	private final Map<UUID, Map<Integer, ItemDisplay>> editando = new HashMap<>();   // lo que ve el editor con la varita
	private final Map<UUID, String> zonaEditando = new HashMap<>();
	private BukkitTask tarea;
	private long tick;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	Desafios(TOD5Servidor plugin) { this.plugin = plugin; this.marcaVarita = new NamespacedKey(plugin, "varita_zona"); }

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "zonas.json"); }
	void cargar() {
		zonas.clear();
		File f = archivo();
		if (f.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String n : o.keySet()) {
				JsonObject j = o.getAsJsonObject(n);
				Zona z = new Zona(); z.nombre = n; z.mundo = j.get("mundo").getAsString();
				z.x = j.get("x").getAsDouble(); z.y = j.get("y").getAsDouble(); z.z = j.get("z").getAsDouble(); z.radio = j.get("radio").getAsDouble();
				for (JsonElement e : j.getAsJsonArray("monedas")) { JsonArray a = e.getAsJsonArray(); z.monedas.add(new double[] { a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble() }); }
				zonas.put(n, z);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer zonas.json: " + e.getMessage()); }
		if (tarea == null) tarea = Bukkit.getScheduler().runTaskTimer(plugin, this::cadaRato, PERIODO, PERIODO);
	}
	private void guardar() {
		JsonObject o = new JsonObject();
		zonas.forEach((n, z) -> {
			JsonObject j = new JsonObject();
			j.addProperty("mundo", z.mundo); j.addProperty("x", z.x); j.addProperty("y", z.y); j.addProperty("z", z.z); j.addProperty("radio", z.radio);
			JsonArray a = new JsonArray();
			for (double[] m : z.monedas) { JsonArray p = new JsonArray(); p.add(m[0]); p.add(m[1]); p.add(m[2]); a.add(p); }
			j.add("monedas", a); o.add(n, j);
		});
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar zonas.json: " + e.getMessage()); }
	}

	// ================= la moneda =================
	private static ItemStack monedaItem() {
		ItemStack it = new ItemStack(Material.GOLD_NUGGET);
		ItemMeta m = it.getItemMeta(); m.setItemModel(new NamespacedKey("tod5", "moneda_desafio")); it.setItemMeta(m);
		return it;
	}
	/** Una moneda que ve solo este jugador. */
	private ItemDisplay aparecer(Player p, World w, double[] m) {
		Location l = new Location(w, m[0], m[1], m[2]);
		ItemDisplay d = w.spawn(l, ItemDisplay.class, e -> {
			e.setVisibleByDefault(false);
			e.setPersistent(false);
			e.setItemStack(monedaItem());
			e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
			e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(TAM, TAM, TAM), new AxisAngle4f()));
			e.setBillboard(Display.Billboard.FIXED);
			e.setTeleportDuration(PERIODO);
		});
		p.showEntity(plugin, d);
		return d;
	}

	// ================= cada rato: entrar/salir de zonas, juntar, girar =================
	private void cadaRato() {
		tick += PERIODO;
		float giro = (tick / PERIODO % 4) * 90f;   // gira 90 grados cada vez, con interpolacion (se ve suave)
		for (Player p : Bukkit.getOnlinePlayers()) {
			Estado st = estados.get(p.getUniqueId());
			Zona dentro = zonaDe(p.getLocation());
			if (st != null && st.zona != dentro) { salir(p, st); st = null; }
			if (st == null && dentro != null && !dentro.monedas.isEmpty()) st = entrar(p, dentro);
			if (st != null) juntar(p, st);
			if (st != null) for (ItemDisplay d : st.visibles.values()) girar(d, giro);
			Map<Integer, ItemDisplay> ed = editando.get(p.getUniqueId());
			if (ed != null) {
				if (!tieneVarita(p)) { ocultarEdicion(p); continue; }
				for (ItemDisplay d : ed.values()) girar(d, giro);
			}
		}
	}
	private static void girar(ItemDisplay d, float yaw) {
		if (!d.isValid()) return;
		Location l = d.getLocation(); l.setYaw(yaw); l.setPitch(0);
		d.teleport(l);   // con setTeleportDuration el giro se interpola (se ve suave)
	}

	private Zona zonaDe(Location l) {
		Zona mejor = null; double dmin = Double.MAX_VALUE;
		for (Zona z : zonas.values()) {
			if (!l.getWorld().getName().equals(z.mundo)) continue;
			double d = Math.pow(l.getX() - z.x, 2) + Math.pow(l.getZ() - z.z, 2);
			if (d <= z.radio * z.radio && Math.abs(l.getY() - z.y) <= z.radio && d < dmin) { dmin = d; mejor = z; }
		}
		return mejor;
	}
	private Estado entrar(Player p, Zona z) {
		Estado st = new Estado(); st.zona = z;
		World w = Bukkit.getWorld(z.mundo);
		for (int i = 0; i < z.monedas.size(); i++) st.visibles.put(i, aparecer(p, w, z.monedas.get(i)));
		estados.put(p.getUniqueId(), st);
		p.playSound(p.getLocation(), "tod5:monedas_aparecen", SoundCategory.MASTER, 1f, 1f);
		mandar(p, "0;" + z.monedas.size());
		return st;
	}
	private void salir(Player p, Estado st) {
		st.visibles.values().forEach(d -> { if (d.isValid()) d.remove(); });
		estados.remove(p.getUniqueId());
		mandar(p, "nada");   // si no termino, se reinicia: la proxima vez arranca de cero
	}
	private void juntar(Player p, Estado st) {
		if (st.terminada) return;
		Location cuerpo = p.getLocation().add(0, 0.9, 0);
		int total = st.zona.monedas.size();
		for (Iterator<Map.Entry<Integer, ItemDisplay>> it = st.visibles.entrySet().iterator(); it.hasNext(); ) {
			ItemDisplay d = it.next().getValue();
			if (!d.isValid() || d.getLocation().distanceSquared(cuerpo) > 1.4 * 1.4) continue;
			d.remove(); it.remove(); st.juntadas++;
			// la anterior se corta y suena la nueva
			p.stopSound("tod5:moneda_roja", SoundCategory.MASTER);
			p.stopSound("tod5:moneda_morada", SoundCategory.MASTER);
			if (total <= ESCALA.length) {
				float tono = (float) Math.pow(2, ESCALA[ESCALA.length - total + st.juntadas - 1] / 12.0);
				p.playSound(p.getLocation(), "tod5:moneda_roja", SoundCategory.MASTER, 1f, tono);
				if (st.juntadas == total) p.playSound(p.getLocation(), "tod5:monedas_completo", SoundCategory.MASTER, 1f, 1f);
			} else {
				p.playSound(p.getLocation(), "tod5:moneda_morada", SoundCategory.MASTER, 1f, 1f);
			}
			mandar(p, (st.juntadas == total ? "fin;" : "") + (st.juntadas == total ? total : st.juntadas + ";" + total));
			if (st.juntadas == total) { st.terminada = true; break; }
		}
	}

	private void mandar(Player p, String s) {
		if (plugin.tieneElMod(p)) p.sendPluginMessage(plugin, CANAL, s.getBytes(StandardCharsets.UTF_8));
	}

	// ================= la varita =================
	private ItemStack varita(String zona) {
		ItemStack it = new ItemStack(Material.STICK);   // un palo comun (la vara de blaze daba el logro "Into Fire")
		ItemMeta m = it.getItemMeta();
		m.itemName(Component.text("Varita de monedas: " + zona, NamedTextColor.GREEN));
		m.lore(List.of(
			Component.text("Clic derecho en un bloque: pone una moneda", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Clic izquierdo mirando una moneda: la saca", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
		m.getPersistentDataContainer().set(marcaVarita, PersistentDataType.STRING, zona);
		it.setItemMeta(m);
		return it;
	}
	private String zonaDeVarita(ItemStack it) {
		if (it == null || !it.hasItemMeta()) return null;
		return it.getItemMeta().getPersistentDataContainer().get(marcaVarita, PersistentDataType.STRING);
	}
	private boolean tieneVarita(Player p) { return zonaDeVarita(p.getInventory().getItemInMainHand()) != null; }

	private void mostrarEdicion(Player p, Zona z) {
		ocultarEdicion(p);
		Map<Integer, ItemDisplay> m = new HashMap<>();
		World w = Bukkit.getWorld(z.mundo);
		for (int i = 0; i < z.monedas.size(); i++) m.put(i, aparecer(p, w, z.monedas.get(i)));
		editando.put(p.getUniqueId(), m);
		zonaEditando.put(p.getUniqueId(), z.nombre);
	}
	private void ocultarEdicion(Player p) {
		Map<Integer, ItemDisplay> m = editando.remove(p.getUniqueId());
		zonaEditando.remove(p.getUniqueId());
		if (m != null) m.values().forEach(d -> { if (d.isValid()) d.remove(); });
	}

	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND) return;
		String nombre = zonaDeVarita(e.getItem());
		if (nombre == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		Zona z = zonas.get(nombre);
		if (z == null || !plugin.tablero().esEditor(p)) { p.sendActionBar(Component.text("Esa zona no existe (o no sos editor).", NamedTextColor.RED)); return; }
		if (!nombre.equals(zonaEditando.get(p.getUniqueId()))) mostrarEdicion(p, z);
		if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
			Location l = e.getClickedBlock().getLocation().add(0.5, 1.6, 0.5);
			if (!l.getWorld().getName().equals(z.mundo)) { p.sendActionBar(Component.text("Esa zona es de otro mundo.", NamedTextColor.RED)); return; }
			z.monedas.add(new double[] { l.getX(), l.getY(), l.getZ() }); guardar();
			mostrarEdicion(p, z);
			p.sendActionBar(Component.text("Moneda puesta (" + z.monedas.size() + " en \"" + nombre + "\")", NamedTextColor.GREEN));
			p.playSound(p.getLocation(), "tod5:moneda_morada", SoundCategory.MASTER, 0.6f, 1.2f);
		} else if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
			int i = monedaMirada(p, z);
			if (i < 0) { p.sendActionBar(Component.text("Mirá una moneda para sacarla.", NamedTextColor.GRAY)); return; }
			z.monedas.remove(i); guardar();
			mostrarEdicion(p, z);
			p.sendActionBar(Component.text("Moneda sacada (quedan " + z.monedas.size() + ")", NamedTextColor.YELLOW));
		}
	}
	/** La moneda que esta mirando (a menos de 8 bloques), o -1. */
	private int monedaMirada(Player p, Zona z) {
		Location ojo = p.getEyeLocation(); Vector dir = ojo.getDirection();
		int mejor = -1; double mejorT = 9;
		for (int i = 0; i < z.monedas.size(); i++) {
			double[] m = z.monedas.get(i);
			Vector a = new Vector(m[0] - ojo.getX(), m[1] - ojo.getY(), m[2] - ojo.getZ());
			double t = a.dot(dir);
			if (t < 0 || t > 8) continue;
			if (a.clone().subtract(dir.clone().multiply(t)).lengthSquared() < 0.5 * 0.5 && t < mejorT) { mejorT = t; mejor = i; }
		}
		return mejor;
	}

	@EventHandler
	public void alSalirDelServer(PlayerQuitEvent e) {
		Estado st = estados.remove(e.getPlayer().getUniqueId());
		if (st != null) st.visibles.values().forEach(d -> { if (d.isValid()) d.remove(); });
		ocultarEdicion(e.getPlayer());
	}
	void apagar() {
		for (Estado st : estados.values()) st.visibles.values().forEach(d -> { if (d.isValid()) d.remove(); });
		estados.clear();
		for (UUID u : new ArrayList<>(editando.keySet())) { Player p = Bukkit.getPlayer(u); if (p != null) ocultarEdicion(p); }
	}

	// ================= comando =================
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		String nombre = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
		Player yo = s instanceof Player pl ? pl : null;
		switch (sub) {
			case "crear" -> {
				Player quien = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : yo;   // crear <nombre> [radio] [jugador]
				if (nombre == null || quien == null) { uso(s); return; }
				Player yo2 = quien;
				Zona z = zonas.getOrDefault(nombre, new Zona());
				z.nombre = nombre; z.mundo = yo2.getWorld().getName();
				z.x = yo2.getLocation().getX(); z.y = yo2.getLocation().getY(); z.z = yo2.getLocation().getZ();
				if (args.length > 3) try { z.radio = Double.parseDouble(args[3]); } catch (NumberFormatException ex) { uso(s); return; }
				zonas.put(nombre, z); guardar();
				yo2.getInventory().addItem(varita(nombre));
				s.sendMessage(Component.text("Zona \"" + nombre + "\" creada (radio " + (int) z.radio + "). Te di la varita para poner las monedas.", NamedTextColor.GREEN));
			}
			case "editar" -> {
				if (nombre == null || yo == null || !zonas.containsKey(nombre)) { uso(s); return; }
				yo.getInventory().addItem(varita(nombre));
				s.sendMessage(Component.text("Ahí tenés la varita de \"" + nombre + "\".", NamedTextColor.GREEN));
			}
			case "radio" -> {
				if (nombre == null || !zonas.containsKey(nombre) || args.length < 4) { uso(s); return; }
				try { zonas.get(nombre).radio = Double.parseDouble(args[3]); } catch (NumberFormatException ex) { uso(s); return; }
				guardar(); s.sendMessage(Component.text("Radio de \"" + nombre + "\": " + args[3], NamedTextColor.GREEN));
			}
			case "lista" -> {
				if (zonas.isEmpty()) s.sendMessage(Component.text("No hay zonas de monedas.", NamedTextColor.GRAY));
				zonas.forEach((n, z) -> s.sendMessage(Component.text(" " + n + " · " + z.monedas.size() + " monedas · radio " + (int) z.radio + " · " + z.mundo + " " + (int) z.x + " " + (int) z.y + " " + (int) z.z, NamedTextColor.GRAY)));
			}
			case "borrar" -> {
				if (nombre == null || zonas.remove(nombre) == null) { uso(s); return; }
				guardar(); s.sendMessage(Component.text("Zona \"" + nombre + "\" borrada.", NamedTextColor.YELLOW));
			}
			// solo para la prueba automatica: pone una moneda corrida desde un jugador
			case "moneda" -> {
				Player donde = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : yo;
				Zona z = nombre == null ? null : zonas.get(nombre);
				if (z == null || donde == null) { uso(s); return; }
				Location l = donde.getLocation().toBlockLocation().add(0.5, 0.6, 0.5);
				if (args.length > 6) l.add(Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
				z.monedas.add(new double[] { l.getX(), l.getY(), l.getZ() }); guardar();
				s.sendMessage(Component.text("Moneda puesta en \"" + nombre + "\" (" + z.monedas.size() + ").", NamedTextColor.GREEN));
			}
			default -> uso(s);
		}
	}
	private static void uso(CommandSender s) {
		s.sendMessage(Component.text("/tod zona crear <nombre> [radio] · editar <nombre> · radio <nombre> <radio> · lista · borrar <nombre>", NamedTextColor.YELLOW));
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "editar", "radio", "lista", "borrar");
		if (args.length == 3 && !args[1].equalsIgnoreCase("crear")) return new ArrayList<>(zonas.keySet());
		return List.of();
	}
}
