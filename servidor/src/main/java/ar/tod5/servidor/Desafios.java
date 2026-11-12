package ar.tod5.servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Zonas de monedas (como las monedas rojas / moradas de Mario Galaxy).
 *
 * El area se arma sola: un rectangulo que abarca todas las monedas, mas un margen (por defecto 4 bloques),
 * asi las monedas aparecen un par de bloques antes de llegar, esten donde esten.
 *
 *  - Cada jugador tiene su propio progreso (lo que junta uno no cambia lo del otro).
 *  - Si salis de la zona sin terminar, NO se pierde: al volver estan solo las que te faltan.
 *  - Todas las monedas suenan igual (hasta 10: moneda_roja; mas de 10: moneda_morada).
 *    Con la ultima suena solo monedas_completo. Si agarras varias juntas suena una sola vez.
 *  - Modo (cuando se puede repetir):  una = una vez por jugador · diaria = una vez por dia · siempre = cada vez que se activa.
 *  - Activacion:  cerca = al acercarte · bloque = tocando un bloque marcado · comando = /tod zona activar.
 *  - Recompensa: hasta 27 items que va a la bandeja de Recompensas del jugador (no al inventario).
 *
 * Armar zonas (editores):
 *   /tod zona crear <nombre> [margen]       te da la varita:
 *        clic derecho en un bloque = pone una moneda en el bloque de arriba (flotando en el medio)
 *        clic izquierdo mirando una moneda = la saca
 *        shift + clic derecho en un bloque = ese bloque activa la zona (activacion "bloque")
 *   /tod zona editar <nombre> · margen <nombre> <bloques> · modo <nombre> <una|diaria|siempre>
 *   /tod zona activacion <nombre> <cerca|bloque|comando> · recompensa <nombre> (abre el cofre para cargarla)
 *   /tod zona activar <nombre> [jugador] · reiniciar <nombre> [jugador] · lista · borrar <nombre>
 * Archivos: plugins/TOD5/datos/zonas.json (las zonas) y zonas_progreso.json (lo de cada jugador)
 */
final class Desafios implements Listener {
	static final String CANAL = "tod5:desafio";
	private static final int GIRO = 5;              // ticks entre cada cuarto de vuelta
	private static final float TAM = 0.375f;        // tamaño de la moneda (la mitad de antes)
	private static final double AGARRE = 0.35;      // cuanto se agranda tu cuerpo para agarrarlas
	private static final ZoneId HORA = ZoneId.of("America/Argentina/Buenos_Aires");
	static final List<String> MODOS = List.of("una", "diaria", "siempre"), ACTIVACIONES = List.of("cerca", "bloque", "comando");

	private static final class Zona {
		String nombre; String mundo; double margen = 4;
		String modo = "una", activacion = "cerca";
		int[] bloque;                                         // el bloque que la activa (activacion "bloque")
		final List<double[]> monedas = new ArrayList<>();     // x, y, z de cada moneda
		final ItemStack[] recompensa = new ItemStack[Recompensas.CASILLAS];
		BoundingBox area() {
			if (monedas.isEmpty()) return null;
			double[] a = monedas.get(0);
			BoundingBox b = new BoundingBox(a[0], a[1], a[2], a[0], a[1], a[2]);
			for (double[] m : monedas) b.union(m[0], m[1], m[2]);
			return b.expand(margen, Math.max(3, margen), margen);
		}
		boolean tieneRecompensa() { return Arrays.stream(recompensa).anyMatch(i -> i != null && !i.getType().isAir()); }
	}
	/** Lo de un jugador en una zona (se guarda). */
	private static final class Progreso {
		final Set<Integer> juntadas = new HashSet<>();
		boolean completa, activa; long dia;
	}
	/** Lo que esta viendo un jugador ahora (no se guarda). */
	private static final class Estado {
		Zona zona; Progreso pr;
		final Map<Integer, ItemDisplay> visibles = new HashMap<>();
	}

