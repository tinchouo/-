package ar.tod5.servidor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;

/**
 * La gravedad de cada jugador (como en Mario Galaxy).
 *
 * Arriba / abajo: se usa el atributo de gravedad del juego (lo trae Minecraft). Con gravedad negativa el jugador "cae" para arriba.
 * El mod da vuelta la camara, el muñeco y los controles, y deja saltar y caminar en el techo.
 *
 * A los costados (este, oeste, sur, norte): Minecraft no tiene gravedad de costado, asi que la hace el mod.
 *  - el server le pone gravedad 0 (asi no lo echa por "volar") y una pose fija chica (una caja de 0,6 x 0,6 x 0,6),
 *    que queda adentro de la caja acostada que usa el mod: asi lo que el mod deja moverse, el server tambien lo acepta
 *  - le avisa a todos los clientes quien tiene la gravedad de costado y para donde (canal tod5:gravedad: "uuid;direccion")
 * "izquierda" y "derecha" en el comando son relativas a donde mira el jugador en ese momento (se pasan a la direccion fija mas cercana).
 *
 * "abajo" es la normalidad. Al morir se vuelve a abajo.
 * Si alguien se va lejos (arriba del mundo con la gravedad para arriba, o afuera del borde del mundo de costado), muere como en el vacio.
 *
 * Comando: /tod gravedad <arriba|abajo|izquierda|derecha|este|oeste|norte|sur> [jugador|todos]
 */
final class Gravedad implements Listener {
	enum Dir {
		ABAJO("abajo", 0, -1, 0), ARRIBA("arriba", 0, 1, 0),
		ESTE("este", 1, 0, 0), OESTE("oeste", -1, 0, 0), SUR("sur", 0, 0, 1), NORTE("norte", 0, 0, -1);
		final String id; final int x, y, z;
		Dir(String id, int x, int y, int z) { this.id = id; this.x = x; this.y = y; this.z = z; }
		static Dir de(String s) { for (Dir d : values()) if (d.id.equalsIgnoreCase(s)) return d; return null; }
		boolean costado() { return y == 0; }
		/** La direccion fija (del mundo) mas cercana a este vector horizontal. */
		static Dir horizontal(double vx, double vz) {
			if (Math.abs(vx) >= Math.abs(vz)) return vx >= 0 ? ESTE : OESTE;
			return vz >= 0 ? SUR : NORTE;
		}
		/** La izquierda de alguien que mira con este yaw (yaw 0 = mira al sur). */
		static Dir izquierdaDe(float yaw) { double r = Math.toRadians(yaw); return horizontal(Math.cos(r), Math.sin(r)); }
		Dir opuesta() { return switch (this) { case ABAJO -> ARRIBA; case ARRIBA -> ABAJO; case ESTE -> OESTE; case OESTE -> ESTE; case SUR -> NORTE; case NORTE -> SUR; }; }
	}
	static final NamespacedKey CLAVE = new NamespacedKey("tod5", "gravedad");
	static final NamespacedKey CLAVE_DIR = new NamespacedKey("tod5", "gravedad_dir");
	static final String CANAL = "tod5:gravedad";

