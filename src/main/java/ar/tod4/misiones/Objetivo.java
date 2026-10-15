package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;

/**
 * Un objetivo de una mision.
 *  - Modo normal: cada entrada es una meta aparte con su cantidad (casco 0/1, pechera 0/1...).
 *  - Modo "en conjunto": todas las entradas suman a un solo contador (mena de diamante o de pizarra: 0/20).
 * Cada meta completada suena "meta"; la ultima de toda la mision suena "gg".
 */
public final class Objetivo {

    public enum Tipo {
        TENER("Conseguir (tenerlo)", "Conseguir", Material.CHEST,
                "&7El jugador tiene que &ftenerlo en el inventario&7.",
                "&7No importa de dónde lo sacó (se lo pueden pasar).",
                "&7Por defecto &cse consume al reclamar&7."),
        FABRICAR("Fabricar / Obtener", "Conseguir", Material.CRAFTING_TABLE,
                "&7Solo cuenta si &fÉL MISMO&7 lo saca de: mesa de crafteo,",
                "&7hornos, aldeanos, herrería, cortapiedras, telar,",
                "&7cartografía, afiladora, yunque, pesca o baldes.",
                "&7Si se lo pasa un compañero, no cuenta.",
                "&7El jugador lo ve como &f\"Conseguir\"&7."),
        PICAR("Picar", "Picar", Material.DIAMOND_PICKAXE,
                "&7Cuenta cuando &fél&7 rompe el bloque.",
                "&7Funciona con bloques de Oraxen.",
                "&7Por defecto los bloques puestos por jugadores no cuentan."),
        MATAR("Matar", "Matar", Material.IRON_SWORD,
                "&7Cuenta cuando &fél&7 mata al mob.",
                "&7Mobs normales, variantes (panda marrón...) o MythicMobs."),
        LOGRO("Logro", "Logro", Material.KNOWLEDGE_BOOK,
                "&7Tener un logro (vanilla o del pack de TOD4)."),
        DIMENSION("Ir a una dimensión", "Ir a", Material.ENDER_EYE,
                "&7Entrar a un mundo / dimensión (Nether, End, Aether...)."),
        EVENTO("Evento", "Evento", Material.NAME_TAG,
                "&7Algo que el juego no puede detectar.",
                "&7Lo completas tú con:",
                "&e/tod4:completar <jugador|todos> <id> evento");

        /** Nombre para el admin. */
        public final String label;
        /** Nombre que ve el jugador. */
        public final String visible;
        public final Material icono;
        public final String[] desc;

        Tipo(String label, String visible, Material icono, String... desc) {
            this.label = label; this.visible = visible; this.icono = icono; this.desc = desc;
        }

        /** Los que usan el cofre de items. */
        public boolean conCofre() { return this == TENER || this == FABRICAR || this == PICAR || this == MATAR; }

        /** Los que pueden entregar los items al reclamar. */
        public boolean consumible() { return this == TENER || this == FABRICAR; }
    }

    /** Identificador interno (el progreso se guarda con esto, asi editar no mezcla los contadores). */
    public String uid;
    public Tipo tipo;
    /** objetivo (mat:/ox:/ent:/mm:/adv:/dim:) -> cantidad (en modo conjunto la cantidad de cada uno no importa). */
    public LinkedHashMap<String, Integer> entradas = new LinkedHashMap<>();
    public boolean conjunto;
    /** Cantidad total del modo en conjunto (el relojito). */
    public int total = 1;
    /** Tener / Fabricar: se saca del inventario al reclamar. */
    public boolean consumir;
    /** Picar: los bloques puestos por jugadores no cuentan. */
    public boolean sinPuestos = true;
    /** El jugador ve ??? hasta completarlo. */
    public boolean oculto;
    /** Evento: descripcion. */
    public String texto = "";

    public Objetivo(Tipo t) {
        this.tipo = t;
        this.uid = UUID.randomUUID().toString().substring(0, 8);
        this.consumir = t == Tipo.TENER;
    }

    /** Una meta: un objetivo puede tener varias (modo normal) o una sola (conjunto / evento). */
    public static final class Meta {
        public final String clave;        // para guardar el progreso
        public final List<String> objs;   // que cuenta
        public final int cant;
        Meta(String clave, List<String> objs, int cant) { this.clave = clave; this.objs = objs; this.cant = cant; }
    }

    public List<Meta> metas() {
        List<Meta> out = new ArrayList<>();
        if (tipo == Tipo.EVENTO) { out.add(new Meta("ev", List.of(), 1)); return out; }
        if (entradas.isEmpty()) return out;
        if (conjunto) {
            out.add(new Meta("t", new ArrayList<>(entradas.keySet()), Math.max(1, total)));
        } else {
            for (Map.Entry<String, Integer> e : entradas.entrySet()) {
                int c = (tipo == Tipo.LOGRO || tipo == Tipo.DIMENSION) ? 1 : Math.max(1, e.getValue());
                out.add(new Meta(Integer.toHexString(e.getKey().hashCode()), List.of(e.getKey()), c));
            }
        }
        return out;
    }

    public boolean vacio() { return tipo != Tipo.EVENTO && entradas.isEmpty(); }

    // ------------------------------------------------------------------ guardar / cargar

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", uid);
        m.put("tipo", tipo.name());
        // las claves llevan ':' y '.', por eso se guardan como lista
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entradas.entrySet()) {
            Map<String, Object> x = new HashMap<>();
            x.put("obj", e.getKey());
            x.put("cant", e.getValue());
            l.add(x);
        }
        m.put("entradas", l);
        m.put("conjunto", conjunto);
        m.put("total", total);
        m.put("consumir", consumir);
        m.put("sin_puestos", sinPuestos);
        m.put("oculto", oculto);
        m.put("texto", texto);
        return m;
    }

    public static Objetivo of(Map<?, ?> m) {
        Objetivo o;
        String t = String.valueOf(m.get("tipo"));
        // misiones creadas con la version anterior del plugin
        if (t.equals("CONSEGUIR")) {
            o = new Objetivo(Boolean.TRUE.equals(m.get("consumir")) ? Tipo.TENER : Tipo.FABRICAR);
        } else {
            o = new Objetivo(Tipo.valueOf(t));
        }
        if (m.get("uid") != null) o.uid = String.valueOf(m.get("uid"));
        Object en = m.get("entradas");
        if (en instanceof List<?> l) {
            for (Object x : l) if (x instanceof Map<?, ?> mx && mx.get("obj") != null)
                o.entradas.put(String.valueOf(mx.get("obj")), num(mx.get("cant"), 1));
        } else if (m.get("objs") instanceof List<?> viejos) {
            // version anterior: los objetivos se sumaban -> modo en conjunto
            for (Object x : viejos) o.entradas.put(String.valueOf(x), 1);
            o.conjunto = true;
            o.total = num(m.get("cant"), 1);
        }
        if (m.containsKey("conjunto")) o.conjunto = Boolean.TRUE.equals(m.get("conjunto"));
        if (m.containsKey("total")) o.total = num(m.get("total"), 1);
        if (m.containsKey("consumir")) o.consumir = Boolean.TRUE.equals(m.get("consumir"));
        if (m.containsKey("sin_puestos")) o.sinPuestos = !Boolean.FALSE.equals(m.get("sin_puestos"));
        o.oculto = Boolean.TRUE.equals(m.get("oculto"));
        Object tx = m.get("texto");
        o.texto = tx == null ? "" : String.valueOf(tx);
        return o;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? Math.max(1, n.intValue()) : def;
    }
}