	private final TOD5Servidor plugin;
	private final NamespacedKey marcaVarita;
	private final Map<String, Zona> zonas = new LinkedHashMap<>();
	private final Map<String, Map<UUID, Progreso>> progreso = new HashMap<>();
	private final Map<UUID, Estado> estados = new HashMap<>();
	private final Map<UUID, Map<Integer, ItemDisplay>> editando = new HashMap<>();
	private final Map<UUID, String> zonaEditando = new HashMap<>();
	private BukkitTask tarea;
	private long tick;
	private boolean sucio;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	Desafios(TOD5Servidor plugin) { this.plugin = plugin; this.marcaVarita = new NamespacedKey(plugin, "varita_zona"); }

	private static long hoy() { return LocalDate.now(HORA).toEpochDay(); }
	private static String b64(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

	// ================= archivos =================
	private File archivo(String n) { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, n); }
	void cargar() {
		zonas.clear(); progreso.clear();
		File f = archivo("zonas.json");
		if (f.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String n : o.keySet()) {
				JsonObject j = o.getAsJsonObject(n);
				Zona z = new Zona(); z.nombre = n; z.mundo = j.get("mundo").getAsString();
				if (j.has("margen")) z.margen = j.get("margen").getAsDouble();
				if (j.has("modo")) z.modo = j.get("modo").getAsString();
				if (j.has("activacion")) z.activacion = j.get("activacion").getAsString();
				if (j.has("bloque")) { JsonArray b = j.getAsJsonArray("bloque"); z.bloque = new int[] { b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt() }; }
				for (JsonElement e : j.getAsJsonArray("monedas")) { JsonArray a = e.getAsJsonArray(); z.monedas.add(new double[] { a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble() }); }
				if (j.has("recompensa")) { JsonArray r = j.getAsJsonArray("recompensa");
					for (int i = 0; i < r.size() && i < z.recompensa.length; i++) if (!r.get(i).isJsonNull()) z.recompensa[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(r.get(i).getAsString())); }
				zonas.put(n, z);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer zonas.json: " + e.getMessage()); }
		File fp = archivo("zonas_progreso.json");
		if (fp.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(fp.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String zn : o.keySet()) {
				Map<UUID, Progreso> m = new HashMap<>();
				JsonObject jz = o.getAsJsonObject(zn);
				for (String u : jz.keySet()) {
					JsonObject j = jz.getAsJsonObject(u); Progreso p = new Progreso();
					for (JsonElement e : j.getAsJsonArray("juntadas")) p.juntadas.add(e.getAsInt());
					p.completa = j.has("completa") && j.get("completa").getAsBoolean();
					p.activa = j.has("activa") && j.get("activa").getAsBoolean();
					p.dia = j.has("dia") ? j.get("dia").getAsLong() : 0;
					m.put(UUID.fromString(u), p);
				}
				progreso.put(zn, m);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer zonas_progreso.json: " + e.getMessage()); }
		if (tarea == null) tarea = Bukkit.getScheduler().runTaskTimer(plugin, this::cadaTick, 1, 1);
	}
	private void guardar() {
		JsonObject o = new JsonObject();
		zonas.forEach((n, z) -> {
			JsonObject j = new JsonObject();
			j.addProperty("mundo", z.mundo); j.addProperty("margen", z.margen); j.addProperty("modo", z.modo); j.addProperty("activacion", z.activacion);
			if (z.bloque != null) { JsonArray b = new JsonArray(); for (int v : z.bloque) b.add(v); j.add("bloque", b); }
			JsonArray a = new JsonArray();
			for (double[] m : z.monedas) { JsonArray p = new JsonArray(); p.add(m[0]); p.add(m[1]); p.add(m[2]); a.add(p); }
			j.add("monedas", a);
			JsonArray r = new JsonArray();
			for (ItemStack it : z.recompensa) if (it == null || it.getType().isAir()) r.add(JsonNull.INSTANCE); else r.add(b64(it));
			j.add("recompensa", r);
			o.add(n, j);
		});
		try { Files.writeString(archivo("zonas.json").toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar zonas.json: " + e.getMessage()); }
	}
	private void guardarProgreso() {
		sucio = false;
		JsonObject o = new JsonObject();
		progreso.forEach((zn, m) -> {
			JsonObject jz = new JsonObject();
			m.forEach((u, p) -> {
				JsonObject j = new JsonObject();
				JsonArray a = new JsonArray(); p.juntadas.stream().sorted().forEach(a::add); j.add("juntadas", a);
				j.addProperty("completa", p.completa); j.addProperty("activa", p.activa); j.addProperty("dia", p.dia);
				jz.add(u.toString(), j);
			});
			o.add(zn, jz);
		});
		try { Files.writeString(archivo("zonas_progreso.json").toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar zonas_progreso.json: " + e.getMessage()); }
	}

	private Progreso progreso(Zona z, UUID u) {
		Progreso p = progreso.computeIfAbsent(z.nombre, k -> new HashMap<>()).computeIfAbsent(u, k -> new Progreso());
		// diaria: al cambiar el dia se puede hacer de nuevo
		if (z.modo.equals("diaria") && p.dia != hoy() && (p.completa || !p.juntadas.isEmpty())) { reiniciar(p); sucio = true; }
		return p;
	}
	private static void reiniciar(Progreso p) { p.juntadas.clear(); p.completa = false; p.activa = false; p.dia = 0; }

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
			e.setTeleportDuration(GIRO);
		});
		p.showEntity(plugin, d);
		return d;
	}

	// ================= cada tick: entrar/salir, juntar; cada 5 ticks: girar =================
	private void cadaTick() {
		tick++;
		boolean girar = tick % GIRO == 0;
		float giro = (tick / GIRO % 4) * 90f;
		for (Player p : Bukkit.getOnlinePlayers()) {
			Estado st = estados.get(p.getUniqueId());
			Zona dentro = zonaDe(p.getLocation());
			if (st != null && st.zona != dentro) { ocultar(p, st); st = null; }
			if (st == null && dentro != null && puedeVer(p, dentro)) st = mostrar(p, dentro);
			if (st != null) juntar(p, st);
			if (girar && st != null) for (ItemDisplay d : st.visibles.values()) girar(d, giro);
			Map<Integer, ItemDisplay> ed = editando.get(p.getUniqueId());
			if (ed != null) {
				if (!tieneVarita(p)) { ocultarEdicion(p); continue; }
				if (girar) for (ItemDisplay d : ed.values()) girar(d, giro);
			}
		}
		if (sucio && tick % 100 == 0) guardarProgreso();
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
			BoundingBox b = z.area();
			if (b == null || !b.contains(l.toVector())) continue;
			double d = b.getCenter().distanceSquared(l.toVector());
			if (d < dmin) { dmin = d; mejor = z; }
		}
		return mejor;
	}
	/** ¿Le aparecen las monedas al entrar? */
	private boolean puedeVer(Player p, Zona z) {
		Progreso pr = progreso(z, p.getUniqueId());
		if (pr.completa) {
			if (z.modo.equals("siempre") && z.activacion.equals("cerca")) { reiniciar(pr); sucio = true; }   // otra vuelta
			else return false;
		}
		return z.activacion.equals("cerca") || pr.activa;
	}
	private Estado mostrar(Player p, Zona z) {
		Estado st = new Estado(); st.zona = z; st.pr = progreso(z, p.getUniqueId());
		World w = Bukkit.getWorld(z.mundo);
		for (int i = 0; i < z.monedas.size(); i++) if (!st.pr.juntadas.contains(i)) st.visibles.put(i, aparecer(p, w, z.monedas.get(i)));
		estados.put(p.getUniqueId(), st);
		p.playSound(p.getLocation(), "tod5:monedas_aparecen", SoundCategory.MASTER, 1f, 1f);
		mandar(p, Math.min(st.pr.juntadas.size(), z.monedas.size()) + ";" + z.monedas.size());
		return st;
	}
	private void ocultar(Player p, Estado st) {
		st.visibles.values().forEach(d -> { if (d.isValid()) d.remove(); });
		estados.remove(p.getUniqueId());
		mandar(p, "nada");   // el progreso queda guardado
	}
	private void juntar(Player p, Estado st) {
		if (st.pr.completa) return;
		BoundingBox cuerpo = p.getBoundingBox().expand(AGARRE);
		int total = st.zona.monedas.size(), nuevas = 0;
		for (Iterator<Map.Entry<Integer, ItemDisplay>> it = st.visibles.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, ItemDisplay> e = it.next();
			double[] m = st.zona.monedas.get(e.getKey());
			if (!cuerpo.contains(m[0], m[1], m[2])) continue;
			if (e.getValue().isValid()) e.getValue().remove();
			it.remove(); st.pr.juntadas.add(e.getKey()); nuevas++;
		}
		if (nuevas == 0) return;
		sucio = true;
		int juntadas = st.pr.juntadas.size();
		if (juntadas >= total) { completar(p, st); return; }
		// un solo sonido aunque agarres varias juntas; siempre el mismo tono
		p.playSound(p.getLocation(), total <= 10 ? "tod5:moneda_roja" : "tod5:moneda_morada", SoundCategory.MASTER, 1f, 1f);
		mandar(p, juntadas + ";" + total);
	}
	private void completar(Player p, Estado st) {
		Zona z = st.zona; Progreso pr = st.pr;
		pr.completa = true; pr.activa = false; pr.dia = hoy(); sucio = true;
		p.playSound(p.getLocation(), "tod5:monedas_completo", SoundCategory.MASTER, 1f, 1f);   // con la ultima, solo este
		p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 25, 0.6, 0.7, 0.6, 0);
		p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 18, 0.3, 0.5, 0.3, 0.06);
		mandar(p, "fin;" + z.monedas.size());
		if (z.tieneRecompensa()) plugin.recompensas.dar(p.getUniqueId(), "Zona " + z.nombre, Arrays.asList(z.recompensa));
		plugin.getLogger().info(p.getName() + " completo la zona " + z.nombre);
		guardarProgreso();
	}

	/** Activa la zona para un jugador (por bloque o comando). En modo "siempre" arranca una vuelta nueva. */
	private void activar(Player p, Zona z) {
		Progreso pr = progreso(z, p.getUniqueId());
		if (pr.completa && !z.modo.equals("siempre")) {
			p.sendActionBar(Component.text(z.modo.equals("diaria") ? "Ya completaste esta zona hoy." : "Ya completaste esta zona.", NamedTextColor.GRAY));
			return;
		}
		if (z.modo.equals("siempre")) reiniciar(pr);
		pr.activa = true; sucio = true;
		Estado st = estados.get(p.getUniqueId());
		if (st != null && st.zona == z) ocultar(p, st);   // al tick siguiente aparecen de nuevo
		if (zonaDe(p.getLocation()) != z) p.playSound(p.getLocation(), "tod5:monedas_aparecen", SoundCategory.MASTER, 1f, 1f);
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
			Component.text("Clic izquierdo mirando una moneda: la saca", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
			Component.text("Shift + clic derecho: ese bloque activa la zona", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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
	/** Cambiaron las monedas de una zona: a los que la estaban viendo se les vuelve a armar. */
	private void rearmar(Zona z) {
		for (Map.Entry<UUID, Estado> e : new ArrayList<>(estados.entrySet())) {
			Player p = Bukkit.getPlayer(e.getKey());
			if (p != null && e.getValue().zona == z) ocultar(p, e.getValue());
		}
	}

	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND) return;
		Player p = e.getPlayer();
		String nombre = zonaDeVarita(e.getItem());
		if (nombre == null) {   // ¿toco el bloque que activa una zona?
			if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
			Block b = e.getClickedBlock();
			for (Zona z : zonas.values())
				if (z.activacion.equals("bloque") && z.bloque != null && b.getWorld().getName().equals(z.mundo)
					&& b.getX() == z.bloque[0] && b.getY() == z.bloque[1] && b.getZ() == z.bloque[2]) activar(p, z);
			return;
		}
		e.setCancelled(true);
		Zona z = zonas.get(nombre);
		if (z == null || !plugin.tablero().esEditor(p)) { p.sendActionBar(Component.text("Esa zona no existe (o no sos editor).", NamedTextColor.RED)); return; }
		if (!nombre.equals(zonaEditando.get(p.getUniqueId()))) mostrarEdicion(p, z);
		if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
			Block b = e.getClickedBlock();
			if (!b.getWorld().getName().equals(z.mundo)) { p.sendActionBar(Component.text("Esa zona es de otro mundo.", NamedTextColor.RED)); return; }
			if (p.isSneaking()) {
				z.bloque = new int[] { b.getX(), b.getY(), b.getZ() }; z.activacion = "bloque"; guardar();
				p.sendActionBar(Component.text("Este bloque activa \"" + nombre + "\" (activación: bloque)", NamedTextColor.GREEN));
				return;
			}
			Location l = b.getLocation().add(0.5, 1.5, 0.5);   // flotando en el medio del bloque de arriba
			z.monedas.add(new double[] { l.getX(), l.getY(), l.getZ() }); guardar(); rearmar(z);
			mostrarEdicion(p, z);
			p.sendActionBar(Component.text("Moneda puesta (" + z.monedas.size() + " en \"" + nombre + "\")", NamedTextColor.GREEN));
			p.playSound(p.getLocation(), "tod5:moneda_morada", SoundCategory.MASTER, 0.6f, 1.2f);
		} else if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
			int i = monedaMirada(p, z);
			if (i < 0) { p.sendActionBar(Component.text("Mirá una moneda para sacarla.", NamedTextColor.GRAY)); return; }
			z.monedas.remove(i); guardar();
			// los indices cambiaron: el progreso de esta zona arranca de cero para todos
			progreso.remove(z.nombre); sucio = true; rearmar(z);
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
			if (a.clone().subtract(dir.clone().multiply(t)).lengthSquared() < 0.4 * 0.4 && t < mejorT) { mejorT = t; mejor = i; }
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
		if (sucio) guardarProgreso();
	}

	// ================= cargar la recompensa (cofre de 27, se ponen COPIAS) =================
	private static final int GUARDAR = Recompensas.TAM - 1, CANCELAR = Recompensas.CASILLAS;
	private static final class EditorPremio implements InventoryHolder {
		final String zona; Inventory inv;
		EditorPremio(String zona) { this.zona = zona; }
		@Override public Inventory getInventory() { return inv; }
	}
	private void abrirRecompensa(Player p, Zona z) {
		EditorPremio ed = new EditorPremio(z.nombre);
		Inventory inv = Bukkit.createInventory(ed, Recompensas.TAM, Component.text("Recompensa de la zona " + z.nombre));
		ed.inv = inv;
		for (int i = 0; i < Recompensas.CASILLAS; i++) inv.setItem(i, z.recompensa[i] == null ? null : z.recompensa[i].clone());
		for (int i = Recompensas.CASILLAS; i < Recompensas.TAM; i++) inv.setItem(i, deco(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
		inv.setItem(CANCELAR, deco(Material.RED_STAINED_GLASS_PANE, "Cancelar", NamedTextColor.RED));
		inv.setItem(Recompensas.TAM - 5, deco(Material.BOOK, "Cómo se carga", NamedTextColor.YELLOW,
			"Clic con un item: pone una copia (tus items no se gastan).", "Clic derecho: de a uno. Clic sin nada: la saca.", "Al completar la zona, va a la bandeja de Recompensas."));
		inv.setItem(GUARDAR, deco(Material.LIME_STAINED_GLASS_PANE, "Guardar recompensa", NamedTextColor.GREEN));
		p.openInventory(inv);
	}
	private static ItemStack deco(Material mat, String nombre, NamedTextColor color, String... lore) {
		ItemStack it = new ItemStack(mat); ItemMeta m = it.getItemMeta();
		m.itemName(Component.text(nombre, color));
		if (lore.length > 0) m.lore(Arrays.stream(lore).map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		m.setHideTooltip(nombre.isBlank());
		it.setItemMeta(m); return it;
	}
	private static boolean vacio(ItemStack it) { return it == null || it.getType().isAir(); }
	@EventHandler
	public void alClicPremio(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof EditorPremio ed)) return;
		int slot = e.getRawSlot();
		if (slot >= Recompensas.TAM) {   // tu inventario: libre; con shift pone una copia en la primera casilla vacia
			if (e.isShiftClick()) {
				e.setCancelled(true);
				ItemStack it = e.getCurrentItem(); if (vacio(it)) return;
				for (int c = 0; c < Recompensas.CASILLAS; c++) if (vacio(ed.inv.getItem(c))) { ed.inv.setItem(c, it.clone()); break; }
			}
			return;
		}
		e.setCancelled(true);
		if (slot < Recompensas.CASILLAS) {
			ItemStack cur = e.getCursor(), en = ed.inv.getItem(slot);
			if (!vacio(cur)) {
				if (e.isRightClick()) {
					if (!vacio(en) && en.isSimilar(cur)) { en.setAmount(Math.min(en.getMaxStackSize(), en.getAmount() + 1)); ed.inv.setItem(slot, en); }
					else { ItemStack c = cur.clone(); c.setAmount(1); ed.inv.setItem(slot, c); }
				} else ed.inv.setItem(slot, cur.clone());
			} else if (!vacio(en)) {
				if (e.isRightClick() && en.getAmount() > 1) { en.setAmount(en.getAmount() - 1); ed.inv.setItem(slot, en); }
				else ed.inv.setItem(slot, null);
			}
			return;
		}
		Zona z = zonas.get(ed.zona);
		if (slot == GUARDAR && z != null) {
			for (int i = 0; i < Recompensas.CASILLAS; i++) { ItemStack it = ed.inv.getItem(i); z.recompensa[i] = vacio(it) ? null : it.clone(); }
			guardar();
			e.getWhoClicked().closeInventory();
			e.getWhoClicked().sendMessage(Component.text("Recompensa de la zona \"" + z.nombre + "\" guardada.", NamedTextColor.GREEN));
		} else if (slot == CANCELAR) e.getWhoClicked().closeInventory();
	}
	@EventHandler
	public void alArrastrarPremio(InventoryDragEvent e) {
		if (!(e.getInventory().getHolder() instanceof EditorPremio)) return;
		for (int s : e.getRawSlots()) if (s < Recompensas.TAM) { e.setCancelled(true); return; }
	}

	// ================= comando =================
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		String nombre = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
		Player yo = s instanceof Player pl ? pl : null;
		Zona z = nombre == null ? null : zonas.get(nombre);
		String a3 = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
		switch (sub) {
			case "crear" -> {
				Player quien = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : yo;   // crear <nombre> [margen] [jugador]
				if (nombre == null || quien == null) { uso(s); return; }
				if (z == null) { z = new Zona(); z.nombre = nombre; }
				z.mundo = quien.getWorld().getName();
				if (a3 != null) try { z.margen = Double.parseDouble(a3); } catch (NumberFormatException ex) { uso(s); return; }
				zonas.put(nombre, z); guardar();
				quien.getInventory().addItem(varita(nombre));
				s.sendMessage(Component.text("Zona \"" + nombre + "\" creada (margen " + (int) z.margen + "). Con la varita poné las monedas: el área se arma sola alrededor de ellas.", NamedTextColor.GREEN));
			}
			case "editar" -> {
				if (z == null || yo == null) { uso(s); return; }
				yo.getInventory().addItem(varita(nombre));
				s.sendMessage(Component.text("Ahí tenés la varita de \"" + nombre + "\".", NamedTextColor.GREEN));
			}
			case "margen", "radio" -> {
				if (z == null || a3 == null) { uso(s); return; }
				try { z.margen = Double.parseDouble(a3); } catch (NumberFormatException ex) { uso(s); return; }
				guardar(); s.sendMessage(Component.text("Las monedas de \"" + nombre + "\" aparecen a " + a3 + " bloques.", NamedTextColor.GREEN));
			}
			case "modo" -> {
				if (z == null || !MODOS.contains(a3)) { s.sendMessage(Component.text("/tod zona modo <nombre> <una|diaria|siempre>", NamedTextColor.YELLOW)); return; }
				z.modo = a3; guardar();
				s.sendMessage(Component.text("\"" + nombre + "\": " + switch (a3) { case "una" -> "una vez por jugador"; case "diaria" -> "una vez por día"; default -> "cada vez que se activa"; }, NamedTextColor.GREEN));
			}
			case "activacion" -> {
				if (z == null || !ACTIVACIONES.contains(a3)) { s.sendMessage(Component.text("/tod zona activacion <nombre> <cerca|bloque|comando>", NamedTextColor.YELLOW)); return; }
				z.activacion = a3; guardar();
				String extra = a3.equals("bloque") && z.bloque == null ? " Marcá el bloque con la varita (shift + clic derecho)." : "";
				s.sendMessage(Component.text("\"" + nombre + "\" se activa: " + a3 + "." + extra, NamedTextColor.GREEN));
			}
			case "recompensa" -> {
				if (z == null || yo == null) { uso(s); return; }
				abrirRecompensa(yo, z);
			}
			case "activar" -> {   // activar <nombre> [jugador]  (sin jugador: todos los conectados)
				if (z == null) { uso(s); return; }
				Collection<? extends Player> quienes = args.length > 3 ? (Bukkit.getPlayerExact(args[3]) == null ? List.of() : List.of(Bukkit.getPlayerExact(args[3]))) : Bukkit.getOnlinePlayers();
				for (Player p : quienes) activar(p, z);
				s.sendMessage(Component.text("Zona \"" + nombre + "\" activada para " + quienes.size() + " jugador(es).", NamedTextColor.GREEN));
			}
			case "reiniciar" -> {   // reiniciar <nombre> [jugador]: borra el progreso (para probar o dar otra oportunidad)
				if (z == null) { uso(s); return; }
				if (args.length > 3) {
					Player p = Bukkit.getPlayerExact(args[3]);
					Map<UUID, Progreso> m = progreso.get(nombre);
					if (p != null && m != null) m.remove(p.getUniqueId());
				} else progreso.remove(nombre);
				sucio = true; rearmar(z);
				s.sendMessage(Component.text("Progreso de \"" + nombre + "\" reiniciado.", NamedTextColor.YELLOW));
			}
			case "lista" -> {
				if (zonas.isEmpty()) s.sendMessage(Component.text("No hay zonas de monedas.", NamedTextColor.GRAY));
				zonas.forEach((n, zz) -> s.sendMessage(Component.text(" " + n + " · " + zz.monedas.size() + " monedas · " + zz.modo + " · " + zz.activacion
					+ " · margen " + (int) zz.margen + (zz.tieneRecompensa() ? " · con recompensa" : "") + " · " + zz.mundo, NamedTextColor.GRAY)));
			}
			case "borrar" -> {
				if (z == null) { uso(s); return; }
				rearmar(z); zonas.remove(nombre); progreso.remove(nombre); sucio = true;
				guardar(); s.sendMessage(Component.text("Zona \"" + nombre + "\" borrada.", NamedTextColor.YELLOW));
			}
			// solo para la prueba automatica: pone una moneda corrida desde un jugador (en el medio de su bloque)
			case "moneda" -> {
				Player donde = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : yo;
				if (z == null || donde == null) { uso(s); return; }
				Location l = donde.getLocation().toBlockLocation().add(0.5, 0.5, 0.5);
				if (args.length > 6) l.add(Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
				z.monedas.add(new double[] { l.getX(), l.getY(), l.getZ() }); guardar(); rearmar(z);
				s.sendMessage(Component.text("Moneda puesta en \"" + nombre + "\" (" + z.monedas.size() + ").", NamedTextColor.GREEN));
			}
			// solo para la prueba automatica: copia la recompensa desde el inventario de un jugador (primeras 9 casillas)
			case "premioprueba" -> {
				Player desde = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : yo;
				if (z == null || desde == null) { uso(s); return; }
				for (int i = 0; i < 9; i++) { ItemStack it = desde.getInventory().getItem(i); z.recompensa[i] = vacio(it) ? null : it.clone(); }
				guardar(); s.sendMessage(Component.text("Recompensa de prueba cargada.", NamedTextColor.GREEN));
			}
			default -> uso(s);
		}
	}
	private static void uso(CommandSender s) {
		s.sendMessage(Component.text("/tod zona crear <nombre> [margen] · editar · margen · modo · activacion · recompensa · activar · reiniciar · lista · borrar", NamedTextColor.YELLOW));
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "editar", "margen", "modo", "activacion", "recompensa", "activar", "reiniciar", "lista", "borrar");
		if (args.length == 3 && !args[1].equalsIgnoreCase("crear")) return new ArrayList<>(zonas.keySet());
		if (args.length == 4 && args[1].equalsIgnoreCase("modo")) return MODOS;
		if (args.length == 4 && args[1].equalsIgnoreCase("activacion")) return ACTIVACIONES;
		if (args.length == 4 && (args[1].equalsIgnoreCase("activar") || args[1].equalsIgnoreCase("reiniciar")))
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		return List.of();
	}
}
