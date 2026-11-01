package ar.tod4.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Chat: "Nombre: mensaje" (nombre y dos puntos en verde, el mensaje en blanco).
 * Entrar / salir: el mensaje amarillo de siempre, sin el equipo ni los simbolos del tab.
 * Logros y muertes: solo el nombre (el equipo y los simbolos se ven solo en el tab y arriba de la cabeza).
 */
public final class Chat implements Listener {

    private final TOD4 pl;

    public Chat(TOD4 pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    /**
     * :GENIO: en el chat -> la imagen del pack (fuente tod4:emojis, alto de 3 renglones).
     * La imagen cuelga hacia abajo desde el renglon del mensaje, asi que se agregan 2 renglones vacios para que no tape nada.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void emojis(AsyncChatEvent e) {
        String plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message());
        if (!plano.toUpperCase(java.util.Locale.ROOT).contains(":GENIO:")) return;
        Component genio = Component.text("\ue100").font(net.kyori.adventure.key.Key.key("tod4", "emojis")).color(NamedTextColor.WHITE);
        Component m = e.message().replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                .match("(?i):GENIO:").replacement(genio).build());
        e.message(m.append(Component.text("\n\n")));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        if (!pl.getConfig().getBoolean("chat.formato", true)) return;
        String color = pl.getConfig().getString("chat.color_nombre", "&a");
        e.renderer(ChatRenderer.viewerUnaware((src, nombre, msg) ->
                Txt.c(color + src.getName() + ":").append(Component.text(" ")).append(msg.colorIfAbsent(NamedTextColor.WHITE))));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void entra(PlayerJoinEvent e) {
        if (e.joinMessage() == null) return;
        e.joinMessage(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, Component.text(e.getPlayer().getName())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void sale(PlayerQuitEvent e) {
        if (e.quitMessage() == null) return;
        Player p = e.getPlayer();
        e.quitMessage(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, Component.text(p.getName())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void logro(PlayerAdvancementDoneEvent e) {
        if (e.message() != null) e.message(sinEquipo(e.message()));
    }

    /**
     * En los mensajes del juego (muertes, logros) el nombre del jugador viene con el prefijo de su team
     * (mision, alma, [EQUIPO]): lo cambia por el nombre solo.
     */
    public static Component sinEquipo(Component c) {
        if (c == null) return null;
        HoverEvent<?> h = c.hoverEvent();
        if (h != null && h.action() == HoverEvent.Action.SHOW_ENTITY && h.value() instanceof HoverEvent.ShowEntity se) {
            Player p = Bukkit.getPlayer(se.id());
            if (p != null) return Component.text(p.getName()).style(c.style()).color(null).hoverEvent(h);
        }
        if (c instanceof TranslatableComponent tc) {
            List<Component> args = new ArrayList<>();
            for (TranslationArgument a : tc.arguments()) args.add(sinEquipo(a.asComponent()));
            return tc.arguments(args);
        }
        return c;
    }
}
