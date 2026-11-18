package ar.tod5.menu.tablet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Lo que muestra la tablet. En el server de TOD lo manda el plugin (solo lo que ese jugador puede ver).
 * Sin server (un mundo de un jugador, para probar) muestra datos de EJEMPLO.
 */
public class TabletDatos {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	/** titulo puede ser null (se ve solo el texto). "nuevo" = el jugador todavia no la vio. */
	public record Novedad(String id, int dia, String tipo, String titulo, String texto) {
		boolean nuevo() { return !Vistos.visto("n:" + id); }
	}
	/**
	 * Un objetivo de una mision. tipo: tener, fabricar, matar, picar, ir, evento.
	 * clave: el mob ("zombie", "custom:etiqueta"). texto: como lo lee el jugador (si esta vacio se arma solo).
	 * lugar y radio: solo en el modo admin.
	 */
	public record Objetivo(String uid, String tipo, String clave, String texto, int cantidad, int hecho, double radio, ItemStack item, String lugar) {
		boolean cumplido() { return hecho >= cantidad; }
		/** Lo que lee el jugador: "Conseguí 16 × Obsidiana". */
		public net.minecraft.network.chat.Component frase() {
			var c = net.minecraft.network.chat.Component.empty();
			String n = cantidad > 1 ? cantidad + " × " : "";
			boolean propio = texto != null && !texto.isBlank();
			switch (tipo) {
				case "matar" -> {
					c.append("Matá " + n);
					if (propio) c.append(texto);
					else if (clave.startsWith("custom:")) c.append(clave.substring(7));
					else c.append(net.minecraft.network.chat.Component.translatable("entity.minecraft." + clave.replace("minecraft:", "").trim().toLowerCase()));
				}
				case "picar" -> { c.append("Picá " + n); if (propio) c.append(texto); else c.append(item.isEmpty() ? net.minecraft.network.chat.Component.literal("?") : item.getHoverName()); }
				case "ir" -> { String l = propio ? texto.trim() : "un lugar"; c.append(l.toLowerCase().startsWith("el ") ? "Andá al " + l.substring(3) : "Andá a " + l); }
				case "evento" -> c.append(propio ? texto : "Evento");
				default -> { c.append("Conseguí " + n); if (propio) c.append(texto); else c.append(item.isEmpty() ? net.minecraft.network.chat.Component.literal("?") : item.getHoverName()); }
			}
			return c;
		}
	}
	/**
	 * Una mision. clase: diaria u opcional. equipo: el progreso es de todo el equipo.
	 * estado (jugador): en_curso, completa, resto (reclamo y no le entro todo), reclamada, vencida. (admin): oculta, activa.
	 */
	public record Mision(String id, String titulo, String clase, int dia, boolean equipo, String estado, List<Objetivo> objetivos, List<ItemStack> recompensas) {
		boolean diaria() { return "diaria".equals(clase); }
		int hecho() { int n = 0; for (Objetivo o : objetivos) n += Math.min(o.hecho(), o.cantidad()); return n; }
		int total() { int n = 0; for (Objetivo o : objetivos) n += o.cantidad(); return Math.max(1, n); }
		boolean paraReclamar() { return "completa".equals(estado) || "resto".equals(estado); }
	}
	private static Mision ej(String id, String titulo, String clase, int dia, boolean equipo, String estado, String tipo, String clave, Item item, int hecho, int total, List<ItemStack> premios) {
		return new Mision(id, titulo, clase, dia, equipo, estado, List.of(new Objetivo("o", tipo, clave, tipo.equals("evento") || tipo.equals("ir") ? titulo : "", total, hecho, 0, item == null ? ItemStack.EMPTY : new ItemStack(item), "")), premios);
	}
	private static ItemStack it(Item i, int n) { return new ItemStack(i, n); }
	/**
	 * Un crafteo. estado: oculto / sombra / activo. Con sombra (y no activo) el jugador ve solo la silueta negra (sin nombre).
	 * tipo: mesa, horno, fundicion, ahumador, herreria, pociones. entrada: 9 casillas (mesa) o las que use ese tipo.
	 */
	public record Crafteo(String id, String nombre, String categoria, int dia, String estado, String tipo, boolean forma,
	                      ItemStack icono, ItemStack resultado, ItemStack[] entrada, String nota) {
		boolean activo() { return "activo".equals(estado); }
		boolean sombra() { return "sombra".equals(estado); }
		boolean nuevo() { return activo() && !Vistos.visto("c:" + id); }
		boolean soloSombra() { return !activo() && nombre == null; }
		int cantidad() { return resultado == null || resultado.isEmpty() ? 1 : resultado.getCount(); }
	}
	/** Un item propio del evento (para la pestaña Trucofdeath del creativo y para usar en recetas). */
	public record ItemPropio(String id, ItemStack item) {}
	/** Un anuncio guardado (solo lo ven los editores). grupo: un dia, 0 = General, -1 = Momento truco. */
	public record Anuncio(String id, String tipo, String titulo, int grupo, boolean ruleta, boolean enviado, String crafteo, String mision, List<EditorTexto.Letra> texto) {}
	/** Un premio sin reclamar de la bandeja de Recompensas (zonas de monedas, desafios...). */
	public record Paquete(String id, String titulo, List<ItemStack> items) {}
	public record Miembro(String nombre, boolean conectado, String uuid, boolean lider) {
		Miembro(String nombre, boolean conectado) { this(nombre, conectado, "", false); }
	}
	public record Mensaje(String hora, String quien, String texto) {}
	public record Nota(String titulo, String texto, String autor) {}
	public static class Equipo {
		String id = "", colorId = ""; boolean soyLider; List<String> invitables = new ArrayList<>();
		String nombre; int color; int mensajesNuevos;
		List<Miembro> miembros = new ArrayList<>(); List<Mensaje> chat = new ArrayList<>(); List<Nota> notas = new ArrayList<>();
	}

