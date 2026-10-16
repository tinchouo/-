package ar.tod4.aether;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import ar.tod4.aether.Estado.Camino;
import ar.tod4.aether.Estado.Puerto;
import ar.tod4.aether.Estado.Trabajo;
import ar.tod4.aether.Piezas.Pieza;

/**
 * Planificador del mapa (el del aether.sk v7.3 pasado al plugin, con las ramas nuevas).
 *
 * Caminos principales (verdes): 4, salen del spawn hacia los 4 puntos cardinales, infinitos.
 * Caminos azules: cada ~1500 bloques cada principal abre uno SIEMPRE hacia su izquierda (su cuadrante:
 *   este -> norte, norte -> oeste, oeste -> sur, sur -> este). Son infinitos, tienen sus ramas, pero no abren otros azules.
 * Ramas (marrones): maximo 3 islas. Con estructura: 1 isla + estructura o 2 islas + estructura. Sin estructura: 3 islas
 *   (la ultima con cobre o una ruinita). Del lado izquierdo (donde salen los azules) son mas cortas: 1 isla + estructura o 2 islas.
 * Las estructuras (village, coliseum, windmill, mushroom_island) nunca quedan pegadas entre si (separacion minima).
 */
final class Plan {

    private static final int CS = 8;          // celda de ocupacion
    private static final int BR_YS = 10;      // los puentes se pegan 10 bloques abajo de la cubierta
    static final int NADIE = Integer.MIN_VALUE;

    private final Conf k;
    private final Estado e;
    Random r = new Random();
    private final java.util.function.BiPredicate<Integer, Integer> cerca;
    private final Consumer<String> log;

    Plan(Conf k, Estado e, java.util.function.BiPredicate<Integer, Integer> cerca, Consumer<String> log) {
        this.k = k;
        this.e = e;
        this.cerca = cerca;
        this.log = log;
    }

    private int rnd(int a, int b) { return a + r.nextInt(b - a + 1); }
    private boolean pct(int p) { return rnd(1, 100) <= p; }
    private int newId() { return ++e.nextId; }

    // ================================================================= ocupacion

    private static long key(int i, int j) { return ((long) i << 32) | (j & 0xffffffffL); }
    static int puente(int id) { return -(id + 1); }

    private boolean libre(Integer v, int ign) {
        return v == null || (ign != NADIE && (v == ign || v == puente(ign)));
    }

    boolean freeR(int x1, int z1, int x2, int z2, int ign) {
        for (int i = Math.floorDiv(x1, CS); i <= Math.floorDiv(x2, CS); i++)
            for (int j = Math.floorDiv(z1, CS); j <= Math.floorDiv(z2, CS); j++)
                if (!libre(e.occ.get(key(i, j)), ign)) return false;
        return true;
    }

    void markR(int x1, int z1, int x2, int z2, int owner) {
        for (int i = Math.floorDiv(x1, CS); i <= Math.floorDiv(x2, CS); i++)
            for (int j = Math.floorDiv(z1, CS); j <= Math.floorDiv(z2, CS); j++)
                e.occ.put(key(i, j), owner);
    }

    /** La forma real de la isla (superelipse de exponente 4), no la caja de la plantilla. */
    boolean freeE(int cx, int cz, int rad, int ign) {
        double r4 = Math.pow(rad, 4);
        for (int i = Math.floorDiv(cx - rad, CS); i <= Math.floorDiv(cx + rad, CS); i++) {
            int lx = i * CS, hx = lx + CS - 1;
            int ex = cx < lx ? lx - cx : cx > hx ? cx - hx : 0;
            for (int j = Math.floorDiv(cz - rad, CS); j <= Math.floorDiv(cz + rad, CS); j++) {
                int lz = j * CS, hz = lz + CS - 1;
                int ez = cz < lz ? lz - cz : cz > hz ? cz - hz : 0;
                if (Math.pow(ex, 4) + Math.pow(ez, 4) < r4 && !libre(e.occ.get(key(i, j)), ign)) return false;
            }
        }
        return true;
    }

    void markE(int cx, int cz, int rad, int owner) {
        double r4 = Math.pow(rad, 4);
        for (int i = Math.floorDiv(cx - rad, CS); i <= Math.floorDiv(cx + rad, CS); i++) {
            int lx = i * CS, hx = lx + CS - 1;
            int ex = cx < lx ? lx - cx : cx > hx ? cx - hx : 0;
            for (int j = Math.floorDiv(cz - rad, CS); j <= Math.floorDiv(cz + rad, CS); j++) {
                int lz = j * CS, hz = lz + CS - 1;
                int ez = cz < lz ? lz - cz : cz > hz ? cz - hz : 0;
                if (Math.pow(ex, 4) + Math.pow(ez, 4) < r4) e.occ.put(key(i, j), owner);
            }
        }
    }

