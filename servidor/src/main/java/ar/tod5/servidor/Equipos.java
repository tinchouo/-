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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Los equipos del evento.
 *  - por dentro son equipos de Minecraft (scoreboard): el color del nombre, etc. El mod dibuja arriba de la cabeza la vida, el nombre y <Equipo>.
 *  - el admin (editor) hace todo desde la tablet: crear y borrar equipos, meter, sacar y mover gente, elegir lider, y la whitelist.
 *  - el primero que entra a un equipo es el lider; si el lider se va, pasa a ser lider el que hace mas tiempo que esta (orden de llegada).
 *  - el lider invita y echa. Cualquiera se puede ir. Las invitaciones llegan por el chat (clic para aceptar o rechazar) con /equipo.
 * Textos para los jugadores: en castellano neutro.
 */
final class Equipos implements Listener, CommandExecutor {
	static final class Equipo {
		String id, nombre, color = "yellow";
		List<String> miembros = new ArrayList<>();   // uuids, por orden de llegada
		String lider;
	}
	/** Los colores que deja usar Minecraft para un equipo (con su nombre para mostrar). */
	static final LinkedHashMap<String, String> COLORES = new LinkedHashMap<>();
	static {
		COLORES.put("red", "Rojo"); COLORES.put("gold", "Naranja"); COLORES.put("yellow", "Amarillo"); COLORES.put("green", "Verde");
		COLORES.put("dark_green", "Verde oscuro"); COLORES.put("aqua", "Celeste"); COLORES.put("dark_aqua", "Turquesa"); COLORES.put("blue", "Azul");
		COLORES.put("dark_blue", "Azul oscuro"); COLORES.put("light_purple", "Rosa"); COLORES.put("dark_purple", "Violeta"); COLORES.put("dark_red", "Rojo oscuro");
		COLORES.put("white", "Blanco"); COLORES.put("gray", "Gris"); COLORES.put("dark_gray", "Gris oscuro"); COLORES.put("black", "Negro");
	}
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final TOD5Servidor plugin;
	final Map<String, Equipo> equipos = new LinkedHashMap<>();
	private final Map<UUID, Set<String>> invitaciones = new HashMap<>();   // a quien invitaron, a que equipos (no se guarda)
	private int proximo = 1;

	Equipos(TOD5Servidor plugin) { this.plugin = plugin; }

	// ================= archivo =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "equipos.json"); }
	void cargar() {
		equipos.clear();
		try {
			File f = archivo();
			if (f.exists()) {
				JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
				proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
				for (JsonElement e : o.getAsJsonArray("equipos")) { Equipo q = GSON.fromJson(e, Equipo.class); if (q != null && q.id != null) equipos.put(q.id, q); }
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer los equipos: " + e.getMessage()); }
		for (Equipo q : equipos.values()) aplicar(q);
		plugin.getLogger().info("Equipos: " + equipos.size());
	}
	private void guardar() {
		JsonObject o = new JsonObject(); o.addProperty("proximo", proximo);
		JsonArray a = new JsonArray(); for (Equipo q : equipos.values()) a.add(GSON.toJsonTree(q));
		o.add("equipos", a);
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar los equipos: " + e.getMessage()); }
	}
	/** Guarda, actualiza el equipo de Minecraft y la tablet de todos. */
	private void cambio(Equipo q) {
		guardar();
		if (q != null) aplicar(q);
		plugin.tablero().sincronizarTodos();
	}

	// ================= el equipo de Minecraft =================
	private Scoreboard tablero() { return Bukkit.getScoreboardManager().getMainScoreboard(); }
	private static String nombreMc(Equipo q) { return "tod5_" + q.id; }
	private static NamedTextColor colorMc(String c) { NamedTextColor n = NamedTextColor.NAMES.value(c); return n == null ? NamedTextColor.WHITE : n; }
	/** Deja el equipo de Minecraft igual que el nuestro (nombre, color y miembros). */
	private void aplicar(Equipo q) {
		Team t = tablero().getTeam(nombreMc(q));
		if (t == null) t = tablero().registerNewTeam(nombreMc(q));
		t.displayName(Component.text(q.nombre));
		t.color(colorMc(q.color));
		Set<String> deberian = new HashSet<>();
		for (String u : q.miembros) { String n = nombre(u); if (n != null) deberian.add(n); }
		for (String e : new ArrayList<>(t.getEntries())) if (!deberian.contains(e)) t.removeEntry(e);
		for (String n : deberian) if (!t.hasEntry(n)) t.addEntry(n);
	}
	private static String nombre(String uuid) {
		try { OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid)); return op.getName(); } catch (Exception e) { return null; }
	}
	@EventHandler
	public void alEntrar(PlayerJoinEvent e) {   // por si cambio de nombre
		Equipo q = de(e.getPlayer().getUniqueId());
		if (q != null) aplicar(q);
	}

