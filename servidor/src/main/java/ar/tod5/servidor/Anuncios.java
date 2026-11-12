package ar.tod5.servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Los anuncios guardados (se arman en la tablet, en modo admin) y como salen en el chat.
 *
 * Cada anuncio: tipo (el color de la ruleta), titulo, grupo (un dia, General o Momento truco), si gira la ruleta,
 * y el texto con estilos (pedacitos con color, negrita, cursiva, subrayado, tachado).
 * Los renglones los corta la tablet (con la fuente de verdad) y los manda armados: asi todos los anuncios
 * quedan con el mismo ancho y el chat no los vuelve a cortar.
 *
 * En el chat (todo con un poco de sangria, sin lineas):
 *              (renglon vacio)
 *                [piedra del color]  Titulo:    (negrita, color fuerte)
 *              (renglon vacio)
 *                texto...                       (color claro, o el que se eligio en cada parte)
 *              (renglon vacio)
 * y suena tod5:anuncio.
 *
 * Archivo: plugins/TOD5/datos/anuncios.json
 */
public class Anuncios {
	/** Grupos que no son un dia. */
	static final int GENERAL = 0, MOMENTO_TRUCO = -1;
	/** Ancho de cada renglon en pixeles (igual que en la tablet). */
	static final int ANCHO = 270;
	/** Sangria de cada renglon, para que no quede pegado al borde del chat. */
	static final String MARGEN = "  ";
	private static final Key FUENTE = Key.key("tod5", "simbolos");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Un pedacito de texto con su estilo. color null = el color claro del tipo. */
	record Trozo(String t, Integer color, boolean b, boolean i, boolean u, boolean s) {}
	static final class Anuncio {
		String id, tipo, titulo, crafteo = ""; int grupo; boolean ruleta = true, enviado;   // crafteo: el que se activa al enviarlo
		List<Trozo> texto = new ArrayList<>();
		List<List<Trozo>> lineas = new ArrayList<>();
	}

	private final TOD5Servidor plugin;
	private final Tablero tablero;
	private final Map<String, Anuncio> anuncios = new LinkedHashMap<>();
	private int proximo = 1;
	private final Set<UUID> probando = new HashSet<>();

	Anuncios(TOD5Servidor plugin, Tablero tablero) { this.plugin = plugin; this.tablero = tablero; }

