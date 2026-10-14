package ar.tod4.hook;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import ar.tod4.TOD4;
import ar.tod4.sidebar.Sidebar;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * Placeholders de TOD4 (para el script del tab, SimpleScore, etc.):
 *  %tod4_dia%        dia actual
 *  %tod4_alma%       ✦ (con alma) / ✧ (sin alma), con color
 *  %tod4_alma_raw%   true / false
 *  %tod4_mundo%      nombre del mundo con su color (Overworld, Nether, End, Aether, ???)
 *  %tod4_jugadores%  conectados/maximo
 */
public final class Papi extends PlaceholderExpansion {

    private final TOD4 pl;

    public Papi(TOD4 pl) { this.pl = pl; }

    @Override public String getIdentifier() { return "tod4"; }
    @Override public String getAuthor() { return "TOD4"; }
    @Override public String getVersion() { return pl.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer op, String id) {
        switch (id.toLowerCase()) {
            case "dia": return String.valueOf(pl.dia());
            case "jugadores": return Sidebar.jugadores(pl);
            default: break;
        }
        if (op == null) return "";
        switch (id.toLowerCase()) {
            case "alma": return pl.revivir().tieneAlma(op.getName()) ? "&f✦" : "&f✧";
            case "alma_raw": return String.valueOf(pl.revivir().tieneAlma(op.getName()));
            case "mundo": {
                Player p = op.getPlayer();
                return p == null ? "" : Sidebar.mundo(pl, p.getWorld());
            }
            default: return null;
        }
    }
}