    // ================================================================= cola, puertos, caminos

    void encolar(String n, int x0, int y0, int z0, int x1, int z1, String rot) {
        Trabajo t = new Trabajo();
        t.n = n; t.x0 = x0; t.y0 = y0; t.z0 = z0; t.x1 = x1; t.z1 = z1; t.rot = rot;
        e.cola.add(t);
    }

    private Puerto puerto(int id, int x, int z, int y, int h, int dx, int dz, boolean rama, int t, int left, int owner,
                          int odx, int odz, int sw, boolean big, int idx, int idz, int mc, boolean st) {
        Puerto p = new Puerto();
        p.id = id; p.x = x; p.z = z; p.y = y; p.h = h; p.dx = dx; p.dz = dz; p.rama = rama; p.t = t; p.left = left;
        p.owner = owner; p.odx = odx; p.odz = odz; p.sw = sw; p.big = big; p.idx = idx; p.idz = idz; p.mc = mc; p.st = st;
        p.fw = k.rectoMin;
        e.puertos.put(id, p);
        if (!rama) e.tp.put(t, id);
        return p;
    }

    private void soltar(int id) {
        Puerto p = e.puertos.remove(id);
        if (p != null && !p.rama && Integer.valueOf(id).equals(e.tp.get(p.t))) e.tp.remove(p.t);
    }

    private Camino cam(int t) {
        return e.caminos.computeIfAbsent(t, x -> { Camino c = new Camino(); c.t = x; return c; });
    }

    /** Distancia (desde el spawn, sobre su eje) a la que sale el camino azul n de un camino principal. */
    private int planA(Camino c, int n) {
        while (c.planA.size() <= n) {
            int i = c.planA.size() + 1;
            c.planA.add(i * k.azulSeparacion + rnd(-k.azulVariacion, k.azulVariacion));
        }
        return c.planA.get(n);
    }

    // ================================================================= zonas reservadas

    private static boolean arm(int px, int pz, int ax, int az, int alen, int x, int z, int rad) {
        int al = (x - px) * ax + (z - pz) * az;
        int la = Math.abs((x - px) * az + (z - pz) * ax);
        return al > -rad && al < alen + rad && la < 14 + rad;
    }

    /**
     * Lo que ni las ramas ni la decoracion pueden tapar: el frente de cada camino (320 bloques) y la franja donde sale
     * (o va a salir) cada camino azul.
     */
    boolean reservado(int x, int z, int rad, int skipFrente, int skipBanda) { return reservado(x, z, rad, skipFrente, skipBanda, true); }

    boolean reservado(int x, int z, int rad, int skipFrente, int skipBanda, boolean bandas) {
        for (int t = 1; t <= e.ntr; t++) {
            if (t == skipFrente) continue;
            Integer pid = e.tp.get(t);
            Puerto q = pid == null ? null : e.puertos.get(pid);
            if (q == null) continue;
            int al = (x - q.x) * q.odx + (z - q.z) * q.odz;
            int la = Math.abs((x - q.x) * q.odz + (z - q.z) * q.odx);
            if (al > -rad && al < 320 + rad && la < 75 + rad) return true;
            if (q.idx != 0 || q.idz != 0) {
                int hh = q.h + 70;
                if (arm(q.x, q.z, q.idx, q.idz, hh, x, z, rad)) return true;
                if (arm(q.x, q.z, q.idz, q.idx, hh, x, z, rad)) return true;
                if (arm(q.x, q.z, -q.idz, -q.idx, hh, x, z, rad)) return true;
            }
        }
        if (!bandas) return false;
        for (int m = 1; m <= 4; m++) {
            Camino c = e.caminos.get(m);
            if (c == null) continue;
            int al = x * c.odx + z * c.odz;
            int le = x * c.odz - z * c.odx;   // positivo = a la izquierda del camino
            for (int n = c.sig; n < c.sig + 2; n++) {
                int a = planA(c, n);
                if (le > -171 - rad && al > a - 180 - rad && al < a + 560 + rad) return true;
            }
            for (int[] az : c.azules) {
                if (az[1] == skipBanda) continue;
                if (le > az[2] - 61 - rad && al > az[0] - 180 - rad && al < az[0] + 180 + rad) return true;
            }
        }
        return false;
    }

