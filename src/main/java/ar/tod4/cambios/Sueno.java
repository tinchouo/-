package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
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
 *  - Cambio del dia 6 (d6.noches): la noche del Overworld dura el triple (noches.factor). Dormir la sigue salteando.
 *    Mientras es de noche el plugin apaga el ciclo del dia y avanza la hora 1 tick cada 3; de dia lo vuelve a prender.
 */
final class Sueno implements Listener {

    private final TOD4 pl;
    private final Cambios cb;

    Sueno(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
        // la noche es desde que se puede dormir (12542) hasta que se hace de dia (23460); antes decia 13000 a 23000
        boolean cambio = false;
        if (pl.getConfig().getLong("noches.desde", 12542) == 13000) { pl.getConfig().set("noches.desde", 12542); cambio = true; }
        if (pl.getConfig().getLong("noches.hasta", 23460) == 23000) { pl.getConfig().set("noches.hasta", 23460); cambio = true; }
        if (cambio) pl.saveConfig();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::noche, 1L, 1L);
    }

    // ================================================================= noches el triple (d6.noches)

    private double resto;

    /** Pausado por nosotros (se guarda en datos: si el server se apaga de noche, al volver se arregla solo). */
    private boolean pausado() { return pl.datos().y().getBoolean("noches.pausado", false); }

    private void pausar(World w, boolean si) {
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !si);
        pl.datos().set("noches.pausado", si);
    }

    private void noche() {
        World w = mundos().get(0);
        long t = Math.floorMod(w.getTime(), 24000L);
        boolean deNoche = deNoche(pl, t);
        if (!cb.on("d6.noches") || !deNoche) {
            if (pausado()) pausar(w, false);
            resto = 0;
            return;
        }
        if (!pausado()) {
            // si alguien apago el ciclo a mano (por ejemplo para un evento), no lo tocamos
            if (!Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))) return;
            pausar(w, true);
        }
        double f = Math.max(1, pl.getConfig().getDouble("noches.factor", 3));
        resto += 1 / f;
        if (resto >= 1) {
            long n = (long) resto;
            resto -= n;
            w.setFullTime(w.getFullTime() + n);
        }
    }

    /** Si esa hora del juego es de noche (desde que se puede dormir hasta que amanece). */
    static boolean deNoche(TOD4 pl, long t) {
        t = Math.floorMod(t, 24000L);
        return t >= pl.getConfig().getLong("noches.desde", 12542) && t < pl.getConfig().getLong("noches.hasta", 23460);
    }

    /** Al apagar el plugin el ciclo vuelve a la normalidad. */
    void apagar() {
        World w = mundos().get(0);
        if (pausado()) pausar(w, false);
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
        // el juego no salta la noche solo: lo decide el plugin
        int regla = activo() ? 101 : 100;
        for (World w : ws) if (!Integer.valueOf(regla).equals(w.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE)))
            w.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, regla);
        if (!activo()) return;

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
     * Como vanilla, el que duerme resetea su contador de phantoms; enseguida ve "Durmiendo X/Y".
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void acuesta(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player p = e.getPlayer();
        // un tick despues, asi tapa el mensaje del juego ("no se puede dormir esta noche")
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            if (activo()) tick();
        }, 1L);
    }
}
