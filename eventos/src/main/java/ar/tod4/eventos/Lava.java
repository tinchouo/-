package ar.tod4.eventos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lava que sube por capas dentro de un volcan. Se marca un punto en el fondo y la altura maxima.
 * Cada capa llena solo el aire conectado (en esa altura) con la lava de la capa de abajo: las paredes la frenan solas,
 * asi se adapta a cualquier forma aunque el volcan se achique. Si una capa necesitaria demasiados bloques
 * (la lava se esta escapando por un agujero), se frena y avisa. Opcional: un limite (caja) que nunca pasa.
 */
final class Lava {

    private final TOD4Eventos pl;
    private final Store st;       // lava.yml (configuracion y estado)
    private final Store puesta;   // lava_puesta.yml (donde se puso lava, por capa, para el reset)
    private final Map<String, BukkitTask> tareas = new HashMap<>();
    private final ArrayDeque<Block> cola = new ArrayDeque<>();
    private final Random rnd = new Random();

    Lava(TOD4Eventos pl) {
        this.pl = pl;
        this.st = new Store(pl, "lava");
        this.puesta = new Store(pl, "lava_puesta");
        Bukkit.getScheduler().runTaskTimer(pl, this::colocar, 1L, 1L);
        for (String id : ids()) if (sec(id).getBoolean("corriendo")) empezar(id);
    }

    void apagar() {
        // lo que quedo en la cola se pone de una (asi no queda una capa por la mitad)
        while (!cola.isEmpty()) { Block b = cola.poll(); if (llenable(b)) b.setType(Material.LAVA, false); }
        st.save();
        puesta.save();
    }

    // ================================================================= datos