    /** Sobre la franja de un camino azul (hecho o por hacer): ahi no salen ramas. */
    private boolean enBanda(Camino c, int a, int extra) {
        for (int n = c.sig; n < c.sig + 2; n++) {
            int pa = planA(c, n);
            if (a > pa - 240 - extra && a < pa + 620 + extra) return true;
        }
        for (int[] az : c.azules) if (a > az[0] - 240 - extra && a < az[0] + 240 + extra) return true;
        return false;
    }

    private boolean especialCerca(int x, int z, int dist) {
        long d2 = (long) dist * dist;
        for (List<int[]> l : e.loc.values())
            for (int[] p : l) {
                long ddx = p[0] - x, ddz = p[2] - z;
                if (ddx * ddx + ddz * ddz < d2) return true;
            }
        return false;
    }

    // ================================================================= decoracion

    /** Islitas sueltas alrededor de un puente (sin conexion): cerca, lejos y algunas sueltas en el vacio a otra altura. */
    private void decor(int mx, int mz, int my, int dx, int dz, int skipt, int bsd) {
        if ((double) mx * mx + (double) mz * mz < 200.0 * 200.0) return;   // el spawn queda limpio
        int px = dz, pz = dx;
        // {chance, distancia min, distancia max, variacion de altura, 1 = puede ser chica}
        int[][] capas = {{k.decorCerca, 26, 58, 8, 1}, {k.decorCerca, 26, 58, 8, 1}, {k.decorLejos, 70, 120, 16, 1},
                         {k.decorLejos, 70, 120, 16, 0}, {k.decorVacio, 130, 260, 30, 0}, {k.decorVacio, 130, 260, 30, 1}};
        for (int kk = 0; kk < capas.length; kk++) {
            int[] cp_ = capas[kk];
            if (!pct(cp_[0])) continue;
            int sgn = pct(50) ? -1 : 1;
            if (bsd != 0 && kk < 2) sgn = -bsd;
            int d = rnd(cp_[1], cp_[2]), jit = rnd(-14, 14);
            int cx = mx + dx * jit + px * sgn * d;
            int cz = mz + dz * jit + pz * sgn * d;
            int cy = my + rnd(-cp_[3], cp_[3]);
            if (kk < 2) cy = Math.max(k.baseY - k.maxDev, Math.min(k.baseY + k.maxDev, cy));
            // se decide mas tarde: asi nunca le quita lugar a una rama o a una estructura
            e.decorPend.add(new int[]{cx, cy, cz, cp_[4], skipt, e.expansiones + 40});
        }
    }

    private void decorPendiente() {
        int hechas = 0;
        for (java.util.Iterator<int[]> it = e.decorPend.iterator(); it.hasNext() && hechas < 24; ) {
            int[] d = it.next();
            if (d[5] > e.expansiones) continue;
            it.remove();
            islita(d[0], d[1], d[2], d[3] == 1, d[4]);
            hechas++;
        }
    }

