package ar.tod4.eventos.agujero;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La forma del Agujero del Infierno 2.0 (sin nada de Bukkit, asi se puede probar y dibujar afuera del server).
 *
 * Una cueva cerrada dentro de una caja de bedrock: una sala de entrada (la "linea" es salir de esa sala), tuneles anchos
 * (8 a 11 bloques) que unen cuatro salas grandes, pasillos chicos (3 a 5) con salidas sin salida y nichos altos.
 *  - Sala de columnas: columnas de estalactita del piso al techo y estalactitas/estalagmitas.
 *  - Lago de lava: un lago con caminos de basalto que lo cruzan y una isla en el medio.
 *  - Catedral: una sala altisima con piedras en punta gigantes colgando del techo y saliendo del piso.
 *  - Pozo: un pozo hondo sin salida (se entra por arriba).
 * Todo sale de la semilla: misma semilla, misma cueva.
 */
public final class CuevaGen {

    // ================================================================= paleta

    public static final String[] PALETA = {
        "air", "stone", "deepslate", "tuff", "andesite", "granite", "blackstone", "basalt", "magma_block", "lava",
        "dripstone_block", "bedrock", "cobbled_deepslate", "smooth_basalt", "calcite",
        "pointed_dripstone[vertical_direction=down,thickness=tip]",
        "pointed_dripstone[vertical_direction=down,thickness=frustum]",
        "pointed_dripstone[vertical_direction=down,thickness=middle]",
        "pointed_dripstone[vertical_direction=down,thickness=base]",
        "pointed_dripstone[vertical_direction=up,thickness=tip]",
        "pointed_dripstone[vertical_direction=up,thickness=frustum]",
        "pointed_dripstone[vertical_direction=up,thickness=middle]",
        "pointed_dripstone[vertical_direction=up,thickness=base]",
        "glow_lichen[north=true]", "glow_lichen[south=true]", "glow_lichen[east=true]", "glow_lichen[west=true]",
        "glow_lichen[up=true]",
    };
    public static final short AIRE = 0, PIEDRA = 1, PIZARRA = 2, TOBA = 3, ANDESITA = 4, GRANITO = 5, NEGRA = 6, BASALTO = 7,
            MAGMA = 8, LAVA = 9, GOTEO = 10, BEDROCK = 11, PIZARRA_ROTA = 12, BASALTO_LISO = 13, CALCITA = 14,
            PUNTA_ABAJO = 15, PUNTA_ARRIBA = 19, LIQUEN = 23;

    // ================================================================= limites

    public static final int MIN_X = -110, MAX_X = 110, MIN_Z = -110, MAX_Z = 110, MIN_Y = 4, MAX_Y = 104;

    // ================================================================= piezas

    /** Un tramo de tunel: esfera achatada (mas ancha que alta) con el piso plano. */
    static final class Seg {
        final double x, y, z, r; final boolean chico;
        Seg(double x, double y, double z, double r, boolean chico) { this.x = x; this.y = y; this.z = z; this.r = r; this.chico = chico; }
    }

    /** Una sala: elipsoide con el piso plano a la altura piso. */
    public static final class Sala {
        public final String tipo; public final double x, y, z, rx, ry, rz, piso;
        Sala(String tipo, double x, double piso, double z, double rx, double ry, double rz) {
            this.tipo = tipo; this.x = x; this.z = z; this.rx = rx; this.ry = ry; this.rz = rz; this.piso = piso; this.y = piso + ry * 0.55;
        }
    }

    /** Columna (del piso al techo, mas finita en el medio) o cono (piedra en punta: cuelga del techo o sale del piso). */
    static final class Forma {
        final double x, z, r, y0, y1; final int tipo;   // 0 columna, 1 cono que cuelga (base arriba), 2 cono que sube (base abajo)
        Forma(double x, double z, double r, double y0, double y1, int tipo) { this.x = x; this.z = z; this.r = r; this.y0 = y0; this.y1 = y1; this.tipo = tipo; }
    }

    /** Un lugar para un cofre (facil o dificil) o para un mob. */
    public static final class Punto {
        public final String tipo; public final int x, y, z;
        Punto(String tipo, double x, double y, double z) { this.tipo = tipo; this.x = (int) Math.floor(x); this.y = (int) Math.floor(y); this.z = (int) Math.floor(z); }
    }

