package ar.tod5.servidor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.block.Biome;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * El Purgatorio: una dimension aparte (un mundo vacio) con islas de roca flotando, un edificio tragado por la piedra,
 * una sala con una palanca que te cambia la gravedad (usa el sistema de Gravedad) y un ascensor que te saca.
 * Caer al vacio = morir (y perder las cosas). Lo visual (estatica, texto corrupto) lo hace el mod.
 * Todavia no tiene un proposito: se entra con /tod purgatorio [jugador], se sale con el ascensor o /tod purgatorio salir.
 * La roca es pizarra reforzada (el mod le pone la textura verde con aristas). Se arma solo la primera vez; /tod purgatorio regenerar lo rehace.
 */
final class Purgatorio implements Listener {
	static final String MUNDO = "tod5_purgatorio";
	static final String CANAL = "tod5:sistema";
	private static final int Y = 100;   // altura de referencia

	private final TOD5Servidor plugin;
	private World mundo;
	private final Map<UUID, Location> volver = new HashMap<>();
	private final Map<UUID, String> zonaDe = new HashMap<>();
	private final List<BlockDisplay> lejanos = new ArrayList<>();
	private Location palanca, boton;
	private float giro;

	Purgatorio(TOD5Servidor plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskLater(plugin, this::cargar, 40L);
	}