	int hoy;
	boolean editor;      // es editor: ve lo oculto y tiene el modo edicion
	boolean conAnuncios; // el plugin del server sabe guardar anuncios (0.15 o mas nuevo)
	String plugin = ""; // version del plugin del server (vacio = 0.15 o mas viejo)
	/** El plugin del server es por lo menos esta version ("0.16"). */
	public boolean pluginAlMenos(String v) {
		String[] a = this.plugin.split("\\."), b = v.split("\\.");
		for (int i = 0; i < b.length; i++) {
			int x = i < a.length ? num(a[i]) : 0, y = num(b[i]);
			if (x != y) return x > y;
		}
		return true;
	}
	private static int num(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }
	List<Novedad> novedades = new ArrayList<>();
	List<Mision> misiones = new ArrayList<>();        // las que ve el jugador (activas), con su progreso
	List<Mision> misionesAdmin = new ArrayList<>();   // todas, como estan guardadas (solo editores)
	List<Mision> diariasDe(int dia) { return misiones.stream().filter(m -> m.diaria() && m.dia() == dia).toList(); }
	List<Mision> opcionales() { return misiones.stream().filter(m -> !m.diaria()).toList(); }
	int misionesParaReclamar() { return (int) misiones.stream().filter(Mision::paraReclamar).count(); }
	List<Crafteo> crafteos = new ArrayList<>();
	List<ItemPropio> items = new ArrayList<>();
	List<Anuncio> anuncios = new ArrayList<>();
	List<Paquete> recompensas = new ArrayList<>();
	/** Que anuncio activa cada cosa: "c:<crafteo>" / "m:<mision>" -> id del anuncio (solo editores). */
	java.util.Map<String, String> vinculos = new java.util.HashMap<>();
	String vinculo(String clave) { return vinculos.getOrDefault(clave, ""); }
	Anuncio anuncio(String id) { return id == null || id.isEmpty() ? null : anuncios.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null); }
	Equipo equipo;   // null = no tiene equipo (la seccion sale en gris)
	/** Para el panel de equipos del admin. */
	public record Jugador(String uuid, String nombre, String equipo, boolean whitelist, boolean conectado) {}
	public record ColorEquipo(String id, String nombre, int rgb) {}
	List<Equipo> equiposAdmin = new ArrayList<>();
	List<Jugador> jugadores = new ArrayList<>();
	List<ColorEquipo> coloresEquipo = new ArrayList<>();
	boolean whitelistActiva, conEquipos;
	Equipo equipoAdmin(String id) { return equiposAdmin.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null); }
	static Equipo equipoDeJson(JsonObject o) {
		Equipo e = new Equipo();
		e.id = o.get("id").getAsString(); e.nombre = o.get("nombre").getAsString(); e.colorId = o.get("color").getAsString();
		e.color = 0xFF000000 | o.get("rgb").getAsInt();
		e.soyLider = o.has("soyLider") && o.get("soyLider").getAsBoolean();
		for (JsonElement m : o.getAsJsonArray("miembros")) {
			JsonObject j = m.getAsJsonObject();
			e.miembros.add(new Miembro(j.get("nombre").getAsString(), j.get("conectado").getAsBoolean(), j.get("uuid").getAsString(), j.get("lider").getAsBoolean()));
		}
		if (o.has("invitables")) for (JsonElement i : o.getAsJsonArray("invitables")) e.invitables.add(i.getAsString());
		return e;
	}

	int novedadesNuevas() { return (int) novedades.stream().filter(Novedad::nuevo).count(); }
	int crafteosNuevos() { return (int) crafteos.stream().filter(Crafteo::nuevo).count(); }
	boolean esDelServer() { return this == servidor; }
	boolean hayNuevoEn(int dia) { return novedades.stream().anyMatch(n -> n.dia() == dia && n.nuevo()); }
	List<Novedad> novedadesDe(int dia, String filtro) { return novedades.stream().filter(n -> n.dia() == dia && (filtro.equals("todo") || n.tipo().equals(filtro))).toList(); }
	/** Los que ve un jugador: los activos y las sombras, de una categoria ("todos" = todas). */
	List<Crafteo> crafteosDe(String cat) {
		return crafteos.stream()
			.filter(c -> c.activo() || c.sombra())
			.filter(c -> cat.equals("todos") || cat.equals(c.categoria())).toList();
	}
	/** Las categorias que tienen algo para ver (en el orden en que aparecen). */
	List<String> categorias() {
		LinkedHashSet<String> l = new LinkedHashSet<>();
		for (Crafteo c : crafteos) if ((c.activo() || c.sombra()) && c.categoria() != null) l.add(c.categoria());
		return new ArrayList<>(l);
	}

	String[][] resumen() {
		Mision d = diariasDe(hoy).stream().findFirst().orElse(null);
		long cambiosHoy = novedades.stream().filter(n -> n.dia() == hoy && n.tipo().equals("cambio")).count();
		String[] ultima = equipo != null
			? new String[] { "EQUIPO", equipo.miembros.stream().filter(Miembro::conectado).count() + " / " + equipo.miembros.size() + " conectados", equipo.mensajesNuevos + " mensajes nuevos" }
			: new String[] { "CRAFTEOS", crafteosNuevos() + " nuevos", "Mirá los crafteos nuevos" };
		return new String[][] {
			{ "NOVEDADES", novedadesNuevas() + " nuevas", novedades.isEmpty() ? "" : textoCorto(novedades.get(novedades.size() - 1)) },
			{ "MISIÓN DIARIA", d == null ? "-" : d.hecho() + " / " + d.total(), d == null ? "Hoy no hay" : d.paraReclamar() ? "Lista para reclamar" : d.titulo() },
			{ "CAMBIOS DE HOY", cambiosHoy + " cambios", novedades.stream().filter(n -> n.dia() == hoy && n.tipo().equals("cambio")).map(TabletDatos::textoCorto).findFirst().orElse("") },
			ultima
		};
	}

	static String textoCorto(Novedad n) { return n.titulo() != null ? n.titulo() : n.texto(); }

	// ================= lo que manda el server =================
	private static TabletDatos servidor;
	private static TabletDatos ejemplo;

	/** Lo que hay que mostrar: lo del server si estoy en el de TOD; si no, el ejemplo. */
	public static TabletDatos actual() {
		if (servidor != null) return servidor;
		if (ejemplo == null) ejemplo = ejemplo();
		return ejemplo;
	}
	public static void olvidar() { servidor = null; }

	public static void recibir(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			TabletDatos d = new TabletDatos();
			d.hoy = o.get("dia").getAsInt();
			d.editor = o.has("editor") && o.get("editor").getAsBoolean();
			for (JsonElement e : o.getAsJsonArray("novedades")) {
				JsonObject n = e.getAsJsonObject();
				d.novedades.add(new Novedad(n.get("id").getAsString(), n.get("dia").getAsInt(), n.get("tipo").getAsString(),
					n.has("titulo") ? n.get("titulo").getAsString() : null, n.get("texto").getAsString()));
			}
			for (JsonElement e : o.getAsJsonArray("crafteos")) {
				JsonObject c = e.getAsJsonObject();
				ItemStack[] entrada = new ItemStack[9];
				Arrays.fill(entrada, ItemStack.EMPTY);
				if (c.has("entrada")) { var g = c.getAsJsonArray("entrada"); for (int k = 0; k < 9 && k < g.size(); k++) entrada[k] = itemDeJson(g.get(k)); }
				d.crafteos.add(new Crafteo(c.get("id").getAsString(), c.has("nombre") ? c.get("nombre").getAsString() : null,
					c.has("categoria") ? c.get("categoria").getAsString() : null, c.has("dia") ? c.get("dia").getAsInt() : 0,
					c.has("estado") ? c.get("estado").getAsString() : "oculto", c.has("tipo") ? c.get("tipo").getAsString() : "mesa",
					!c.has("forma") || c.get("forma").getAsBoolean(), itemDeJson(c.get("icono")), itemDeJson(c.get("resultado")), entrada,
					c.has("nota") ? c.get("nota").getAsString() : ""));
			}
			if (o.has("items")) for (JsonElement e : o.getAsJsonArray("items")) {
				JsonObject it = e.getAsJsonObject();
				d.items.add(new ItemPropio(it.get("id").getAsString(), itemDeJson(it.get("item"))));
			}
			if (o.has("misiones")) for (JsonElement e : o.getAsJsonArray("misiones")) d.misiones.add(misionDeJson(e.getAsJsonObject()));
			if (o.has("misiones_admin")) for (JsonElement e : o.getAsJsonArray("misiones_admin")) d.misionesAdmin.add(misionDeJson(e.getAsJsonObject()));
			if (o.has("vinculos")) for (var en : o.getAsJsonObject("vinculos").entrySet()) d.vinculos.put(en.getKey(), en.getValue().getAsString());
			d.conAnuncios = o.has("anuncios");
			d.plugin = o.has("plugin") ? o.get("plugin").getAsString() : "";
			if (o.has("anuncios")) for (JsonElement e : o.getAsJsonArray("anuncios")) {
				JsonObject a = e.getAsJsonObject();
				d.anuncios.add(new Anuncio(a.get("id").getAsString(), a.get("tipo").getAsString(), a.has("titulo") ? a.get("titulo").getAsString() : "",
					a.has("grupo") ? a.get("grupo").getAsInt() : 0, !a.has("ruleta") || a.get("ruleta").getAsBoolean(),
					a.has("enviado") && a.get("enviado").getAsBoolean(), a.has("crafteo") ? a.get("crafteo").getAsString() : "", a.has("mision") ? a.get("mision").getAsString() : "",
					EditorTexto.deJson(a.has("texto") ? a.getAsJsonArray("texto") : null)));
			}
			if (o.has("equipo")) d.equipo = equipoDeJson(o.getAsJsonObject("equipo"));
			if (o.has("equipos_admin")) {
				JsonObject ea = o.getAsJsonObject("equipos_admin");
				d.conEquipos = true;
				for (JsonElement e : ea.getAsJsonArray("equipos")) d.equiposAdmin.add(equipoDeJson(e.getAsJsonObject()));
				for (JsonElement e : ea.getAsJsonArray("jugadores")) {
					JsonObject j = e.getAsJsonObject();
					d.jugadores.add(new Jugador(j.get("uuid").getAsString(), j.get("nombre").getAsString(), j.get("equipo").getAsString(), j.get("whitelist").getAsBoolean(), j.get("conectado").getAsBoolean()));
				}
				for (JsonElement e : ea.getAsJsonArray("colores")) {
					JsonObject j = e.getAsJsonObject();
					d.coloresEquipo.add(new ColorEquipo(j.get("id").getAsString(), j.get("nombre").getAsString(), 0xFF000000 | j.get("rgb").getAsInt()));
				}
				d.whitelistActiva = ea.get("whitelistActiva").getAsBoolean();
			}
			servidor = d;
			ar.tod5.menu.ItemsTOD.actualizar(d.items);   // los items propios van a la pestaña del creativo
			LOG.info("TOD5 tablet: datos del server (día {}, {} novedades, {} crafteos, editor: {})", d.hoy, d.novedades.size(), d.crafteos.size(), d.editor);
		} catch (Exception e) {
			LOG.error("TOD5 tablet: no entendi lo que mando el server", e);
		}
	}

	private static Mision misionDeJson(JsonObject m) {
		List<Objetivo> obs = new ArrayList<>();
		for (JsonElement e : m.getAsJsonArray("objetivos")) {
			JsonObject j = e.getAsJsonObject();
			obs.add(new Objetivo(j.get("uid").getAsString(), j.get("tipo").getAsString(), j.has("clave") ? j.get("clave").getAsString() : "",
				j.has("texto") ? j.get("texto").getAsString() : "", j.get("cantidad").getAsInt(), j.has("hecho") ? j.get("hecho").getAsInt() : 0,
				j.has("radio") ? j.get("radio").getAsDouble() : 0, itemDeJson(j.get("item")), j.has("lugar") ? j.get("lugar").getAsString() : ""));
		}
		List<ItemStack> premios = new ArrayList<>();
		if (m.has("recompensas")) for (JsonElement e : m.getAsJsonArray("recompensas")) { ItemStack st = itemDeJson(e); if (!st.isEmpty()) premios.add(st); }
		return new Mision(m.get("id").getAsString(), m.get("nombre").getAsString(), m.get("clase").getAsString(), m.get("dia").getAsInt(),
			m.has("equipo") && m.get("equipo").getAsBoolean(), m.get("estado").getAsString(), obs, premios);
	}

	/** Un item entero como lo manda el plugin (con nombre, encantamientos, dibujo propio, etc.). */
	static ItemStack itemDeJson(JsonElement e) {
		if (e == null || e.isJsonNull()) return ItemStack.EMPTY;
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.getConnection() == null) return ItemStack.EMPTY;
			var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, mc.getConnection().registryAccess());
			return ItemStack.CODEC.parse(ops, e).result().orElse(ItemStack.EMPTY);
		} catch (Exception ex) { return ItemStack.EMPTY; }
	}

	private static ItemStack item(String id) {
		if (id == null || id.isEmpty()) return ItemStack.EMPTY;
		Identifier k = Identifier.tryParse(id);
		if (k == null) return ItemStack.EMPTY;
		return BuiltInRegistries.ITEM.getOptional(k).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	private static ItemStack i(Item it) { return it == null ? ItemStack.EMPTY : new ItemStack(it); }
	private static ItemStack[] g(Item... items) { ItemStack[] r = new ItemStack[9]; for (int k = 0; k < 9; k++) r[k] = k < items.length ? i(items[k]) : ItemStack.EMPTY; return r; }

	public static TabletDatos ejemplo() {
		TabletDatos d = new TabletDatos();
		int h = 7; d.hoy = h;
		d.novedades.add(new Novedad("e1", h, "aviso", "Hoy a las 20:00: evento en el Nether", "Júntense en el portal del spawn. Es para todos."));
		d.novedades.add(new Novedad("e2", h, "cambio", "Los creepers dejan veneno al morir", "Una nube de 5 segundos. Alejate o tomá leche."));
		d.novedades.add(new Novedad("e3", h, "cambio", "Las noches duran el doble", "Desde hoy y hasta el final del evento. Las camas siguen funcionando, pero solo si están todos los del equipo durmiendo al mismo tiempo."));
		d.novedades.add(new Novedad("e4", h - 1, "aviso", "Nuevo crafteo: TODARMOR", "Ya está en Crafteos."));
		d.novedades.add(new Novedad("e5", h - 1, "cambio", "Los zombies rompen puertas de hierro", "Cuidado con las bases."));
		d.novedades.add(new Novedad("e6", 1, "aviso", "¡Bienvenidos a TRUCOFDEATH: Revenants!", "La billetera ya se puede craftear."));
		d.misiones.add(ej("d" + h, "Matá 30 zombies", "diaria", h, false, "completa", "matar", "zombie", null, 30, 30, List.of(it(Items.GOLD_NUGGET, 3), it(Items.BREAD, 16), it(Items.IRON_INGOT, 8), it(Items.EXPERIENCE_BOTTLE, 5))));
		d.misiones.add(ej("d" + (h - 1), "Conseguí 16 de obsidiana", "diaria", h - 1, false, "reclamada", "tener", "", Items.OBSIDIAN, 16, 16, List.of(it(Items.GOLD_NUGGET, 2), it(Items.COOKED_BEEF, 8))));
		d.misiones.add(ej("o1", "el templo escondido del Aether", "opcional", 1, true, "en_curso", "ir", "", null, 0, 1, List.of(it(Items.TOTEM_OF_UNDYING, 1), it(Items.GOLD_NUGGET, 15), it(Items.DIAMOND, 4), it(Items.GOLDEN_APPLE, 2), it(Items.ENDER_PEARL, 8))));
		d.misiones.add(ej("o2", "Juntá diamantes", "opcional", 1, false, "en_curso", "tener", "", Items.DIAMOND, 2, 4, List.of(it(Items.GOLD_NUGGET, 10), it(Items.RABBIT_HIDE, 16), it(Items.LEATHER, 8))));
		Equipo e = new Equipo(); e.nombre = "Rejunte Morado"; e.color = 0xFFAA00AA; e.mensajesNuevos = 2;
		e.miembros.add(new Miembro("tinchooide", true)); e.miembros.add(new Miembro("Jabbeerr", true)); e.miembros.add(new Miembro("rubiklikexd", true));
		e.miembros.add(new Miembro("Lilross01", false)); e.miembros.add(new Miembro("Arielzunder55", false));
		e.chat.add(new Mensaje("19:42", "Jabbeerr", "Encontré una fortaleza, dejé las coords en notas"));
		e.chat.add(new Mensaje("19:43", "rubiklikexd", "Voy, llevo tótems"));
		e.chat.add(new Mensaje("19:45", "tinchooide", "Esperen que termino la misión de obsidiana y voy para allá, que si no después no llego al evento de las 20 y me lo pierdo"));
		e.chat.add(new Mensaje("19:46", "Jabbeerr", "dale"));
		e.notas.add(new Nota("Fortaleza del Nether", "X 412  Y 70  Z -88", "Jabbeerr · día " + (h - 1)));
		e.notas.add(new Nota("Base", "X -1200  Y 64  Z 340. El cofre de tótems es el de la izquierda, no lo vacíen.", "tinchooide · día 2"));
		d.equipo = e;
		return d;
	}
}
