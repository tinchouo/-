package ar.tod4.creditos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * Una pasada de los creditos para un grupo de jugadores (todos, o solo uno para probar).
 *  - Todos quedan en espectador "pegados" a una camara invisible (no se pueden mover ni girar), adentro de una caja
 *    negra (no se ve el mundo).
 *  - Adelante de la camara, en un "plano" a "distancia" bloques, van los textos, el logo y las capturas (TextDisplay;
 *    el logo y las capturas son "letras" del pack).
 *    Todos estan en el mismo punto (justo adelante) y se mueven con la "translation" de su transformacion: asi el
 *    cliente los sube suave solo (interpolacion), sin mandar nada cada tick.
 *  - Las entidades solo las ven los que miran los creditos.
 * Las medidas estan en pixeles de pantalla (1280 x 720) como el video; "k" pasa de pixeles a bloques para el FOV 70.
 */
final class Show {

    private static final double MARGEN = 60;
    private static final int CW = 352, CH = 198, MARG = 36;

    private final TOD4Creditos pl;
    final List<Player> viendo;
    final boolean prueba;
    private final Contenido c;
    private final int desde;

    private Location ojo, centro;
    private double k;
    private ArmorStand camara;
    private final List<Entity> entidades = new ArrayList<>();
    private BukkitTask tarea;
    private int t;
    private boolean terminado;
    /** El plugin esta despegando la camara a proposito (no cancelarlo). */
    boolean soltando;

    // tiempos en ticks desde que arranca
    private int tMusica, tFlash, tSube, tTermina, tGracias, tColores, tSeVa, tFin;
    private double v;   // pixeles por tick

    private final List<Ev> eventos = new ArrayList<>();
    private int ev;
    private TextDisplay gracias, titulo;
    private int capturas;
    private final List<Fundido> fundidos = new ArrayList<>();
    private record Fundido(TextDisplay d, int t0, int t1, int anim) { }

    private interface Accion { void run(); }
    private record Ev(int tick, Accion a) { }

    Show(TOD4Creditos pl, List<Player> viendo, boolean prueba, Contenido c, int desdeSeg) {
        this.pl = pl;
        this.viendo = new ArrayList<>(viendo);
        this.prueba = prueba;
        this.c = c;
        this.desde = Math.max(0, desdeSeg);
    }

    private int ticks(String k, double def) { return (int) Math.round(pl.getConfig().getDouble("tiempos." + k, def) * 20); }

    /** Cuantas capturas entran (para /creditos datos), sin mostrar nada. */
    int contarCapturas() {
        tiempos();
        planearCapturas();
        return capturas;
    }

    private void tiempos() {
        int negro = ticks("negro_antes", 2.0);
        tMusica = negro;
        tFlash = negro + ticks("flash", 0.93);
        tSube = negro + ticks("empieza_a_subir", 6.0);
        tTermina = negro + ticks("termina_de_subir", 170.0);
        tGracias = negro + ticks("aparece_gracias", 171.5);
        tColores = negro + ticks("colores", 177.0);
        tSeVa = negro + ticks("se_va", 182.5);
        tFin = negro + ticks("fin", 185.5);
        v = c.alto / Math.max(1, tTermina - tSube);

        double dist = pl.getConfig().getDouble("pantalla.distancia", 6);
        double fov = pl.getConfig().getDouble("pantalla.fov", 70);
        k = 2 * dist * Math.tan(Math.toRadians(fov / 2)) / Contenido.H;
    }

    void empezar() {
        tiempos();
        double dist = pl.getConfig().getDouble("pantalla.distancia", 6);
        ojo = lugarCamara();
        Vector f = ojo.getDirection().setY(0).normalize();
        centro = ojo.clone().add(f.clone().multiply(dist));
        World w = ojo.getWorld();
        w.getChunkAt(ojo).load();

        camara = w.spawn(ojo.clone().subtract(0, 1.7875, 0), ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            a.setRotation(ojo.getYaw(), 0);
        });
        mostrar(camara);
        caja();

