package ar.tod4.hook;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;

/**
 * Autocompletado de /gulag (el comando es del script gulag.sk). Skript sin addons solo sugiere a los conectados;
 * aca se sugieren los subcomandos y TODOS los jugadores que alguna vez entraron.
 */
public final class GulagTab implements Listener {

    private static final List<String> SUBS = List.of("agregar", "sacar", "lista", "limpiar", "varita", "pos1", "pos2", "puerta", "puertas", "borrarpuerta", "zonaesp",
            "spawn", "salida", "punto", "llaves", "texto", "textoesp", "preparar", "espectadores", "empezar", "ronda", "fin",
            "liberar", "estado");

    @EventHandler(priority = EventPriority.HIGH)
    public void tab(TabCompleteEvent e) {
        String b = e.getBuffer();
        String low = b.toLowerCase();
        if (!low.startsWith("/gulag ") && !low.startsWith("gulag ")) return;
        if (!(e.getSender().hasPermission("gulag.admin") || e.getSender().isOp())) return;
        String[] a = b.substring(b.indexOf(' ') + 1).split(" ", -1);
        List<String> out = new ArrayList<>();
        String pre = a[a.length - 1].toLowerCase();
        if (a.length == 1) {
            for (String s : SUBS) if (s.startsWith(pre)) out.add(s);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("agregar") || a[0].equalsIgnoreCase("sacar"))) {
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                String n = op.getName();
                if (n != null && n.toLowerCase().startsWith(pre)) out.add(n);
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("puerta") || a[0].equalsIgnoreCase("borrarpuerta"))) {
            for (String s : List.of("1", "2", "3", "4", "5", "6")) if (s.startsWith(pre)) out.add(s);
        } else {
            return;
        }
        e.setCompletions(out);
    }
}
