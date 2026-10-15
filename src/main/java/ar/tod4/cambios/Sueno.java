package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;

/**
 * Dormir:
 *  - Para pasar la noche tiene que dormir un cuarto de los jugadores, pero minimo 5 (si hay menos de 5, todos).
 *  - Cuentan los del Overworld y los del Aether (donde se puede dormir). Si se pasa la noche, se hace de dia en los dos.
 *  - Mensaje como el de vanilla: "Durmiendo 3/5".
 *  - Cambio del dia 6 (d6.noches): la noche no se puede saltar; acostarse igual resetea el contador de phantoms.
 */
final class Sueno implements Listener {

    private final TOD4 pl;
    private final Cambios cb;

    Sueno(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    private boolean activo() { return pl.getConfig().getBoolean("sueno.activo", true); }

    private List<World> mundos() {
        List<World> out = new ArrayList<>();
        World main = Bukkit.getWorld(pl.getConfig().getString("palo.mundo_principal", "world"));
        if (main == null) main = Bukkit.getWorlds().get(0);
        out.add(main);
        for (World w : Bukkit.getWorlds()) if (w != main && w.getName().toLowerCase().contains("aether")) out.add(w);
        return out;
    }

    /** Un cuarto de los jugadores, minimo 5 (o todos si son menos de 5). */
    int necesarios(int jugadores) {
        double pct = pl.getConfig().getDouble("sueno.porcentaje", 25) / 100.0;
        int min = pl.getConfig().getInt("sueno.minimo", 5);
        return Math.max((int) Math.ceil(jugadores * pct), Math.min(min, jugadores));
    }

    private void tick() {
        List<World> ws = mundos();
        // el juego no salta la noche solo: lo decide el plugin (o nadie, con el cambio del dia 6)
        int regla = activo() || cb.on("d6.noches") ? 101 : 100;
        for (World w : ws) if (!Integer.valueOf(regla).equals(w.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE)))
            w.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, regla);
        if (!activo() || cb.on("d6.noches")) return;

        List<Player> cuentan = new ArrayList<>();
        int durmiendo = 0, profundo = 0;
        for (World w : ws) for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            cuentan.add(p);
            if (p.isSleeping()) { durmiendo++; if (p.getSleepTicks() >= 100) profundo++; }
        }
        if (durmiendo == 0 || cuentan.isEmpty()) return;
        int hace = necesarios(cuentan.size());
        if (profundo >= hace) {
            for (World w : ws) {
                long t = w.getFullTime();
                w.setFullTime(t + (24000 - Math.floorMod(t, 24000L)));
                if (w.getEnvironment() == World.Environment.NORMAL) { w.setStorm(false); w.setThundering(false); }
            }
            return;
        }
        String msg = "&fDurmiendo: &e" + durmiendo + "&f/&e" + hace;
        for (Player p : cuentan) p.sendActionBar(Txt.c(msg));
    }

    // ================================================================= al acostarse

    /**
     * Normal: como vanilla, el que duerme resetea su contador de phantoms; enseguida ve "Durmiendo X/Y".
     * Dia 6 (d6.noches): la noche no pasa, pero acostarte igual resetea tu contador y te avisa en verde.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void acuesta(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player p = e.getPlayer();
        // un tick despues, asi tapa el mensaje del juego ("no se puede dormir esta noche")
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            if (cb.on("d6.noches")) {
                p.setStatistic(Statistic.TIME_SINCE_REST, 0);
                p.sendActionBar(Txt.c("&aSe reseteó tu contador de phantoms"));
            } else if (activo()) {
                tick();
            }
        }, 1L);
    }
}