    /** Unas islitas flotando alrededor de una estructura. */
    private void islitasAlrededor(int cx, int cy, int cz, int rm) {
        int n = rnd(3, 5);
        double a0 = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = a0 + i * Math.PI * 2 / n + (r.nextDouble() - 0.5) * 0.8;
            int d = rm + rnd(20, 60);
            e.decorPend.add(new int[]{cx + (int) (Math.cos(a) * d), cy + rnd(-20, 20), cz + (int) (Math.sin(a) * d), 1, 0, e.expansiones + 10});
        }
    }

    private void islita(int cx, int cy, int cz, boolean chica, int skipt) {
        int roll = rnd(1, 100);
        String cp = "tiny_" + rnd(1, 4);
        if (chica) {
            if (roll > 50) cp = "small_" + rnd(1, 4);
            if (roll > 82) cp = "small_f" + rnd(1, 2);
        }
        Pieza pz2 = Piezas.get(cp);
        if (freeE(cx, cz, pz2.rm() + 4, NADIE) && !reservado(cx, cz, pz2.rm(), skipt, skipt)) {
            markE(cx, cz, pz2.rm(), newId());
            encolar(cp, cx - pz2.c(), cy - Piezas.DECK, cz - pz2.c(), cx + pz2.c(), cz + pz2.c(), null);
        }
    }

    // ================================================================= viabilidad

    /** Cabe una isla "piece" con el puente mas corto posible desde (x,z) hacia (dx,dz)? h > 20 = el origen es grande. */
    boolean canGo(int x, int z, int h, int dx, int dz, int owner, String piece) {
        Pieza pc = Piezas.get(piece);
        int ln = 6, hw = 3;
        if (h > 20) {
            ln = 16; hw = 5;
            if (pc.big()) ln = 32;
        } else if (pc.big()) {
            ln = 16; hw = 5;
        }
        int ln1 = ln - 1;
        int reach = h + ln1 + pc.rax();
        int cx = x + dx * reach, cz = z + dz * reach;
        int d = dx + dz;
        int edge = dx != 0 ? x + d * h : z + d * h;
        int mn = d < 0 ? edge - ln1 : edge;
        int r1, r2, r3, r4;
        if (dx != 0) { r1 = mn; r2 = z - hw; r3 = mn + ln1; r4 = z + hw; }
        else { r1 = x - hw; r2 = mn; r3 = x + hw; r4 = mn + ln1; }
        return freeE(cx, cz, pc.rm(), owner) && freeR(r1, r2, r3, r4, owner);
    }

    /** Direccion de salida: deseada, recto, original, izquierda, derecha (nunca hacia atras). null = ninguna. */
    private int[] pickDir(int cx, int cz, int hc, int dx, int dz, int ndx, int ndz, int odx, int odz, int owner, String piece) {
        int[][] c = {{ndx, ndz}, {dx, dz}, {odx, odz}, {dz, dx}, {-dz, -dx}};
        for (int[] q : c) {
            if (q[0] == -dx && q[1] == -dz) continue;
            if (q[0] == -odx && q[1] == -odz) continue;   // nunca en contra de la direccion del camino
            if (canGo(cx, cz, hc, q[0], q[1], owner, piece)) return q;
        }
        return null;
    }

    /** Camino atrapado: retrocede a una isla anterior con un lado libre y sigue desde ahi. */
    private void retroceder(int id) {
        Puerto p = e.puertos.get(id);
        if (p == null) return;
        Camino c = cam(p.t);
        int n = c.th.size();
        // si ya retrocedio sin avanzar, prueba opciones mas viejas
        if (c.thBase == c.islas) c.retros++; else { c.retros = 0; c.thBase = c.islas; }
        int saltar = c.retros;
        for (int kk = n - 2; kk >= 0 && kk > n - 31; kk--) {
            int[] h = c.th.get(kk);
            String pc = h[6] >= k.medianasMax ? "large_1" : "medium_1";
            int[][] dd = {{p.odx, p.odz}, {p.odz, p.odx}, {-p.odz, -p.odx}};
            for (int[] q : dd) {
                if (canGo(h[0], h[1], h[3], q[0], q[1], h[4], pc)) {
                    if (saltar-- > 0) continue;
                    soltar(id);
                    int nsw = q[0] == p.odx && q[1] == p.odz ? 0 : 1;
                    puerto(newId(), h[0], h[1], h[2], h[3], q[0], q[1], false, p.t, 0, h[4], p.odx, p.odz, nsw, h[5] == 1, 0, 0, h[6], false);
                    log.accept("Camino " + p.t + " retrocede a (" + h[0] + ", " + h[1] + ") para seguir.");
                    return;
                }
            }
        }
    }

    /** El camino no dobla hacia ese lado por 3 islas (ahi salio una rama o un camino azul). */
    private void evitar(Puerto np, int sx, int sz) {
        np.evx = sx; np.evz = sz; np.evn = 3;
        if (np.dx == sx && np.dz == sz) { np.dx = np.odx; np.dz = np.odz; np.sw = 0; }
    }

    private String elegirEspecial() {
        if (e.force != null) return e.force;
        int x = rnd(1, 100);
        if (x <= k.wVillage) return "village";
        if (x <= k.wVillage + k.wColiseum) return "coliseum";
        if (x <= k.wVillage + k.wColiseum + k.wWindmill) return "windmill";
        return "mushroom_island";
    }

    // ================================================================= inicio

    void iniciar(double[] spawn) {
        e.listo = true;
        e.ntr = 4;
        e.spawn = spawn;
        Pieza s = Piezas.get("spawn");
        encolar("spawn", -s.c(), k.baseY - Piezas.DECK, -s.c(), s.c(), s.c(), null);
        markE(0, 0, s.rm(), 0);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int t = 1; t <= 4; t++) {
            int dx = dirs[t - 1][0], dz = dirs[t - 1][1];
            Camino c = cam(t);
            c.odx = dx; c.odz = dz;
            puerto(newId(), 0, 0, k.baseY, s.rax(), dx, dz, false, t, 0, 0, dx, dz, 0, true, 0, 0, 0, false);
        }
    }

    // ================================================================= crecer

    /** Expande hasta "max" puertos que esten cerca de algun jugador. */
    void crecer(int max) {
        decorPendiente();
        int hechas = 0;
        for (Integer id : new ArrayList<>(e.puertos.keySet())) {
            if (hechas >= max || e.cola.size() >= k.colaMax) return;
            Puerto p = e.puertos.get(id);
            if (p == null || !cerca.test(p.x, p.z)) continue;
            expandir(id);
            hechas++;
        }
    }

    /** Expande un puerto cualquiera (para /tod4:aether_fill). */
    boolean expandirAlguno() {
        decorPendiente();
        if (e.puertos.isEmpty()) return false;
        List<Integer> ids = new ArrayList<>(e.puertos.keySet());
        expandir(ids.get(r.nextInt(ids.size())));
        return true;
    }

    /** Puente + isla siguiente (+ camino azul, rama y decoracion). */
    void expandir(int id) {
        Puerto p = e.puertos.get(id);
        if (p == null) return;
        final int x = p.x, z = p.z, y = p.y, h = p.h, dx = p.dx, dz = p.dz;
        final boolean rama = p.rama, pbig = p.big, st = p.st;
        final int t = p.t, left = p.left, owner = p.owner, odx = p.odx, odz = p.odz, sw = p.sw, idx = p.idx, idz = p.idz, mc = p.mc;
        boolean gate = !rama && (owner == 0 || p.gate);
        int bx = x, bz = z, bh = h;
        if (p.ccx != null) { bx = p.ccx; bz = p.ccz; bh = h + 7; }
        int dir = dx + dz;

        boolean ok = false;
        int tries = 0;
        String sp = "", cp = null, rot = null;
        Pieza dp = null;
        int len = 6, cx = 0, cz = 0, cy = 0, rp = 0, by = 0, bx0 = 0, bz0 = 0, bx1 = 0, bz1 = 0, nmc = 0;
        boolean axX = true;
        char bc = 'w';
        while (!ok && tries < 5) {
            tries++;
            sp = "";
            cp = "medium_" + rnd(1, 4);
            boolean wantbig = mc >= k.medianasMax || gate;
            if (tries == 1 && pct(rama ? k.ramaGrande : k.chanceGrande)) wantbig = true;
            if (wantbig) {
                cp = "large_" + rnd(1, 4);
                if (tries == 1 && !rama) {
                    if (pct(k.casas)) cp = "large_h" + rnd(1, 2);
                    if (pct(k.ruina)) cp = "large_ruin";
                }
            }
            if (!rama && t <= 4 && pct(35)) {
                // caminos principales: islas con arcos de flores, bancos y canteros
                if (cp.startsWith("medium_")) cp = "medium_v" + rnd(1, 2);
                else if (cp.matches("large_[1-4]")) cp = "large_v" + rnd(1, 2);
            }
            if (rama) {
                if (cp.startsWith("medium_")) cp = "bm_" + rnd(1, 4);
                if (cp.startsWith("large")) cp = "bl_" + rnd(1, 3);
                if (left <= 1) {
                    // ultima isla de la rama: la estructura (si le toca) o el remate (cobre o ruinita)
                    if (tries <= 3 && (st || e.force != null)
                            && !especialCerca(x + dx * (h + 130), z + dz * (h + 130), Math.min(300, k.especialSeparacion))) {
                        sp = elegirEspecial();
                        cp = sp;
                        if (sp.equals("village")) cp = "village_" + rnd(1, 3);
                        if (sp.equals("mushroom_island")) cp = pct(25) ? "mushroom_island_4" : "mushroom_island_" + rnd(1, 3);
                        if (Piezas.get(cp) == null) { sp = ""; cp = "bm_1"; }
                    }
                    if (sp.isEmpty()) cp = pct(k.cobreFinal) ? "bm_c" + rnd(1, 2) : "bm_r" + rnd(1, 2);
                }
            }
            dp = Piezas.get(cp);
            boolean dbig = dp.big();
            boolean ismed = cp.startsWith("medium_") || cp.startsWith("bm_");
            nmc = ismed ? mc + 1 : 0;
            // puente: dos grandes = grande entero (32/40/48); una grande = mitad (16/20/24); dos medianas = madera (6/8/10/12)
            bc = 'w';
            if (dbig || pbig) bc = 'h';
            if (dbig && pbig) bc = 'f';
            int hw = 3;
            len = 6 + 2 * rnd(0, 3);
            if (tries >= 4) len = 6;
            if (bc == 'h') { hw = 5; len = tries >= 4 ? 16 : 16 + 4 * rnd(0, 2); }
            if (bc == 'f') { hw = 5; len = tries >= 4 ? 32 : 32 + 8 * rnd(0, 2); }
            int rr = 0;
            if (bc == 'w') {
                rr = rnd(1, 2);
                int pu = Math.max(15, Math.min(85, 50 + (k.baseY - y) * 4));
                if (rnd(1, 100) > pu) rr = -rr;
            }
            if (y + rr > k.baseY + k.maxDev) rr = -Math.abs(rr);
            if (y + rr < k.baseY - k.maxDev) rr = Math.abs(rr);
            int hc = dp.rax();
            int reach = h + len - 1 + hc;
            cx = x + dx * reach;
            cz = z + dz * reach;
            cy = y + rr;
            rot = null;
            if (sp.equals("village")) {
                int vo = pct(50) ? 78 : 26;
                if (pct(50)) vo = -vo;
                cx += dz * vo;
                cz += dx * vo;
                if (dz == 1) rot = "CLOCKWISE_90";
                if (dx == -1) rot = "CLOCKWISE_180";
                if (dz == -1) rot = "COUNTERCLOCKWISE_90";
            }
            int edge;
            if (dx != 0) { axX = true; edge = x + dir * h; } else { axX = false; edge = z + dir * h; }
            int min = dir < 0 ? edge - (len - 1) : edge;
            rp = rr * dir;
            int ys = rp < 0 ? BR_YS - rp : BR_YS;
            int y0 = dir < 0 ? cy : y;
            by = y0 - ys;
            if (axX) { bx0 = min; bz0 = z - hw; bx1 = bx0 + len - 1; bz1 = z + hw; }
            else { bx0 = x - hw; bz0 = min; bx1 = x + hw; bz1 = bz0 + len - 1; }
            if (freeE(cx, cz, dp.rm(), owner) && freeR(bx0, bz0, bx1, bz1, owner)) {
                ok = true;
                if (rama) {
                    if (reservado(cx, cz, dp.rm(), 0, t)) ok = false;
                } else {
                    // un camino no se mete en el frente de otro camino
                    String npc = nmc >= k.medianasMax ? "large_1" : "medium_1";
                    if (reservado(cx, cz, dp.rm(), t, 0, false)) ok = false;
                    else if (pickDir(cx, cz, hc, dx, dz, dx, dz, odx, odz, NADIE, npc) == null) ok = false;
                }
            }
        }

        if (!ok) {
            if (p.ccx != null) {
                p.x = p.ccx; p.z = p.ccz; p.h = bh;
                p.ccx = null; p.ccz = null;
                return;
            }
            if (rama) {
                Camino c = cam(t);
                c.alive--;
                if (st) c.since = Math.max(c.since, k.malaSuerteMax - 1);
                soltar(id);
            } else {
                p.fails++;
                if (sw == 0) {
                    int sg = p.sgn;
                    p.sgn = -sg;
                    if (odz * sg == -idx && odx * sg == -idz) sg = -sg;
                    if (p.evn > 0 && odz * sg == p.evx && odx * sg == p.evz && p.fails < 4) sg = -sg;
                    p.dx = odz * sg;
                    p.dz = odx * sg;
                    p.sw = 1;
                } else {
                    p.dx = odx; p.dz = odz; p.sw = 0;
                }
                int fl = p.fails;
                if (fl >= 6) retroceder(id);
                if (fl >= 14 && e.puertos.containsKey(id) && cam(t).th.isEmpty()) {
                    log.accept("Camino " + t + " no pudo arrancar; se descarta.");
                    soltar(id);
                }
            }
            return;
        }

        soltar(id);
        e.expansiones++;
        int nuevo = newId();
        if (!sp.isEmpty()) {
            e.loc.computeIfAbsent(sp, s -> new ArrayList<>()).add(new int[]{cx, cy, cz});
            e.force = null;
            log.accept("Estructura " + sp + " en (" + cx + ", " + cz + ").");
            islitasAlrededor(cx, cy, cz, dp.rm());
        }
        String rt = rp < 0 ? "n" + Math.abs(rp) : rp > 0 ? "p" + rp : "z0";
        String bk = "", bpre = "b";
        int minb = 12;
        if (bc != 'w') { bpre = "g"; minb = 20; }
        if (bc == 'f') minb = 32;
        if (len >= minb && pct(k.puenteRoto)) bk = "b";
        if (gate && bc == 'f') { bpre = "t"; bk = ""; }
        else if (!rama && t <= 4 && bc != 'w' && bk.isEmpty()) bk = "f";      // banderines en los caminos principales
        int cc = dp.c();
        encolar(cp, cx - cc, cy - Piezas.DECK, cz - cc, cx + cc, cz + cc, rot);
        encolar(bpre + (axX ? "x" : "z") + bk + "_" + len + "_" + rt, bx0, by, bz0, bx1, bz1, null);
        markR(bx0, bz0, bx1, bz1, puente(nuevo));
        markE(cx, cz, dp.rm(), nuevo);

        // direccion del siguiente puerto: recto o un giro de 90 (zigzag corto), nunca hacia atras
        int hc = dp.rax();
        int ndx = dx, ndz = dz;
        int tc = rama ? k.ramaGiro : k.giro;
        int fwc = dx == odx && dz == odz ? p.fw + 1 : 0;
        if (sw == 0 && fwc >= k.rectoMin && pct(tc)) {
            int sg = pct(50) ? -1 : 1;
            if (!rama) {
                Camino c = cam(t);
                int lat = (cx - c.ox) * odz + (cz - c.oz) * odx;
                if (lat > k.derivaMax) sg = -1;
                if (lat < -k.derivaMax) sg = 1;
            }
            ndx = odz * sg;
            ndz = odx * sg;
            if (p.evn > 0 && ndx == p.evx && ndz == p.evz) { ndx = -ndx; ndz = -ndz; }
        }
        if (sw > 0) {
            boolean back = sw >= k.costadoMax || pct(50) || rama;
            if (back) { ndx = odx; ndz = odz; }
        }
        String npc = nmc >= k.medianasMax ? "large_1" : "medium_1";
        int[] pk = pickDir(cx, cz, hc, dx, dz, ndx, ndz, odx, odz, nuevo, npc);
        if (pk != null) { ndx = pk[0]; ndz = pk[1]; }
        int nsw = ndx == odx && ndz == odz ? 0 : 1;
        if (nsw == 1 && ndx == dx && ndz == dz && sw > 0) nsw = sw + 1;
        boolean nbig = dp.big();
        int px2 = cx, pz2 = cz, off = 0;
        if (cp.startsWith("large") || cp.startsWith("bl_")) {
            int roll = rnd(1, 100);
            if (roll <= 35) off = -22;
            if (roll > 65) off = 22;
            if (off != 0) {
                px2 = cx + ndz * off;
                pz2 = cz + ndx * off;
                if (!canGo(px2, pz2, hc - 7, ndx, ndz, nuevo, npc)) { off = 0; px2 = cx; pz2 = cz; }
            }
        }
        int ph = off != 0 ? hc - 7 : hc;

        int skip = 0, bsd = 0, azulId = 0;
        if (!rama) {
            Camino c = cam(t);
            c.th.add(new int[]{cx, cz, cy, hc, nuevo, nbig ? 1 : 0, nmc});
            c.islas++;
            if (c.th.size() > 40) c.th.remove(0);
            Puerto np = puerto(nuevo, px2, pz2, cy, ph, ndx, ndz, false, t, 0, nuevo, odx, odz, nsw, nbig, dx, dz, nmc, false);
            np.fw = fwc;
            if (off != 0) { np.ccx = cx; np.ccz = cz; }
            if (p.evn > 1) { np.evx = p.evx; np.evz = p.evz; np.evn = p.evn - 1; }
            boolean recto = dx == odx && dz == odz;
            boolean azul = false;

            // camino azul: sale SIEMPRE hacia la izquierda del camino principal, con un puente-puerta Tower Bridge
            // (la isla tiene que haber sido alcanzada de frente: si el camino llego de costado, ese lado esta ocupado)
            if (t <= 4 && recto && idx == odx && idz == odz) {
                int a = bx * odx + bz * odz;
                int pa = planA(c, c.sig);
                if (a >= pa) {
                    if (a > pa + 500) {
                        c.sig++;
                    } else if ((pbig || a > pa + 250) && canGo(bx, bz, bh, odz, -odx, owner, "large_1")) {
                        int nt = ++e.ntr;
                        int sdx = odz, sdz = -odx;
                        Camino nc = cam(nt);
                        nc.padre = t; nc.ox = bx; nc.oz = bz; nc.odx = sdx; nc.odz = sdz;
                        int lat = bx * odz - bz * odx;
                        c.azules.add(new int[]{a, nt, lat});
                        c.sig++;
                        Puerto sp2 = puerto(newId(), bx, bz, y, bh, sdx, sdz, false, nt, 0, owner, sdx, sdz, 0, pbig, 0, 0, mc, false);
                        sp2.gate = true;
                        azulId = sp2.id;
                        evitar(np, sdx, sdz);
                        azul = true;
                        bsd = dz != 0 ? 1 : -1;
                        log.accept("Camino azul " + nt + " sale del camino " + t + " en (" + bx + ", " + bz + ").");
                    }
                }
            }

            // rama: maximo 3 islas. Contador de mala suerte: cada isla del camino sin estructura sube la chance.
            c.since++;
            int since = c.since;
            boolean far = Math.sqrt((double) bx * bx + (double) bz * bz) >= k.especialDistMin;
            boolean force = since >= k.malaSuerteMax && far;
            boolean brok = !azul && recto && (double) bx * bx + (double) bz * bz > 300.0 * 300.0;
            int aqui = bx * odx + bz * odz;
            if (t <= 4 && enBanda(c, aqui, 0)) brok = false;
            if (t > 4) {
                int propio = (bx - c.ox) * c.odx + (bz - c.oz) * c.odz;
                if (propio < k.azulRamasDesde) brok = false;
            }
            if (c.lb && !force) {
                double ddx = c.lbx - bx, ddz = c.lbz - bz;
                if (Math.sqrt(ddx * ddx + ddz * ddz) < k.ramaSeparacion) brok = false;
            }
            if (brok && c.alive < k.ramaVivasMax && (force || pct(k.ramaChance))) {
                int sg = pct(50) ? -1 : 1;
                if (c.lastside != 0 && pct(85)) sg = -c.lastside;
                if (dz * sg == -idx && dx * sg == -idz) sg = -sg;
                int sdx = dz * sg, sdz = dx * sg;
                boolean izq = sdx == odz && sdz == -odx;
                boolean go = true;
                if (izq && t <= 4 && enBanda(c, aqui, 260)) {
                    sg = -sg; sdx = -sdx; sdz = -sdz; izq = false;
                    if (dz * sg == -idx && dx * sg == -idz) go = false;
                }
                if (go) {
                    c.lb = true; c.lbx = bx; c.lbz = bz;
                    c.lastside = sg;
                    boolean bst = false;
                    if (far) {
                        int stc = k.especialChance + (since > 3 ? (since - 3) * k.malaSuertePaso : 0);
                        if (force) stc = 100;
                        bst = pct(stc);
                    }
                    if (bst) {
                        int proy = izq ? 230 : 330;
                        if (especialCerca(bx + sdx * proy, bz + sdz * proy, k.especialSeparacion)) bst = false;
                    }
                    int n;
                    if (bst) { n = izq ? 2 : rnd(2, 3); c.since = 0; }
                    else n = izq ? 2 : 3;
                    puerto(newId(), bx, bz, y, bh, sdx, sdz, true, t, n, owner, sdx, sdz, 0, pbig, idx, idz, 0, bst);
                    c.alive++;
                    bsd = sg;
                    evitar(np, sdx, sdz);
                }
            }
            skip = t;
        } else {
            int nleft = left - 1;
            if (nleft > 0) {
                Puerto np = puerto(nuevo, px2, pz2, cy, ph, ndx, ndz, true, t, nleft, nuevo, odx, odz, nsw, nbig, dx, dz, nmc, st);
                np.fw = fwc;
                if (off != 0) { np.ccx = cx; np.ccz = cz; }
            } else {
                Camino c = cam(t);
                c.alive--;
                if (st && sp.isEmpty()) c.since = Math.max(c.since, k.malaSuerteMax - 1);
            }
        }
        int mx = x + dx * (h + len / 2), mz = z + dz * (h + len / 2);
        decor(mx, mz, y, dx, dz, skip, bsd);
        // el camino azul pega su primera isla enseguida, asi nada le ocupa la salida
        if (azulId != 0) expandir(azulId);
    }
}