	// ================= cargar / guardar =================
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "anuncios.json"); }

	void cargar() {
		anuncios.clear();
		File f = archivo();
		if (!f.exists()) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			proximo = o.has("proximo") ? o.get("proximo").getAsInt() : 1;
			for (JsonElement e : o.getAsJsonArray("anuncios")) { Anuncio a = deJson(e.getAsJsonObject()); anuncios.put(a.id, a); }
		} catch (Exception e) { plugin.getLogger().severe("No pude leer anuncios.json: " + e.getMessage()); }
		plugin.getLogger().info("Anuncios guardados: " + anuncios.size());
	}

	private void guardar() {
		JsonObject o = new JsonObject();
		o.addProperty("proximo", proximo);
		JsonArray l = new JsonArray();
		for (Anuncio a : anuncios.values()) l.add(aJson(a));
		o.add("anuncios", l);
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (IOException e) { plugin.getLogger().severe("No pude guardar anuncios.json: " + e.getMessage()); }
	}

	static List<Trozo> trozos(JsonArray a) {
		List<Trozo> l = new ArrayList<>();
		if (a == null) return l;
		for (JsonElement e : a) {
			JsonObject t = e.getAsJsonObject();
			l.add(new Trozo(t.get("t").getAsString(), t.has("c") ? Integer.valueOf(t.get("c").getAsInt()) : null,
				bool(t, "b"), bool(t, "i"), bool(t, "u"), bool(t, "s")));
		}
		return l;
	}
	private static boolean bool(JsonObject o, String k) { return o.has(k) && o.get(k).getAsBoolean(); }
	static JsonArray json(List<Trozo> l) {
		JsonArray a = new JsonArray();
		for (Trozo t : l) {
			JsonObject j = new JsonObject();
			j.addProperty("t", t.t());
			if (t.color() != null) j.addProperty("c", t.color());
			if (t.b()) j.addProperty("b", true);
			if (t.i()) j.addProperty("i", true);
			if (t.u()) j.addProperty("u", true);
			if (t.s()) j.addProperty("s", true);
			a.add(j);
		}
		return a;
	}
	private static Anuncio deJson(JsonObject o) {
		Anuncio a = new Anuncio();
		a.id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : null;
		a.tipo = o.has("tipo") ? o.get("tipo").getAsString() : "aviso";
		if (Tablero.Tipo.de(a.tipo) == null) a.tipo = "aviso";
		a.titulo = o.has("titulo") ? o.get("titulo").getAsString() : "";
		a.grupo = o.has("grupo") ? o.get("grupo").getAsInt() : GENERAL;
		a.ruleta = !o.has("ruleta") || o.get("ruleta").getAsBoolean();
		a.enviado = bool(o, "enviado");
		a.crafteo = o.has("crafteo") && !o.get("crafteo").isJsonNull() ? o.get("crafteo").getAsString() : "";
		a.texto = trozos(o.has("texto") ? o.getAsJsonArray("texto") : null);
		if (o.has("lineas")) for (JsonElement e : o.getAsJsonArray("lineas")) a.lineas.add(trozos(e.getAsJsonArray()));
		return a;
	}
	private static JsonObject aJson(Anuncio a) {
		JsonObject o = new JsonObject();
		o.addProperty("id", a.id); o.addProperty("tipo", a.tipo); o.addProperty("titulo", a.titulo);
		o.addProperty("grupo", a.grupo); o.addProperty("ruleta", a.ruleta); o.addProperty("enviado", a.enviado);
		o.addProperty("crafteo", a.crafteo);
		o.add("texto", json(a.texto));
		JsonArray ls = new JsonArray();
		for (List<Trozo> l : a.lineas) ls.add(json(l));
		o.add("lineas", ls);
		return o;
	}

	/** Lo que va en la tablet de un editor. */
	JsonArray paraTablet() {
		JsonArray l = new JsonArray();
		for (Anuncio a : anuncios.values()) l.add(aJson(a));
		return l;
	}

	// ================= acciones desde la tablet =================
	/** Guarda (si no tiene id, es nuevo). Devuelve el id. */
	String guardar(JsonObject o) {
		Anuncio a = deJson(o);
		// el id lo inventa la tablet (asi sabe cual es el que esta editando); si viene vacio, lo pongo yo
		if (a.id == null || a.id.isBlank() || a.id.length() > 40) a.id = "a" + (proximo++);
		Anuncio viejo = anuncios.get(a.id);
		a.enviado = viejo != null && viejo.enviado;   // "enviado" lo decide el server, no la tablet
		if (a.titulo == null || a.titulo.isBlank()) a.titulo = tituloPorDefecto(Tablero.Tipo.de(a.tipo));
		anuncios.put(a.id, a);
		guardar();
		tablero.sincronizarTodos();
		return a.id;
	}
	/** La novedad que deja un anuncio enviado en la tablet de todos lleva este id. */
	static String idNovedad(String idAnuncio) { return "t" + idAnuncio; }

	/** Borrar: se va de la lista y, si estaba enviado, tambien de la tablet de los jugadores. */
	boolean borrar(String id) {
		Anuncio a = anuncios.remove(id);
		if (a == null) return false;
		guardar();
		if (a.enviado) sacarNovedad(a);
		tablero.sincronizarTodos();
		return true;
	}
	/** Retirar: lo saca de la tablet de los jugadores sin avisar a nadie y vuelve a ser borrador. */
	boolean retirar(String id) {
		Anuncio a = anuncios.get(id);
		if (a == null || !a.enviado) return false;
		a.enviado = false;
		guardar();
		sacarNovedad(a);
		if (!a.crafteo.isEmpty()) tablero.crafteos.estado(a.crafteo, "oculto");   // el crafteo que activo, vuelve a quedar oculto
		tablero.sincronizarTodos();
		return true;
	}
	/** Saca la novedad de un anuncio. Los enviados con el plugin 0.15 no tenian el id atado: esos se buscan por titulo y texto. */
	private void sacarNovedad(Anuncio a) {
		if (!tablero.borrarNovedad(idNovedad(a.id))) tablero.borrarNovedadIgual(a.titulo, textoPlano(a));
	}
	/** Solo para el editor: la ruleta y el mensaje le llegan solo a el. */
	void probar(Player p, String id) {
		Anuncio a = anuncios.get(id);
		if (a == null || !probando.add(p.getUniqueId())) return;
		Tablero.Tipo t = Tablero.Tipo.de(a.tipo);
		long espera = 0;
		if (a.ruleta && plugin.tieneElMod(p)) { tablero.girarRuleta(List.of(p), t); espera = Tablero.DURACION_RULETA; }
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			probando.remove(p.getUniqueId());
			if (p.isOnline()) mostrar(List.of(p), t, a.titulo, a.lineas);
		}, espera);
	}
	/** A todos: va a la fila de la ruleta como cualquier anuncio. */
	void enviar(String id) {
		Anuncio a = anuncios.get(id);
		if (a == null) return;
		a.enviado = true;
		guardar();
		tablero.sincronizarTodos();   // en la tablet pasa a "Enviado" en el momento, no al terminar la ruleta
		Tablero.Tipo t = Tablero.Tipo.de(a.tipo);
		tablero.enFila(t, a.ruleta, () -> {
			Anuncio act = anuncios.get(id);   // si lo retiraron o borraron mientras giraba la ruleta, no sale
			if (act == null || !act.enviado) return;
			if (!act.crafteo.isEmpty()) tablero.crafteos.estado(act.crafteo, "activo");   // se activa justo cuando sale el anuncio
			mostrar(Bukkit.getOnlinePlayers(), t, act.titulo, act.lineas);
			tablero.anotarNovedad(t, act.titulo, textoPlano(act), idNovedad(id));
		});
	}

	static String textoPlano(Anuncio a) {
		StringBuilder sb = new StringBuilder();
		for (List<Trozo> l : a.lineas) {
			if (sb.length() > 0) sb.append(' ');
			for (Trozo t : l) sb.append(t.t());
		}
		return sb.toString().replaceAll("\\s+", " ").trim();
	}

	// ================= como sale en el chat =================
	static String tituloPorDefecto(Tablero.Tipo t) {
		if (t == null) return "Aviso";
		return switch (t) {
			case DIARIA -> "Misión diaria"; case AVISO -> "Aviso"; case CAMBIO -> "Cambio de dificultad"; case SANCION -> "Sanción";
			case TRUCO -> "Momento truco"; case CUY -> "Momento cuy"; case MOB -> "Nuevo mob"; case DUNGEON -> "Nueva dungeon";
		};
	}

	/** El mensaje: renglon vacio, la piedra del color y el titulo en negrita, renglon vacio, el texto y otro renglon vacio. Despues suena el anuncio. */
	static void mostrar(Collection<? extends Player> quienes, Tablero.Tipo t, String titulo, List<List<Trozo>> lineas) {
		if (t == null) t = Tablero.Tipo.AVISO;
		TextColor fuerte = TextColor.color(t.color), claro = TextColor.color(Tablero.aclarar(t.color));
		String tit = titulo == null || titulo.isBlank() ? tituloPorDefecto(t) : titulo;
		TextComponent.Builder msg = Component.text()
			.append(Component.text(" ")).append(Component.newline())   // renglon vacio arriba
			.append(Component.text(MARGEN))
			.append(Component.text(String.valueOf((char) (0xE000 + t.gajo))).font(FUENTE).color(NamedTextColor.WHITE))
			.append(Component.text(" "))
			.append(Component.text(tit + ":", fuerte).decorate(TextDecoration.BOLD))
			.append(Component.newline()).append(Component.text(" "));   // renglon vacio entre el titulo y el texto
		for (List<Trozo> linea : lineas) {
			msg.append(Component.newline());
			msg.append(Component.text(MARGEN));
			for (Trozo tr : linea) {
				Component c = Component.text(tr.t(), tr.color() == null ? claro : TextColor.color(tr.color()))
					.decoration(TextDecoration.BOLD, tr.b()).decoration(TextDecoration.ITALIC, tr.i())
					.decoration(TextDecoration.UNDERLINED, tr.u()).decoration(TextDecoration.STRIKETHROUGH, tr.s());
				msg.append(c);
			}
		}
		msg.append(Component.newline()).append(Component.text(" "));   // renglon vacio abajo
		Component m = msg.build();
		for (Player p : quienes) {
			p.sendMessage(m);
			p.playSound(p.getLocation(), "tod5:anuncio", SoundCategory.MASTER, 1f, 1f);
		}
		Bukkit.getConsoleSender().sendMessage(m);
	}

	// ================= cortar renglones en el server (para los avisos que no vienen de la tablet) =================
	private static int letra(char c) {
		switch (c) {
			case ' ': return 4;
			case '!': case '.': case ',': case ':': case ';': case '|': case 'i': case '\'': case '¡': return 2;
			case 'l': case '`': return 3;
			case 'I': case 't': case '[': case ']': case '"': case '(': case ')': case '{': case '}': case '*': case 'í': return 4;
			case 'f': case 'k': case '<': case '>': return 5;
			case '@': case '~': return 7;
			default: return c < 128 || "áéóúñÁÉÓÚÑü¿".indexOf(c) >= 0 ? 6 : 9;
		}
	}
	/** Corta un texto sin estilos en renglones del mismo ancho que los de la tablet. */
	static List<List<Trozo>> cortar(String texto) {
		List<List<Trozo>> out = new ArrayList<>();
		for (String parrafo : texto.split("\n", -1)) {
			StringBuilder linea = new StringBuilder(); int ancho = 0;
			for (String palabra : parrafo.split(" ")) {
				int w = 0; for (char c : palabra.toCharArray()) w += letra(c);
				int espacio = linea.length() > 0 ? 4 : 0;
				if (linea.length() > 0 && ancho + espacio + w > ANCHO) {
					out.add(List.of(new Trozo(linea.toString(), null, false, false, false, false)));
					linea.setLength(0); ancho = 0; espacio = 0;
				}
				if (espacio > 0) linea.append(' ');
				linea.append(palabra); ancho += espacio + w;
			}
			out.add(List.of(new Trozo(linea.toString(), null, false, false, false, false)));
		}
		return out;
	}
}
