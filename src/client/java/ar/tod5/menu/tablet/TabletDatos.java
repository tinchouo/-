package ar.tod5.menu.tablet;

import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Lo que muestra la tablet. Por ahora datos de EJEMPLO; despues los va a mandar el plugin del server. */
public class TabletDatos {
	public record Novedad(int dia, String tipo, String titulo, String texto, boolean nuevo) {}
	public record Mision(String id, String titulo, int hecho, int total, String premio) {}
	public record Crafteo(String nombre, String categoria, int dia, boolean nuevo, ItemStack icono, int cantidad, ItemStack[] grilla, String nota) {}
	public record Miembro(String nombre, boolean conectado) {}
	public record Mensaje(String hora, String quien, String texto) {}
	public record Nota(String titulo, String texto, String autor) {}
	public static class Equipo {
		String nombre; int color; int mensajesNuevos;
		List<Miembro> miembros = new ArrayList<>(); List<Mensaje> chat = new ArrayList<>(); List<Nota> notas = new ArrayList<>();
	}

	int hoy;
	List<Novedad> novedades = new ArrayList<>();
	Map<Integer, Mision> diarias = new HashMap<>();
	List<Mision> opcionales = new ArrayList<>();
	List<Crafteo> crafteos = new ArrayList<>();
	Equipo equipo;   // null = no tiene equipo (la seccion no aparece)

	int novedadesNuevas() { return (int) novedades.stream().filter(Novedad::nuevo).count(); }
	int crafteosNuevos() { return (int) crafteos.stream().filter(Crafteo::nuevo).count(); }
	int diariaParaReclamar(Set<String> reclamadas) { Mision d = diarias.get(hoy); return d != null && d.hecho() >= d.total() && !reclamadas.contains(d.id()) ? 1 : 0; }
	boolean hayNuevoEn(int dia) { return novedades.stream().anyMatch(n -> n.dia() == dia && n.nuevo()); }
	List<Novedad> novedadesDe(int dia, String filtro) { return novedades.stream().filter(n -> n.dia() == dia && (filtro.equals("todo") || n.tipo().equals(filtro))).toList(); }
	List<Crafteo> crafteosDe(String cat) { return crafteos.stream().filter(c -> cat.equals("todos") || c.categoria().equals(cat)).toList(); }

	String[][] resumen(Set<String> reclamadas) {
		Mision d = diarias.get(hoy);
		long cambiosHoy = novedades.stream().filter(n -> n.dia() == hoy && n.tipo().equals("cambio")).count();
		String[] ultima = equipo != null
			? new String[] { "EQUIPO", equipo.miembros.stream().filter(Miembro::conectado).count() + " / " + equipo.miembros.size() + " conectados", equipo.mensajesNuevos + " mensajes nuevos" }
			: new String[] { "CRAFTEOS", crafteosNuevos() + " nuevos", "Mirá los crafteos nuevos" };
		return new String[][] {
			{ "NOVEDADES", novedadesNuevas() + " nuevas", novedades.isEmpty() ? "" : novedades.get(0).titulo() },
			{ "MISIÓN DIARIA", d == null ? "-" : d.hecho() + " / " + d.total(), d == null ? "Hoy no hay" : diariaParaReclamar(reclamadas) > 0 ? "Lista para reclamar" : d.titulo() },
			{ "CAMBIOS DE HOY", cambiosHoy + " cambios", novedades.stream().filter(n -> n.dia() == hoy && n.tipo().equals("cambio")).map(Novedad::titulo).findFirst().orElse("") },
			ultima
		};
	}

	private static ItemStack i(Item it) { return it == null ? ItemStack.EMPTY : new ItemStack(it); }
	private static ItemStack[] g(Item... items) { ItemStack[] r = new ItemStack[9]; for (int k = 0; k < 9; k++) r[k] = k < items.length ? i(items[k]) : ItemStack.EMPTY; return r; }

	public static TabletDatos ejemplo() {
		TabletDatos d = new TabletDatos();
		int h = 7; d.hoy = h;
		d.novedades.add(new Novedad(h, "aviso", "Hoy a las 20:00: evento en el Nether", "Júntense en el portal del spawn. Es para todos.", true));
		d.novedades.add(new Novedad(h, "cambio", "Los creepers dejan veneno al morir", "Una nube de 5 segundos. Alejate o tomá leche.", true));
		d.novedades.add(new Novedad(h, "cambio", "Las noches duran el doble", "Desde hoy y hasta el final del evento. Las camas siguen funcionando, pero solo si están todos los del equipo durmiendo al mismo tiempo.", false));
		d.novedades.add(new Novedad(h - 1, "aviso", "Nuevo crafteo: TODARMOR", "Ya está en Crafteos.", true));
		d.novedades.add(new Novedad(h - 1, "cambio", "Los zombies rompen puertas de hierro", "Cuidado con las bases.", false));
		d.novedades.add(new Novedad(1, "aviso", "¡Bienvenidos a TRUCOFDEATH: Revenants!", "La billetera ya se puede craftear.", false));
		d.diarias.put(h, new Mision("d" + h, "Matá 30 zombies", 30, 30, "+3 monedas"));
		d.diarias.put(h - 1, new Mision("d" + (h - 1), "Conseguí 16 de obsidiana", 16, 16, "+2 monedas"));
		d.opcionales.add(new Mision("o1", "Encontrá el templo escondido del Aether", 0, 1, "Tótem especial"));
		d.opcionales.add(new Mision("o2", "Crafteá un set completo de TODARMOR", 2, 4, "+10 monedas"));
		d.opcionales.add(new Mision("o3", "Sobreviví 3 noches seguidas sin morir", 1, 3, "+5 monedas"));
		d.crafteos.add(new Crafteo("Billetera", "general", 1, false, i(Items.BUNDLE), 1, g(null, Items.STRING, null, Items.LEATHER, Items.GOLD_NUGGET, Items.LEATHER, null, Items.LEATHER, null), "Guarda tus monedas. Podés tener más de una, pero no se apilan."));
		d.crafteos.add(new Crafteo("Pechera TODARMOR", "general", h - 1, true, i(Items.NETHERITE_CHESTPLATE), 1, g(Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.DIAMOND_CHESTPLATE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE, Items.RABBIT_HIDE), "8 pieles de conejo en cada casilla. Admite 2 mejoras."));
		d.crafteos.add(new Crafteo("Brújula del Aether", "aether", 4, false, i(Items.RECOVERY_COMPASS), 1, g(null, Items.DIAMOND, null, Items.DIAMOND, Items.COMPASS, Items.DIAMOND, null, Items.DIAMOND, null), "Ejemplo de un crafteo de dimensión."));
		d.crafteos.add(new Crafteo("Pluma del Aether", "aether", 5, false, i(Items.FEATHER), 4, g(null, null, null, null, Items.FEATHER, null, null, Items.GOLD_NUGGET, null), "Ejemplo."));
		d.crafteos.add(new Crafteo("Llave de obsidiana", "general", 6, false, i(Items.TRIAL_KEY), 1, g(null, Items.OBSIDIAN, null, null, Items.OBSIDIAN, null, null, Items.IRON_INGOT, null), "Ejemplo."));
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
