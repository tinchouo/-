package ar.tod5.servidor;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * TOD5 (server):
 *  - Estado para el menu del mod: el menu le pregunta al server como esta (sin entrar) y el plugin contesta
 *    en la descripcion "tod5;<abierto|preparacion>;<jugadores en la fila>".
 *    La fila son los que apretaron JUGAR con el server sin abrir (el mod manda un codigo propio al preguntar).
 *  - Cerrado (en preparacion): solo entran los admins (OP o permiso tod5.admin, o los de la lista "admins" de la config).
 *  - Control del mod: sin el mod de TOD no se entra; con mods que no estan permitidos tampoco.
 */
public class TOD5Servidor extends JavaPlugin implements Listener, PluginMessageListener {
	static final String CANAL_HOLA = "tod5:hola";
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final Set<String> SIEMPRE = Set.of("minecraft", "java", "fabricloader", "mixinextras");
	private static final long VIGENCIA_FILA = 10_000;   // ms: si no pregunta en 10 s, ya no esta en la fila

	private final Set<UUID> saludaron = new HashSet<>();
	private final Map<String, Long> fila = new ConcurrentHashMap<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getServer().getMessenger().registerIncomingPluginChannel(this, CANAL_HOLA, this);
		getServer().getPluginManager().registerEvents(this, this);
		getLogger().info("TOD5 listo. Server " + (abierto() ? "ABIERTO" : "EN PREPARACION (solo admins)"));
	}

	boolean abierto() { return getConfig().getBoolean("abierto", false); }
	private Component msg(String clave, String... reemplazos) {
		String s = getConfig().getString("mensajes." + clave, clave);
		for (int i = 0; i + 1 < reemplazos.length; i += 2) s = s.replace(reemplazos[i], reemplazos[i + 1]);
		return MM.deserialize(s);
	}

	private int enFila() {
		long ahora = System.currentTimeMillis();
		fila.values().removeIf(t -> ahora - t > VIGENCIA_FILA);
		return fila.size();
	}

	// ---------- lo que ve el menu del mod ----------
	@EventHandler
	public void alPreguntarEstado(PaperServerListPingEvent e) {
		String saludo = "";
		InetSocketAddress v = e.getClient().getVirtualHost();
		if (v != null) saludo = v.getHostString();
		int i = saludo.indexOf("tod5:");
		if (i >= 0) {
			String[] p = saludo.substring(i + 5).split(":");
			if (p.length > 1 && p[1].startsWith("fila")) fila.put(p[0], System.currentTimeMillis());
			else fila.remove(p[0]);
		}
		e.motd(Component.text("tod5;" + (abierto() ? "abierto" : "preparacion") + ";" + enFila()));
	}

	// ---------- entrar ----------
	private boolean esAdmin(UUID id, String nombre) {
		OfflinePlayer op = Bukkit.getOfflinePlayer(id);
		if (op.isOp()) return true;
		return getConfig().getStringList("admins").stream().anyMatch(n -> n.equalsIgnoreCase(nombre));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void antesDeEntrar(AsyncPlayerPreLoginEvent e) {
		if (abierto() || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
		if (esAdmin(e.getUniqueId(), e.getName())) return;
		e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, msg("en_preparacion"));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void alEntrar(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		saludaron.remove(p.getUniqueId());
		if (!abierto() && !esAdmin(p.getUniqueId(), p.getName()) && !p.hasPermission("tod5.admin")) { p.kick(msg("en_preparacion")); return; }
		if (getConfig().getBoolean("exigir_mod", true)) {
			int seg = Math.max(2, getConfig().getInt("espera_saludo", 6));
			Bukkit.getScheduler().runTaskLater(this, () -> {
				if (p.isOnline() && !saludaron.contains(p.getUniqueId())) {
					getLogger().info(p.getName() + " entro sin el mod de TOD: afuera");
					p.kick(msg("sin_mod"));
				}
			}, seg * 20L);
		}
	}

	@Override
	public void onPluginMessageReceived(String canal, Player p, byte[] datos) {
		if (!CANAL_HOLA.equals(canal)) return;
		String[] partes = new String(datos, StandardCharsets.UTF_8).split(";", 2);
		String version = partes[0];
		Set<String> mods = new TreeSet<>(partes.length > 1 && !partes[1].isBlank() ? Arrays.asList(partes[1].split(",")) : List.of());
		Set<String> permitidos = new HashSet<>(getConfig().getStringList("mods_permitidos"));
		List<String> malos = mods.stream().filter(m -> !permitidos.contains(m) && !SIEMPRE.contains(m)).toList();
		getLogger().info(p.getName() + " tiene el mod de TOD v" + version + " - mods: " + mods);
		if (!malos.isEmpty()) {
			getLogger().info(p.getName() + " tiene mods prohibidos: " + malos + " -> afuera");
			p.kick(msg("mods_prohibidos", "{mods}", String.join(", ", malos)));
			return;
		}
		saludaron.add(p.getUniqueId());
	}

	@EventHandler
	public void alSalir(PlayerQuitEvent e) { saludaron.remove(e.getPlayer().getUniqueId()); }

	// ---------- comandos ----------
	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		String sub = args.length > 0 ? args[0].toLowerCase() : "estado";
		switch (sub) {
			case "abrir" -> {
				getConfig().set("abierto", true); saveConfig();
				s.sendMessage(MM.deserialize("<green>Server ABIERTO.</green> <gray>Los " + enFila() + " de la fila van a entrar solos en unos segundos.</gray>"));
			}
			case "cerrar" -> {
				getConfig().set("abierto", false); saveConfig();
				s.sendMessage(MM.deserialize("<yellow>Server EN PREPARACIÓN.</yellow> <gray>Solo entran los admins. Los que ya estan adentro siguen jugando.</gray>"));
			}
			case "recargar" -> { reloadConfig(); s.sendMessage(MM.deserialize("<green>Config recargada.</green>")); }
			default -> s.sendMessage(MM.deserialize((abierto() ? "<green>Server ABIERTO" : "<yellow>Server EN PREPARACIÓN") + "</yellow></green> <gray>- en la fila: " + enFila() + " - conectados: " + Bukkit.getOnlinePlayers().size() + "</gray>"));
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
		return args.length == 1 ? List.of("abrir", "cerrar", "estado", "recargar") : List.of();
	}
}