	// ================= consultas =================
	Equipo de(UUID u) { for (Equipo q : equipos.values()) if (q.miembros.contains(u.toString())) return q; return null; }
	boolean esLider(Player p) { Equipo q = de(p.getUniqueId()); return q != null && p.getUniqueId().toString().equals(q.lider); }

	// ================= cambios =================
	Equipo crear(String nombre, String color) {
		Equipo q = new Equipo();
		q.id = "e" + (proximo++); q.nombre = nombre.isBlank() ? "Equipo " + q.id.substring(1) : nombre.trim(); q.color = COLORES.containsKey(color) ? color : "yellow";
		equipos.put(q.id, q); cambio(q);
		plugin.getLogger().info("Equipo creado: " + q.id + " " + q.nombre);
		return q;
	}
	void borrar(String id) {
		Equipo q = equipos.remove(id);
		if (q == null) return;
		Team t = tablero().getTeam(nombreMc(q));
		if (t != null) t.unregister();
		cambio(null);
	}
	/** Lo mete en este equipo (sacandolo del que estaba). Si el equipo estaba vacio, queda de lider. */
	void meter(String id, UUID u) {
		Equipo q = equipos.get(id);
		if (q == null) return;
		Equipo antes = de(u);
		if (antes == q) return;
		if (antes != null) sacarDe(antes, u);
		q.miembros.add(u.toString());
		if (q.lider == null || !q.miembros.contains(q.lider)) q.lider = u.toString();
		invitaciones.remove(u);
		if (antes != null) aplicar(antes);
		cambio(q);
	}
	private void sacarDe(Equipo q, UUID u) {
		q.miembros.remove(u.toString());
		if (u.toString().equals(q.lider)) q.lider = q.miembros.isEmpty() ? null : q.miembros.get(0);   // el que mas tiempo lleva
	}
	void sacar(UUID u) {
		Equipo q = de(u);
		if (q == null) return;
		sacarDe(q, u); cambio(q);
	}
	void hacerLider(String id, UUID u) {
		Equipo q = equipos.get(id);
		if (q == null || !q.miembros.contains(u.toString())) return;
		q.lider = u.toString(); cambio(q);
	}

