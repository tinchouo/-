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
	public record Mision(String id, String titulo, int hecho, int total, List<ItemStack> recompensas) {}
	private static ItemStack it(Item i, int n) { return new ItemStack(i, n); }
	/** activo = ya salio. Si no salio y tiene sombra, el jugador ve solo la silueta negra (sin nombre). */
	public record Crafteo(String id, String nombre, String categoria, int dia, boolean activo, boolean sombra, ItemStack icono, int cantidad, ItemStack[] grilla, String nota) {
		boolean nuevo() { return activo && !Vistos.visto("c:" + id); }
		boolean soloSombra() { return !activo && nombre == null; }
	}
	/** Un anuncio guardado (solo lo ven los editores). grupo: un dia, 0 = General, -1 = Momento truco. */
	public record Anuncio(String id, String tipo, String titulo, int grupo, boolean ruleta, boolean enviado, List<EditorTexto.Letra> texto) {}
	public record Miembro(String nombre, boolean conectado) {}
	public record Mensaje(String hora, String quien, String texto) {}
	public record Nota(String titulo, String texto, String autor) {}
	public static class Equipo {
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
	Map<Integer, Mision> diarias = new HashMap<>();
	List<Mision> opcionales = new ArrayList<>();
	List<Crafteo> crafteos = new ArrayList<>();
	List<Anuncio> anuncios = new ArrayList<>();
	Equipo equipo;   // null = no tiene equipo (la seccion no aparece)

	int novedadesNuevas() { return (int) novedades.stream().filter(Novedad::nuevo).count(); }
	int crafteosNuevos() { return (int) crafteos.stream().filter(Crafteo::nuevo).count(); }
	boolean esDelServer() { return this == servidor; }
	int diariaParaReclamar(Set<String> reclamadas) { Mision d = diarias.get(hoy); return d != null && d.hecho() >= d.total() && !reclamadas.contains(d.id()) ? 1 : 0; }
	boolean hayNuevoEn(int dia) { return novedades.stream().anyMatch(n -> n.dia() == dia && n.nuevo()); }
	List<Novedad> novedadesDe(int dia, String filtro) { return novedades.stream().filter(n -> n.dia() == dia && (filtro.equals("todo") || n.tipo().equals(filtro))).toList(); }
	/** Los que se ven en la grilla. Editando: todos. Si no: los activos y las sombras. */
	List<Crafteo> crafteosDe(String cat, boolean editando) {
		return crafteos.stream()
			.filter(c -> editando || c.activo() || c.sombra())
			.filter(c -> cat.equals("todos") || c.categoria() == null || c.categoria().equals(cat)).toList();
	}

	String[][] resumen(Set<String> reclamadas) {
		Mision d = diarias.get(hoy);
		long cambiosHoy = novedades.stream().filter(n -> n.dia() == hoy && n.tipo().equals("cambio")).count();
		String[] ultima = equipo != null
			? new String[] { "EQUIPO", equipo.miembros.stream().filter(Miembro::conectado).count() + " / " + equipo.miembros.size() + " conectados", equipo.mensajesNuevos + " mensajes nuevos" }
			: new String[] { "CRAFTEOS", crafteosNuevos() + " nuevos", "Mirá los crafteos nuevos" };
		return new String[][] {
			{ "NOVEDADES", novedadesNuevas() + " nuevas", novedades.isEmpty() ? "" : textoCorto(novedades.get(novedades.size() - 1)) },
			{ "MISIÓN DIARIA", d == null ? "-" : d.hecho() + " / " + d.total(), d == null ? "Hoy no hay" : diariaParaReclamar(reclamadas) > 0 ? "Lista para reclamar" : d.titulo() },
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
				ItemStack[] grilla = new ItemStack[9];
				Arrays.fill(grilla, ItemStack.EMPTY);
				if (c.has("grilla")) { var g = c.getAsJsonArray("grilla"); for (int k = 0; k < 9 && k < g.size(); k++) grilla[k] = item(g.get(k).getAsString()); }
				d.crafteos.add(new Crafteo(c.get("id").getAsString(), c.has("nombre") ? c.get("nombre").getAsString() : null,
					c.has("categoria") ? c.get("categoria").getAsString() : null, c.has("dia_crafteo") ? c.get("dia_crafteo").getAsInt() : 0,
					c.get("activo").getAsBoolean(), c.get("sombra").getAsBoolean(), item(c.get("icono").getAsString()),
					c.has("cantidad") ? c.get("cantidad").getAsInt() : 1, grilla, c.has("nota") ? c.get("nota").getAsString() : ""));
			}
			d.conAnuncios = o.has("anuncios");
			d.plugin = o.has("plugin") ? o.get("plugin").getAsString() : "";
			if (o.has("anuncios")) for (JsonElement e : o.getAsJsonArray("anuncios")) {
				JsonObject a = e.getAsJsonObject();
				d.anuncios.add(new Anuncio(a.get("id").getAsString(), a.get("tipo").getAsString(), a.has("titulo") ? a.get("titulo").getAsString() : "",
					a.has("grupo") ? a.get("grupo").getAsInt() : 0, !a.has("ruleta") || a.get("ruleta").getAsBoolean(),
					a.has("enviado") && a.get("enviado").getAsBoolean(), EditorTexto.deJson(a.has("texto") ? a.getAsJsonArray("texto") : null)));
			}
			servidor = d;
			LOG.info("TOD5 tablet: datos del server (día {}, {} novedades, {} crafteos, editor: {})", d.hoy, d.novedades.size(), d.crafteos.size(), d.editor);
		} catch (Exception e) {
			LOG.error("TOD5 tablet: no entendi lo que mando el server", e);
		}
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
		d.diarias.put(h, new Mision("d" + h, "Matá 30 zombies", 30, 30, List.of(it(Items.GOLD_NUGGET, 3), it(Items.BREAD, 16), it(Items.IRON_INGOT, 8), it(Items.EXPERIENCE_BOTTLE, 5))));
		d.diarias.put(h - 1, new Mision("d" + (h - 1), "Conseguí 16 de obsidiana", 16, 16, List.of(it(Items.GOLD_NUGGET, 2), it(Items.COOKED_BEEF, 8))));
		d.opcionales.add(new Mision("o1", "Encontrá el templo escondido del Aether", 0, 1, List.of(it(Items.TOTEM_OF_UNDYING, 1), it(Items.GOLD_NUGGET, 15), it(Items.DIAMOND, 4), it(Items.GOLDEN_APPLE, 2), it(Items.ENDER_PEARL, 8), it(Items.OBSIDIAN, 32), it(Items.COBBLED_DEEPSLATE, 64), it(Items.OAK_LOG, 64), it(Items.TORCH, 64), it(Items.ARROW, 32), it(Items.EMERALD, 12))));
		d.opcionales.add(new Mision("o2", "Crafteá un set completo de TODARMOR", 2, 4, List.of(it(Items.GOLD_NUGGET, 10), it(Items.RABBIT_HIDE, 16), it(Items.LEATHER, 8))));
		d.opcionales.add(new Mision("o3", "Sobreviví 3 noches seguidas sin morir", 1, 3, List.of(it(Items.GOLD_NUGGET, 5), it(Items.COOKED_PORKCHOP, 12), it(Items.CAMPFIRE, 2))));
		d.crafteos.add(new Crafteo("billetera", "Billetera", "general", 1, true, false, i(Items.BUNDLE), 1, g(null, Items.STRING, null, Items.LEATHER, Items.GOLD_NUGGET, Items.LEATHER, null, Items.LEATHER, null), "Guarda tus monedas. Podés tener más de una, pero no se apilan."));
		d.crafteos.add(new Crafteo("todarmor", "Pechera TODARMOR", "general", h - 1, true, false, i(Items.NETHERITE_CHESTPLATE), 1, g(Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.DIAMOND_CHESTPLATE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE), "8 pieles de conejo en cada casilla. Admite 2 mejoras."));
		d.crafteos.add(new Crafteo("brujula", "Brújula del Aether", "aether", 4, true, false, i(Items.RECOVERY_COMPASS), 1, g(null, Items.DIAMOND, null, Items.DIAMOND, Items.COMPASS, Items.DIAMOND, null, Items.DIAMOND, null), "Ejemplo de un crafteo de dimensión."));
		d.crafteos.add(new Crafteo("pluma", "Pluma del Aether", "aether", 5, true, false, i(Items.FEATHER), 4, g(null, null, null, null, Items.FEATHER, null, null, Items.GOLD_NUGGET, null), "Ejemplo."));
		d.crafteos.add(new Crafteo("llave", "Llave de obsidiana", "general", 6, true, false, i(Items.TRIAL_KEY), 1, g(null, Items.OBSIDIAN, null, null, Items.OBSIDIAN, null, null, Items.IRON_INGOT, null), "Ejemplo."));
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
