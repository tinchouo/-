package ar.tod4.aether;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Las piezas del datapack aether_v7 (data/aether/structure/*.nbt). La tabla la genera el generador (build.py) en
 * src/main/resources/aether_piezas.json: medidas, baldosas (las piezas grandes se pegan de a pedazos), menas de Truco y atriles.
 * c = indice del centro dentro de la plantilla, rax = radio sobre los ejes (hasta el borde donde apoya el puente),
 * rm = radio para la ocupacion (superelipse), ylo/yhi = alto real de la isla dentro de la plantilla.
 */
final class Piezas {

    record Pieza(String n, int c, int rax, int rm, int ylo, int yhi, boolean big,
                 int[] size, List<Object[]> tiles, List<int[]> ores, List<int[]> lecterns) { }

    /** Altura de la cubierta (la superficie de la isla) dentro de la plantilla. */
    static final int DECK = 44;
    /** Posicion del marco del tridente dentro de la plantilla del windmill. */
    static int[] MARCO_WINDMILL = {103, 128, 98};

    private static final Map<String, Pieza> P = new HashMap<>();

    static Pieza get(String n) { return P.get(n); }

    static {
        try (InputStreamReader r = new InputStreamReader(Piezas.class.getResourceAsStream("/aether_piezas.json"), StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            JsonArray mw = root.getAsJsonArray("marco_windmill");
            MARCO_WINDMILL = new int[]{mw.get(0).getAsInt(), mw.get(1).getAsInt(), mw.get(2).getAsInt()};
            for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("piezas").entrySet()) {
                JsonObject o = en.getValue().getAsJsonObject();
                List<Object[]> tiles = new java.util.ArrayList<>();
                if (o.has("tiles")) for (JsonElement t : o.getAsJsonArray("tiles")) {
                    JsonArray a = t.getAsJsonArray();
                    tiles.add(new Object[]{a.get(0).getAsString(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
                int[] size = o.has("size") ? arr(o.getAsJsonArray("size")) : new int[]{2 * o.get("c").getAsInt() + 1, 0, 2 * o.get("c").getAsInt() + 1};
                P.put(en.getKey(), new Pieza(en.getKey(), o.get("c").getAsInt(), o.get("rax").getAsInt(), o.get("rm").getAsInt(),
                        o.get("ylo").getAsInt(), o.get("yhi").getAsInt(), o.get("big").getAsBoolean(), size, tiles,
                        lista(o, "ores"), lista(o, "lecterns")));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No pude leer aether_piezas.json", ex);
        }
    }

    private static int[] arr(JsonArray a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
        return r;
    }

    private static List<int[]> lista(JsonObject o, String k) {
        List<int[]> l = new java.util.ArrayList<>();
        if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) l.add(arr(e.getAsJsonArray()));
        return l;
    }

    /** Puentes: [bgt][xz][bf]?_<largo>_<z0|p1|p2|n1|n2>  (b = roto, f = con banderines) */
    static boolean esPuente(String n) { return n.matches("[bgt][xz][bf]?_.*") && !n.startsWith("bm_") && !n.startsWith("bl_"); }

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
