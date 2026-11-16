package ar.tod5.servidor;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * La gravedad de cada jugador (como en Mario Galaxy).
 *
 * Arriba / abajo: se usa el atributo de gravedad del juego (lo trae Minecraft). Con gravedad negativa el jugador "cae" para arriba.
 * El server lo manda solo al cliente, asi que el movimiento es el de verdad (no se rebota). El mod da vuelta la camara,
 * el muñeco y los controles, y deja saltar y caminar en el techo.
 * Izquierda / derecha: todavia no (van en la segunda etapa).
 *
 * "abajo" es la normalidad. Al morir se vuelve a abajo.
 * Si alguien con la gravedad para arriba se va al cielo (64 bloques arriba del techo del mundo), muere como en el vacio.
 *
 * Comando: /tod gravedad <arriba|abajo|izquierda|derecha> [jugador|todos]
 */
final class Gravedad implements Listener {
	enum Dir {
		ABAJO("abajo"), ARRIBA("arriba"), IZQUIERDA("izquierda"), DERECHA("derecha");
		final String id;
		Dir(String id) { this.id = id; }
		static Dir de(String s) { for (Dir d : values()) if (d.id.equalsIgnoreCase(s)) return d; return null; }
		/** Por ahora solo arriba y abajo funcionan. */
		boolean lista() { return this == ABAJO || this == ARRIBA; }
	}
	static final NamespacedKey CLAVE = new NamespacedKey("tod5", "gravedad");

	private final TOD5Servidor plugin;
	Gravedad(TOD5Servidor plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::alCielo, 20, 20);
	}

	Dir de(Player p) {
		AttributeInstance a = p.getAttribute(Attribute.GRAVITY);
		return a != null && a.getModifier(CLAVE) != null ? Dir.ARRIBA : Dir.ABAJO;
	}

	/** Cambia la gravedad. Devuelve false si esa direccion todavia no existe. */
	boolean poner(Player p, Dir d) {
		if (!d.lista()) return false;
		AttributeInstance a = p.getAttribute(Attribute.GRAVITY);
		if (a == null) return false;
		a.removeModifier(CLAVE);
		// -2 multiplicando el total: 0.08 * (1 - 2) = -0.08 -> misma fuerza, para el otro lado
		if (d == Dir.ARRIBA) a.addModifier(new AttributeModifier(CLAVE, -2.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		p.setFallDistance(0);
		return true;
	}

	private void alCielo() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (de(p) != Dir.ARRIBA || p.isDead()) continue;
			if (p.getLocation().getY() > p.getWorld().getMaxHeight() + 64)
				p.damage(10000, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
		}
	}

	@EventHandler
	public void alMorir(PlayerDeathEvent e) { poner(e.getPlayer(), Dir.ABAJO); }
	@EventHandler
	public void alReaparecer(PlayerRespawnEvent e) {
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) poner(p, Dir.ABAJO); });
	}

	// ================= /tod gravedad =================
	void comando(CommandSender s, String[] args) {
		Dir d = args.length > 1 ? Dir.de(args[1]) : null;
		if (d == null) { s.sendMessage(Component.text("Uso: /tod gravedad <arriba|abajo|izquierda|derecha> [jugador|todos]  (abajo = normal)", NamedTextColor.YELLOW)); return; }
		if (!d.lista()) { s.sendMessage(Component.text("Izquierda y derecha todavía no están: por ahora solo arriba y abajo.", NamedTextColor.YELLOW)); return; }
		List<Player> quienes = new ArrayList<>();
		if (args.length > 2) {
			if (args[2].equalsIgnoreCase("todos")) quienes.addAll(Bukkit.getOnlinePlayers());
			else { Player p = Bukkit.getPlayerExact(args[2]); if (p == null) { s.sendMessage(Component.text("No está conectado: " + args[2], NamedTextColor.RED)); return; } quienes.add(p); }
		} else if (s instanceof Player p) quienes.add(p);
		else { s.sendMessage(Component.text("Desde la consola decí a quién: /tod gravedad " + d.id + " <jugador|todos>", NamedTextColor.YELLOW)); return; }
		for (Player p : quienes) {
			if (de(p) == d) continue;
			poner(p, d);
			p.playSound(p.getLocation(), "tod5:gravedad_cambio", SoundCategory.MASTER, 1f, 1f);
		}
		s.sendMessage(Component.text("Gravedad " + d.id + ": " + (quienes.size() == 1 ? quienes.get(0).getName() : quienes.size() + " jugadores"), NamedTextColor.GREEN));
		plugin.getLogger().info("Gravedad " + d.id + " para " + quienes.stream().map(Player::getName).toList());
	}
	List<String> completar(String[] args) {
		if (args.length == 2) return List.of("arriba", "abajo", "izquierda", "derecha");
		if (args.length == 3) { List<String> l = new ArrayList<>(List.of("todos")); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
		return List.of();
	}
}