	// ================= para la tablet =================
	/** Lo que ve cada jugador de su equipo (null si no tiene). */
	JsonObject paraTablet(Player p) {
		Equipo q = de(p.getUniqueId());
		if (q == null) return null;
		JsonObject o = json(q);
		o.addProperty("soyLider", p.getUniqueId().toString().equals(q.lider));
		// para invitar: los conectados que no estan en este equipo
		JsonArray inv = new JsonArray();
		for (Player o2 : Bukkit.getOnlinePlayers()) if (!q.miembros.contains(o2.getUniqueId().toString())) inv.add(o2.getName());
		o.add("invitables", inv);
		return o;
	}
	private JsonObject json(Equipo q) {
		JsonObject o = new JsonObject();
		o.addProperty("id", q.id); o.addProperty("nombre", q.nombre); o.addProperty("color", q.color);
		Integer rgb = colorMc(q.color).value();
		o.addProperty("rgb", rgb);
		JsonArray m = new JsonArray();
		for (String u : q.miembros) {
			JsonObject j = new JsonObject();
			j.addProperty("uuid", u); j.addProperty("nombre", Objects.requireNonNullElse(nombre(u), "?"));
			Player pl = Bukkit.getPlayer(UUID.fromString(u));
			j.addProperty("conectado", pl != null && pl.isOnline());
			j.addProperty("lider", u.equals(q.lider));
			m.add(j);
		}
		o.add("miembros", m);
		return o;
	}
	/** Todo, para el panel del admin: los equipos, la whitelist y los conectados. */
	JsonObject paraAdmin() {
		JsonObject o = new JsonObject();
		JsonArray eqs = new JsonArray(); for (Equipo q : equipos.values()) eqs.add(json(q));
		o.add("equipos", eqs);
		JsonArray wl = new JsonArray();
		Set<String> vistos = new HashSet<>();
		for (OfflinePlayer op : Bukkit.getWhitelistedPlayers()) {
			JsonObject j = new JsonObject();
			j.addProperty("uuid", op.getUniqueId().toString()); j.addProperty("nombre", Objects.requireNonNullElse(op.getName(), "?"));
			Equipo q = de(op.getUniqueId()); j.addProperty("equipo", q == null ? "" : q.id);
			j.addProperty("whitelist", true); j.addProperty("conectado", op.isOnline());
			wl.add(j); vistos.add(op.getUniqueId().toString());
		}
		for (Player pl : Bukkit.getOnlinePlayers()) {   // los conectados que no estan en la whitelist tambien (para poder meterlos)
			if (vistos.contains(pl.getUniqueId().toString())) continue;
			JsonObject j = new JsonObject();
			j.addProperty("uuid", pl.getUniqueId().toString()); j.addProperty("nombre", pl.getName());
			Equipo q = de(pl.getUniqueId()); j.addProperty("equipo", q == null ? "" : q.id);
			j.addProperty("whitelist", false); j.addProperty("conectado", true);
			wl.add(j);
		}
		o.add("jugadores", wl);
		o.addProperty("whitelistActiva", Bukkit.hasWhitelist());
		JsonArray cs = new JsonArray();
		for (Map.Entry<String, String> c : COLORES.entrySet()) { JsonObject j = new JsonObject(); j.addProperty("id", c.getKey()); j.addProperty("nombre", c.getValue()); j.addProperty("rgb", colorMc(c.getKey()).value()); cs.add(j); }
		o.add("colores", cs);
		return o;
	}

