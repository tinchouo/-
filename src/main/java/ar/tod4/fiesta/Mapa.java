package ar.tod4.fiesta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;

/**
 * El mapa del Level Fun (fijo, se genera una sola vez con /tod4:fiesta crear).
 * Coordenadas locales: a (x) y b (z) de 0 a 64, y = 0 es el piso. Techo de 4 de alto (y 1..4), techo en y 5.
 * Todo simetrico:
 *  - 9 zonas: 4 salas de las esquinas (cumpleanos), 4 salas de los bordes y el salon del medio (pilares de colores).
 *  - Paredes naranjas con zocalo blanco, alfombra oscura con papelitos de colores, paneles de luz en el techo.
 *  - Casilleros siempre de a dos, mesas con sillas, globos, banderines y cofres.
 *  - Entrada: pasillo negro al sur (b 65..80). Volver por ese pasillo te lleva a las backrooms.
 */
final class Mapa {

    static final int T = 64;               // de 0 a 64 (63 por dentro)
    static final int ALTO = 4;
    /** Lugares de las luces (simetricos). */
    static final int[] LUCES = {4, 12, 22, 27, 32, 37, 42, 52, 60};

    /** Casillero: lugar (a, b) y hacia donde mira el frente. */
    record Casillero(int a, int b, BlockFace frente) {}

    static final List<Casillero> CASILLEROS = new ArrayList<>();
    static final List<int[]> APARICIONES = new ArrayList<>();
    static final List<int[]> COFRES = new ArrayList<>();

    static {
        // esquinas (y su espejo)
        par(6, 1, 7, 1, BlockFace.SOUTH);
        par(1, 11, 1, 12, BlockFace.EAST);
        // bordes
        par(22, 1, 23, 1, BlockFace.SOUTH);
        par(1, 22, 1, 23, BlockFace.EAST);
        // salon del medio (rotado de a 90 grados)
        CASILLEROS.add(new Casillero(21, 18, BlockFace.SOUTH)); CASILLEROS.add(new Casillero(22, 18, BlockFace.SOUTH));
        CASILLEROS.add(new Casillero(46, 21, BlockFace.WEST));  CASILLEROS.add(new Casillero(46, 22, BlockFace.WEST));
        CASILLEROS.add(new Casillero(43, 46, BlockFace.NORTH)); CASILLEROS.add(new Casillero(42, 46, BlockFace.NORTH));
        CASILLEROS.add(new Casillero(18, 43, BlockFace.EAST));  CASILLEROS.add(new Casillero(18, 42, BlockFace.EAST));

        int[][] ap = {{8, 13}, {32, 8}, {8, 32}, {28, 28}, {28, 36}};
        for (int[] p : ap) for (int[] e : espejos(p[0], p[1])) if (!contiene(APARICIONES, e)) APARICIONES.add(e);
        for (int[] e : espejos(2, 2)) COFRES.add(e);
    }

    /** Un par de casilleros y sus espejos en las 4 esquinas / bordes. */
    private static void par(int a1, int b1, int a2, int b2, BlockFace f) {
        for (int ma = 0; ma < 2; ma++) for (int mb = 0; mb < 2; mb++) {
            BlockFace ff = f;
            if (ma == 1 && (f == BlockFace.EAST || f == BlockFace.WEST)) ff = f.getOppositeFace();
            if (mb == 1 && (f == BlockFace.NORTH || f == BlockFace.SOUTH)) ff = f.getOppositeFace();
            int[][] ps = {{a1, b1}, {a2, b2}};
            for (int[] p : ps) {
                int a = ma == 1 ? T - p[0] : p[0], b = mb == 1 ? T - p[1] : p[1];
                Casillero c = new Casillero(a, b, ff);
                boolean ya = false;
                for (Casillero x : CASILLEROS) if (x.a() == a && x.b() == b) { ya = true; break; }
                if (!ya) CASILLEROS.add(c);
            }
        }
    }