    private final long semilla;
    private final List<Seg> segs = new ArrayList<>();
    private final List<Sala> salas = new ArrayList<>();
    private final List<Forma> formas = new ArrayList<>();
    private final List<Punto> puntos = new ArrayList<>();
    /** El camino de basalto del lago (polilinea en x,z) y su altura. */
    private final List<double[]> camino = new ArrayList<>();
    private Sala lago, pozo;
    private double rampa0;
    /** Piso donde llega el tunel al pozo; de ahi baja la rampa hasta el fondo (12 bloques por vuelta). */
    private static final double POZO_ARRIBA = 40, POZO_POR_VUELTA = 12;
    private double nivelLava;
    private final Map<Long, int[]> porChunk = new ConcurrentHashMap<>();
    public final Sala entrada;

    public CuevaGen(long semilla) {
        this.semilla = semilla;
        Random r = new Random(semilla);
        // ---- salas
        // pisos parecidos entre si: pocas subidas y bajadas
        entrada = sala("entrada", 0, 44, -98, 7, 8, 7);
        Sala junta = sala("cruce", 0, 42, -50, 11, 9, 10);
        Sala columnas = sala("columnas", -58, 40, -18, 25, 15, 21);
        lago = sala("lago", 58, 33, 8, 23, 17, 25);
        nivelLava = lago.piso + 3;
        Sala catedral = sala("catedral", 2, 38, 58, 27, 34, 23);
        pozo = sala("pozo", 70, 10, 78, 8, 30, 8);
        Sala oeste = sala("galeria", -78, 42, 62, 13, 10, 15);

        // ---- tuneles anchos (piso = el de la sala de donde salen y adonde llegan; el lago entra a la altura del camino)
        double lagoPiso = nivelLava + 1;
        tunel(r, entrada.x, entrada.piso, entrada.z, junta.x, junta.piso, junta.z, 5.0, false);
        tunel(r, junta.x, junta.piso, junta.z, columnas.x + 14, columnas.piso, columnas.z - 8, 5.2, false);
        tunel(r, junta.x, junta.piso, junta.z, lago.x - 16, lagoPiso, lago.z - 12, 4.8, false);
        tunel(r, columnas.x + 8, columnas.piso, columnas.z + 14, catedral.x - 16, catedral.piso, catedral.z - 10, 5.0, false);
        tunel(r, lago.x - 6, lagoPiso, lago.z + 18, catedral.x + 18, catedral.piso, catedral.z - 4, 4.6, false);
        tunel(r, columnas.x - 6, columnas.piso, columnas.z + 16, oeste.x, oeste.piso, oeste.z - 10, 4.4, false);
        tunel(r, oeste.x + 10, oeste.piso, oeste.z + 4, catedral.x - 22, catedral.piso + 4, catedral.z + 6, 3.6, false);   // entra un poco alto a la catedral
        // al pozo se entra por arriba: el tunel llega a la boca y el pozo baja ~28 bloques
        // al pozo se llega por arriba y se baja por una rampa en espiral pegada a la pared (el medio es un hueco hasta el fondo)
        tunel(r, lago.x + 14, lagoPiso, lago.z + 18, pozo.x - 1, POZO_ARRIBA, pozo.z - 8, 3.8, false);
        rampa0 = Math.atan2(-8, -1);

        // ---- pasillos chicos: algunos sin salida (con un nicho al final), otros que unen tuneles
        List<Seg> anchos = new ArrayList<>(segs);
        int sinSalida = 0;
        for (int i = 0; i < 26; i++) {
            Seg a = anchos.get(r.nextInt(anchos.size()));
            double ang = r.nextDouble() * Math.PI * 2, largo = 22 + r.nextDouble() * 34;
            double bx = a.x + Math.cos(ang) * largo, bz = a.z + Math.sin(ang) * largo, by = a.y - a.r * 0.6 + (r.nextDouble() - 0.4) * 10;
            bx = Math.max(MIN_X + 14, Math.min(MAX_X - 14, bx));
            bz = Math.max(MIN_Z + 14, Math.min(MAX_Z - 14, bz));
            by = Math.max(MIN_Y + 10, Math.min(MAX_Y - 16, by));
            boolean une = i % 3 == 2;
            if (une) {   // va hasta otro tunel ancho cercano
                Seg b = cercano(anchos, bx, by, bz, a);
                if (b != null) { tunel(r, a.x, a.y - a.r * 0.6, a.z, b.x, b.y - b.r * 0.6, b.z, 1.8 + r.nextDouble() * 0.6, true); continue; }
            }
            tunel(r, a.x, a.y - a.r * 0.6, a.z, bx, by, bz, 1.6 + r.nextDouble() * 0.8, true);
            Sala n = sala("nicho", bx, by, bz, 3.2, 3.5, 3.2);
            puntos.add(new Punto("dificil", n.x, n.piso, n.z));
            sinSalida++;
        }
        // nichos altos en las paredes de las salas grandes (hay que subir para llegar)
        for (Sala s : new Sala[]{columnas, catedral, lago, oeste}) {
            for (int k = 0; k < 2; k++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double nx = s.x + Math.cos(ang) * s.rx * 0.92, nz = s.z + Math.sin(ang) * s.rz * 0.92;
                double ny = s.piso + 6 + r.nextDouble() * 5;
                Sala n = sala("nicho_alto", nx, ny, nz, 2.6, 3.2, 2.6);
                puntos.add(new Punto("dificil", n.x, n.piso, n.z));
            }
        }
        puntos.add(new Punto("dificil", pozo.x, pozo.piso, pozo.z));                 // el fondo del pozo
        puntos.add(new Punto("dificil", lago.x, nivelLava + 1, lago.z));               // la isla del lago

        // ---- adornos
        for (int i = 0; i < 9; i++) {   // columnas
            double ang = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * 0.72;
            formas.add(new Forma(columnas.x + Math.cos(ang) * columnas.rx * d, columnas.z + Math.sin(ang) * columnas.rz * d,
                    1.6 + r.nextDouble() * 1.8, columnas.piso - 2, columnas.y + columnas.ry + 2, 0));
        }
        for (int i = 0; i < 8; i++) {   // piedras en punta gigantes que cuelgan
            double ang = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * 0.7;
            double x = catedral.x + Math.cos(ang) * catedral.rx * d, z = catedral.z + Math.sin(ang) * catedral.rz * d;
            double techo = techo(catedral, x, z);
            double largo = 12 + r.nextDouble() * 12;
            formas.add(new Forma(x, z, 2.6 + r.nextDouble() * 2.2, techo - largo, techo + 3, 1));
        }
        for (int i = 0; i < 6; i++) {   // y que suben del piso
            double ang = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * 0.75;
            double x = catedral.x + Math.cos(ang) * catedral.rx * d, z = catedral.z + Math.sin(ang) * catedral.rz * d;
            formas.add(new Forma(x, z, 2.0 + r.nextDouble() * 1.6, catedral.piso - 2, catedral.piso + 7 + r.nextDouble() * 9, 2));
        }
        // camino del lago: de cada boca de tunel a la isla del medio
        camino.add(new double[]{lago.x - 16, lago.z - 12, lago.x, lago.z});
        camino.add(new double[]{lago.x - 6, lago.z + 18, lago.x, lago.z});
        camino.add(new double[]{lago.x + 14, lago.z + 18, lago.x, lago.z});

        // ---- cofres faciles y mobs: a lo largo de los tuneles anchos, contra la pared
        int k = 0;
        for (Seg s : segs) {
            if (s.chico) continue;
            if (k++ % 23 != 11) continue;
            double ang = r.nextDouble() * Math.PI * 2;
            puntos.add(new Punto("facil", s.x + Math.cos(ang) * s.r * 0.55, s.y - s.r * 0.6, s.z + Math.sin(ang) * s.r * 0.55));
        }
        k = 0;
        for (Seg s : segs) if (!s.chico && k++ % 17 == 5) puntos.add(new Punto("mob", s.x, s.y - s.r * 0.6, s.z));
        for (Sala s : salas) {
            if (s.tipo.equals("entrada") || s.tipo.startsWith("nicho")) continue;
            for (int i = 0; i < 3; i++) {
                double ang = r.nextDouble() * Math.PI * 2, d = 0.3 + r.nextDouble() * 0.45;
                puntos.add(new Punto(i == 0 ? "facil" : "mob", s.x + Math.cos(ang) * s.rx * d, s.piso, s.z + Math.sin(ang) * s.rz * d));
            }
        }
    }

