package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;

/** Un requisito de mision: tipo + objetivos (se suman) + cantidad. */
public final class Req {

    public enum Tipo {
        CONSEGUIR("Conseguir", Material.CHEST, "Agarrarlo, craftearlo en cualquier mesa,", "comprarlo, fundirlo, sacarlo de un cofre nuevo..."),
        PICAR("Picar", Material.DIAMOND_PICKAXE, "Romper los bloques (se suman entre sí).", "Los bloques puestos por jugadores no cuentan."),
        MATAR("Matar", Material.IRON_SWORD, "Matar mobs vanilla o de MythicMobs", "(se suman entre sí)."),
        LOGRO("Logro", Material.KNOWLEDGE_BOOK, "Tener logros (vanilla o custom)."),
        DIMENSION("Ir a", Material.ENDER_EYE, "Entrar a un mundo / dimensión."),
        EVENTO("Evento", Material.PAPER, "Lo marca un admin con /tod4:completar.");

        public final String label;
        public final Material icono;
        public final String[] desc;

        Tipo(String label, Material icono, String... desc) { this.label = label; this.icono = icono; this.desc = desc; }
    }

    public Tipo tipo;
    public List<String> objs = new ArrayList<>();
    public int cant = 1;
    /** Solo Conseguir: se entrega (se saca del inventario) al reclamar. */
    public boolean consumir;
    /** Solo Evento: descripcion. */
    public String texto = "";

    public Req(Tipo t) { this.tipo = t; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("tipo", tipo.name());
        m.put("objs", new ArrayList<>(objs));
        m.put("cant", cant);
        m.put("consumir", consumir);
        m.put("texto", texto);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Req of(Map<?, ?> m) {
        Req r = new Req(Tipo.valueOf(String.valueOf(m.get("tipo"))));
        Object o = m.get("objs");
        if (o instanceof List<?> l) for (Object x : l) r.objs.add(String.valueOf(x));
        Object c = m.get("cant");
        r.cant = c instanceof Number n ? Math.max(1, n.intValue()) : 1;
        r.consumir = Boolean.TRUE.equals(m.get("consumir"));
        Object t = m.get("texto");
        r.texto = t == null ? "" : String.valueOf(t);
        return r;
    }
}