    private static List<int[]> espejos(int a, int b) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{a, b}); out.add(new int[]{T - a, b}); out.add(new int[]{a, T - b}); out.add(new int[]{T - a, T - b});
        return out;
    }

    private static boolean contiene(List<int[]> l, int[] p) {
        for (int[] x : l) if (x[0] == p[0] && x[1] == p[1]) return true;
        return false;
    }

    // ================================================================= construccion

    private final World w;
    private final int ox, oy, oz;
    private final Random rnd = new Random(7);
    /** Cada paso pone un bloque; se ejecutan de a muchos por tick para no trabar el server. */
    final List<Runnable> pasos = new ArrayList<>();

    Mapa(Location origen) {
        this.w = origen.getWorld();
        this.ox = origen.getBlockX();
        this.oy = origen.getBlockY();
        this.oz = origen.getBlockZ();
    }

    Block b(int a, int y, int bb) { return w.getBlockAt(ox + a, oy + y, oz + bb); }

    private void poner(int a, int y, int bb, Material m) { pasos.add(() -> b(a, y, bb).setType(m, false)); }

    private void poner(int a, int y, int bb, BlockData d) { pasos.add(() -> b(a, y, bb).setBlockData(d, false)); }

    static boolean pared(int a, int b) {
        if (a == 0 || a == T || b == 0 || b == T) return true;
        if ((a == 17 || a == 47) && b > 0 && b < T) return true;
        return (b == 17 || b == 47) && a > 0 && a < T;
    }

    /** Huecos de las puertas (solo en y 1..3). */
    static boolean puerta(int a, int b) {
        // salon del medio con las salas de los bordes
        if ((b == 17 || b == 47) && a >= 30 && a <= 34) return true;
        if ((a == 17 || a == 47) && b >= 30 && b <= 34) return true;
        // salas de los bordes con las esquinas
        boolean cerca = (b >= 8 && b <= 9) || (b >= 55 && b <= 56);
        if ((a == 17 || a == 47) && cerca) return true;
        boolean cercaA = (a >= 8 && a <= 9) || (a >= 55 && a <= 56);
        if ((b == 17 || b == 47) && cercaA) return true;
        // entrada (pasillo del sur)
        return b == T && a >= 31 && a <= 33;
    }

    static boolean luz(int a, int b) {
        boolean ea = false, eb = false;
        for (int x : LUCES) { if (x == a) ea = true; if (x == b) eb = true; }
        return ea && eb && !pared(a, b);
    }

    private static final Material[] PAPELITOS = {Material.MAGENTA_CONCRETE, Material.LIME_CONCRETE, Material.CYAN_CONCRETE,
            Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE, Material.LIGHT_BLUE_CONCRETE};

    void construir() {
        // piso, aire, techo y paredes
        for (int a = 0; a <= T; a++) for (int bb = 0; bb <= T; bb++) {
            Material piso = rnd.nextDouble() < 0.07 ? PAPELITOS[rnd.nextInt(PAPELITOS.length)] : Material.BLACK_CONCRETE;
            poner(a, 0, bb, piso);
            poner(a, ALTO + 1, bb, luz(a, bb) ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ);
            boolean p = pared(a, bb);
            for (int y = 1; y <= ALTO; y++) {
                if (p && !(puerta(a, bb) && y <= 3)) poner(a, y, bb, y == 1 ? Material.WHITE_CONCRETE : Material.ORANGE_TERRACOTTA);
                else poner(a, y, bb, Material.AIR);
            }
        }
        // pasillo de la entrada (negro, sin luz)
        for (int bb = T + 1; bb <= T + 17; bb++) for (int a = 30; a <= 34; a++) {
            boolean fin = bb == T + 17;
            for (int y = 0; y <= ALTO; y++) {
                boolean hueco = !fin && a >= 31 && a <= 33 && y >= 1 && y <= 3;
                poner(a, y, bb, hueco ? Material.AIR : Material.BLACK_CONCRETE);
            }
        }
        // pilares de colores del salon
        Material[] col = {Material.ORANGE_CONCRETE, Material.LIME_CONCRETE, Material.RED_CONCRETE, Material.BLUE_CONCRETE};
        int[][] pil = {{24, 24}, {24, 39}, {39, 24}, {39, 39}};
        for (int i = 0; i < 4; i++) for (int da = 0; da < 2; da++) for (int db = 0; db < 2; db++)
            for (int y = 1; y <= ALTO; y++) poner(pil[i][0] + da, y, pil[i][1] + db, col[i]);
        // mesas
        mesa(27, 37, 30, true, true);
        mesa(27, 37, 34, true, true);
        for (int[] e : new int[][]{{6, 8}, {53, 8}, {6, 56}, {53, 56}}) mesa(e[0], e[0] + 5, e[1], true, true);
        // globos en las esquinas
        for (int[] e : espejos(13, 3)) globo(e[0], e[1], Material.RED_WOOL);
        for (int[] e : espejos(14, 3)) globo(e[0], e[1], Material.BLUE_WOOL);
        for (int[] e : espejos(13, 4)) globo(e[0], e[1], Material.YELLOW_WOOL);
        // banderines arriba de las paredes del salon
        banderines();
        // cofres
        for (int[] c : COFRES) {
            BlockData d = Material.CHEST.createBlockData();
            ((Directional) d).setFacing(c[1] < 32 ? BlockFace.SOUTH : BlockFace.NORTH);
            poner(c[0], 1, c[1], d);
        }
    }

    /** Mesa de a hasta a2 en la fila b: patas de cerco y mantel blanco, con sillas a los dos lados. */
    private void mesa(int a1, int a2, int b, boolean sillaN, boolean sillaS) {
        for (int a = a1; a <= a2; a++) {
            poner(a, 1, b, Material.BIRCH_FENCE);
            poner(a, 2, b, Material.WHITE_CARPET);
            if ((a - a1) % 2 == 1) {
                if (sillaN) {
                    BlockData s = Material.POLISHED_ANDESITE_STAIRS.createBlockData();
                    ((Directional) s).setFacing(BlockFace.NORTH);
                    poner(a, 1, b - 1, s);
                }
                if (sillaS) {
                    BlockData s = Material.POLISHED_ANDESITE_STAIRS.createBlockData();
                    ((Directional) s).setFacing(BlockFace.SOUTH);
                    poner(a, 1, b + 1, s);
                }
            }
        }
    }

    private void globo(int a, int b, Material color) {
        poner(a, 1, b, Material.CHAIN);
        poner(a, 2, b, Material.CHAIN);
        poner(a, 3, b, color);
    }

    private static final Material[] BANDERAS = {Material.RED_WALL_BANNER, Material.YELLOW_WALL_BANNER, Material.LIME_WALL_BANNER,
            Material.LIGHT_BLUE_WALL_BANNER, Material.MAGENTA_WALL_BANNER, Material.ORANGE_WALL_BANNER};

    private void banderines() {
        int k = 0;
        for (int x = 19; x <= 45; x += 2) {
            if (x >= 29 && x <= 35) continue;
            bandera(x, 18, BlockFace.SOUTH, k);
            bandera(x, 46, BlockFace.NORTH, k + 3);
            bandera(18, x, BlockFace.EAST, k + 1);
            bandera(46, x, BlockFace.WEST, k + 4);
            k++;
        }
    }

    private void bandera(int a, int b, BlockFace f, int k) {
        BlockData d = BANDERAS[Math.floorMod(k, BANDERAS.length)].createBlockData();
        ((Directional) d).setFacing(f);
        poner(a, ALTO, b, d);
    }

    // ================================================================= utiles para Fiesta

    Location centro(int a, int y, int b) { return new Location(w, ox + a + 0.5, oy + y, oz + b + 0.5); }

    /** Todas las luces del techo. */
    List<Block> luces() {
        List<Block> out = new ArrayList<>();
        for (int a : LUCES) for (int b : LUCES) if (!pared(a, b)) out.add(b(a, ALTO + 1, b));
        return out;
    }

    /** Esta adentro de la fiesta (con el pasillo). */
    boolean adentro(Location l) {
        if (l.getWorld() != w) return false;
        int a = l.getBlockX() - ox, b = l.getBlockZ() - oz, y = l.getBlockY() - oy;
        return a >= -1 && a <= T + 1 && b >= -1 && b <= T + 18 && y >= -2 && y <= ALTO + 3;
    }

    /** Llego al final del pasillo (vuelve a las backrooms). */
    boolean enSalida(Location l) {
        if (l.getWorld() != w) return false;
        int a = l.getBlockX() - ox, b = l.getBlockZ() - oz;
        return a >= 30 && a <= 34 && b >= T + 13 && b <= T + 17;
    }

    /** Donde aparece el que entra (en el pasillo, mirando hacia adentro). */
    Location llegada() {
        Location l = centro(32, 1, T + 9);
        l.setYaw(180);
        return l;
    }
}