    private Sala sala(String tipo, double x, double piso, double z, double rx, double ry, double rz) {
        Sala s = new Sala(tipo, x, piso, z, rx, ry, rz);
        salas.add(s);
        return s;
    }

    private static double techo(Sala s, double x, double z) {
        double q = 1 - Math.pow((x - s.x) / s.rx, 2) - Math.pow((z - s.z) / s.rz, 2);
        return s.y + s.ry * Math.sqrt(Math.max(0, q)) * 0.92;
    }

    private static Seg cercano(List<Seg> l, double x, double y, double z, Seg no) {
        Seg mejor = null;
        double bd = Double.MAX_VALUE;
        for (Seg s : l) {
            double d = (s.x - x) * (s.x - x) + (s.y - y) * (s.y - y) + (s.z - z) * (s.z - z);
            double dn = (s.x - no.x) * (s.x - no.x) + (s.z - no.z) * (s.z - no.z);
            if (dn < 30 * 30) continue;
            if (d < bd) { bd = d; mejor = s; }
        }
        return mejor != null && bd < 60 * 60 ? mejor : null;
    }

    /**
     * Un tunel de A a B (pisos dados): avanza de a un bloque hacia B, serpenteando, sin subir ni bajar mas de 1 cada 3
     * bloques (se puede caminar). Cada paso es un Seg.
     */
    private void tunel(Random r, double ax, double apiso, double az, double bx, double bpiso, double bz, double radio, boolean chico) {
        double x = ax, piso = apiso, z = az;
        double fase = r.nextDouble() * 1000, amp = chico ? 0.9 : 0.6;
        int pasos = 0, max = (int) (Math.hypot(bx - ax, bz - az) * 2.5) + 40;
        while (pasos++ < max) {
            double dx = bx - x, dz = bz - z, d = Math.hypot(dx, dz);
            if (d < 1.5 && Math.abs(bpiso - piso) < 1.5) break;
            double ang = Math.atan2(dz, dx);
            // serpentea menos cuando esta cerca (para llegar)
            ang += Math.sin(fase + pasos * 0.045) * amp * Math.min(1, d / 30) + Math.sin(fase * 1.7 + pasos * 0.11) * amp * 0.4 * Math.min(1, d / 20);
            if (d >= 1) { x += Math.cos(ang); z += Math.sin(ang); }
            // sube y baja un poco por el camino (lomas), sin pasarse de lo caminable
            double loma = chico ? 0 : 1.2 * Math.sin(fase * 0.3 + pasos * 0.05) * Math.min(1, d / 25);
            double falta = bpiso + loma - piso, quedan = Math.max(1, d);
            double sube = Math.max(-0.25, Math.min(0.25, falta / quedan * 1.4 + (loma != 0 ? Math.cos(fase * 0.3 + pasos * 0.05) * 0.15 : 0)));
            if (d < 1) sube = Math.max(-0.25, Math.min(0.25, falta));
            piso += sube;
            x = Math.max(MIN_X + 8, Math.min(MAX_X - 8, x));
            z = Math.max(MIN_Z + 8, Math.min(MAX_Z - 8, z));
            double rr = radio * (1 + 0.22 * Math.sin(fase * 0.7 + pasos * 0.06) + 0.12 * Math.sin(fase * 1.3 + pasos * 0.17));
            segs.add(new Seg(x, piso + rr * 0.6, z, rr, chico));
            // de vez en cuando se ensancha (un bolson a un costado)
            if (!chico && pasos % 37 == 19 && d > 12) {
                double lado = (r.nextBoolean() ? 1 : -1) * rr * 0.8, a2 = ang + Math.PI / 2;
                double br = rr * (1.2 + r.nextDouble() * 0.5);
                segs.add(new Seg(x + Math.cos(a2) * lado, piso + br * 0.6, z + Math.sin(a2) * lado, br, false));
            }
        }
    }