	// ================= el mundo =================
	private static final class Vacio extends ChunkGenerator { }
	private static final class Bioma extends BiomeProvider {
		@Override public Biome getBiome(WorldInfo w, int x, int y, int z) { return Biome.THE_VOID; }
		@Override public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.THE_VOID); }
	}
	private void cargar() {
		mundo = new WorldCreator(MUNDO).generator(new Vacio()).biomeProvider(new Bioma()).generateStructures(false).createWorld();
		if (mundo == null) { plugin.getLogger().warning("No se pudo crear el Purgatorio"); return; }
		mundo.setStorm(false);
		mundo.setTime(18000);
		palanca = new Location(mundo, 0, Y + 3, -75);
		boton = new Location(mundo, 23, Y + 6, -71);
		File hecho = new File(mundo.getWorldFolder(), "tod5_armado");
		if (!hecho.exists()) { armar(); try { hecho.createNewFile(); } catch (Exception ignorado) { } }
		else juntarLejanos();
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
	}
	boolean es(World w) { return w != null && w.getName().equals(MUNDO); }

	// ================= armar todo =================
	private final Random r = new Random(1124);
	private static final Material ROCA = Material.REINFORCED_DEEPSLATE;
	private static final Material[] CEMENTO = { Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS };
	private Material cemento() { return CEMENTO[r.nextInt(CEMENTO.length)]; }
	private void poner(int x, int y, int z, Material m) { mundo.getBlockAt(x, y, z).setType(m, false); }
	private void llenar(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) poner(x, y, z, m);
	}
	private void llenarCemento(int x0, int y0, int z0, int x1, int y1, int z1) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) poner(x, y, z, cemento());
	}
	/** Una sala: paredes, piso y techo de cemento y el adentro vacio. */
	private void sala(int x0, int y0, int z0, int x1, int y1, int z1) {
		llenarCemento(x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1);
		llenar(x0, y0, z0, x1, y1, z1, Material.AIR);
	}
	/** Isla flotante: arriba plana (arriba = la altura del piso), abajo en punta, bordes irregulares. */
	private void isla(int cx, int arriba, int cz, int w, int d, int prof) {
		double rx = w / 2.0, rz = d / 2.0;
		for (int x = (int) Math.floor(cx - rx - 1); x <= cx + rx + 1; x++) for (int z = (int) Math.floor(cz - rz - 1); z <= cz + rz + 1; z++) {
			double nx = (x - cx) / rx, nz = (z - cz) / rz, dist = Math.max(Math.abs(nx), Math.abs(nz)) * 0.6 + Math.hypot(nx, nz) * 0.4;
			if (dist > 1.0 + r.nextDouble() * 0.12) continue;
			int hondo = (int) Math.round(prof * Math.max(0.15, 1 - dist) + r.nextInt(2));
			for (int y = arriba; y > arriba - hondo; y--) poner(x, y, z, ROCA);
		}
	}
	private void cadena(int x, int z, int y0, int y1) {
		Material c = Material.matchMaterial("iron_chain"); if (c == null) c = Material.matchMaterial("chain");
		if (c == null) return;
		for (int y = y0; y <= y1; y++) poner(x, y, z, c);
	}
	private void texto(double x, double y, double z, float yaw, String t, NamedTextColor color, float esc) {
		mundo.spawn(new Location(mundo, x, y, z, yaw, 0), TextDisplay.class, e -> {
			e.text(Component.text(t, color));
			e.setBillboard(Display.Billboard.FIXED);
			e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
			e.setShadowed(true);
			e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(esc, esc, esc), new Quaternionf()));
			e.addScoreboardTag("tod5_purgatorio");
		});
	}

	void armar() {
		for (Entity e : mundo.getEntities()) if (e.getScoreboardTags().contains("tod5_purgatorio")) e.remove();
		lejanos.clear();
		// limpiar la zona
		llenar(-45, Y - 45, -100, 45, Y + 30, 25, Material.AIR);

		// ---- afuera: isla de inicio, escalones, un bloque colgado de una cadena ----
		isla(0, Y, 0, 16, 16, 14);
		isla(3, Y, -11, 3, 3, 4);
		isla(-1, Y + 1, -15, 3, 3, 4);
		llenar(1, Y + 1, -21, 3, Y + 2, -20, Material.POLISHED_BLACKSTONE);   // bloque colgado (arriba = Y+2)
		cadena(2, -20, Y + 3, Y + 40);
		isla(0, Y + 2, -26, 3, 3, 5);
		isla(0, Y + 1, -34, 16, 12, 18);
		for (int i = 0; i < 6; i++) {   // otros bloques colgados, lejos
			int x = (r.nextBoolean() ? 1 : -1) * (18 + r.nextInt(20)), z = -r.nextInt(90) + 10, y = Y - 5 + r.nextInt(18);
			int s = 2 + r.nextInt(3);
			llenar(x, y, z, x + s, y + s, z + s, Material.POLISHED_BLACKSTONE);
			cadena(x + s / 2, z + s / 2, y + s + 1, y + s + 45);
		}

		// ---- la roca que envuelve el edificio ----
		for (int x = -12; x <= 30; x++) for (int y = Y - 6; y <= Y + 14; y++) for (int z = -80; z <= -38; z++) poner(x, y, z, ROCA);
		for (int k = 0; k < 26; k++) {   // abajo en punta
			int y = Y - 7 - k; double f = 1 - k / 26.0;
			for (int x = -12; x <= 30; x++) for (int z = -80; z <= -38; z++) {
				double nx = (x - 9) / 21.0, nz = (z + 59) / 21.0;
				if (Math.hypot(nx, nz) < f * (0.95 + r.nextDouble() * 0.1)) poner(x, y, z, ROCA);
			}
		}
		for (int i = 0; i < 20; i++) {   // estalactitas en el frente
			int x = -12 + r.nextInt(43), largo = 2 + r.nextInt(7);
			for (int y = 0; y < largo; y++) poner(x, Y + 13 - y, -37, ROCA);
		}

		// ---- adentro ----
		// pasillo (z -40 a -51), sala 1 (z -53 a -65), sala de la palanca (z -67 a -75), tunel (x 6 a 14), sala del ascensor (x 16 a 26)
		sala(-2, Y + 2, -51, 2, Y + 5, -40);
		sala(-8, Y + 2, -65, 8, Y + 8, -53);
		sala(-4, Y + 2, -75, 4, Y + 10, -67);
		sala(6, Y + 7, -72, 14, Y + 9, -70);
		sala(16, Y + 5, -75, 26, Y + 11, -67);
		// puertas y huecos
		llenar(-2, Y + 2, -39, 2, Y + 5, -37, Material.AIR);    // entrada desde afuera
		llenar(-2, Y + 2, -52, 2, Y + 5, -52, Material.AIR);    // pasillo -> sala 1
		llenar(-2, Y + 2, -66, 2, Y + 5, -66, Material.AIR);    // sala 1 -> sala de la palanca
		llenar(5, Y + 7, -72, 5, Y + 9, -70, Material.AIR);     // la puerta "imposible" (arriba, en la pared derecha)
		llenar(15, Y + 7, -72, 15, Y + 9, -70, Material.AIR);   // tunel -> sala del ascensor
		llenar(-12, Y + 4, -62, -9, Y + 6, -56, Material.AIR);  // ventana al vacio en la sala 1
		// adentro: caños, escombros, luz tenue
		for (int z = -51; z <= -40; z++) { poner(-1, Y + 5, z, Material.IRON_BARS); poner(1, Y + 5, z, Material.IRON_BARS); }
		for (int i = 0; i < 9; i++) poner(-7 + r.nextInt(15), Y + 2, -64 + r.nextInt(11), r.nextBoolean() ? Material.COBBLED_DEEPSLATE_SLAB : Material.COBBLED_DEEPSLATE);
		poner(0, Y + 8, -59, Material.SOUL_LANTERN);
		poner(0, Y + 10, -71, Material.SOUL_LANTERN);
		// la palanca (en la pared del fondo de su sala)
		Block pl = mundo.getBlockAt(palanca); pl.setType(Material.LEVER, false);
		if (pl.getBlockData() instanceof Switch s) { s.setAttachedFace(FaceAttachable.AttachedFace.WALL); s.setFacing(BlockFace.SOUTH); s.setPowered(false); pl.setBlockData(s, false); }
		// el ascensor: un tubo de vidrio verde con luz, y un boton adentro
		for (int x = 22; x <= 24; x++) for (int z = -72; z <= -70; z++) for (int y = Y + 5; y <= Y + 8; y++) {
			boolean centro = x == 23 && z == -71, puerta = x == 22 && z == -71 && y <= Y + 6;
			if (!centro && !puerta) poner(x, y, z, Material.GREEN_STAINED_GLASS);
		}
		llenar(22, Y + 4, -72, 24, Y + 4, -70, Material.SEA_LANTERN);
		llenar(22, Y + 9, -72, 24, Y + 9, -70, Material.SEA_LANTERN);
		poner(24, Y + 6, -71, Material.POLISHED_BLACKSTONE);   // el panel del boton (adentro del tubo)
		Block bt = mundo.getBlockAt(23, Y + 6, -71); bt.setType(Material.STONE_BUTTON, false);
		if (bt.getBlockData() instanceof Switch s) { s.setAttachedFace(FaceAttachable.AttachedFace.WALL); s.setFacing(BlockFace.WEST); bt.setBlockData(s, false); }

		// ---- lo escrito en las paredes ----
		texto(8.9, Y + 5, -57, 90, "NO VAMOS\nA VOLVER", NamedTextColor.WHITE, 2.2f);
		texto(0, Y + 7, -65.1, 0, "NOS ABANDONARON", NamedTextColor.DARK_RED, 2.4f);
		texto(2.9, Y + 4, -45, 90, "TOD3 · TOD4 · ¿TOD5?", NamedTextColor.GRAY, 1.2f);
		texto(-2, Y + 4, -74.9, 0, "AVISO\nNO TOCAR\nLA PALANCA", NamedTextColor.GRAY, 1.0f);
		texto(19, Y + 8, -66.1, 180, "SALIDA", NamedTextColor.GREEN, 1.6f);

		// ---- cubos lejanos que giran (entidades, no bloques: se mueven solos y no cuestan casi nada) ----
		for (int i = 0; i < 40; i++) {
			double a = r.nextDouble() * Math.PI * 2, dist = 55 + r.nextDouble() * 110;
			Location l = new Location(mundo, Math.cos(a) * dist, Y - 30 + r.nextInt(70), -40 + Math.sin(a) * dist);
			float s = 1.5f + r.nextFloat() * 6;
			BlockDisplay b = mundo.spawn(l, BlockDisplay.class, e -> {
				e.setBlock((r.nextBoolean() ? ROCA : Material.POLISHED_BLACKSTONE).createBlockData());
				e.setViewRange(6f);
				e.addScoreboardTag("tod5_purgatorio");
				e.addScoreboardTag("tod5_lejano");
				e.setTransformation(girado(s, r.nextFloat() * 6));
			});
			lejanos.add(b);
		}
	}
	private void juntarLejanos() {
		lejanos.clear();
		for (BlockDisplay b : mundo.getEntitiesByClass(BlockDisplay.class)) if (b.getScoreboardTags().contains("tod5_lejano")) lejanos.add(b);
	}
	private static Transformation girado(float s, float ang) {
		Quaternionf q = new Quaternionf().rotateXYZ(ang * 0.7f, ang, ang * 0.4f);
		Vector3f centro = q.transform(new Vector3f(s / 2, s / 2, s / 2)).negate();
		return new Transformation(centro, q, new Vector3f(s, s, s), new Quaternionf());
	}

	// ================= cada 2 segundos: la noche fija, los cubos giran =================
	private void tick() {
		if (mundo == null) return;
		mundo.setTime(18000);
		if (mundo.getPlayers().isEmpty()) return;
		giro += 0.35f;
		for (BlockDisplay b : lejanos) {
			if (!b.isValid()) continue;
			float s = b.getTransformation().getScale().x;
			b.setInterpolationDelay(0);
			b.setInterpolationDuration(40);
			b.setTransformation(girado(s, giro + (b.getEntityId() % 7)));
		}
	}

	// ================= entrar y salir =================
	void entrar(Player p) {
		if (mundo == null) return;
		if (!es(p.getWorld())) volver.put(p.getUniqueId(), p.getLocation());
		plugin.gravedad.poner(p, Gravedad.Dir.ABAJO);
		p.teleport(new Location(mundo, 0.5, Y + 1, 4.5, 180, 0));
		zonaDe.remove(p.getUniqueId());
		Bukkit.getScheduler().runTaskLater(plugin, () -> sistema(p, p.getName().toUpperCase() + ".ACCESO[PURGATORIO]\nCONCEDER · PERMITIR\nABSORBER"), 20L);
	}
	void salir(Player p) {
		plugin.gravedad.poner(p, Gravedad.Dir.ABAJO);
		Location l = volver.remove(p.getUniqueId());
		if (l == null || l.getWorld() == null) l = Bukkit.getWorlds().get(0).getSpawnLocation();
		p.teleport(l);
		sistema(p, "SALIDA.CONFIRMADA");
	}
	/** Le manda al mod un texto "del sistema" (rojo, corrupto, en el medio de la pantalla). */
	void sistema(Player p, String txt) { p.sendPluginMessage(plugin, CANAL, txt.getBytes(StandardCharsets.UTF_8)); }

	void comando(CommandSender s, String[] a) {
		String sub = a.length >= 2 ? a[1].toLowerCase() : "entrar";
		if (sub.equals("regenerar")) { if (mundo == null) return; armar(); s.sendMessage(Component.text("Purgatorio rehecho.", NamedTextColor.GREEN)); return; }
		if (sub.equals("salir")) {
			Player p = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pp ? pp : null);
			if (p != null) salir(p);
			return;
		}
		String nombre = sub.equals("entrar") ? (a.length >= 3 ? a[2] : null) : a[1];
		Player p = nombre != null ? Bukkit.getPlayerExact(nombre) : (s instanceof Player pp ? pp : null);
		if (p == null) { s.sendMessage(Component.text("Uso: /tod purgatorio [entrar|salir|regenerar] [jugador]", NamedTextColor.GRAY)); return; }
		entrar(p);
	}

	// ================= lo que pasa adentro =================
	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !es(e.getPlayer().getWorld())) return;
		Location l = e.getClickedBlock().getLocation();
		Player p = e.getPlayer();
		if (l.equals(palanca)) {
			Bukkit.getScheduler().runTask(plugin, () -> {
				plugin.gravedad.poner(p, Gravedad.Dir.ESTE);
				sistema(p, "GRAVEDAD.REESCRIBIR()\nORIENTACION ▸ 90°\nNO TE RESISTAS");
			});
		} else if (l.equals(boton)) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> salir(p), 10L);
		}
	}
	@EventHandler
	public void alMoverse(PlayerMoveEvent e) {
		Player p = e.getPlayer();
		if (!es(p.getWorld()) || e.getTo().getBlockY() == e.getFrom().getBlockY() && e.getTo().getBlockX() == e.getFrom().getBlockX() && e.getTo().getBlockZ() == e.getFrom().getBlockZ()) return;
		Location t = e.getTo();
		if (t.getY() < Y - 60) { p.setHealth(0); return; }   // el vacio
		String zona = zona(t);
		String antes = zonaDe.put(p.getUniqueId(), zona);
		if (zona.equals(antes)) return;
		switch (zona) {
			case "edificio" -> sistema(p, "NO DEBERIAS ESTAR ACA\n…\nPERO YA ESTAS");
			case "palanca" -> sistema(p, "HAY UNA PUERTA\nDONDE NO SE PUEDE LLEGAR");
			case "ascensor" -> {
				if (plugin.gravedad.de(p) != Gravedad.Dir.ABAJO) plugin.gravedad.poner(p, Gravedad.Dir.ABAJO);
				sistema(p, "ASCENSOR.ENCONTRADO\nSALIDA ▸ DISPONIBLE");
			}
			default -> { }
		}
	}
	private static String zona(Location t) {
		double x = t.getX(), y = t.getY(), z = t.getZ();
		if (x >= 16 && x <= 27 && z <= -66 && z >= -76 && y >= Y + 4) return "ascensor";
		if (x >= -4 && x <= 5 && z <= -66.5 && z >= -76 && y >= Y + 1) return "palanca";
		if (z <= -39.5 && z >= -66 && x >= -9 && x <= 9 && y >= Y + 1 && y <= Y + 10) return "edificio";
		return "afuera";
	}
	@EventHandler
	public void alMorir(PlayerDeathEvent e) {
		if (!es(e.getPlayer().getWorld())) return;
		e.deathMessage(Component.text(e.getPlayer().getName() + " se perdió en el Purgatorio", NamedTextColor.DARK_GREEN));
		volver.remove(e.getPlayer().getUniqueId());
	}
	@EventHandler
	public void alAparecer(CreatureSpawnEvent e) { if (es(e.getLocation().getWorld()) && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) e.setCancelled(true); }
	@EventHandler
	public void alRomper(BlockBreakEvent e) { if (es(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("tod5.admin")) e.setCancelled(true); }
	@EventHandler
	public void alPoner(BlockPlaceEvent e) { if (es(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("tod5.admin")) e.setCancelled(true); }
}
