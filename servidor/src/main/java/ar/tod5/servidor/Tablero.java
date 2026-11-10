package ar.tod5.servidor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Lo que muestra la tablet: el dia del evento, las novedades y los crafteos.
 * Todo vive aca (en el server); a cada jugador se le manda solo lo que puede ver (sin spoilers).
 * Nada es automatico: los crafteos y los anuncios los activa un editor a mano.
 *
 * Archivos:
 *   plugins/TOD5/crafteos.yml          las recetas (las defino en el plugin; se pueden agregar a mano)
 *   plugins/TOD5/datos/estado.yml      dia actual, crafteos activos, crafteos con sombra
 *   plugins/TOD5/datos/novedades.yml   las novedades publicadas
 */
public class Tablero {
	static final String CANAL_TABLET = "tod5:tablet", CANAL_ACCION = "tod5:accion", CANAL_RULETA = "tod5:ruleta";
	/** Lo que dura la ruleta en el mod (unos 7,7 s): recien despues sale el mensaje en el chat. */
	static final long DURACION_RULETA = 156;
	private static final Gson GSON = new Gson();

	/** Los tipos de anuncio: color de la ruleta, titulo por defecto, y si quedan guardados en la tablet. */
	enum Tipo {
		// el numero es el gajo de la ruleta (mismo orden que en el mod)
		DIARIA("diaria", 0x55FF55, "MISIÓN DIARIA", false, 0),
		AVISO("aviso", 0x55FFFF, "AVISO", true, 1),
		CAMBIO("cambio", 0xFF9600, "CAMBIO DE DIFICULTAD", true, 6),
		SANCION("sancion", 0xB45AFF, "SANCIÓN", false, 3),
		TRUCO("truco", 0xFF3C3C, "MOMENTO TRUCO", false, 5),
		CUY("cuy", 0xFF78C8, "MOMENTO CUY", false, 4),
		MOB("mob", 0xFFE63C, "NUEVO MOB", true, 7),
		DUNGEON("dungeon", 0x4678FF, "NUEVA DUNGEON", true, 2);
		final String id; final int color; final String titulo; final boolean enTablet; final int gajo;
		Tipo(String id, int color, String titulo, boolean enTablet, int gajo) { this.id = id; this.color = color; this.titulo = titulo; this.enTablet = enTablet; this.gajo = gajo; }
		static Tipo de(String s) { for (Tipo t : values()) if (t.id.equalsIgnoreCase(s)) return t; return null; }
	}

	record Crafteo(String id, String nombre, String categoria, int dia, String nota, Material resultado, int cantidad,
	               String[] forma, Map<Character, Material> ingredientes) {
		Material[] grilla() {
			Material[] g = new Material[9];
			for (int f = 0; f < 3 && f < forma.length; f++)
				for (int c = 0; c < 3 && c < forma[f].length(); c++) g[f * 3 + c] = ingredientes.get(forma[f].charAt(c));
			return g;
		}
	}
	record Novedad(String id, int dia, String tipo, String titulo, String texto) {}

	private final TOD5Servidor plugin;
	private final Map<String, Crafteo> crafteos = new LinkedHashMap<>();
	private final Set<String> activos = new LinkedHashSet<>(), sombras = new HashSet<>();
	private final List<Novedad> novedades = new ArrayList<>();
	private int dia = 1, proximaNovedad = 1;

	Tablero(TOD5Servidor plugin) { this.plugin = plugin; }