    // ================================================================= ruido (determinista, sin dependencias)

    private int hash(int x, int y, int z) {
        int h = (int) semilla ^ (int) (semilla >>> 32);
        h ^= x * 0x27d4eb2d; h = Integer.rotateLeft(h, 13) * 0x165667b1;
        h ^= y * 0x2c1b3c6d; h = Integer.rotateLeft(h, 11) * 0x297a2d39;
        h ^= z * 0x6c8e9cf5; h = Integer.rotateLeft(h, 15) * 0x85ebca6b;
        h ^= h >>> 16; h *= 0xc2b2ae35; h ^= h >>> 13;
        return h;
    }

    private double rnd(int x, int y, int z) { return (hash(x, y, z) & 0xffffff) / (double) 0x1000000; }

    private static double suave(double t) { return t * t * (3 - 2 * t); }

    /** Ruido de valor 3D, de -1 a 1. */
    double ruido(double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = suave(x - x0), fy = suave(y - y0), fz = suave(z - z0);
        double c000 = rnd(x0, y0, z0), c100 = rnd(x0 + 1, y0, z0), c010 = rnd(x0, y0 + 1, z0), c110 = rnd(x0 + 1, y0 + 1, z0);
        double c001 = rnd(x0, y0, z0 + 1), c101 = rnd(x0 + 1, y0, z0 + 1), c011 = rnd(x0, y0 + 1, z0 + 1), c111 = rnd(x0 + 1, y0 + 1, z0 + 1);
        double a = c000 + (c100 - c000) * fx, b = c010 + (c110 - c010) * fx, c = c001 + (c101 - c001) * fx, d = c011 + (c111 - c011) * fx;
        double e = a + (b - a) * fy, f = c + (d - c) * fy;
        return (e + (f - e) * fz) * 2 - 1;
    }

