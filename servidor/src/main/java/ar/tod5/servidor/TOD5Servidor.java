package ar.tod5.servidor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * TOD5 (server):
 *  - Sala de espera: con el server cerrado, los que entran quedan esperando (quietos, invisibles) y el mod les muestra
 *    la pantalla con la cantidad de jugadores. /tod abrir los hace entrar a todos juntos. Los admins entran igual.
 *  - Control del mod: sin el mod de TOD no se entra; con mods que no estan permitidos tampoco.
 */
public class TOD5Servidor extends JavaPlugin implements Listener, PluginMessageListener {
	static final String CANAL_SALA = "tod5:sala", CANAL_HOLA = "tod5:hola";
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final Set<String> SIEMPRE = Set.of("minecraft", "java", "fabricloader", "mixinextras");

	private final Set<UUID> saludaron = new HashSet<>();
	private final LinkedHashSet<UUID> esperando = new LinkedHashSet<>();
	private final Map<UUID, GameMode> modoAntes = new HashMap<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getServer().getMessenger().registerOutgoingPluginChannel(this, CANAL_SALA);
		getServer().getMessenger().registerIncomingPluginChannel(this, CANAL_HOLA, this);
		getServer().getPluginManager().registerEvents(this, this);
		// cada 5 segundos le recuerdo a la sala cuantos son (por si alguno se perdio un mensaje)
		Bukkit.getScheduler().runTaskTimer(this, this::avisarSala, 100, 100);
		getLogger().info("TOD5 listo. Server " + (abierto() ? "ABIERTO" : "CERRADO (sala de espera)"));
	}

	boolean abierto() { return getConfig().getBoolean("abierto", false); }
	private Component msg(String clave, String... reemplazos) {
		String s = getConfig().getString("mensajes." + clave, clave);
		for (int i = 0; i + 1 < reemplazos.length; i += 2) s = s.replace(reemplazos[i], reemplazos[i + 1]);
		return MM.deserialize(s);
	}
	private void enviar(Player p, String texto) { p.sendPluginMessage(this, CANAL_SALA, texto.getBytes(StandardCharsets.UTF_8)); }

	// ---------- entrar ----------
	@EventHandler(priority = EventPriority.HIGHEST)
	public void alEntrar(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		saludaron.remove(p.getUniqueId());
		if (getConfig().getBoolean("exigir_mod", true)) {
			int seg = Math.max(2, getConfig().getInt("espera_saludo", 6));
			Bukkit.getScheduler().runTaskLater(this, () -> {
				if (p.isOnline() && !saludaron.contains(p.getUniqueId())) {
					getLogger().info(p.getName() + " entro sin el mod de TOD: afuera");
					p.kick(msg("sin_mod"));
				}
			}, seg * 20L);
		}
		if (!abierto() && !p.hasPermission("tod5.admin")) ponerEnSala(p);
	}

	@Override
	public void onPluginMessageReceived(String canal, Player p, byte[] datos) {
		if (!CANAL_HOLA.equals(canal)) return;
		String s = new String(datos, StandardCharsets.UTF_8);
		String[] partes = s.split(";", 2);
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
		if (esperando.contains(p.getUniqueId())) avisarSala();   // ahora que se que tiene el mod, le muestro la sala
	}

	// ---------- sala de espera ----------
	private void ponerEnSala(Player p) {
		esperando.add(p.getUniqueId());
		modoAntes.putIfAbsent(p.getUniqueId(), p.getGameMode());
		p.setGameMode(GameMode.SPECTATOR);
		Location sala = p.getWorld().getSpawnLocation().clone().add(0.5, 60, 0.5);
		p.teleport(sala);
		for (Player otro : Bukkit.getOnlinePlayers()) { if (otro != p) { otro.hidePlayer(this, p); p.hidePlayer(this, otro); } }
		avisarSala();
	}

	private void avisarSala() {
		int n = esperando.size();
		for (UUID id : esperando) { Player p = Bukkit.getPlayer(id); if (p != null) enviar(p, "espera;" + n); }
	}

	private void abrirTodo() {
		List<Player> entran = new ArrayList<>();
		for (UUID id : esperando) { Player p = Bukkit.getPlayer(id); if (p != null) entran.add(p); }
		esperando.clear();
		for (Player p : entran) {
			enviar(p, "abrir");
			p.setGameMode(modoAntes.getOrDefault(p.getUniqueId(), GameMode.SURVIVAL) == GameMode.SPECTATOR ? GameMode.SURVIVAL : modoAntes.getOrDefault(p.getUniqueId(), GameMode.SURVIVAL));
			p.teleport(p.getWorld().getSpawnLocation().clone().add(0.5, 0, 0.5));
			p.showTitle(Title.title(msg("abrio"), Component.empty()));
		}
		modoAntes.clear();
		for (Player a : Bukkit.getOnlinePlayers()) for (Player b : Bukkit.getOnlinePlayers()) if (a != b) a.showPlayer(this, b);
	}

	// mientras esperan: no se mueven, no hablan entre ellos por chat de juego (se puede cambiar), no hacen nada
	@EventHandler
	public void alMoverse(PlayerMoveEvent e) {
		if (esperando.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) e.setCancelled(true);
	}

	@EventHandler
	public void alTeletransportarse(PlayerTeleportEvent e) {
		if (esperando.contains(e.getPlayer().getUniqueId()) && e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) e.setCancelled(true);
	}

	@EventHandler
	public void alSalir(PlayerQuitEvent e) {
		UUID id = e.getPlayer().getUniqueId();
		saludaron.remove(id);
		if (esperando.remove(id)) {
			GameMode antes = modoAntes.remove(id);
			if (antes != null) e.getPlayer().setGameMode(antes);
			Bukkit.getScheduler().runTask(this, this::avisarSala);
		}
	}

	// ---------- comandos ----------
	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		String sub = args.length > 0 ? args[0].toLowerCase() : "estado";
		switch (sub) {
			case "abrir" -> {
				getConfig().set("abierto", true); saveConfig();
				int n = esperando.size();
				abrirTodo();
				s.sendMessage(MM.deserialize("<green>Server ABIERTO.</green> <gray>Entraron " + n + " jugadores de la sala.</gray>"));
			}
			case "cerrar" -> {
				getConfig().set("abierto", false); saveConfig();
				s.sendMessage(MM.deserialize("<red>Server CERRADO.</red> <gray>Los que entren ahora van a la sala de espera (los admins entran igual).</gray>"));
			}
			case "recargar" -> { reloadConfig(); s.sendMessage(MM.deserialize("<green>Config recargada.</green>")); }
			default -> s.sendMessage(MM.deserialize((abierto() ? "<green>Server ABIERTO" : "<red>Server CERRADO") + "</green></red> <gray>- en la sala: " + esperando.size() + "</gray>"));
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
		return args.length == 1 ? List.of("abrir", "cerrar", "estado", "recargar") : List.of();
	}
}