	private final TOD5Servidor plugin;
	Gravedad(TOD5Servidor plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::alCielo, 20, 20);
	}

	Dir de(Player p) {
		String s = p.getPersistentDataContainer().get(CLAVE_DIR, PersistentDataType.STRING);
		Dir d = s == null ? null : Dir.de(s);
		if (d != null) return d;
		AttributeInstance a = p.getAttribute(Attribute.GRAVITY);
		return a != null && a.getModifier(CLAVE) != null ? Dir.ARRIBA : Dir.ABAJO;
	}

	/** Pasa "izquierda"/"derecha" (relativas a donde mira el jugador) a una direccion fija. Lo demas lo deja igual. */
	static Dir leer(String s, Player quien) {
		if (s.equalsIgnoreCase("izquierda")) return Dir.izquierdaDe(quien.getLocation().getYaw());
		if (s.equalsIgnoreCase("derecha")) return Dir.izquierdaDe(quien.getLocation().getYaw()).opuesta();
		return Dir.de(s);
	}

	/** Cambia la gravedad. */
	boolean poner(Player p, Dir d) {
		AttributeInstance a = p.getAttribute(Attribute.GRAVITY);
		if (a == null) return false;
		boolean eraCostado = de(p).costado();
		a.removeModifier(CLAVE);
		// arriba: -2 multiplicando el total: 0.08 * (1 - 2) = -0.08 -> misma fuerza, para el otro lado
		// costado: -1 -> 0.08 * 0 = 0 (la gravedad de costado la hace el mod)
		if (d == Dir.ARRIBA) a.addModifier(new AttributeModifier(CLAVE, -2.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		else if (d.costado()) a.addModifier(new AttributeModifier(CLAVE, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		if (d.costado()) {
			p.getPersistentDataContainer().set(CLAVE_DIR, PersistentDataType.STRING, d.id);
			p.setPose(Pose.SWIMMING, true);
		} else {
			p.getPersistentDataContainer().remove(CLAVE_DIR);
			if (eraCostado) p.setPose(Pose.STANDING, false);
		}
		p.setFallDistance(0);
		avisar(p, d);
		return true;
	}

	/** Les cuenta a todos para donde tiene la gravedad este jugador (lo usa el mod para la gravedad de costado). */
	private void avisar(Player quien, Dir d) {
		byte[] b = (quien.getUniqueId() + ";" + d.id).getBytes(StandardCharsets.UTF_8);
		for (Player p : Bukkit.getOnlinePlayers()) p.sendPluginMessage(plugin, CANAL, b);
	}

	private void alCielo() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.isDead()) continue;
			Dir d = de(p);
			Location l = p.getLocation();
			boolean lejos = d == Dir.ARRIBA && l.getY() > p.getWorld().getMaxHeight() + 64
				|| d.costado() && !p.getWorld().getWorldBorder().isInside(l);
			if (lejos) p.damage(10000, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
		}
	}

	@EventHandler
	public void alMorir(PlayerDeathEvent e) { poner(e.getPlayer(), Dir.ABAJO); }
	@EventHandler
	public void alReaparecer(PlayerRespawnEvent e) {
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) poner(p, Dir.ABAJO); });
	}
	/** Al entrar: el mod tarda un poco en avisar que escucha el canal; despues le mando como esta cada uno (y lo suyo, si quedo de costado). */
	@EventHandler
	public void alEntrar(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			for (Player o : Bukkit.getOnlinePlayers()) {
				Dir d = de(o);
				if (!d.costado()) continue;
				if (o == p) { poner(p, d); continue; }   // vuelve a poner la pose fija (no se guarda) y avisa a todos
				p.sendPluginMessage(plugin, CANAL, (o.getUniqueId() + ";" + d.id).getBytes(StandardCharsets.UTF_8));
			}
		}, 40);
	}

	// ================= /tod gravedad =================
	void comando(CommandSender s, String[] args) {
		String dir = args.length > 1 ? args[1] : "";
		boolean relativa = dir.equalsIgnoreCase("izquierda") || dir.equalsIgnoreCase("derecha");
		if (!relativa && Dir.de(dir) == null) {
			s.sendMessage(Component.text("Uso: /tod gravedad <arriba|abajo|izquierda|derecha|este|oeste|norte|sur> [jugador|todos]  (abajo = normal; izquierda/derecha: según para dónde mira cada uno)", NamedTextColor.YELLOW));
			return;
		}
		List<Player> quienes = new ArrayList<>();
		if (args.length > 2) {
			if (args[2].equalsIgnoreCase("todos")) quienes.addAll(Bukkit.getOnlinePlayers());
			else { Player p = Bukkit.getPlayerExact(args[2]); if (p == null) { s.sendMessage(Component.text("No está conectado: " + args[2], NamedTextColor.RED)); return; } quienes.add(p); }
		} else if (s instanceof Player p) quienes.add(p);
		else { s.sendMessage(Component.text("Desde la consola decí a quién: /tod gravedad " + dir + " <jugador|todos>", NamedTextColor.YELLOW)); return; }
		List<String> log = new ArrayList<>();
		for (Player p : quienes) {
			Dir d = leer(dir, p);
			log.add(p.getName() + "=" + d.id);
			if (de(p) == d) continue;
			poner(p, d);
			p.playSound(p.getLocation(), "tod5:gravedad_cambio", SoundCategory.MASTER, 1f, 1f);
		}
		s.sendMessage(Component.text("Gravedad " + dir.toLowerCase(Locale.ROOT) + ": " + (quienes.size() == 1 ? quienes.get(0).getName() : quienes.size() + " jugadores"), NamedTextColor.GREEN));
		plugin.getLogger().info("Gravedad " + dir + " -> " + log);
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("arriba", "abajo", "izquierda", "derecha", "este", "oeste", "norte", "sur");
		if (args.length == 3) { List<String> l = new ArrayList<>(List.of("todos")); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
		return List.of();
	}
}