    // ================================================================= que hay en cada bloque

    /** Los Seg que pueden tocar este chunk (indices). */
    private int[] segsDe(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        return porChunk.computeIfAbsent(key, kk -> {
            double x0 = cx * 16 - 4, x1 = cx * 16 + 20, z0 = cz * 16 - 4, z1 = cz * 16 + 20;
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                double m = s.r + 2;
                if (s.x + m < x0 || s.x - m > x1 || s.z + m < z0 || s.z - m > z1) continue;
                l.add(i);
            }
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            return a;
        });
    }

    private boolean hueco(int x, int y, int z, int[] cerca) {
        double n = ruido(x * 0.09, y * 0.11, z * 0.09) * 0.75 + ruido(x * 0.23, y * 0.23, z * 0.23) * 0.25;
        for (int i : cerca) {
            Seg s = segs.get(i);
            double rr = s.r + (s.chico ? 0.7 : 2.0) * n;
            if (y < s.y - s.r * 0.6) continue;                 // piso plano
            double dx = x + 0.5 - s.x, dy = (y + 0.5 - s.y) * 1.25, dz = z + 0.5 - s.z;
            if (dx * dx + dy * dy + dz * dz <= rr * rr) return true;
        }
        for (Sala s : salas) {
            if (y < s.piso) continue;
            double f = 1 + (s.tipo.startsWith("nicho") ? 0.05 : 0.12) * n;
            double dx = (x + 0.5 - s.x) / s.rx, dy = (y + 0.5 - s.y) / s.ry, dz = (z + 0.5 - s.z) / s.rz;
            if (dx * dx + dy * dy + dz * dz <= f * f) return true;
        }
        return false;
    }

    /** Solido de un adorno (columna o piedra en punta). */
    private boolean adorno(int x, int y, int z) {
        double px = x + 0.5, pz = z + 0.5, py = y + 0.5;
        for (Forma f : formas) {
            if (py < f.y0 || py > f.y1) continue;
            double d = Math.hypot(px - f.x, pz - f.z);
            if (d > f.r + 1) continue;
            if (f.tipo == 3 && (y < f.y0 || y >= f.y1)) continue;
            double t = (py - f.y0) / (f.y1 - f.y0);   // 0 abajo, 1 arriba
            double r = switch (f.tipo) {
                case 0 -> f.r * (0.7 + 0.6 * Math.abs(t - 0.5));                     // mas finita en el medio
                case 3 -> f.r;                                                        // escalon (disco)
                case 1 -> f.r * Math.pow(Math.max(0, t), 0.9);                        // cuelga: base arriba, punta abajo
                default -> f.r * Math.pow(Math.max(0, 1 - t), 0.9);                   // sube: base abajo
            };
            if (f.tipo != 3) r += 0.35 * ruido(x * 0.3, y * 0.3, z * 0.3);
            if (d <= r) return true;
        }
        return false;
    }

    /** La rampa del pozo: una franja pegada a la pared que baja en espiral; el bloque de abajo del piso es solido. */
    private boolean rampa(int x, int y, int z) {
        double dx = x + 0.5 - pozo.x, dz = z + 0.5 - pozo.z, rho = Math.hypot(dx, dz);
        if (rho < 3.6 || rho > 9.5 || y < pozo.piso - 1 || y > POZO_ARRIBA) return false;
        double phi = Math.atan2(dz, dx) - rampa0;
        phi = ((phi % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
        double vueltas = (POZO_ARRIBA - pozo.piso) / POZO_POR_VUELTA;
        for (int k = 0; k <= Math.ceil(vueltas); k++) {
            double f = phi + Math.PI * 2 * k;
            if (f / (Math.PI * 2) > vueltas) break;
            double piso = Math.max(pozo.piso, Math.floor(POZO_ARRIBA - f / (Math.PI * 2) * POZO_POR_VUELTA));
            if (y == piso - 1 || y == piso - 2) return true;
        }
        return false;
    }

    private boolean enLago(int x, int z) {
        double dx = (x + 0.5 - lago.x) / lago.rx, dz = (z + 0.5 - lago.z) / lago.rz;
        return dx * dx + dz * dz < 1;
    }

    private boolean enCamino(int x, int z) {
        double px = x + 0.5, pz = z + 0.5;
        if (Math.hypot(px - lago.x, pz - lago.z) < 5.5) return true;   // la isla
        for (double[] c : camino) {
            double vx = c[2] - c[0], vz = c[3] - c[1], l2 = vx * vx + vz * vz;
            double t = Math.max(0, Math.min(1, ((px - c[0]) * vx + (pz - c[1]) * vz) / l2));
            double qx = c[0] + vx * t, qz = c[1] + vz * t;
            double ancho = 1.7 + 0.6 * ruido(px * 0.2, 0, pz * 0.2);
            if (Math.hypot(px - qx, pz - qz) < ancho) return true;
        }
        return false;
    }

    /** La roca de cada lugar: pizarra abajo, piedra arriba, vetas de toba, andesita y granito; piedra negra cerca del lago. */
    private short roca(int x, int y, int z) {
        double n1 = ruido(x * 0.045, y * 0.06, z * 0.045), n2 = ruido(x * 0.09 + 50, y * 0.09, z * 0.09 + 50);
        double dl = Math.hypot((x - lago.x) / (lago.rx + 14), (z - lago.z) / (lago.rz + 14));
        if (dl < 1 && n2 > -0.2) return n1 > 0.3 ? BASALTO : NEGRA;
        if (n2 > 0.62) return TOBA;
        if (n2 < -0.66) return n1 > 0 ? ANDESITA : GRANITO;
        return y < 40 + 5 * n1 ? PIZARRA : PIEDRA;
    }

    private Sala salaDe(int x, int y, int z, String tipo) {
        for (Sala s : salas) {
            if (!s.tipo.equals(tipo)) continue;
            double dx = (x + 0.5 - s.x) / (s.rx + 3), dy = (y + 0.5 - s.y) / (s.ry + 3), dz = (z + 0.5 - s.z) / (s.rz + 3);
            if (dx * dx + dy * dy + dz * dz <= 1) return s;
        }
        return null;
    }

    /**
     * Un chunk entero: [ (y - MIN_Y) * 256 + z * 16 + x ] con x,z de 0 a 15 y y de MIN_Y a MAX_Y.
     * Afuera de la caja todo aire (vacio); el borde de la caja es bedrock.
     */
    public short[] chunk(int cx, int cz) {
        int alto = MAX_Y - MIN_Y + 1;
        short[] out = new short[alto * 256];
        int[] cerca = segsDe(cx, cz);
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = cx * 16 + lx, z = cz * 16 + lz;
            if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z) continue;
            boolean borde = x == MIN_X || x == MAX_X || z == MIN_Z || z == MAX_Z;
            boolean lago = enLago(x, z), camino = lago && enCamino(x, z);
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                int i = (y - MIN_Y) * 256 + lz * 16 + lx;
                if (borde || y == MIN_Y || y == MAX_Y) { out[i] = BEDROCK; continue; }
                boolean h = hueco(x, y, z, cerca);
                if (!h) { out[i] = roca(x, y, z); continue; }
                if (lago && y <= nivelLava && salaDe(x, y, z, "lago") != null) {
                    out[i] = camino ? (y == nivelLava ? BASALTO_LISO : BASALTO) : LAVA;
                    continue;
                }
                if (rampa(x, y, z)) { out[i] = PIZARRA; continue; }
                if (adorno(x, y, z)) { out[i] = salaDe(x, y, z, "pozo") != null ? PIZARRA : GOTEO; continue; }
                out[i] = AIRE;
            }
        }
        decorar(out, cx, cz);
        return out;
    }

    /** Estalactitas y estalagmitas chicas, magma junto a la lava, liquen que brilla, piso de grava/pizarra rota. */
    private void decorar(short[] out, int cx, int cz) {
        int alto = MAX_Y - MIN_Y + 1;
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = cx * 16 + lx, z = cz * 16 + lz;
            if (x <= MIN_X || x >= MAX_X || z <= MIN_Z || z >= MAX_Z) continue;
            for (int yi = 1; yi < alto - 1; yi++) {
                int i = yi * 256 + lz * 16 + lx, y = yi + MIN_Y;
                if (out[i] != AIRE) continue;
                short arriba = out[i + 256], abajo = out[i - 256];
                double q = rnd(x, y, z);
                boolean goteo = salaDe(x, y, z, "columnas") != null || salaDe(x, y, z, "catedral") != null;
                // estalactita colgando del techo
                if (solida(arriba) && arriba != LAVA && q < (goteo ? 0.09 : 0.012)) {
                    int largo = 1 + (int) (rnd(x, y + 7, z) * (goteo ? 5 : 3));
                    colgar(out, lx, lz, yi, largo, true);
                    continue;
                }
                // estalagmita en el piso
                if (solida(abajo) && abajo != LAVA && abajo != BASALTO_LISO && q > (goteo ? 0.93 : 0.992)) {
                    int largo = 1 + (int) (rnd(x, y + 3, z) * (goteo ? 4 : 2));
                    colgar(out, lx, lz, yi, largo, false);
                    continue;
                }
                // liquen que brilla, poquito, en las paredes
                if (q > 0.5 && q < 0.508) {
                    if (lz > 0 && solida(out[i - 16])) out[i] = LIQUEN;            // north
                    else if (lz < 15 && solida(out[i + 16])) out[i] = (short) (LIQUEN + 1);
                    else if (lx < 15 && solida(out[i + 1])) out[i] = (short) (LIQUEN + 2);
                    else if (lx > 0 && solida(out[i - 1])) out[i] = (short) (LIQUEN + 3);
                    continue;
                }
                // magma en la orilla del lago
                if (abajo != AIRE && abajo != LAVA && solida(abajo) && salaDe(x, y, z, "lago") != null && rnd(x, y - 1, z) < 0.25
                        && abajo != BASALTO_LISO) out[i - 256] = MAGMA;
                // piso: pizarra rota de vez en cuando (piedras sueltas)
                if (solida(abajo) && abajo != LAVA && abajo != BEDROCK && abajo != BASALTO_LISO && rnd(x, y - 2, z) < 0.06) out[i - 256] = PIZARRA_ROTA;
            }
        }
    }

    private static boolean solida(short b) { return b != AIRE && b != LAVA && b < PUNTA_ABAJO; }

    /** Pone una estalactita (abajo=true cuelga) o estalagmita de largo bloques, con las puntas bien armadas. */
    private void colgar(short[] out, int lx, int lz, int yi, int largo, boolean cuelga) {
        int alto = MAX_Y - MIN_Y + 1;
        int libres = 0;
        for (int k = 0; k < largo; k++) {
            int yy = cuelga ? yi - k : yi + k;
            if (yy <= 0 || yy >= alto - 1 || out[yy * 256 + lz * 16 + lx] != AIRE) break;
            libres++;
        }
        // que no toque la de enfrente: deja un aire
        if (libres < largo) libres = Math.max(0, libres - 1);
        if (libres == 0) return;
        short base = cuelga ? PUNTA_ABAJO : PUNTA_ARRIBA;   // +0 punta, +1 frustum, +2 middle, +3 base
        for (int k = 0; k < libres; k++) {
            int yy = cuelga ? yi - k : yi + k;
            int desdePunta = libres - 1 - k;
            short t = desdePunta == 0 ? (short) (base) : desdePunta == 1 ? (short) (base + 1) : k == 0 ? (short) (base + 3) : (short) (base + 2);
            out[yy * 256 + lz * 16 + lx] = t;
        }
    }

    // ================================================================= para el plugin

    public List<Punto> puntos() { return puntos; }
    public List<Sala> salas() { return salas; }
    public double nivelLava() { return nivelLava; }
}
