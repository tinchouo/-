package ar.tod4.aether;

import java.util.HashMap;
import java.util.Map;

/**
 * Las piezas del datapack aether_v7 (data/aether/structure/*.nbt).
 * c = indice del centro dentro de la plantilla, rax = radio sobre los ejes (hasta el borde donde apoya el puente),
 * rm = radio para la ocupacion (superelipse), ylo/yhi = alto real de la isla dentro de la plantilla.
 */
final class Piezas {

    record Pieza(String n, int c, int rax, int rm, int ylo, int yhi, boolean big) { }

    /** Altura de la cubierta (la superficie de la isla) dentro de la plantilla. */
    static final int DECK = 44;
    /** Posicion del marco del tridente dentro de la plantilla del windmill. */
    static final int[] MARCO_WINDMILL = {103, 128, 98};

    private static final Map<String, Pieza> P = new HashMap<>();

    private static void p(String n, int c, int rax, int rm, int ylo, int yhi, boolean big) {
        P.put(n, new Pieza(n, c, rax, rm, ylo, yhi, big));
    }

    static Pieza get(String n) { return P.get(n); }

    static {
        p("village_1", 104, 100, 123, 23, 57, true);
        p("village_2", 104, 100, 123, 23, 58, true);
        p("village_3", 104, 100, 123, 23, 58, true);
        p("windmill", 103, 99, 122, 18, 149, true);
        p("coliseum", 108, 80, 95, 23, 58, true);
        p("mushroom_island_1", 102, 75, 90, 21, 109, true);
        p("mushroom_island_2", 102, 75, 90, 21, 109, true);
        p("mushroom_island_3", 102, 75, 90, 21, 109, true);
        p("mushroom_island_4", 102, 75, 90, 21, 101, true);
        p("spawn", 61, 44, 54, 10, 74, true);
        p("tiny_1", 7, 2, 6, 38, 45, false);
        p("tiny_2", 8, 3, 7, 36, 46, false);
        p("tiny_3", 8, 3, 7, 37, 45, false);
        p("tiny_4", 9, 4, 9, 35, 46, false);
        p("small_1", 11, 5, 10, 33, 47, false);
        p("small_2", 12, 6, 11, 31, 45, false);
        p("small_3", 12, 6, 11, 32, 47, false);
        p("small_4", 12, 6, 11, 31, 47, false);
        p("small_f1", 17, 6, 11, 31, 46, false);
        p("small_f2", 17, 6, 11, 30, 47, false);
        p("medium_1", 24, 15, 21, 27, 50, false);
        p("medium_2", 24, 15, 21, 27, 51, false);
        p("medium_3", 29, 15, 21, 27, 52, false);
        p("medium_4", 24, 15, 21, 28, 52, false);
        p("bm_1", 24, 15, 21, 28, 55, false);
        p("bm_2", 24, 15, 21, 28, 57, false);
        p("bm_3", 24, 15, 21, 27, 55, false);
        p("bm_4", 29, 15, 21, 27, 57, false);
        p("bm_c1", 24, 15, 21, 27, 57, false);
        p("bm_c2", 24, 15, 21, 26, 56, false);
        p("bm_r1", 24, 15, 21, 27, 57, false);
        p("bm_r2", 24, 15, 21, 28, 57, false);
        p("bl_1", 69, 50, 61, 6, 57, true);
        p("bl_2", 69, 50, 61, 4, 57, true);
        p("bl_3", 69, 50, 61, 5, 57, true);
        p("large_1", 69, 50, 61, 5, 54, true);
        p("large_2", 69, 50, 61, 4, 54, true);
        p("large_3", 74, 50, 61, 4, 54, true);
        p("large_4", 69, 50, 61, 2, 54, true);
        p("large_h1", 69, 50, 61, 3, 54, true);
        p("large_h2", 69, 50, 61, 3, 58, true);
        p("large_ruin", 69, 50, 61, 4, 54, true);
    }

    /** Puentes: [bgt][xz]b?_<largo>_<z0|p1|p2|n1|n2> */
    static boolean esPuente(String n) { return n.matches("[bgt][xz]b?_.*"); }

    /** Nombre del bioma (F3) para una pieza. */
    static String bioma(String n) {
        if (n.startsWith("village")) return "village";
        if (n.equals("windmill")) return "windmill";
        if (n.equals("coliseum")) return "coliseum";
        if (n.startsWith("mushroom_island")) return "mushroom_island";
        return "land";
    }

    /** Nombre de la estructura especial ("village_2" -> "village"), o null si es una isla comun. */
    static String especial(String n) {
        if (n.startsWith("village")) return "village";
        if (n.equals("windmill")) return "windmill";
        if (n.equals("coliseum")) return "coliseum";
        if (n.startsWith("mushroom_island")) return "mushroom_island";
        return null;
    }
}