        for (Player p : viendo) pl.guardarYPreparar(p, this, ojo);
        for (Player a : viendo) for (Player b : viendo) if (a != b) a.hidePlayer(pl, b);

        planear();
        t = desde * 20;
        if (desde > 0) t = Math.max(t, tSube);
        // lo que ya tendria que estar (si se empieza mas adelante para probar)
        while (ev < eventos.size() && eventos.get(ev).tick < t) {
            Ev e = eventos.get(ev++);
            e.a().run();
        }
        tarea = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
        long caps = eventos.stream().filter(e -> e.tick() >= 0).count();
        pl.getLogger().info("[Creditos] empiezan para " + viendo.size() + " jugador(es)" + (prueba ? " (prueba)" : "") + ": "
                + c.items.size() + " renglones, " + Math.round(v * 20) + " px/s, camara en " + ojo.getWorld().getName() + " "
                + ojo.getBlockX() + " " + ojo.getBlockY() + " " + ojo.getBlockZ() + ", " + caps + " eventos, " + capturas + " capturas");
    }

    private Location lugarCamara() {
        String s = pl.getConfig().getString("pantalla.camara", "auto");
        World w = Bukkit.getWorlds().get(0);
        Location l = w.getSpawnLocation().clone();
        if (s != null && !s.equalsIgnoreCase("auto")) {
            // "mundo,x,y,z,yaw"
            String[] p = s.split(",");
            try {
                World ww = Bukkit.getWorld(p[0].trim());
                if (ww != null) w = ww;
                l = new Location(w, Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()),
                        p.length > 4 ? Float.parseFloat(p[4].trim()) : 180f, 0f);
            } catch (Exception e) {
                pl.getLogger().warning("pantalla.camara mal escrita (\"mundo,x,y,z,yaw\"): uso auto");
            }
        } else {
            l.setY(330);
            l.setYaw(180);
        }
        l.setX(l.getBlockX() + 0.5);
        l.setZ(l.getBlockZ() + 0.5);
        // mirando derecho a un punto cardinal (la caja es recta)
        l.setYaw(Math.round(l.getYaw() / 90f) * 90f);
        l.setPitch(0);
        return l;
    }

    private void mostrar(Entity e) {
        for (Player p : viendo) p.showEntity(pl, e);
        entidades.add(e);
    }

    /** Caja negra alrededor de la camara: 6 paredes finas (de adentro una caja no se ve). */
    private void caja() {
        double r = 18, g = 0.4, lado = 2 * r + 2;
        double[][] paredes = {
                {r, 0, 0, g, lado, lado}, {-r, 0, 0, g, lado, lado},
                {0, r, 0, lado, g, lado}, {0, -r, 0, lado, g, lado},
                {0, 0, r, lado, lado, g}, {0, 0, -r, lado, lado, g}};
        for (double[] p : paredes) {
            Location l = ojo.clone().add(p[0], p[1], p[2]);
            l.setYaw(0); l.setPitch(0);
            BlockDisplay b = ojo.getWorld().spawn(l, BlockDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.setBlock(Material.BLACK_CONCRETE.createBlockData());
                d.setBrightness(new Display.Brightness(0, 0));
                d.setViewRange(4f);
                d.setTransformation(new Transformation(new Vector3f((float) (-p[3] / 2), (float) (-p[4] / 2), (float) (-p[5] / 2)),
                        new AxisAngle4f(), new Vector3f((float) p[3], (float) p[4], (float) p[5]), new AxisAngle4f()));
            });
            mostrar(b);
        }
    }

    // ------------------------------------------------------------------------------------------------ plan

    private double scroll(double tick) { return tick <= tSube ? 0 : v * (tick - tSube); }

    private void planear() {
        for (Contenido.Item it : c.items) planearItem(it);
        planearCapturas();
        eventos.add(new Ev(tMusica, this::musica));
        eventos.add(new Ev(tFlash, this::flash));
        eventos.add(new Ev(tGracias, this::gracias));
        eventos.add(new Ev(tColores, () -> colores(0xFF5555, 0x781111)));
        eventos.add(new Ev(tColores + 10, () -> colores(0x55AAFF, 0x113388)));
        eventos.add(new Ev(tColores + 20, () -> colores(0x55FF55, 0x117811)));
        eventos.add(new Ev(tFin, this::terminar));
        eventos.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
    }

    private void planearItem(Contenido.Item it) {
        double sIn = it.y - Contenido.H - MARGEN, sOut = it.y + it.h + MARGEN;
        int spawn, mover;
        double sSpawn;
        if (sIn <= 0) {
            spawn = tFlash - 1;
            mover = tSube;
            sSpawn = 0;
        } else {
            mover = tSube + (int) Math.ceil(sIn / v);
            spawn = mover - 2;
            sSpawn = scroll(mover);
        }
        int dur = (int) Math.max(1, Math.round((sOut - sSpawn) / v));
        int quitar = mover + dur + 1;
        Display[] ref = new Display[1];
        eventos.add(new Ev(spawn, () -> ref[0] = crear(it, sSpawn)));
        eventos.add(new Ev(mover, () -> { if (ref[0] != null) mover(ref[0], it, sOut, dur); }));
        eventos.add(new Ev(quitar, () -> { if (ref[0] != null) { ref[0].remove(); entidades.remove(ref[0]); } }));
    }

    private Vector3f lugar(Contenido.Item it, double s) {
        float x = (float) ((it.x - Contenido.W / 2.0) * k);
        double arriba = it.y - s;
        float y = it.logo ? (float) ((Contenido.H / 2.0 - (arriba + it.h / 2)) * k)
                : (float) ((Contenido.H / 2.0 - (arriba + 8 * it.s)) * k);
        return new Vector3f(x, y, 0);
    }

    private float escala(Contenido.Item it) { return it.logo ? (float) (it.h * k / (512 * 0.025)) : (float) (it.s * k / 0.025); }

    /** Una imagen del pack: 4 pedazos (la letra de Minecraft no deja glifos de mas de 256) armados con espacios negativos. */
    static Component imagen(String fuente) {
        return Component.text("\uE001\uF801\uE002\uF802\uE003\uF801\uE004", net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .font(Key.key(fuente));
    }

    private Display crear(Contenido.Item it, double s) {
        Transformation tr = new Transformation(lugar(it, s), new AxisAngle4f(), new Vector3f(escala(it)), new AxisAngle4f());
        Display d;
        if (it.logo) {
            d = centro.getWorld().spawn(centro, TextDisplay.class, x -> {
                x.text(imagen(pl.getConfig().getString("logo", "creditos:logo")));
                texto(x);
                x.setShadowed(false);
                base(x, tr);
            });
        } else {
            d = centro.getWorld().spawn(centro, TextDisplay.class, x -> {
                x.text(it.texto);
                texto(x);
                base(x, tr);
            });
        }
        mostrar(d);
        return d;
    }

    private void base(Display x, Transformation tr) {
        x.setVisibleByDefault(false);
        x.setPersistent(false);
        x.setBillboard(Display.Billboard.CENTER);
        x.setBrightness(new Display.Brightness(15, 15));
        x.setViewRange(4f);
        x.setTransformation(tr);
    }

    private static void texto(TextDisplay x) {
        x.setLineWidth(2000);
        x.setDefaultBackground(false);
        x.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        x.setShadowed(true);
        x.setSeeThrough(false);
        x.setAlignment(TextDisplay.TextAlignment.CENTER);
    }

    private void mover(Display d, Contenido.Item it, double sOut, int dur) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(dur);
        d.setTransformation(new Transformation(lugar(it, sOut), new AxisAngle4f(), new Vector3f(escala(it)), new AxisAngle4f()));
    }


    // ------------------------------------------------------------------------------------------------ capturas

    /** Las capturas quedan quietas en la pantalla (al medio, a un costado), aparecen y desaparecen, una de cada lado. */
    private void planearCapturas() {
        List<String> caps = pl.getConfig().getStringList("capturas");
        if (caps.isEmpty() && pl.getConfig().getDefaults() != null) caps = pl.getConfig().getDefaults().getStringList("capturas");
        if (caps.isEmpty()) return;
        int vis = (int) Math.round(pl.getConfig().getDouble("capturas_segundos", 5.0) * 20);
        int anim = 12, total = vis + 2 * anim, hueco = 16;
        int tt = tSube + 60, n = 0, lado = 0;
        while (tt + total < tTermina && n < caps.size()) {
            if (scroll(tt + total) + Contenido.H >= c.inicioJugadores) break;
            boolean izq = lado % 2 == 0;
            Boolean donde = libre(tt, tt + total, izq) ? Boolean.valueOf(izq) : libre(tt, tt + total, !izq) ? Boolean.valueOf(!izq) : null;
            if (donde == null) { tt += 5; continue; }
            final String fuente = caps.get(n);
            final boolean iz = donde;
            final int t0 = tt, t1 = tt + total;
            float cx = (float) (((iz ? MARG + CW / 2.0 : Contenido.W - MARG - CW / 2.0) - Contenido.W / 2.0) * k);
            float sc = (float) (CH * k / (288 * 0.025));
            eventos.add(new Ev(t0, () -> {
                TextDisplay x = centro.getWorld().spawn(centro, TextDisplay.class, d -> {
                    d.text(imagen(fuente));
                    texto(d);
                    d.setShadowed(false);
                    base(d, new Transformation(new Vector3f(cx, 0, 0), new AxisAngle4f(), new Vector3f(sc), new AxisAngle4f()));
                    d.setTextOpacity((byte) 26);
                });
                mostrar(x);
                fundidos.add(new Fundido(x, t0, t1, anim));
            }));
            n++;
            capturas++;
            lado = iz ? 1 : 0;
            tt += total + hueco;
        }
    }

    /** Que el texto no se pise con la captura en todo ese rato. */
    private boolean libre(int t0, int t1, boolean izq) {
        double yc = (Contenido.H - CH) / 2.0;
        for (int tt = t0; tt <= t1; tt += 2) {
            double s = scroll(tt), a = s + yc - 12, b = s + yc + CH + 12;
            for (Contenido.Item it : c.items) {
                if (it.y + it.h < a || it.y > b) continue;
                double izqX = it.x - it.ancho / 2, derX = it.x + it.ancho / 2;
                if (izq && izqX <= MARG + CW + 30) return false;
                if (!izq && derX >= Contenido.W - MARG - CW - 30) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ momentos

    private void musica() {
        if (desde > 0) return;
        String s = pl.getConfig().getString("musica", "creditos:musica");
        Key key = Key.key(s);
        for (Player p : viendo) p.playSound(Sound.sound(key, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self());
    }

    private void flash() {
        if (desde > 0) return;
        Key font = Key.key(pl.getConfig().getString("flash", "creditos:flash"));
        Component blanco = Component.text("").font(font);
        Title ti = Title.title(blanco, Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(1200)));
        for (Player p : viendo) p.showTitle(ti);
    }

    private void gracias() {
        gracias = textoFinal(Component.text("Gracias por jugar", Contenido.BL), 5, Contenido.H / 2.0 - 95);
        titulo = textoFinal(Contenido.tituloFinal(0x55FF55, 0x117811), 9, Contenido.H / 2.0 - 10);
    }

    private TextDisplay textoFinal(Component comp, int s, double arriba) {
        float y = (float) ((Contenido.H / 2.0 - (arriba + 8 * s)) * k);
        float sc = (float) (s * k / 0.025);
        TextDisplay d = centro.getWorld().spawn(centro, TextDisplay.class, x -> {
            x.text(comp);
            texto(x);
            base(x, new Transformation(new Vector3f(0, y, 0), new AxisAngle4f(), new Vector3f(sc), new AxisAngle4f()));
            x.setTextOpacity((byte) 26);
        });
        mostrar(d);
        return d;
    }

    private void colores(int a, int b) { if (titulo != null) titulo.text(Contenido.tituloFinal(a, b)); }

    private void tick() {
        if (terminado) return;
        // la camara siempre trabada (aunque aprieten shift)
        int desdeInicio = t - desde * 20;
        for (Player p : new ArrayList<>(viendo)) {
            if (!p.isOnline()) { viendo.remove(p); continue; }
            // al principio el cliente puede no conocer todavia a la camara (lo acabamos de traer de lejos): se la vuelve
            // a mandar unas veces. Despues, si se despego o se movio aunque sea un poco, se lo vuelve a pegar.
            boolean reenganchar = desdeInicio == 5 || desdeInicio == 15 || desdeInicio == 30 || desdeInicio == 60;
            boolean suelto = p.getSpectatorTarget() != camara || p.getWorld() != camara.getWorld()
                    || p.getLocation().distanceSquared(camara.getLocation()) > 0.25;
            if (reenganchar || suelto) {
                if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                soltando = true;   // que el plugin no se cancele a si mismo (ver dejaDeMirar)
                try { if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null); } finally { soltando = false; }
                p.teleport(camara);
                p.setSpectatorTarget(camara);
            }
        }
        if (viendo.isEmpty()) { terminar(); return; }
        while (ev < eventos.size() && eventos.get(ev).tick <= t) {
            try { eventos.get(ev).a().run(); } catch (Exception e) { pl.getLogger().warning("Creditos: " + e); }
            ev++;
            if (terminado) return;
        }
        // las capturas: aparecen y se van de a poco
        for (java.util.Iterator<Fundido> i = fundidos.iterator(); i.hasNext(); ) {
            Fundido f = i.next();
            if (t >= f.t1()) { f.d().remove(); entidades.remove(f.d()); i.remove(); continue; }
            double a = Math.min(1, Math.min((t - f.t0()) / (double) f.anim(), (f.t1() - t) / (double) f.anim()));
            f.d().setTextOpacity((byte) Math.max(26, Math.min(255, (int) Math.round(26 + 229 * Math.max(0, a)))));
        }
        // aparecer / irse de a poco
        if (gracias != null && titulo != null) {
            int op = 255;
            if (t < tGracias + 30) op = 26 + (int) ((255 - 26) * Math.max(0, t - tGracias) / 30.0);
            else if (t >= tSeVa) op = (int) (255 - (255 - 26) * Math.min(1, (t - tSeVa) / 30.0));
            byte b = (byte) Math.max(26, Math.min(255, op));
            gracias.setTextOpacity(b);
            titulo.setTextOpacity(b);
            if (t >= tSeVa + 30) { gracias.remove(); titulo.remove(); gracias = null; titulo = null; }
        }
        t++;
    }

    void terminar() {
        if (terminado) return;
        terminado = true;
        soltando = true;
        if (tarea != null) tarea.cancel();
        String s = pl.getConfig().getString("musica", "creditos:musica");
        for (Player p : viendo) {
            if (!p.isOnline()) continue;
            p.stopSound(SoundStop.named(Key.key(s)));
            p.clearTitle();
        }
        for (Player a : viendo) for (Player b : viendo) if (a != b && a.isOnline() && b.isOnline()) a.showPlayer(pl, b);
        for (Player p : viendo) if (p.isOnline()) pl.restaurar(p);
        for (Entity e : entidades) e.remove();
        entidades.clear();
        pl.getLogger().info("[Creditos] terminaron (tick " + t + ")");
        pl.showTermino(this);
    }

    boolean incluye(Player p) { return viendo.contains(p); }

    /** Hay que mantenerle la camara pegada (no la esta soltando el plugin). */
    boolean trabar(Player p) { return !terminado && !soltando && viendo.contains(p); }

    Entity camara() { return camara; }

    static Map<String, Object> estado(Player p) {
        Map<String, Object> m = new HashMap<>();
        m.put("lugar", p.getLocation());
        m.put("modo", p.getGameMode().name());
        m.put("volaba", p.isFlying());
        m.put("puede_volar", p.getAllowFlight());
        return m;
    }

}
