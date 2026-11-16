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
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;

/**
 * Las misiones del evento. Se arman en la tablet (modo admin > Misiones), sin tocar archivos.
 *
 * Cada mision: nombre, clase (diaria u opcional), dia, si es DE EQUIPO o de cada jugador,
 * estado (oculta / activa), una lista de objetivos y la recompensa (hasta 27 items).
 *
 * Objetivos:
 *   tener     tener el item en el inventario (NO se consume). Una vez que lo tuviste, queda cumplido.
 *   fabricar  sacarlo VOS de la mesa, los hornos, la herreria o un crafteo del evento (si te lo pasan, no cuenta).
 *   matar     matar un mob: "zombie", "creeper"... o "custom:<etiqueta>" (un mob con esa etiqueta / tag).
 *   picar     romper ese bloque (los que puso un jugador no cuentan).
 *   ir        llegar a un lugar (punto + radio) o a una dimension entera (radio 0).
 *   evento    lo que el juego no puede detectar: lo completa un editor con /tod mision completar.
 *
 * De equipo: el progreso es uno solo para todo el equipo (los equipos son los "teams" del juego: /team).
 * Cada miembro reclama su recompensa. Un jugador sin equipo la hace solo.
 * Diarias: solo avanzan el dia que les toca. Reclamar: lo que no entra en el inventario queda guardado en la mision.
 * Un anuncio puede activar una mision al enviarse (y al retirarlo vuelve a quedar oculta).
 *
 * Archivos: plugins/TOD5/datos/misiones.json (las misiones) y misiones_progreso.json (lo de cada jugador / equipo)
 */
final class Misiones implements Listener {
	static final List<String> TIPOS = List.of("tener", "fabricar", "matar", "picar", "ir", "evento");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class Objetivo {
		String uid, tipo = "tener", clave = "", texto = "", mundo = "";
		int cantidad = 1; double x, y, z, radio = 6;
		ItemStack item;
	}
	static final class Mision {
		String id, nombre = "Misión nueva", clase = "opcional", estado = "oculta", anuncio = "";   // anuncio: el que la activa (y que sale al activarla)
		int dia = 1; boolean equipo;
		final List<Objetivo> objetivos = new ArrayList<>();
		final ItemStack[] recompensa = new ItemStack[Recompensas.CASILLAS];
	}
	/** El progreso de un dueño (un jugador o un equipo) en una mision. */
	private static final class Prog {
		final Map<String, Integer> hecho = new HashMap<>();
		boolean completa;
		final Set<UUID> reclamaron = new HashSet<>();
		final Map<UUID, List<ItemStack>> resto = new HashMap<>();   // lo que no les entro al reclamar
	}

	private final TOD5Servidor plugin;
	final Map<String, Mision> misiones = new LinkedHashMap<>();
	private final Map<String, Map<String, Prog>> progreso = new HashMap<>();   // mision -> dueño -> progreso
	private final Set<String> puestos = new HashSet<>();   // bloques que puso un jugador (no cuentan para "picar")
	private final Set<UUID> porSincronizar = new HashSet<>();
	private boolean sucio;
	private int proximo = 1;
	private long tick;

	Misiones(TOD5Servidor plugin) { this.plugin = plugin; }

