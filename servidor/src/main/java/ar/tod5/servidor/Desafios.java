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
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Desafios de monedas (provisorio): un desafio es una lista de lugares con monedas verdes 3D.
 * Al empezar, aparecen las monedas girando; el jugador las junta pasando por encima.
 *  - Hasta 10 monedas: cada una suena mas aguda que la anterior (como las rojas del Mario 64).
 *  - Mas de 10: todas suenan igual (como las de Galaxy).
 * El contador sale en pantalla con el mod (canal tod5:desafio: "juntadas;total" o "fin").
 *
 * /tod desafio crear|moneda|sacar|empezar|parar|lista|borrar ...   (solo editores)
 * Archivo: plugins/TOD5/datos/desafios.json
 */
final class Desafios implements Listener {
	static final String CANAL = "tod5:desafio";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Escala para hasta 10 monedas: semitonos (de grave a agudo). */
	private static final int[] ESCALA = { -5, -3, -1, 0, 2, 4, 5, 7, 9, 11 };

	private final TOD5Servidor plugin;
	private final Map<String, List<Location>> desafios = new LinkedHashMap<>();
	private final Map<UUID, Partida> partidas = new HashMap<>();
	private BukkitTask tarea;
	private int tick;

	private static final class Partida {
		String nombre; int total, juntadas;
		final List<ItemDisplay> monedas = new ArrayList<>();
	}

	Desafios(TOD5Servidor plugin) { this.plugin = plugin; }

