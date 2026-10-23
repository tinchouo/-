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
 *  %tod4_mision_tab%       simbolo para el tab: ✔ azul / ✔ verde / ✖ rojo / - gris (el tab ya lo pone el plugin)
 *  %tod4_mision_diaria%    estado de la diaria (En curso, Completada, ✔ Reclamada ✔...)
 *  %tod4_mision_opcional%  estado de la opcional
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
            case "muertes": return String.valueOf(pl.extras().muertesTotal());
            case "muertes_hoy": return String.valueOf(pl.extras().muertesHoy());
            case "revividos": return String.valueOf(pl.extras().total("revividos"));
            case "revividos_hoy": return String.valueOf(pl.extras().del("revividos", pl.dia()));
            case "totems": return String.valueOf(pl.extras().total("totems"));
            case "totems_hoy": return String.valueOf(pl.extras().del("totems", pl.dia()));
            default:
                if (id.toLowerCase().startsWith("muertes_dia_")) {
                    try { return String.valueOf(pl.extras().muertesDia(Integer.parseInt(id.substring(12)))); } catch (NumberFormatException ignored) { }
                }
                break;
        }
        if (op == null) return "";
        switch (id.toLowerCase()) {
            case "alma": return pl.revivir().tieneAlma(op.getName()) ? "&f✦" : "&f✧";
            case "alma_raw": return String.valueOf(pl.revivir().tieneAlma(op.getName()));
            case "mision_tab": {
                Player p = op.getPlayer();
                return p == null ? "&7-" : pl.misiones().simboloTab(p);
            }
            case "mision_diaria": {
                Player p = op.getPlayer();
                return p == null ? "" : pl.misiones().textoEstado(pl.misiones().estado(ar.tod4.misiones.Misiones.DIA, p));
            }
            case "mision_opcional": {
                Player p = op.getPlayer();
                return p == null ? "" : pl.misiones().textoEstado(pl.misiones().estado(ar.tod4.misiones.Misiones.OP, p));
            }
            case "mundo": {
                Player p = op.getPlayer();
                return p == null ? "" : Sidebar.mundo(pl, p.getWorld());
            }
            default: return null;
        }
    }
}