	// ================= archivos =================
	private File archivo(String n) { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, n); }
	private static String b64(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
	private static ItemStack deB64(JsonElement e) { return e == null || e.isJsonNull() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(e.getAsString())); }
	private static boolean vacio(ItemStack it) { return it == null || it.getType().isAir(); }
	private static String str(JsonObject o, String k, String def) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; }

	void cargar() {
		misiones.clear(); progreso.clear();
		File f = archivo("misiones.json");
		if (f.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
			for (JsonElement e : o.getAsJsonArray("misiones")) {
				JsonObject j = e.getAsJsonObject(); Mision m = new Mision();
				m.id = j.get("id").getAsString(); m.nombre = str(j, "nombre", m.nombre); m.clase = str(j, "clase", "opcional"); m.estado = str(j, "estado", "oculta");
				m.dia = j.has("dia") ? j.get("dia").getAsInt() : 1; m.equipo = j.has("equipo") && j.get("equipo").getAsBoolean(); m.anuncio = str(j, "anuncio", "");
				for (JsonElement oe : j.getAsJsonArray("objetivos")) {
					JsonObject jo = oe.getAsJsonObject(); Objetivo ob = new Objetivo();
					ob.uid = jo.get("uid").getAsString(); ob.tipo = str(jo, "tipo", "tener"); ob.clave = str(jo, "clave", ""); ob.texto = str(jo, "texto", ""); ob.mundo = str(jo, "mundo", "");
					ob.cantidad = jo.has("cantidad") ? jo.get("cantidad").getAsInt() : 1;
					if (jo.has("x")) { ob.x = jo.get("x").getAsDouble(); ob.y = jo.get("y").getAsDouble(); ob.z = jo.get("z").getAsDouble(); }
					if (jo.has("radio")) ob.radio = jo.get("radio").getAsDouble();
					ob.item = deB64(jo.get("item"));
					m.objetivos.add(ob);
				}
				if (j.has("recompensa")) { JsonArray r = j.getAsJsonArray("recompensa"); for (int i = 0; i < r.size() && i < m.recompensa.length; i++) m.recompensa[i] = deB64(r.get(i)); }
				misiones.put(m.id, m);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer misiones.json: " + e.getMessage()); }
		File fp = archivo("misiones_progreso.json");
		if (fp.exists()) try {
			JsonObject o = JsonParser.parseString(Files.readString(fp.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String mid : o.keySet()) {
				Map<String, Prog> mp = new HashMap<>();
				JsonObject jm = o.getAsJsonObject(mid);
				for (String dueno : jm.keySet()) {
					JsonObject j = jm.getAsJsonObject(dueno); Prog p = new Prog();
					JsonObject h = j.getAsJsonObject("hecho"); for (String u : h.keySet()) p.hecho.put(u, h.get(u).getAsInt());
					p.completa = j.has("completa") && j.get("completa").getAsBoolean();
					if (j.has("reclamaron")) for (JsonElement e : j.getAsJsonArray("reclamaron")) p.reclamaron.add(UUID.fromString(e.getAsString()));
					if (j.has("resto")) { JsonObject r = j.getAsJsonObject("resto"); for (String u : r.keySet()) { List<ItemStack> l = new ArrayList<>(); for (JsonElement e : r.getAsJsonArray(u)) l.add(deB64(e)); p.resto.put(UUID.fromString(u), l); } }
					mp.put(dueno, p);
				}
				progreso.put(mid, mp);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer misiones_progreso.json: " + e.getMessage()); }
		plugin.getLogger().info("Misiones: " + misiones.size() + " (" + misiones.values().stream().filter(m -> m.estado.equals("activa")).count() + " activas)");
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray();
		for (Mision m : misiones.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", m.id); j.addProperty("nombre", m.nombre); j.addProperty("clase", m.clase); j.addProperty("estado", m.estado);
			j.addProperty("dia", m.dia); j.addProperty("equipo", m.equipo); j.addProperty("anuncio", m.anuncio);
			JsonArray obs = new JsonArray();
			for (Objetivo ob : m.objetivos) {
				JsonObject jo = new JsonObject();
				jo.addProperty("uid", ob.uid); jo.addProperty("tipo", ob.tipo); jo.addProperty("clave", ob.clave); jo.addProperty("texto", ob.texto);
				jo.addProperty("cantidad", ob.cantidad); jo.addProperty("mundo", ob.mundo);
				jo.addProperty("x", ob.x); jo.addProperty("y", ob.y); jo.addProperty("z", ob.z); jo.addProperty("radio", ob.radio);
				if (!vacio(ob.item)) jo.addProperty("item", b64(ob.item));
				obs.add(jo);
			}
			j.add("objetivos", obs);
			JsonArray r = new JsonArray(); for (ItemStack it : m.recompensa) if (vacio(it)) r.add(JsonNull.INSTANCE); else r.add(b64(it));
			j.add("recompensa", r);
			a.add(j);
		}
		o.add("misiones", a);
		try { Files.writeString(archivo("misiones.json").toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar misiones.json: " + e.getMessage()); }
	}
	void guardarProgreso() {
		sucio = false;
		JsonObject o = new JsonObject();
		progreso.forEach((mid, mp) -> {
			JsonObject jm = new JsonObject();
			mp.forEach((dueno, p) -> {
				JsonObject j = new JsonObject();
				JsonObject h = new JsonObject(); p.hecho.forEach(h::addProperty); j.add("hecho", h);
				j.addProperty("completa", p.completa);
				JsonArray rc = new JsonArray(); p.reclamaron.forEach(u -> rc.add(u.toString())); j.add("reclamaron", rc);
				JsonObject rs = new JsonObject(); p.resto.forEach((u, l) -> { JsonArray la = new JsonArray(); l.forEach(i -> la.add(b64(i))); rs.add(u.toString(), la); }); j.add("resto", rs);
				jm.add(dueno, j);
			});
			o.add(mid, jm);
		});
		try { Files.writeString(archivo("misiones_progreso.json").toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar misiones_progreso.json: " + e.getMessage()); }
	}

	// ================= de quien es el progreso =================
	/** El equipo del jugador: su "team" del juego (/team). null = no tiene. */
	private static String equipoDe(Player p) {
		Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
		return t == null ? null : t.getName();
	}
	private static String dueno(Mision m, Player p) {
		String eq = m.equipo ? equipoDe(p) : null;
		return eq != null ? "e:" + eq : "j:" + p.getUniqueId();
	}
	private Prog prog(Mision m, String dueno) { return progreso.computeIfAbsent(m.id, k -> new HashMap<>()).computeIfAbsent(dueno, k -> new Prog()); }
	/** Los conectados que comparten ese progreso. */
	private static List<Player> companeros(Mision m, String dueno) {
		List<Player> l = new ArrayList<>();
		for (Player p : Bukkit.getOnlinePlayers()) if (dueno(m, p).equals(dueno)) l.add(p);
		return l;
	}
	/** ¿Esta mision esta corriendo? (activa; las diarias solo el dia que les toca) */
	private boolean corre(Mision m) {
		return m.estado.equals("activa") && (!m.clase.equals("diaria") || m.dia == plugin.tablero().dia());
	}

	// ================= avanzar =================
	private interface Filtro { boolean sirve(Objetivo o); }

	/** Suma n a los objetivos de ese tipo que cumplan el filtro, en todas las misiones que estan corriendo. */
	private void sumar(Player p, String tipo, Filtro f, int n) {
		if (n <= 0) return;
		for (Mision m : misiones.values()) {
			if (!corre(m)) continue;
			for (Objetivo o : m.objetivos) if (o.tipo.equals(tipo) && f.sirve(o)) poner(m, o, dueno(m, p), prog(m, dueno(m, p)).hecho.getOrDefault(o.uid, 0) + n);
		}
	}
	/** Deja el objetivo en ese valor (nunca baja). Avisa si se cumplio el objetivo o toda la mision. */
	private void poner(Mision m, Objetivo o, String dueno, int valor) {
		Prog pr = prog(m, dueno);
		int antes = pr.hecho.getOrDefault(o.uid, 0), nuevo = Math.min(o.cantidad, valor);
		if (nuevo <= antes) return;
		pr.hecho.put(o.uid, nuevo); sucio = true;
		List<Player> quienes = companeros(m, dueno);
		for (Player q : quienes) porSincronizar.add(q.getUniqueId());
		if (nuevo < o.cantidad) return;
		boolean todo = m.objetivos.stream().allMatch(x -> pr.hecho.getOrDefault(x.uid, 0) >= x.cantidad);
		if (todo && !pr.completa) {
			pr.completa = true;
			for (Player q : quienes) {
				q.playSound(q.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.7f, 1f);
				q.sendActionBar(Component.text("¡Misión completa! " + m.nombre + " · reclamá la recompensa en la tablet", NamedTextColor.GREEN));
			}
			plugin.getLogger().info(dueno + " completo la mision " + m.id + " (" + m.nombre + ")");
			guardarProgreso();
		} else if (!todo) for (Player q : quienes) {
			q.playSound(q.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.7f, 1.3f);
			q.sendActionBar(Component.text("Objetivo cumplido · " + m.nombre, NamedTextColor.GREEN));
		}
	}

	private static boolean mismoItem(Objetivo o, ItemStack it) {
		if (vacio(o.item) || vacio(it)) return false;
		if (!o.item.hasItemMeta()) return o.item.getType() == it.getType();   // item comun: alcanza con el tipo (aunque este usado o encantado)
		return o.item.isSimilar(it);                                           // item especial: tiene que ser ese
	}
	private static int contar(Player p, Objetivo o) {
		int n = 0;
		for (ItemStack it : p.getInventory().getContents()) if (mismoItem(o, it)) n += it.getAmount();
		return n;
	}

	/** Cada tick: una vez por segundo reviso "tener" e "ir"; mando las tablets que cambiaron; guardo cada tanto. */
	void cadaTick() {
		tick++;
		if (tick % 20 == 0) for (Mision m : misiones.values()) {
			if (!corre(m)) continue;
			for (Objetivo o : m.objetivos) {
				if (o.tipo.equals("tener")) {
					Map<String, Integer> suma = new HashMap<>();   // en equipo: lo que tienen entre todos
					for (Player p : Bukkit.getOnlinePlayers()) suma.merge(dueno(m, p), contar(p, o), Integer::sum);
					suma.forEach((d, n) -> { if (n > 0) poner(m, o, d, n); });
				} else if (o.tipo.equals("ir")) {
					for (Player p : Bukkit.getOnlinePlayers()) if (estaEn(p.getLocation(), o)) poner(m, o, dueno(m, p), o.cantidad);
				}
			}
		}
		if (!porSincronizar.isEmpty() && tick % 5 == 0) {
			for (UUID u : porSincronizar) { Player p = Bukkit.getPlayer(u); if (p != null) plugin.tablero().sincronizar(p); }
			porSincronizar.clear();
		}
		if (sucio && tick % 200 == 0) guardarProgreso();
	}
	private static boolean estaEn(Location l, Objetivo o) {
		if (o.mundo.isEmpty() || !l.getWorld().getName().equals(o.mundo)) return false;
		if (o.radio <= 0) return true;   // toda la dimension
		double dx = l.getX() - o.x, dy = l.getY() - o.y, dz = l.getZ() - o.z;
		return dx * dx + dy * dy + dz * dz <= o.radio * o.radio;
	}

	// ---- fabricar: cuento lo que aparecio en tu inventario despues del clic (asi el shift+clic cuenta bien) ----
	private static int cuantos(Player p, ItemStack igual) {
		int n = 0;
		for (ItemStack it : p.getInventory().getContents()) if (!vacio(it) && it.isSimilar(igual)) n += it.getAmount();
		ItemStack c = p.getItemOnCursor(); if (!vacio(c) && c.isSimilar(igual)) n += c.getAmount();
		return n;
	}
	private void alSacar(Player p, ItemStack res) {
		if (vacio(res)) return;
		ItemStack r = res.clone(); int antes = cuantos(p, r);
		Bukkit.getScheduler().runTask(plugin, () -> fabricado(p, r, cuantos(p, r) - antes));
	}
	/** El jugador fabrico n de ese item (mesa, horno, herreria o un crafteo del evento). */
	void fabricado(Player p, ItemStack item, int n) { sumar(p, "fabricar", o -> mismoItem(o, item), n); }

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alCraftear(CraftItemEvent e) { if (e.getWhoClicked() instanceof Player p) alSacar(p, e.getCurrentItem()); }
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alHerreria(SmithItemEvent e) { if (e.getWhoClicked() instanceof Player p) alSacar(p, e.getCurrentItem()); }
	@EventHandler(priority = EventPriority.MONITOR)
	public void alHorno(FurnaceExtractEvent e) {
		Material mat = e.getItemType();
		sumar(e.getPlayer(), "fabricar", o -> !vacio(o.item) && o.item.getType() == mat, e.getItemAmount());
	}
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alMatar(EntityDeathEvent e) {
		LivingEntity mob = e.getEntity(); Player p = mob.getKiller();
		if (p == null) return;
		String tipo = mob.getType().getKey().getKey();
		sumar(p, "matar", o -> {
			String c = o.clave.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
			if (c.startsWith("custom:")) return mob.getScoreboardTags().contains(c.substring(7));
			return c.equals(tipo);
		}, 1);
	}
	private static String pos(org.bukkit.block.Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alPoner(BlockPlaceEvent e) { if (puestos.size() < 200_000) puestos.add(pos(e.getBlock())); }
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alPicar(BlockBreakEvent e) {
		if (puestos.remove(pos(e.getBlock()))) return;   // lo puso un jugador: no cuenta
		Material mat = e.getBlock().getType();
		sumar(e.getPlayer(), "picar", o -> !vacio(o.item) && o.item.getType() == mat, 1);
	}

	// ================= reclamar =================
	void reclamar(Player p, String id) {
		Mision m = misiones.get(id);
		if (m == null) return;
		Prog pr = prog(m, dueno(m, p)); UUID u = p.getUniqueId();
		if (!pr.completa || pr.reclamaron.contains(u)) { plugin.tablero().sincronizar(p); return; }
		List<ItemStack> dar = pr.resto.containsKey(u) ? pr.resto.get(u) : Arrays.stream(m.recompensa).filter(i -> !vacio(i)).map(ItemStack::clone).toList();
		List<ItemStack> quedan = new ArrayList<>();
		for (ItemStack it : dar) quedan.addAll(p.getInventory().addItem(it.clone()).values());
		if (quedan.isEmpty()) {
			pr.resto.remove(u); pr.reclamaron.add(u);
			p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.7f, 1.2f);
			p.sendActionBar(Component.text("Recompensa reclamada: " + m.nombre, NamedTextColor.GREEN));
		} else {
			pr.resto.put(u, quedan);
			p.sendActionBar(Component.text("No te entra todo: quedan " + quedan.size() + " cosas guardadas en la misión. Hacé lugar y reclamá de nuevo.", NamedTextColor.YELLOW));
		}
		guardarProgreso();
		plugin.tablero().sincronizar(p);
	}

	// ================= lo que va a la tablet =================
	private JsonObject objetivoJson(Objetivo o, int hecho, boolean admin) {
		JsonObject j = new JsonObject();
		j.addProperty("uid", o.uid); j.addProperty("tipo", o.tipo); j.addProperty("clave", o.clave); j.addProperty("texto", o.texto);
		j.addProperty("cantidad", o.cantidad); j.addProperty("hecho", hecho);
		if (!vacio(o.item)) j.add("item", Crafteos.itemJson(o.item));
		if (admin) {
			j.addProperty("radio", o.radio);
			j.addProperty("lugar", o.mundo.isEmpty() ? "" : o.radio <= 0 ? o.mundo : o.mundo + " " + (int) Math.floor(o.x) + " " + (int) Math.floor(o.y) + " " + (int) Math.floor(o.z));
		}
		return j;
	}
	private JsonObject misionJson(Mision m, Prog pr, UUID u, boolean admin) {
		JsonObject j = new JsonObject();
		j.addProperty("id", m.id); j.addProperty("nombre", m.nombre); j.addProperty("clase", m.clase); j.addProperty("dia", m.dia); j.addProperty("equipo", m.equipo);
		String est = admin ? m.estado : pr.reclamaron.contains(u) ? "reclamada" : pr.resto.containsKey(u) ? "resto" : pr.completa ? "completa" : corre(m) ? "en_curso" : "vencida";
		j.addProperty("estado", est);
		JsonArray obs = new JsonArray();
		for (Objetivo o : m.objetivos) obs.add(objetivoJson(o, pr == null ? 0 : pr.hecho.getOrDefault(o.uid, 0), admin));
		j.add("objetivos", obs);
		JsonArray r = new JsonArray(); for (ItemStack it : m.recompensa) if (!vacio(it)) r.add(Crafteos.itemJson(it));
		j.add("recompensas", r);
		return j;
	}
	/** Las misiones que ve este jugador (las activas), con su progreso. */
	JsonArray paraTablet(Player p) {
		JsonArray a = new JsonArray();
		for (Mision m : misiones.values()) if (m.estado.equals("activa")) a.add(misionJson(m, prog(m, dueno(m, p)), p.getUniqueId(), false));
		return a;
	}
	/** Todas, como estan guardadas (para el modo admin). */
	JsonArray paraAdmin() {
		JsonArray a = new JsonArray();
		for (Mision m : misiones.values()) a.add(misionJson(m, null, null, true));
		return a;
	}

	// ================= acciones del modo admin =================
	void guardarDesdeTablet(JsonObject j) {
		String id = str(j, "id", "");
		if (id.isBlank() || id.length() > 40) id = "m" + (proximo++);
		Mision m = misiones.get(id);
		if (m == null) { m = new Mision(); m.id = id; misiones.put(id, m); }
		m.nombre = str(j, "nombre", m.nombre).trim(); if (m.nombre.isEmpty()) m.nombre = "Misión";
		m.clase = str(j, "clase", m.clase).equals("diaria") ? "diaria" : "opcional";
		m.dia = j.has("dia") ? Math.max(1, j.get("dia").getAsInt()) : m.dia;
		m.equipo = j.has("equipo") && j.get("equipo").getAsBoolean();
		if (j.has("objetivos")) {
			Map<String, Objetivo> viejos = new HashMap<>(); for (Objetivo o : m.objetivos) viejos.put(o.uid, o);
			m.objetivos.clear();
			for (JsonElement e : j.getAsJsonArray("objetivos")) {
				JsonObject jo = e.getAsJsonObject(); String uid = str(jo, "uid", "");
				Objetivo o = viejos.getOrDefault(uid, new Objetivo());   // el item y el lugar se conservan (los pone el server)
				o.uid = uid.isBlank() ? "o" + Long.toString(System.nanoTime(), 36) : uid;
				String tipo = str(jo, "tipo", "tener"); o.tipo = TIPOS.contains(tipo) ? tipo : "tener";
				o.clave = str(jo, "clave", ""); o.texto = str(jo, "texto", "");
				o.cantidad = Math.max(1, Math.min(100000, jo.has("cantidad") ? jo.get("cantidad").getAsInt() : 1));
				if (jo.has("radio")) o.radio = Math.max(0, jo.get("radio").getAsDouble());
				if (o.tipo.equals("ir") || o.tipo.equals("evento")) o.cantidad = 1;
				m.objetivos.add(o);
			}
		}
		guardar();
	}
	void estado(String id, String est) {
		Mision m = misiones.get(id);
		if (m == null || !(est.equals("oculta") || est.equals("activa"))) return;
		m.estado = est; guardar();
	}
	/** El anuncio que la activa ("" = ninguno). */
	void vincular(String id, String anuncio) { Mision m = misiones.get(id); if (m != null) { m.anuncio = anuncio; guardar(); } }
	private Objetivo objetivo(String id, String uid) {
		Mision m = misiones.get(id);
		if (m == null) return null;
		return m.objetivos.stream().filter(o -> o.uid.equals(uid)).findFirst().orElse(null);
	}
	/** El item del objetivo = el que el editor tiene en la mano. */
	void itemDeLaMano(Player p, String id, String uid) {
		Objetivo o = objetivo(id, uid); ItemStack it = p.getInventory().getItemInMainHand();
		if (o == null) return;
		if (vacio(it)) { p.sendMessage(Component.text("Tené en la mano el item (o el bloque) del objetivo.", NamedTextColor.RED)); return; }
		ItemStack c = it.clone(); c.setAmount(1); o.item = c; guardar();
	}
	/** El lugar del objetivo = donde esta parado el editor. */
	void lugarAca(Player p, String id, String uid) {
		Objetivo o = objetivo(id, uid);
		if (o == null) return;
		Location l = p.getLocation(); o.mundo = l.getWorld().getName(); o.x = l.getX(); o.y = l.getY(); o.z = l.getZ(); guardar();
	}
	void borrar(String id) { if (misiones.remove(id) != null) { progreso.remove(id); guardar(); guardarProgreso(); } }
	void duplicar(String id) {
		Mision m = misiones.get(id);
		if (m == null) return;
		Mision c = new Mision(); c.id = "m" + (proximo++); c.nombre = m.nombre + " (copia)"; c.clase = m.clase; c.dia = m.dia; c.equipo = m.equipo;
		for (Objetivo o : m.objetivos) {
			Objetivo n = new Objetivo(); n.uid = "o" + Long.toString(System.nanoTime(), 36); n.tipo = o.tipo; n.clave = o.clave; n.texto = o.texto; n.mundo = o.mundo;
			n.cantidad = o.cantidad; n.x = o.x; n.y = o.y; n.z = o.z; n.radio = o.radio; n.item = vacio(o.item) ? null : o.item.clone();
			c.objetivos.add(n);
		}
		for (int i = 0; i < m.recompensa.length; i++) c.recompensa[i] = vacio(m.recompensa[i]) ? null : m.recompensa[i].clone();
		misiones.put(c.id, c); guardar();
	}
	void reiniciar(String id) { progreso.remove(id); guardarProgreso(); }

	// ---- cargar la recompensa: un cofre de 27 donde se ponen COPIAS (igual que en las zonas) ----
	private static final int GUARDAR = Recompensas.TAM - 1, CANCELAR = Recompensas.CASILLAS;
	private static final class EditorPremio implements InventoryHolder {
		final String mision; Inventory inv;
		EditorPremio(String mision) { this.mision = mision; }
		@Override public Inventory getInventory() { return inv; }
	}
	void abrirRecompensa(Player p, String id) {
		Mision m = misiones.get(id);
		if (m == null) return;
		EditorPremio ed = new EditorPremio(id);
		Inventory inv = Bukkit.createInventory(ed, Recompensas.TAM, Component.text("Recompensa: " + m.nombre));
		ed.inv = inv;
		for (int i = 0; i < Recompensas.CASILLAS; i++) inv.setItem(i, vacio(m.recompensa[i]) ? null : m.recompensa[i].clone());
		for (int i = Recompensas.CASILLAS; i < Recompensas.TAM; i++) inv.setItem(i, deco(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
		inv.setItem(CANCELAR, deco(Material.RED_STAINED_GLASS_PANE, "Cancelar", NamedTextColor.RED));
		inv.setItem(Recompensas.TAM - 5, deco(Material.BOOK, "Cómo se carga", NamedTextColor.YELLOW,
			"Clic con un item: pone una copia (tus items no se gastan).", "Clic derecho: de a uno. Clic sin nada: la saca.", "Shift + clic en tu inventario: copia la pila."));
		inv.setItem(GUARDAR, deco(Material.LIME_STAINED_GLASS_PANE, "Guardar recompensa", NamedTextColor.GREEN));
		p.openInventory(inv);
	}
	private static ItemStack deco(Material mat, String nombre, NamedTextColor color, String... lore) {
		ItemStack it = new ItemStack(mat); ItemMeta im = it.getItemMeta();
		im.itemName(Component.text(nombre, color));
		if (lore.length > 0) im.lore(Arrays.stream(lore).map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		im.setHideTooltip(nombre.isBlank());
		it.setItemMeta(im); return it;
	}
	@EventHandler
	public void alClicPremio(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof EditorPremio ed)) return;
		int slot = e.getRawSlot();
		if (slot >= Recompensas.TAM) {
			if (e.isShiftClick()) {
				e.setCancelled(true);
				ItemStack it = e.getCurrentItem(); if (vacio(it)) return;
				for (int c = 0; c < Recompensas.CASILLAS; c++) if (vacio(ed.inv.getItem(c))) { ed.inv.setItem(c, it.clone()); break; }
			}
			return;
		}
		e.setCancelled(true);
		if (slot < 0) return;
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
		Mision m = misiones.get(ed.mision);
		if (slot == GUARDAR && m != null) {
			for (int i = 0; i < Recompensas.CASILLAS; i++) { ItemStack it = ed.inv.getItem(i); m.recompensa[i] = vacio(it) ? null : it.clone(); }
			guardar();
			e.getWhoClicked().closeInventory();
			e.getWhoClicked().sendMessage(Component.text("Recompensa de \"" + m.nombre + "\" guardada.", NamedTextColor.GREEN));
			plugin.tablero().sincronizarTodos();
		} else if (slot == CANCELAR) e.getWhoClicked().closeInventory();
	}
	@EventHandler
	public void alArrastrarPremio(InventoryDragEvent e) {
		if (!(e.getInventory().getHolder() instanceof EditorPremio)) return;
		for (int s : e.getRawSlots()) if (s < Recompensas.TAM) { e.setCancelled(true); return; }
	}

	// ================= comando =================
	/** /tod mision lista | completar <id> <jugador|todos> [n] | reiniciar <id> | estado <id> <oculta|activa> */
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "lista";
		Mision m = args.length > 2 ? misiones.get(args[2]) : null;
		switch (sub) {
			case "completar" -> {   // completa los objetivos "evento" (o el objetivo numero n, del tipo que sea)
				if (m == null || args.length < 4) { uso(s); return; }
				Collection<? extends Player> quienes = args[3].equalsIgnoreCase("todos") ? Bukkit.getOnlinePlayers()
					: Bukkit.getPlayerExact(args[3]) == null ? List.of() : List.of(Bukkit.getPlayerExact(args[3]));
				int n = args.length > 4 ? num(args[4]) : 0;
				for (Player p : quienes) for (int i = 0; i < m.objetivos.size(); i++) {
					Objetivo o = m.objetivos.get(i);
					if (n > 0 ? i == n - 1 : o.tipo.equals("evento")) poner(m, o, dueno(m, p), o.cantidad);
				}
				s.sendMessage(Component.text("Listo: \"" + m.nombre + "\" para " + quienes.size() + " jugador(es).", NamedTextColor.GREEN));
			}
			case "reiniciar" -> {
				if (m == null) { uso(s); return; }
				reiniciar(m.id); plugin.tablero().sincronizarTodos();
				s.sendMessage(Component.text("Progreso de \"" + m.nombre + "\" borrado.", NamedTextColor.YELLOW));
			}
			case "estado" -> {
				if (m == null || args.length < 4) { uso(s); return; }
				plugin.tablero().estadoMision(m.id, args[3].toLowerCase(Locale.ROOT)); plugin.tablero().sincronizarTodos();
				s.sendMessage(Component.text("\"" + m.nombre + "\": " + m.estado, NamedTextColor.GREEN));
			}
			// solo para la prueba automatica: la recompensa = las primeras 9 casillas del inventario de un jugador
			case "premioprueba" -> {
				Player desde = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : null;
				if (m == null || desde == null) { uso(s); return; }
				for (int i = 0; i < 9; i++) { ItemStack it = desde.getInventory().getItem(i); m.recompensa[i] = vacio(it) ? null : it.clone(); }
				guardar(); plugin.tablero().sincronizarTodos();
				s.sendMessage(Component.text("Recompensa de prueba cargada.", NamedTextColor.GREEN));
			}
			default -> {
				if (misiones.isEmpty()) s.sendMessage(Component.text("No hay misiones. Se arman en la tablet: modo admin > Misiones.", NamedTextColor.GRAY));
				for (Mision x : misiones.values()) s.sendMessage(Component.text(" " + x.id + " · " + x.nombre + " · " + x.clase + (x.clase.equals("diaria") ? " día " + x.dia : "")
					+ (x.equipo ? " · de equipo" : "") + " · " + x.estado + " · " + x.objetivos.size() + " objetivos", NamedTextColor.GRAY));
			}
		}
	}
	private static int num(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
	private static void uso(CommandSender s) { s.sendMessage(Component.text("/tod mision lista · completar <id> <jugador|todos> [n° de objetivo] · reiniciar <id> · estado <id> <oculta|activa>", NamedTextColor.YELLOW)); }
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("lista", "completar", "reiniciar", "estado");
		if (args.length == 3) return new ArrayList<>(misiones.keySet());
		if (args.length == 4 && args[1].equalsIgnoreCase("estado")) return List.of("oculta", "activa");
		if (args.length == 4 && args[1].equalsIgnoreCase("completar")) { List<String> l = new ArrayList<>(List.of("todos")); Bukkit.getOnlinePlayers().forEach(p -> l.add(p.getName())); return l; }
		return List.of();
	}
}