	// ---------- archivo ----------
	private File archivo() { File d = new File(plugin.getDataFolder(), "datos"); d.mkdirs(); return new File(d, "desafios.json"); }
	void cargar() {
		desafios.clear();
		File f = archivo();
		if (!f.exists()) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String n : o.keySet()) {
				List<Location> l = new ArrayList<>();
				for (JsonElement e : o.getAsJsonArray(n)) {
					JsonObject j = e.getAsJsonObject();
					World w = Bukkit.getWorld(j.get("mundo").getAsString());
					if (w != null) l.add(new Location(w, j.get("x").getAsDouble(), j.get("y").getAsDouble(), j.get("z").getAsDouble()));
				}
				desafios.put(n, l);
			}
		} catch (Exception e) { plugin.getLogger().severe("No pude leer desafios.json: " + e.getMessage()); }
	}
	private void guardar() {
		JsonObject o = new JsonObject();
		desafios.forEach((n, l) -> {
			JsonArray a = new JsonArray();
			for (Location x : l) { JsonObject j = new JsonObject(); j.addProperty("mundo", x.getWorld().getName()); j.addProperty("x", x.getX()); j.addProperty("y", x.getY()); j.addProperty("z", x.getZ()); a.add(j); }
			o.add(n, a);
		});
		try { Files.writeString(archivo().toPath(), GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (Exception e) { plugin.getLogger().severe("No pude guardar desafios.json: " + e.getMessage()); }
	}

	// ---------- la moneda ----------
	private static ItemStack moneda() {
		ItemStack it = new ItemStack(Material.GOLD_NUGGET);
		ItemMeta m = it.getItemMeta();
		m.setItemModel(new NamespacedKey("tod5", "moneda_desafio"));
		it.setItemMeta(m);
		return it;
	}
	private static ItemDisplay aparecer(Location l) {
		Location en = l.clone().add(0, 0.9, 0); en.setYaw(0); en.setPitch(0);
		return l.getWorld().spawn(en, ItemDisplay.class, d -> {
			d.setItemStack(moneda());
			d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
			d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
			d.setBrightness(new Display.Brightness(15, 15));
			d.setGlowing(true);
			d.setGlowColorOverride(Color.fromRGB(0x3AFF6A));
			d.setPersistent(false);
		});
	}

	// ---------- jugar ----------
	void empezar(Player p, String nombre) {
		parar(p, false);
		List<Location> l = desafios.get(nombre);
		if (l == null || l.isEmpty()) return;
		Partida pa = new Partida();
		pa.nombre = nombre; pa.total = l.size();
		for (Location x : l) pa.monedas.add(aparecer(x));
		partidas.put(p.getUniqueId(), pa);
		mandar(p, "0;" + pa.total);
		if (tarea == null) tarea = Bukkit.getScheduler().runTaskTimer(plugin, this::cadaTick, 1, 1);
	}
	void parar(Player p, boolean avisar) {
		Partida pa = partidas.remove(p.getUniqueId());
		if (pa == null) return;
		pa.monedas.forEach(d -> { if (d.isValid()) d.remove(); });
		mandar(p, "nada");
		if (avisar) p.sendMessage(Component.text("Desafío cancelado.", NamedTextColor.GRAY));
	}

	private void cadaTick() {
		tick++;
		if (partidas.isEmpty()) { tarea.cancel(); tarea = null; return; }
		for (Iterator<Map.Entry<UUID, Partida>> it = partidas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Partida> e = it.next();
			Player p = Bukkit.getPlayer(e.getKey());
			Partida pa = e.getValue();
			if (p == null) { pa.monedas.forEach(ItemDisplay::remove); it.remove(); continue; }
			// girar (de a 60 grados cada 4 ticks, con interpolacion: se ve suave)
			if (tick % 4 == 0) {
				float ang = (float) Math.toRadians((tick / 4 * 60) % 360);
				for (ItemDisplay d : pa.monedas) {
					d.setInterpolationDelay(0); d.setInterpolationDuration(4);
					d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(ang, 0, 1, 0), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
				}
			}
			// juntar
			Location ojo = p.getLocation().add(0, 0.9, 0);
			for (Iterator<ItemDisplay> im = pa.monedas.iterator(); im.hasNext(); ) {
				ItemDisplay d = im.next();
				if (!d.getWorld().equals(ojo.getWorld()) || d.getLocation().distanceSquared(ojo) > 1.3 * 1.3) continue;
				d.remove(); im.remove(); pa.juntadas++;
				float tono = pa.total <= ESCALA.length
					? (float) Math.pow(2, ESCALA[ESCALA.length - pa.total + pa.juntadas - 1] / 12.0)
					: 1f;
				p.playSound(p.getLocation(), "tod5:moneda_desafio", SoundCategory.MASTER, 1f, tono);
				mandar(p, pa.juntadas + ";" + pa.total);
			}
			if (pa.monedas.isEmpty()) {
				it.remove();
				p.playSound(p.getLocation(), "tod5:desafio_completo", SoundCategory.MASTER, 1f, 1f);
				p.showTitle(Title.title(Component.text("¡Desafío completo!", NamedTextColor.GREEN), Component.text(pa.total + " de " + pa.total + " monedas", NamedTextColor.GRAY)));
				mandar(p, "fin;" + pa.total);
			}
		}
	}

	private void mandar(Player p, String s) {
		if (plugin.tieneElMod(p)) p.sendPluginMessage(plugin, CANAL, s.getBytes(StandardCharsets.UTF_8));
	}

	@EventHandler
	public void alSalir(PlayerQuitEvent e) { parar(e.getPlayer(), false); }

	void apagar() { for (UUID u : new ArrayList<>(partidas.keySet())) { Player p = Bukkit.getPlayer(u); if (p != null) parar(p, false); } }

	// ---------- comando ----------
	void comando(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		String nombre = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
		Player yo = s instanceof Player pl ? pl : null;
		switch (sub) {
			case "crear" -> {
				if (nombre == null) { uso(s); return; }
				desafios.putIfAbsent(nombre, new ArrayList<>()); guardar();
				s.sendMessage(Component.text("Desafío \"" + nombre + "\" creado. Pará donde va cada moneda y usá /tod desafio moneda " + nombre, NamedTextColor.GREEN));
			}
			case "moneda" -> {
				if (nombre == null || yo == null || !desafios.containsKey(nombre)) { uso(s); return; }
				desafios.get(nombre).add(yo.getLocation().toBlockLocation().add(0.5, 0, 0.5)); guardar();
				ItemDisplay muestra = aparecer(yo.getLocation().toBlockLocation().add(0.5, 0, 0.5));
				Bukkit.getScheduler().runTaskLater(plugin, muestra::remove, 40);
				s.sendMessage(Component.text("Moneda " + desafios.get(nombre).size() + " puesta en \"" + nombre + "\".", NamedTextColor.GREEN));
			}
			case "sacar" -> {
				if (nombre == null || yo == null || !desafios.containsKey(nombre)) { uso(s); return; }
				List<Location> l = desafios.get(nombre);
				Location cerca = l.stream().filter(x -> x.getWorld().equals(yo.getWorld())).min(Comparator.comparingDouble(x -> x.distanceSquared(yo.getLocation()))).orElse(null);
				if (cerca == null || cerca.distanceSquared(yo.getLocation()) > 9) { s.sendMessage(Component.text("No hay ninguna moneda de ese desafío a menos de 3 bloques.", NamedTextColor.RED)); return; }
				l.remove(cerca); guardar();
				s.sendMessage(Component.text("Moneda sacada. Quedan " + l.size() + ".", NamedTextColor.YELLOW));
			}
			case "empezar" -> {
				Player p = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : yo;
				if (nombre == null || p == null || !desafios.containsKey(nombre) || desafios.get(nombre).isEmpty()) { s.sendMessage(Component.text("Ese desafío no existe o no tiene monedas.", NamedTextColor.RED)); return; }
				empezar(p, nombre);
				s.sendMessage(Component.text("Desafío \"" + nombre + "\" empezado para " + p.getName() + " (" + desafios.get(nombre).size() + " monedas).", NamedTextColor.GREEN));
			}
			case "parar" -> { Player p = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : yo; if (p != null) parar(p, true); }
			case "lista" -> {
				if (desafios.isEmpty()) s.sendMessage(Component.text("No hay desafíos.", NamedTextColor.GRAY));
				desafios.forEach((n, l) -> s.sendMessage(Component.text(" " + n + " · " + l.size() + " monedas", NamedTextColor.GRAY)));
			}
			case "borrar" -> {
				if (nombre == null || desafios.remove(nombre) == null) { uso(s); return; }
				guardar(); s.sendMessage(Component.text("Desafío \"" + nombre + "\" borrado.", NamedTextColor.YELLOW));
			}
			default -> uso(s);
		}
	}
	private static void uso(CommandSender s) {
		s.sendMessage(Component.text("/tod desafio crear <nombre> · moneda <nombre> · sacar <nombre> · empezar <nombre> [jugador] · parar [jugador] · lista · borrar <nombre>", NamedTextColor.YELLOW));
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("crear", "moneda", "sacar", "empezar", "parar", "lista", "borrar");
		if (args.length == 3 && !args[1].equalsIgnoreCase("crear") && !args[1].equalsIgnoreCase("parar")) return new ArrayList<>(desafios.keySet());
		return List.of();
	}
}
