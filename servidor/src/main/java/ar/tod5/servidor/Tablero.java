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
 *   plugins/TOD5/datos/crafteos.json   los crafteos y los items propios (se arman en la tablet, ver Crafteos)
 *   plugins/TOD5/datos/estado.yml      el dia actual
 *   plugins/TOD5/datos/novedades.yml   las novedades publicadas
 */
public class Tablero {
	static final String CANAL_TABLET = "tod5:tablet", CANAL_ACCION = "tod5:accion", CANAL_RULETA = "tod5:ruleta";
	/** Lo que dura la ruleta en el mod (9,3 s): recien despues sale el mensaje en el chat. */
	static final long DURACION_RULETA = 187;
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

	record Novedad(String id, int dia, String tipo, String titulo, String texto) {}

	private final TOD5Servidor plugin;
	private final List<Novedad> novedades = new ArrayList<>();
	private int dia = 1, proximaNovedad = 1;

	final Anuncios anuncios;
	final Crafteos crafteos;

	Tablero(TOD5Servidor plugin) { this.plugin = plugin; this.anuncios = new Anuncios(plugin, this); this.crafteos = new Crafteos(plugin, this); }

	// ================= cargar / guardar =================
	void cargar() {
		YamlConfiguration est = yml("estado");
		dia = est.getInt("dia", 1);
		novedades.clear();
		YamlConfiguration ny = yml("novedades");
		proximaNovedad = ny.getInt("proxima", 1);
		for (Map<?, ?> m : ny.getMapList("novedades"))
			novedades.add(new Novedad(str(m, "id"), Integer.parseInt(str(m, "dia")), str(m, "tipo"), m.get("titulo") == null ? null : str(m, "titulo"), str(m, "texto")));
		crafteos.cargar();
		anuncios.cargar();
		plugin.getLogger().info("Tablet: día " + dia + ", " + novedades.size() + " novedades");
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

	void cambiarDia(int n) { dia = Math.max(1, n); guardarEstado(); sincronizarTodos(); }
	int dia() { return dia; }

	// ---- la ruleta: los anuncios van en fila; cada uno gira la ruleta y recien al terminar sale en el chat ----
	private final ArrayDeque<Runnable> cola = new ArrayDeque<>();
	private boolean girando = false;

	/** La ruleta solo para algunos (los que tienen el mod). */
	void girarRuleta(Collection<? extends Player> quienes, Tipo t) {
		byte[] b = String.valueOf(t.gajo).getBytes(StandardCharsets.UTF_8);
		for (Player p : quienes) if (plugin.tieneElMod(p)) p.sendPluginMessage(plugin, CANAL_RULETA, b);
	}

	/** Un anuncio a todos, en fila: si tiene ruleta, gira y recien al terminar se publica; si no, sale directo. */
	void enFila(Tipo t, boolean ruleta, Runnable publicar) {
		cola.add(() -> {
			if (ruleta) girarRuleta(Bukkit.getOnlinePlayers(), t);
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				publicar.run();
				sincronizarTodos();
				Bukkit.getScheduler().runTaskLater(plugin, this::siguiente, 10);   // medio segundo entre anuncios
			}, ruleta ? DURACION_RULETA : 1);
		});
		if (!girando) siguiente();
	}

	/** Un anuncio de texto simple (comandos, crafteos nuevos): mismo formato que los de la tablet. */
	void anunciar(Tipo t, String titulo, String texto) {
		enFila(t, true, () -> {
			Anuncios.mostrar(Bukkit.getOnlinePlayers(), t, titulo, Anuncios.cortar(texto));
			anotarNovedad(t, titulo, texto);
		});
	}
	private void siguiente() {
		Runnable r = cola.poll();
		girando = r != null;
		if (r != null) r.run();
	}

	/** Los tipos que quedan en la tablet (avisos, cambios, mobs, dungeons) se guardan como novedad del dia. */
	void anotarNovedad(Tipo t, String titulo, String texto) { anotarNovedad(t, titulo, texto, null); }
	/** idFijo: la novedad de un anuncio de la tablet lleva un id atado al anuncio, asi "Retirar" la encuentra. */
	void anotarNovedad(Tipo t, String titulo, String texto, String idFijo) {
		if (!t.enTablet) return;
		String tit = titulo == null || titulo.isBlank() ? null : titulo;
		String id = idFijo != null ? idFijo : "n" + (proximaNovedad++);
		novedades.removeIf(n -> n.id().equals(id));   // si se reenvia, no queda repetida
		novedades.add(new Novedad(id, dia, t.id, tit, texto));
		guardarNovedades();
	}
	boolean borrarNovedad(String id) {
		boolean ok = novedades.removeIf(n -> n.id().equals(id));
		if (ok) { guardarNovedades(); sincronizarTodos(); }
		return ok;
	}
	boolean borrarNovedadIgual(String titulo, String texto) {
		String tit = titulo == null || titulo.isBlank() ? null : titulo;
		boolean ok = novedades.removeIf(n -> Objects.equals(n.titulo(), tit) && Objects.equals(n.texto(), texto));
		if (ok) { guardarNovedades(); sincronizarTodos(); }
		return ok;
	}
	static int aclarar(int c) {
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
		o.addProperty("plugin", plugin.getPluginMeta().getVersion());   // la tablet avisa si el plugin es viejo para algo
		JsonArray nov = new JsonArray();
		for (Novedad n : novedades) {
			JsonObject j = new JsonObject();
			j.addProperty("id", n.id()); j.addProperty("dia", n.dia()); j.addProperty("tipo", n.tipo());
			if (n.titulo() != null) j.addProperty("titulo", n.titulo());
			j.addProperty("texto", n.texto());
			nov.add(j);
		}
		o.add("novedades", nov);
		o.add("crafteos", crafteos.paraTablet(editor));
		if (plugin.recompensas != null) o.add("recompensas", plugin.recompensas.paraTablet(p));   // la bandeja de este jugador
		if (editor) o.add("items", crafteos.itemsParaTablet());   // los items propios (para el creativo de los editores)
		if (editor) o.add("anuncios", anuncios.paraTablet());   // los anuncios guardados: solo los editores
		return GSON.toJson(o);
	}

	void sincronizar(Player p) {
		if (!plugin.tieneElMod(p)) return;
		p.sendPluginMessage(plugin, CANAL_TABLET, json(p).getBytes(StandardCharsets.UTF_8));
	}
	void sincronizarTodos() { for (Player p : Bukkit.getOnlinePlayers()) sincronizar(p); }

	/** Lo que pide la tablet en modo edicion. Solo para editores: si no, se ignora. */
	void accion(Player p, byte[] datos) {
		// la bandeja de recompensas la usa cualquiera (no solo los editores)
		try {
			JsonObject r = JsonParser.parseString(new String(datos, StandardCharsets.UTF_8)).getAsJsonObject();
			String ar = r.has("a") ? r.get("a").getAsString() : "";
			String rid = r.has("ids") && r.getAsJsonArray("ids").size() > 0 ? r.getAsJsonArray("ids").get(0).getAsString() : "";
			if (ar.equals("recompensa_abrir")) { plugin.recompensas.abrir(p, rid); return; }
			if (ar.equals("recompensa_todo")) { plugin.recompensas.reclamarTodo(p, rid); return; }
		} catch (Exception e) { return; }
		if (!esEditor(p)) {
			plugin.getLogger().warning(p.getName() + " intento editar la tablet sin ser editor");
			p.sendMessage(Component.text("La tablet: no se guardó nada porque no sos editor. Desde la consola: tod editor agregar " + p.getName(), NamedTextColor.RED));
			sincronizar(p);   // asi la tablet se entera y sale del modo admin
			return;
		}
		JsonObject o;
		try { o = JsonParser.parseString(new String(datos, StandardCharsets.UTF_8)).getAsJsonObject(); } catch (Exception e) { return; }
		String a = o.has("a") ? o.get("a").getAsString() : "";
		List<String> ids = new ArrayList<>();
		if (o.has("ids")) o.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
		switch (a) {
			case "crafteo_guardar" -> { crafteos.guardarDesdeTablet(o.getAsJsonObject("crafteo")); sincronizarTodos(); }
			case "crafteo_estado" -> {
				String est = o.has("estado") ? o.get("estado").getAsString() : "";
				for (String id : ids) { crafteos.estado(id, est); plugin.getLogger().info(p.getName() + " puso el crafteo " + id + " en " + est); }
				sincronizarTodos();
			}
			case "crafteo_receta" -> { if (!ids.isEmpty()) crafteos.abrirMesa(p, ids.get(0)); }
			case "crafteo_borrar" -> { for (String id : ids) crafteos.borrar(id); sincronizarTodos(); }
			case "crafteo_duplicar" -> { for (String id : ids) crafteos.duplicar(id); sincronizarTodos(); }
			case "item_guardar" -> { crafteos.guardarItem(o.getAsJsonObject("item")); sincronizarTodos(); }
			case "item_mano" -> {
				if (crafteos.itemDeLaMano(p) == null) p.sendMessage(Component.text("Tené en la mano el item que querés guardar.", NamedTextColor.RED));
				else p.sendMessage(Component.text("Item guardado: ya está en la pestaña Trucofdeath del creativo.", NamedTextColor.GREEN));
				sincronizarTodos();
			}
			case "item_borrar" -> { for (String id : ids) crafteos.borrarItem(id); sincronizarTodos(); }
			case "anuncio_guardar" -> anuncios.guardar(o.getAsJsonObject("anuncio"));
			case "anuncio_probar" -> { String id = anuncios.guardar(o.getAsJsonObject("anuncio")); anuncios.probar(p, id); }
			case "anuncio_enviar" -> {
				String id = anuncios.guardar(o.getAsJsonObject("anuncio"));
				anuncios.enviar(id);
				plugin.getLogger().info(p.getName() + " envio el anuncio " + id);
			}
			case "anuncio_borrar" -> { for (String id : ids) anuncios.borrar(id); }
			case "anuncio_retirar" -> {
				for (String id : ids) if (anuncios.retirar(id)) plugin.getLogger().info(p.getName() + " retiro el anuncio " + id);
			}
			default -> {}
		}
	}

	// ================= comandos =================
	/** /tod dia|crafteo|anuncio|novedad|editor ... Devuelve false si no es un comando de la tablet. */
	boolean comando(CommandSender s, String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (!List.of("dia", "crafteo", "anuncio", "novedad", "editor", "moneda", "zona").contains(sub)) return false;
		if (!esEditor(s)) { s.sendMessage(Component.text("Eso es solo para los editores de TOD.", NamedTextColor.RED)); return true; }
		switch (sub) {
			case "dia" -> {
				if (args.length == 1) { s.sendMessage(Component.text("Estamos en el día " + dia + ".", NamedTextColor.GREEN)); return true; }
				int n = args[1].equalsIgnoreCase("siguiente") ? dia + 1 : entero(args[1], dia);
				cambiarDia(n);
				s.sendMessage(Component.text("Ahora es el día " + dia + ".", NamedTextColor.GREEN));
			}
			case "crafteo" -> {
				s.sendMessage(Component.text("Los crafteos se arman en la tablet: modo admin > Crafteos. Hay " + crafteos.recetas.size() + ".", NamedTextColor.YELLOW));
				for (Crafteos.Receta r : crafteos.recetas.values()) s.sendMessage(Component.text(" " + r.id + " · " + r.nombre + " · " + r.tipo + " · " + r.estado, NamedTextColor.GRAY));
			}
			case "anuncio" -> {
				Tipo t = args.length > 1 ? Tipo.de(args[1]) : null;
				if (t == null || args.length < 3) {
					s.sendMessage(Component.text("Uso: /tod anuncio <diaria|aviso|cambio|sancion|truco|cuy|mob|dungeon> [Título |] texto", NamedTextColor.YELLOW));
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
			case "moneda" -> plugin.monedas.comando(s, args);
			case "zona" -> plugin.desafios.comando(s, args);
		}
		return true;
	}
	private static int entero(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

	List<String> completar(String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("zona")) return plugin.desafios.completar(args);
		if (args.length == 2) return switch (sub) {
			case "dia" -> List.of("siguiente");
			case "anuncio" -> Arrays.stream(Tipo.values()).map(t -> t.id).toList();
			case "novedad" -> List.of("borrar");
			case "editor" -> List.of("agregar", "sacar");
			case "moneda" -> Monedas.tipos();
			default -> List.of();
		};
		return List.of();
	}
}