	/** Acciones de la tablet de los jugadores (lider y miembros). Devuelve true si era de equipos. */
	boolean accionJugador(Player p, String a, JsonObject o) {
		if (!a.startsWith("equipo_")) return false;
		Equipo q = de(p.getUniqueId());
		String quien = o.has("nombre") ? o.get("nombre").getAsString() : "";
		switch (a) {
			case "equipo_salir" -> {
				if (q == null) return true;
				sacar(p.getUniqueId());
				p.sendMessage(Component.text("Saliste del equipo " + q.nombre + ".", NamedTextColor.YELLOW));
				avisarEquipo(q, p.getName() + " salió del equipo.");
			}
			case "equipo_invitar" -> {
				if (q == null || !esLider(p)) return true;
				Player otro = Bukkit.getPlayerExact(quien);
				if (otro == null) { p.sendMessage(Component.text("Ese jugador no está conectado.", NamedTextColor.RED)); return true; }
				invitar(q, otro, p.getName());
				p.sendMessage(Component.text("Invitaste a " + otro.getName() + ".", NamedTextColor.GREEN));
			}
			case "equipo_echar" -> {
				if (q == null || !esLider(p)) return true;
				OfflinePlayer otro = Bukkit.getOfflinePlayerIfCached(quien);
				if (otro == null || !q.miembros.contains(otro.getUniqueId().toString()) || otro.getUniqueId().equals(p.getUniqueId())) return true;
				sacar(otro.getUniqueId());
				avisarEquipo(q, otro.getName() + " fue expulsado del equipo.");
				if (otro.getPlayer() != null) otro.getPlayer().sendMessage(Component.text("Te expulsaron del equipo " + q.nombre + ".", NamedTextColor.RED));
			}
			default -> {}
		}
		return true;
	}
	/** Acciones del panel del admin. */
	void accionAdmin(Player p, String a, JsonObject o) {
		String id = o.has("equipo") ? o.get("equipo").getAsString() : "";
		UUID u = null;
		try { if (o.has("uuid")) u = UUID.fromString(o.get("uuid").getAsString()); } catch (Exception ignorar) {}
		switch (a) {
			case "eq_crear" -> crear(o.has("nombre") ? o.get("nombre").getAsString() : "", o.has("color") ? o.get("color").getAsString() : "yellow");
			case "eq_editar" -> {
				Equipo q = equipos.get(id);
				if (q == null) return;
				if (o.has("nombre") && !o.get("nombre").getAsString().isBlank()) q.nombre = o.get("nombre").getAsString().trim();
				if (o.has("color") && COLORES.containsKey(o.get("color").getAsString())) q.color = o.get("color").getAsString();
				cambio(q);
			}
			case "eq_borrar" -> borrar(id);
			case "eq_meter" -> { if (u != null) meter(id, u); }
			case "eq_sacar" -> { if (u != null) sacar(u); }
			case "eq_lider" -> { if (u != null) hacerLider(id, u); }
			case "wl_agregar" -> {
				String n = o.has("nombre") ? o.get("nombre").getAsString().trim() : "";
				if (n.isEmpty()) return;
				OfflinePlayer op = Bukkit.getOfflinePlayer(n);
				op.setWhitelisted(true);
				plugin.getLogger().info(p.getName() + " agrego a " + n + " a la whitelist");
				plugin.tablero().sincronizarTodos();
			}
			case "wl_sacar" -> { if (u != null) { Bukkit.getOfflinePlayer(u).setWhitelisted(false); plugin.tablero().sincronizarTodos(); } }
			case "wl_activar" -> { Bukkit.setWhitelist(!Bukkit.hasWhitelist()); plugin.tablero().sincronizarTodos(); }
			default -> {}
		}
	}

	// ================= invitaciones =================
	private void invitar(Equipo q, Player otro, String deQuien) {
		invitaciones.computeIfAbsent(otro.getUniqueId(), k -> new HashSet<>()).add(q.id);
		Component msj = Component.text(deQuien + " te invitó al equipo ", NamedTextColor.YELLOW)
			.append(Component.text(q.nombre, colorMc(q.color)))
			.append(Component.text(". ", NamedTextColor.YELLOW))
			.append(Component.text("[Aceptar]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/equipo aceptar " + q.id)))
			.append(Component.text(" "))
			.append(Component.text("[Rechazar]", NamedTextColor.RED, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/equipo rechazar " + q.id)));
		otro.sendMessage(msj);
		if (de(otro.getUniqueId()) != null) otro.sendMessage(Component.text("Si aceptas, dejas tu equipo actual.", NamedTextColor.GRAY));
	}
	private void avisarEquipo(Equipo q, String texto) {
		for (String u : q.miembros) { Player pl = Bukkit.getPlayer(UUID.fromString(u)); if (pl != null) pl.sendMessage(Component.text(texto, NamedTextColor.GRAY)); }
	}
	/** /equipo aceptar|rechazar <id>: lo usan los clics del chat (cualquier jugador). */
	@Override
	public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
		if (!(s instanceof Player p)) return true;
		String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
		String id = args.length > 1 ? args[1] : "";
		Set<String> mias = invitaciones.getOrDefault(p.getUniqueId(), Set.of());
		Equipo q = equipos.get(id);
		if (q == null || !mias.contains(id)) { p.sendMessage(Component.text("Esa invitación ya no existe.", NamedTextColor.RED)); return true; }
		if (sub.equals("aceptar")) {
			meter(q.id, p.getUniqueId());
			p.sendMessage(Component.text("Ahora eres del equipo " + q.nombre + ".", NamedTextColor.GREEN));
			avisarEquipo(q, p.getName() + " se unió al equipo.");
		} else if (sub.equals("rechazar")) {
			mias.remove(id);
			p.sendMessage(Component.text("Rechazaste la invitación.", NamedTextColor.GRAY));
		}
		return true;
	}
}