	// ================= cargar / guardar =================
	void cargar() {
		if (!new File(plugin.getDataFolder(), "crafteos.yml").exists()) plugin.saveResource("crafteos.yml", false);
		crafteos.clear();
		YamlConfiguration cy = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "crafteos.yml"));
		ConfigurationSection cs = cy.getConfigurationSection("crafteos");
		if (cs != null) for (String id : cs.getKeys(false)) {
			ConfigurationSection c = cs.getConfigurationSection(id);
			try {
				Map<Character, Material> ing = new HashMap<>();
				ConfigurationSection is = c.getConfigurationSection("ingredientes");
				if (is != null) for (String k : is.getKeys(false)) ing.put(k.charAt(0), material(is.getString(k)));
				List<String> forma = c.getStringList("forma");
				crafteos.put(id, new Crafteo(id, c.getString("nombre", id), c.getString("categoria", "general"), c.getInt("dia", 1),
					c.getString("nota", ""), material(c.getString("resultado", "stone")), c.getInt("cantidad", 1), forma.toArray(new String[0]), ing));
			} catch (Exception e) { plugin.getLogger().warning("Crafteo '" + id + "' mal escrito en crafteos.yml: " + e.getMessage()); }
		}
		YamlConfiguration est = yml("estado");
		boolean primeraVez = !est.contains("dia");
		dia = est.getInt("dia", 1);
		activos.clear(); activos.addAll(est.getStringList("crafteos_activos"));
		sombras.clear(); sombras.addAll(est.getStringList("crafteos_sombra"));
		if (primeraVez) { // la primera vez, los que dicen "activo: true" en crafteos.yml arrancan activos (la billetera)
			for (String id : crafteos.keySet()) if (cy.getBoolean("crafteos." + id + ".activo", false)) activos.add(id);
			guardarEstado();
		}
		novedades.clear();
		YamlConfiguration ny = yml("novedades");
		proximaNovedad = ny.getInt("proxima", 1);
		for (Map<?, ?> m : ny.getMapList("novedades"))
			novedades.add(new Novedad(str(m, "id"), Integer.parseInt(str(m, "dia")), str(m, "tipo"), m.get("titulo") == null ? null : str(m, "titulo"), str(m, "texto")));
		for (String id : activos) registrarReceta(id);
		plugin.getLogger().info("Tablet: día " + dia + ", " + crafteos.size() + " crafteos (" + activos.size() + " activos), " + novedades.size() + " novedades");
	}

	private static String str(Map<?, ?> m, String k) { return String.valueOf(m.get(k)); }
	private static Material material(String s) {
		Material m = Material.matchMaterial(s);
		if (m == null) throw new IllegalArgumentException("no existe el item '" + s + "'");
		return m;
	}
	private File archivo(String n) { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, n + ".yml"); }
	private YamlConfiguration yml(String n) { return YamlConfiguration.loadConfiguration(archivo(n)); }

	private void guardarEstado() {
		YamlConfiguration y = new YamlConfiguration();
		y.set("dia", dia);
		y.set("crafteos_activos", new ArrayList<>(activos));
		y.set("crafteos_sombra", new ArrayList<>(sombras));
		guardar(y, "estado");
	}
	private void guardarNovedades() {
		YamlConfiguration y = new YamlConfiguration();
		y.set("proxima", proximaNovedad);
		List<Map<String, Object>> l = new ArrayList<>();
		for (Novedad n : novedades) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", n.id()); m.put("dia", n.dia()); m.put("tipo", n.tipo());
			if (n.titulo() != null) m.put("titulo", n.titulo());
			m.put("texto", n.texto());
			l.add(m);
		}
		y.set("novedades", l);
		guardar(y, "novedades");
	}
	private void guardar(YamlConfiguration y, String n) {
		try { y.save(archivo(n)); } catch (IOException e) { plugin.getLogger().severe("No pude guardar " + n + ".yml: " + e.getMessage()); }
	}

	// ================= quien edita =================
	/** Editores: los de la lista "editores" de la config o con el permiso tod5.editor. NO alcanza con ser OP (asi un builder puede tener OP sin ver nada oculto). */
	boolean esEditor(CommandSender s) {
		if (!(s instanceof Player p)) return true;   // la consola puede todo
		if (p.hasPermission("tod5.editor")) return true;
		return plugin.getConfig().getStringList("editores").stream().anyMatch(n -> n.equalsIgnoreCase(p.getName()));
	}

	// ================= recetas =================
	private NamespacedKey clave(String id) { return new NamespacedKey(plugin, id); }

	ItemStack resultado(Crafteo c) {
		ItemStack it = new ItemStack(c.resultado(), Math.max(1, c.cantidad()));
		ItemMeta m = it.getItemMeta();
		m.itemName(Component.text(c.nombre()));
		m.getPersistentDataContainer().set(clave("item"), PersistentDataType.STRING, c.id());
		it.setItemMeta(m);
		return it;
	}

	private void registrarReceta(String id) {
		Crafteo c = crafteos.get(id);
		if (c == null || Bukkit.getRecipe(clave(id)) != null) return;
		ShapedRecipe r = new ShapedRecipe(clave(id), resultado(c));
		r.shape(c.forma());
		for (var e : c.ingredientes().entrySet()) r.setIngredient(e.getKey(), e.getValue());
		Bukkit.addRecipe(r, true);
	}
	private void sacarReceta(String id) { Bukkit.removeRecipe(clave(id), true); }

	// ================= acciones (desde la tablet o desde comandos) =================
	/** Activa uno o varios crafteos: receta real en el server, un solo anuncio general y se actualizan las tablets. */
	List<String> activar(Collection<String> ids) {
		List<String> nuevos = new ArrayList<>();
		for (String id : ids) if (crafteos.containsKey(id) && activos.add(id)) { registrarReceta(id); nuevos.add(crafteos.get(id).nombre()); }
		if (!nuevos.isEmpty()) {
			guardarEstado();
			anunciar(Tipo.AVISO, "NUEVOS CRAFTEOS", "Se desbloquearon nuevos crafteos en tu tablet: " + String.join(", ", nuevos) + ".");
		}
		return nuevos;
	}
	List<String> ocultar(Collection<String> ids) {
		List<String> sacados = new ArrayList<>();
		for (String id : ids) if (activos.remove(id)) { sacarReceta(id); sacados.add(id); }
		if (!sacados.isEmpty()) { guardarEstado(); sincronizarTodos(); }
		return sacados;
	}
	boolean sombra(String id, boolean v) {
		if (!crafteos.containsKey(id)) return false;
		if (v) sombras.add(id); else sombras.remove(id);
		guardarEstado(); sincronizarTodos();
		return true;
	}
	void cambiarDia(int n) { dia = Math.max(1, n); guardarEstado(); sincronizarTodos(); }
	int dia() { return dia; }

	// ---- la ruleta: los anuncios van en fila; cada uno gira la ruleta y recien al terminar sale en el chat ----
	private final ArrayDeque<Runnable> cola = new ArrayDeque<>();
	private boolean girando = false;

	/** Un anuncio como en DeDSafio: primero la ruleta (en el mod), despues el mensaje en el chat. */
	void anunciar(Tipo t, String titulo, String texto) {
		cola.add(() -> {
			byte[] b = String.valueOf(t.gajo).getBytes(StandardCharsets.UTF_8);
			for (Player p : Bukkit.getOnlinePlayers()) if (plugin.tieneElMod(p)) p.sendPluginMessage(plugin, CANAL_RULETA, b);
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				publicar(t, titulo, texto);
				Bukkit.getScheduler().runTaskLater(plugin, this::siguiente, 10);   // medio segundo entre ruletas
			}, DURACION_RULETA);
		});
		if (!girando) siguiente();
	}
	private void siguiente() {
		Runnable r = cola.poll();
		girando = r != null;
		if (r != null) r.run();
	}

	/** El mensaje: titulo en negrita con ">" y abajo el texto, en el mismo color mas claro. */
	private void publicar(Tipo t, String titulo, String texto) {
		String tit = titulo == null || titulo.isBlank() ? t.titulo : titulo.toUpperCase(Locale.ROOT);
		TextColor fuerte = TextColor.color(t.color), claro = TextColor.color(aclarar(t.color));
		Component msg = Component.text("> " + tit + ":", fuerte).decorate(TextDecoration.BOLD)
			.append(Component.newline())
			.append(Component.text(texto, claro).decoration(TextDecoration.BOLD, false));
		Bukkit.broadcast(msg);
		if (t.enTablet) {
			novedades.add(new Novedad("n" + (proximaNovedad++), dia, t.id, titulo == null || titulo.isBlank() ? null : tit, texto));
			guardarNovedades();
		}
		sincronizarTodos();
	}
	boolean borrarNovedad(String id) {
		boolean ok = novedades.removeIf(n -> n.id().equals(id));
		if (ok) { guardarNovedades(); sincronizarTodos(); }
		return ok;
	}
	private static int aclarar(int c) {
		int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
		r += (int) ((255 - r) * 0.45); g += (int) ((255 - g) * 0.45); b += (int) ((255 - b) * 0.45);
		return (r << 16) | (g << 8) | b;
	}

	// ================= lo que se le manda a cada tablet =================
	String json(Player p) {
		boolean editor = esEditor(p);
		JsonObject o = new JsonObject();
		o.addProperty("dia", dia);
		o.addProperty("editor", editor);
		JsonArray nov = new JsonArray();
		for (Novedad n : novedades) {
			JsonObject j = new JsonObject();
			j.addProperty("id", n.id()); j.addProperty("dia", n.dia()); j.addProperty("tipo", n.tipo());
			if (n.titulo() != null) j.addProperty("titulo", n.titulo());
			j.addProperty("texto", n.texto());
			nov.add(j);
		}
		o.add("novedades", nov);
		JsonArray cr = new JsonArray();
		for (Crafteo c : crafteos.values()) {
			boolean activo = activos.contains(c.id()), sombra = sombras.contains(c.id());
			if (!activo && !editor && !sombra) continue;               // oculto: ni se manda
			JsonObject j = new JsonObject();
			j.addProperty("id", c.id());
			j.addProperty("activo", activo);
			j.addProperty("sombra", sombra);
			j.addProperty("icono", c.resultado().getKey().toString());
			if (activo || editor) {                                    // la sombra sola no dice nada mas
				j.addProperty("nombre", c.nombre());
				j.addProperty("categoria", c.categoria());
				j.addProperty("dia_crafteo", c.dia());
				j.addProperty("nota", c.nota());
				j.addProperty("cantidad", c.cantidad());
				JsonArray g = new JsonArray();
				for (Material m : c.grilla()) g.add(m == null ? "" : m.getKey().toString());
				j.add("grilla", g);
			}
			cr.add(j);
		}
		o.add("crafteos", cr);
		return GSON.toJson(o);
	}

	void sincronizar(Player p) {
		if (!plugin.tieneElMod(p)) return;
		p.sendPluginMessage(plugin, CANAL_TABLET, json(p).getBytes(StandardCharsets.UTF_8));
	}
	void sincronizarTodos() { for (Player p : Bukkit.getOnlinePlayers()) sincronizar(p); }

	/** Lo que pide la tablet en modo edicion. Solo para editores: si no, se ignora. */
	void accion(Player p, byte[] datos) {
		if (!esEditor(p)) { plugin.getLogger().warning(p.getName() + " intento editar la tablet sin ser editor"); return; }
		JsonObject o;
		try { o = JsonParser.parseString(new String(datos, StandardCharsets.UTF_8)).getAsJsonObject(); } catch (Exception e) { return; }
		String a = o.has("a") ? o.get("a").getAsString() : "";
		List<String> ids = new ArrayList<>();
		if (o.has("ids")) o.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
		switch (a) {
			case "crafteo_activar" -> { var n = activar(ids); plugin.getLogger().info(p.getName() + " activo crafteos: " + n); }
			case "crafteo_ocultar" -> { var n = ocultar(ids); plugin.getLogger().info(p.getName() + " oculto crafteos: " + n); }
			case "crafteo_sombra" -> { for (String id : ids) sombra(id, o.has("v") && o.get("v").getAsBoolean()); }
			default -> {}
		}
	}

	// ================= comandos =================
	/** /tod dia|crafteo|anuncio|novedad|editor ... Devuelve false si no es un comando de la tablet. */
	boolean comando(CommandSender s, String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (!List.of("dia", "crafteo", "anuncio", "novedad", "editor").contains(sub)) return false;
		if (!esEditor(s)) { s.sendMessage(Component.text("Eso es solo para los editores de TOD.", NamedTextColor.RED)); return true; }
		switch (sub) {
			case "dia" -> {
				if (args.length == 1) { s.sendMessage(Component.text("Estamos en el día " + dia + ".", NamedTextColor.GREEN)); return true; }
				int n = args[1].equalsIgnoreCase("siguiente") ? dia + 1 : entero(args[1], dia);
				cambiarDia(n);
				s.sendMessage(Component.text("Ahora es el día " + dia + ".", NamedTextColor.GREEN));
			}
			case "crafteo" -> {
				String que = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "lista";
				List<String> ids = args.length > 2 ? Arrays.asList(args).subList(2, args.length) : List.of();
				switch (que) {
					case "activar" -> s.sendMessage(Component.text("Activados: " + activar(ids), NamedTextColor.GREEN));
					case "ocultar" -> s.sendMessage(Component.text("Ocultos: " + ocultar(ids), NamedTextColor.YELLOW));
					case "sombra" -> {
						if (ids.isEmpty()) return true;
						boolean v = !sombras.contains(ids.get(0));
						s.sendMessage(Component.text(sombra(ids.get(0), v) ? "Sombra de " + ids.get(0) + ": " + (v ? "sí" : "no") : "No existe ese crafteo.", NamedTextColor.GREEN));
					}
					default -> {
						s.sendMessage(Component.text("Crafteos (" + crafteos.size() + "):", NamedTextColor.GREEN));
						for (Crafteo c : crafteos.values())
							s.sendMessage(Component.text(" " + c.id() + " - " + c.nombre() + (activos.contains(c.id()) ? "  [activo]" : "  [oculto]") + (sombras.contains(c.id()) ? " [sombra]" : ""),
								activos.contains(c.id()) ? NamedTextColor.WHITE : NamedTextColor.GRAY));
					}
				}
			}
			case "anuncio" -> {
				Tipo t = args.length > 1 ? Tipo.de(args[1]) : null;
				if (t == null || args.length < 3) {
					s.sendMessage(Component.text("Uso: /tod anuncio <diaria|aviso|cambio|sancion|truco|cuy|mob|dungeon> [TÍTULO |] texto", NamedTextColor.YELLOW));
					return true;
				}
				String resto = String.join(" ", Arrays.asList(args).subList(2, args.length));
				String titulo = null, texto = resto;
				int barra = resto.indexOf('|');
				if (barra >= 0) { titulo = resto.substring(0, barra).trim(); texto = resto.substring(barra + 1).trim(); }
				anunciar(t, titulo, texto);
			}
			case "novedad" -> {
				if (args.length >= 3 && args[1].equalsIgnoreCase("borrar"))
					s.sendMessage(Component.text(borrarNovedad(args[2]) ? "Novedad borrada." : "No existe esa novedad.", NamedTextColor.YELLOW));
				else for (Novedad n : novedades) s.sendMessage(Component.text(" " + n.id() + " · día " + n.dia() + " · " + n.tipo() + " · " + n.texto(), NamedTextColor.GRAY));
			}
			case "editor" -> {
				List<String> lista = new ArrayList<>(plugin.getConfig().getStringList("editores"));
				if (args.length >= 3 && args[1].equalsIgnoreCase("agregar")) { if (lista.stream().noneMatch(n -> n.equalsIgnoreCase(args[2]))) lista.add(args[2]); }
				else if (args.length >= 3 && args[1].equalsIgnoreCase("sacar")) lista.removeIf(n -> n.equalsIgnoreCase(args[2]));
				plugin.getConfig().set("editores", lista); plugin.saveConfig();
				s.sendMessage(Component.text("Editores: " + lista, NamedTextColor.GREEN));
				sincronizarTodos();
			}
		}
		return true;
	}
	private static int entero(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

	List<String> completar(String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2) return switch (sub) {
			case "dia" -> List.of("siguiente");
			case "crafteo" -> List.of("lista", "activar", "ocultar", "sombra");
			case "anuncio" -> Arrays.stream(Tipo.values()).map(t -> t.id).toList();
			case "novedad" -> List.of("borrar");
			case "editor" -> List.of("agregar", "sacar");
			default -> List.of();
		};
		if (args.length >= 3 && sub.equals("crafteo")) return new ArrayList<>(crafteos.keySet());
		return List.of();
	}
}