    private List<String> ids() {
        ConfigurationSection s = st.y().getConfigurationSection("lavas");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    private ConfigurationSection sec(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("lavas." + id);
        return s != null ? s : st.y().createSection("lavas." + id);
    }

    private boolean existe(String id) { return st.y().isConfigurationSection("lavas." + id); }

    private static long xz(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static int x(long k) { return (int) (k >> 32); }
    private static int z(long k) { return (int) k; }

    private static boolean llenable(Block b) {
        Material m = b.getType();
        return m.isAir() || (m != Material.LAVA && b.isReplaceable());
    }

    private static boolean pasa(Block b) { return llenable(b) || b.getType() == Material.LAVA; }

    // ================================================================= subir

    private void empezar(String id) {
        parar(id);
        sec(id).set("corriendo", true);
        st.touch();
        long cada = Math.max(1, sec(id).getLong("segundos", pl.getConfig().getLong("lava.segundos_por_capa", 20))) * 20L;
        tareas.put(id, Bukkit.getScheduler().runTaskTimer(pl, () -> capa(id, null), 20L, cada));
    }

    private void parar(String id) {
        BukkitTask t = tareas.remove(id);
        if (t != null) t.cancel();
    }

    private void pausar(String id, String porque, CommandSender quien) {
        parar(id);
        sec(id).set("corriendo", false);
        st.touch();
        if (porque != null) avisarAdmins(id, porque, quien);
    }

    private void avisarAdmins(String id, String msg, CommandSender quien) {
        if (quien != null) TOD4Eventos.err(quien, msg);
        for (Player p : Bukkit.getOnlinePlayers()) if (p != quien && p.hasPermission("tod4eventos.admin")) TOD4Eventos.err(p, "&7[lava " + id + "] &c" + msg);
        pl.getLogger().warning("[lava " + id + "] " + msg.replaceAll("&.", ""));
    }

    /** Sube una capa. Devuelve cuantos bloques va a poner (-1 si se freno). */
    private int capa(String id, CommandSender quien) {
        ConfigurationSection c = sec(id);
        World w = Bukkit.getWorld(c.getString("mundo", ""));
        if (w == null) { pausar(id, "No existe el mundo de esta lava.", quien); return -1; }
        int y = c.getInt("y"), tope = c.getInt("altura");
        if (y > tope) { pausar(id, null, null); if (quien != null) TOD4Eventos.info(quien, "La lava ya llegó a la altura máxima."); return -1; }
        // semillas: la capa de abajo (o el punto inicial si es la primera)
        List<Long> abajo = new ArrayList<>();
        for (Long k : puesta.y().getLongList(id + "." + (y - 1))) abajo.add(k);
        if (abajo.isEmpty()) abajo.add(xz(c.getInt("x"), c.getInt("z")));
        boolean conLimite = c.getBoolean("limite.on");
        int lx1 = c.getInt("limite.x1"), lz1 = c.getInt("limite.z1"), lx2 = c.getInt("limite.x2"), lz2 = c.getInt("limite.z2");
        int max = Math.max(100, pl.getConfig().getInt("lava.max_bloques_capa", 5000));
        Set<Long> visto = new HashSet<>();
        ArrayDeque<Long> pend = new ArrayDeque<>();
        for (Long k : abajo) if (pasa(w.getBlockAt(x(k), y, z(k))) && visto.add(k)) pend.add(k);
        List<Long> capa = new ArrayList<>();
        while (!pend.isEmpty()) {
            long k = pend.poll();
            capa.add(k);
            if (capa.size() > max) {
                pausar(id, "En Y=" + y + " la lava necesitaría más de " + max + " bloques: se está escapando (¿hay un agujero en la pared?). Quedó en pausa.", quien);
                return -1;
            }
            int bx = x(k), bz = z(k);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = bx + d[0], nz = bz + d[1];
                if (conLimite && (nx < lx1 || nx > lx2 || nz < lz1 || nz > lz2)) continue;
                long nk = xz(nx, nz);
                if (visto.contains(nk)) continue;
                if (!pasa(w.getBlockAt(nx, y, nz))) continue;
                visto.add(nk);
                pend.add(nk);
            }
        }
        if (capa.isEmpty()) { pausar(id, null, null); if (quien != null) TOD4Eventos.info(quien, "Arriba de la lava no hay más lugar (Y=" + y + "): terminó."); return -1; }
        List<Long> guardar = new ArrayList<>();
        for (long k : capa) {
            Block b = w.getBlockAt(x(k), y, z(k));
            guardar.add(k);
            if (llenable(b)) cola.add(b);
        }
        puesta.set(id + "." + y, guardar);
        c.set("y", y + 1);
        st.touch();
        efectos(w, capa, y);
        return guardar.size();
    }

    private void colocar() {
        int n = Math.max(50, pl.getConfig().getInt("lava.bloques_por_tick", 1500));
        while (n-- > 0 && !cola.isEmpty()) {
            Block b = cola.poll();
            if (llenable(b)) b.setType(Material.LAVA, false);
        }
    }

    private void efectos(World w, List<Long> capa, int y) {
        for (int i = 0; i < Math.min(25, capa.size()); i++) {
            long k = capa.get(rnd.nextInt(capa.size()));
            Location l = new Location(w, x(k) + 0.5, y + 1.0, z(k) + 0.5);
            w.spawnParticle(Particle.LAVA, l, 3, 0.3, 0.1, 0.3, 0);
            if (i < 4) w.playSound(l, i % 2 == 0 ? "block.lava.pop" : "item.bucket.empty_lava", SoundCategory.BLOCKS, 1.5f, 0.7f);
        }
        long k = capa.get(0);
        w.playSound(new Location(w, x(k), y, z(k)), "block.lava.ambient", SoundCategory.BLOCKS, 3f, 0.5f);
    }

    private void reset(String id) {
        pausar(id, null, null);
        ConfigurationSection c = sec(id);
        World w = Bukkit.getWorld(c.getString("mundo", ""));
        cola.removeIf(b -> true);
        ConfigurationSection capas = puesta.y().getConfigurationSection(id);
        if (w != null && capas != null) for (String ys : capas.getKeys(false)) {
            int y;
            try { y = Integer.parseInt(ys); } catch (NumberFormatException e) { continue; }
            for (Long k : capas.getLongList(ys)) {
                Block b = w.getBlockAt(x(k), y, z(k));
                if (b.getType() == Material.LAVA) b.setType(Material.AIR, false);
            }
        }
        puesta.set(id, null);
        puesta.save();
        c.set("y", c.getInt("inicio_y"));
        st.save();
    }

    // ================================================================= comando

    void comando(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        Player p = s instanceof Player pp ? pp : null;
        String id = a.length > 1 ? a[1].toLowerCase() : null;
        if (!List.of("", "ayuda", "pos1", "pos2", "lista", "crear").contains(sub) && (id == null || !existe(id))) {
            TOD4Eventos.err(s, id == null ? "Falta el nombre de la lava." : "No existe la lava &f" + id + "&c. Mira &f/tod4eventos:lava lista&c.");
            return;
        }
        switch (sub) {
            case "pos1", "pos2" -> { if (p != null) pl.marcar(p, sub.equals("pos1") ? 0 : 1); }
            case "crear" -> {
                if (p == null) return;
                if (id == null) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:lava crear <nombre> [altura máxima] &7(mirando el piso del fondo del volcán)"); return; }
                Block piso = TOD4Eventos.mirando(p);
                Block inicio = llenable(piso) ? piso : piso.getRelative(BlockFace.UP);
                int tope = inicio.getY() + 30;
                if (a.length > 2) try { tope = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
                ConfigurationSection c = sec(id);
                c.set("mundo", inicio.getWorld().getName());
                c.set("x", inicio.getX());
                c.set("z", inicio.getZ());
                c.set("inicio_y", inicio.getY());
                c.set("y", inicio.getY());
                c.set("altura", tope);
                c.set("segundos", pl.getConfig().getInt("lava.segundos_por_capa", 20));
                c.set("corriendo", false);
                st.save();
                TOD4Eventos.ok(s, "Lava &f" + id + "&a creada: arranca en &f" + inicio.getX() + " " + inicio.getY() + " " + inicio.getZ()
                        + "&a y sube hasta Y=&f" + tope + "&a, una capa cada &f" + c.getInt("segundos") + " s&a.");
                TOD4Eventos.info(s, "Para probar: &e/tod4eventos:lava capa " + id + " &7(sube una). Para que suba sola: &e/tod4eventos:lava empezar " + id);
            }
            case "altura" -> {
                if (a.length < 3) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:lava altura <nombre> <y>"); return; }
                try { sec(id).set("altura", Integer.parseInt(a[2])); } catch (NumberFormatException e) { TOD4Eventos.err(s, "Tiene que ser un número."); return; }
                st.save();
                TOD4Eventos.ok(s, "Altura máxima: Y=&f" + a[2]);
            }
            case "velocidad" -> {
                if (a.length < 3) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:lava velocidad <nombre> <segundos por capa>"); return; }
                try { sec(id).set("segundos", Math.max(1, Integer.parseInt(a[2]))); } catch (NumberFormatException e) { TOD4Eventos.err(s, "Tiene que ser un número."); return; }
                st.save();
                if (tareas.containsKey(id)) empezar(id);
                TOD4Eventos.ok(s, "Una capa cada &f" + a[2] + " s&a.");
            }
            case "limite" -> {
                ConfigurationSection c = sec(id);
                if (a.length > 2 && a[2].equalsIgnoreCase("quitar")) { c.set("limite", null); st.save(); TOD4Eventos.ok(s, "Límite quitado."); return; }
                if (p == null) return;
                Location[] sel = pl.seleccion(p);
                if (sel == null) return;
                c.set("limite.on", true);
                c.set("limite.x1", Math.min(sel[0].getBlockX(), sel[1].getBlockX()));
                c.set("limite.z1", Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()));
                c.set("limite.x2", Math.max(sel[0].getBlockX(), sel[1].getBlockX()));
                c.set("limite.z2", Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
                st.save();
                TOD4Eventos.ok(s, "La lava de &f" + id + "&a nunca va a salir de ese rectángulo (visto desde arriba).");
            }
            case "empezar" -> { empezar(id); TOD4Eventos.ok(s, "La lava &f" + id + "&a empezó a subir."); }
            case "pausar" -> { pausar(id, null, null); TOD4Eventos.ok(s, "Lava &f" + id + "&a en pausa."); }
            case "capa" -> {
                int n = capa(id, s);
                if (n >= 0) TOD4Eventos.ok(s, "Subió una capa (&f" + n + "&a bloques). Ahora va por Y=&f" + sec(id).getInt("y") + "&a.");
            }
            case "reset" -> { reset(id); TOD4Eventos.ok(s, "Lava &f" + id + "&a borrada: volvió al principio."); }
            case "borrar" -> {
                reset(id);
                st.set("lavas." + id, null);
                st.save();
                TOD4Eventos.ok(s, "Lava &f" + id + "&a borrada.");
            }
            case "info" -> {
                ConfigurationSection c = sec(id);
                TOD4Eventos.raw(s, "&6■ &f" + id + " &7Y actual &f" + c.getInt("y") + " &7/ máx &f" + c.getInt("altura") + " &7| cada &f" + c.getInt("segundos") + " s &7| "
                        + (tareas.containsKey(id) ? "&asubiendo" : "&cen pausa") + (c.getBoolean("limite.on") ? " &7| con límite" : ""));
            }
            case "lista" -> {
                if (ids().isEmpty()) { TOD4Eventos.info(s, "No hay lavas."); return; }
                for (String x : ids()) TOD4Eventos.raw(s, "&6■ &f" + x + " &7Y " + sec(x).getInt("y") + "/" + sec(x).getInt("altura") + " " + (tareas.containsKey(x) ? "&asubiendo" : "&cen pausa"));
            }
            default -> {
                TOD4Eventos.info(s, "&6Lava del volcán:");
                TOD4Eventos.raw(s, " &e/tod4eventos:lava crear <nombre> [altura] &8- &7mirando el piso del fondo");
                TOD4Eventos.raw(s, " &e... capa <nombre> &8- &7sube una capa ya &7/ &eempezar &7/ &epausar &7/ &ereset");
                TOD4Eventos.raw(s, " &e... velocidad <nombre> <seg> &7/ &ealtura <nombre> <y>");
                TOD4Eventos.raw(s, " &e... limite <nombre> &8- &7con pos1/pos2, por si hay huecos &7(&elimite <nombre> quitar&7)");
                TOD4Eventos.raw(s, " &e... info | borrar <nombre> &7/ &elista");
            }
        }
    }

    List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("pos1", "pos2", "crear", "capa", "empezar", "pausar", "reset", "velocidad", "altura", "limite", "info", "borrar", "lista"));
        else if (a.length == 2 && !List.of("pos1", "pos2", "lista", "crear").contains(a[0].toLowerCase())) out.addAll(ids());
        else if (a.length == 3 && a[0].equalsIgnoreCase("limite")) out.add("quitar");
        return out;
    }
}
