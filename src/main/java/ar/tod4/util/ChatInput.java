package ar.tod4.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.event.player.AsyncChatEvent;

/** Espera lo proximo que escribe un jugador en el chat (para editar textos desde los menus). */
public final class ChatInput implements Listener {

    private static final Map<UUID, Consumer<String>> WAIT = new ConcurrentHashMap<>();
    private static Plugin plugin;

    public static void init(Plugin p) {
        plugin = p;
        Bukkit.getPluginManager().registerEvents(new ChatInput(), p);
    }

    /** handler recibe el texto (ya en el hilo principal). "cancel"/"cancelar" cancela. */
    public static void ask(Player p, Consumer<String> handler) {
        WAIT.put(p.getUniqueId(), handler);
    }

    public static boolean waiting(Player p) { return WAIT.containsKey(p.getUniqueId()); }

    public static void cancel(Player p) { WAIT.remove(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Consumer<String> h = WAIT.get(p.getUniqueId());
        if (h == null) return;
        e.setCancelled(true);
        String msg = Txt.plain(e.originalMessage());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("cancelar")) {
                WAIT.remove(p.getUniqueId());
                Style.err(p, "Accion cancelada.");
                return;
            }
            h.accept(msg);
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) { WAIT.remove(e.getPlayer().getUniqueId()); }
}
