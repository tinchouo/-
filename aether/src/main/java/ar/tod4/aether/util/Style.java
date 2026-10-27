package ar.tod4.aether.util;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Estilo TOD4: el mismo formato de mensajes en todo el server.
 *  ✖ error (rojo) | ✔ ok (verde) | ✦ revivir/alma (amarillo) | ▶ info (gris) | ☠ muerte
 */
public final class Style {
    private Style() {}

    public static final String PREFIX = "&8[&b&lAether&8]&r ";

    public static void err(CommandSender s, String msg) {
        s.sendMessage(Txt.c("&c✖ " + msg));
        if (s instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
    }

    public static void ok(CommandSender s, String msg) {
        s.sendMessage(Txt.c("&a✔ " + msg));
    }

    public static void info(CommandSender s, String msg) {
        s.sendMessage(Txt.c("&7▶ " + msg));
    }

    public static void raw(CommandSender s, String msg) {
        s.sendMessage(Txt.c(msg));
    }

    public static void broadcast(String msg) {
        Bukkit.broadcast(Txt.c(msg));
    }

    public static void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }
}
