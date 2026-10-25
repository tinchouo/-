package ar.tod4.misiones;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ar.tod4.TOD4;
import ar.tod4.avisos.Paleta;
import ar.tod4.data.Store;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.title.Title;

/**
 * Misiones TOD4 (portado de misiones.sk): una diaria y una opcional activas a la vez.
 * Cada mision tiene una ID sin espacios (1, 2, 3... / op1, op2...) y un nombre.
 * Estados por jugador: En curso -> Completada (todas las metas) -> Reclamada (recompensa entregada).
 * La opcional esta bloqueada mientras haya una diaria activa sin reclamar.
 */
public final class Misiones {

    public enum Estado { NINGUNA, BLOQUEADA, EN_CURSO, COMPLETADA, RECLAMADA }

    public static final String DIA = "dia", OP = "op";

    final TOD4 pl;
    final Store st;
    private final Map<String, List<Objetivo>> cache = new HashMap<>();

    public Misiones(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "misiones");
        new Seguimiento(pl, this);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { for (Player p : Bukkit.getOnlinePlayers()) verificar(p); }, 40L, 40L);
    }

    public Store store() { return st; }

    public static String nombreClase(String clase) { return clase.equals(DIA) ? "diaria" : "opcional"; }

    /** Color de la mision: verde la diaria, celeste la opcional. */
    public static String color(String clase) { return clase.equals(DIA) ? "&a" : "&b"; }

    // ================================================================= misiones guardadas

    private static final Pattern NUM = Pattern.compile("^(\\D*)(\\d+)$");

    /** IDs de una clase, en orden natural (1, 2, ..., 10 / op1, op2...). */
    public List<String> ids(String clase) {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = st.y().getConfigurationSection("misiones");
        if (s != null) for (String k : s.getKeys(false)) if (clase.equals(s.getString(k + ".clase", DIA))) out.add(k);
        out.sort(Comparator.comparing((String x) -> {
            Matcher m = NUM.matcher(x);
            return m.matches() ? m.group(1) : x;
        }).thenComparingLong(x -> {
            Matcher m = NUM.matcher(x);
            try { return m.matches() ? Long.parseLong(m.group(2)) : Long.MAX_VALUE; } catch (NumberFormatException e) { return Long.MAX_VALUE; }
        }).thenComparing(x -> x));
        return out;
    }

    public boolean existe(String id) { return id != null && st.y().isConfigurationSection("misiones." + id); }

    public String nombre(String id) { return st.y().getString("misiones." + id + ".nombre", id); }

    public void renombrar(String id, String nombre) { st.set("misiones." + id + ".nombre", nombre); }

    public String clase(String id) { return st.y().getString("misiones." + id + ".clase", DIA); }

    public List<Objetivo> objetivos(String id) {
        List<Objetivo> c = cache.get(id);
        if (c != null) return c;
        List<Objetivo> out = new ArrayList<>();
        List<Map<?, ?>> raw = st.y().getMapList("misiones." + id + ".objetivos");
        if (raw.isEmpty()) raw = st.y().getMapList("misiones." + id + ".reqs"); // version anterior
        boolean sinUid = false;
        for (Map<?, ?> m : raw) {
            try { out.add(Objetivo.of(m)); if (m.get("uid") == null) sinUid = true; } catch (Exception ignored) { }
        }
        // misiones de la version anterior: se guardan ya con su uid (si no, el progreso se perderia al reiniciar)
        if (sinUid) guardarObjetivos(id, out);
        cache.put(id, out);
        return out;
    }

    public void guardarObjetivos(String id, List<Objetivo> l) {
        List<Map<String, Object>> m = new ArrayList<>();
        for (Objetivo o : l) m.add(o.toMap());
        st.set("misiones." + id + ".objetivos", m);
        st.set("misiones." + id + ".reqs", null);
        cache.remove(id);
    }

    public List<ItemStack> recompensas(String id) {
        List<ItemStack> out = new ArrayList<>();
        List<?> l = st.y().getList("misiones." + id + ".recompensas");
        if (l != null) for (Object o : l) if (o instanceof ItemStack it) out.add(it);
        return out;
    }

    public void guardarRecompensas(String id, List<ItemStack> l) { st.set("misiones." + id + ".recompensas", l); }

    public void crear(String id, String nombre, String clase) {
        st.set("misiones." + id + ".nombre", nombre);
        st.set("misiones." + id + ".clase", clase);
        st.set("misiones." + id + ".objetivos", new ArrayList<>());
        cache.remove(id);
    }

    public void borrar(String id) {
        for (String c : new String[]{DIA, OP}) if (id.equals(activa(c))) desactivar(c, false);
        st.set("misiones." + id, null);
        cache.remove(id);
    }

    // ================================================================= activa

    public String activa(String clase) {
        String id = st.y().getString("activa." + clase);
        return existe(id) ? id : null;
    }

    public void activar(String clase, String id) {
        if (clase.equals(DIA)) {
            // la diaria se activa cuando la ruleta cae en verde (asi nadie la ve antes)
            st.set("activa." + clase, null);
            pl.avisos().ruletaGlobal(Paleta.VERDE, () -> {
                empezar(clase, id);
                if (pl.discord() != null) pl.discord().publicarMision(this, clase, id);
                caja("&a&l¡NUEVA MISIÓN DIARIA!", "&fCompleta la misión para recibir la", "&frecompensa del día.", "&a");
                sonarTodos(pl.getConfig().getString("misiones.sonido_activar_dia", "block.end_portal.spawn"));
                for (Player p : Bukkit.getOnlinePlayers()) verificar(p);
            });
        } else {
            empezar(clase, id);
            if (pl.discord() != null) pl.discord().publicarMision(this, clase, id);
            caja("&b&l¡NUEVA MISIÓN OPCIONAL!", "&fCompleta la misión para recibir", "&frecompensas extra.", "&b");
            sonarTodos(pl.getConfig().getString("misiones.sonido_activar_op", "block.note_block.chime"));
            for (Player p : Bukkit.getOnlinePlayers()) verificar(p);
        }
    }

    private void empezar(String clase, String id) {
        st.set("activa." + clase, id);
        for (String k : new String[]{"prog", "vis", "ultimo", "reclamo", "bypass"}) st.set(k + "." + clase, null);
    }

    private void sonarTodos(String s) {
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), s, SoundCategory.MASTER, 1f, 1f);
    }

    private void caja(String titulo, String l1, String l2, String cmd) {
        Style.broadcast("");
        Style.broadcast(" &8&m---------------------------------------");
        Style.broadcast("        &k||&r " + titulo + " &r&k||&r");
        Style.broadcast("");
        Style.broadcast("    " + l1);
        Style.broadcast("    " + l2);
        Style.broadcast("");
        Style.broadcast("    &7Revisa los objetivos con " + cmd + "/misiones");
        Style.broadcast(" &8&m---------------------------------------");
        Style.broadcast("");
    }

    public void desactivar(String clase, boolean avisar) {
        if (activa(clase) == null) return;
        st.set("activa." + clase, null);
        if (avisar) Style.broadcast("&c✖ La misión " + nombreClase(clase) + " se ha desactivado.");
    }

    // ================================================================= progreso

    private String base(String k, String clase, Player p) { return k + "." + clase + "." + p.getUniqueId(); }

    private String pk(String clase, Player p, Objetivo o, Objetivo.Meta m) {
        return base("prog", clase, p) + "." + o.uid + "." + m.clave;
    }

    int contador(String clase, Player p, Objetivo o, Objetivo.Meta m) { return st.y().getInt(pk(clase, p, o, m), 0); }

    void sumar(String clase, Player p, Objetivo o, Objetivo.Meta m, int n) {
        if (n <= 0) return;
        int v = Math.min(m.cant, contador(clase, p, o, m) + n);
        st.set(pk(clase, p, o, m), v);
    }

    /** Marca que el jugador estuvo en un mundo (para "Ir a"). */
    void visitar(String clase, Player p, Objetivo o, String obj) {
        st.set(base("vis", clase, p) + "." + o.uid + "." + Integer.toHexString(obj.hashCode()), true);
    }

    private boolean visito(String clase, Player p, Objetivo o, String obj) {
        return st.y().getBoolean(base("vis", clase, p) + "." + o.uid + "." + Integer.toHexString(obj.hashCode()), false)
                || Obj.mundo(obj, p.getWorld());
    }

    public boolean bypass(String clase, Player p) { return st.y().getBoolean(base("bypass", clase, p), false); }

    public boolean reclamada(String clase, Player p) { return st.y().getBoolean(base("reclamo", clase, p), false); }

    /** Progreso actual de una meta (lo de tener / logros / dimensiones se mira en vivo). */
    public int progreso(String clase, Player p, Objetivo o, Objetivo.Meta m) {
        if (bypass(clase, p)) return m.cant;
        int v;
        switch (o.tipo) {
            case TENER:
                v = Obj.contar(p.getInventory(), m.objs);
                break;
            case LOGRO: {
                int n = 0;
                for (String x : m.objs) {
                    NamespacedKey k = NamespacedKey.fromString(x.substring(4));
                    Advancement a = k == null ? null : Bukkit.getAdvancement(k);
                    if (a != null && p.getAdvancementProgress(a).isDone()) n++;
                }
                v = n;
                break;
            }
            case DIMENSION: {
                int n = 0;
                for (String x : m.objs) if (visito(clase, p, o, x)) n++;
                v = n;
                break;
            }
            default:
                v = contador(clase, p, o, m);
        }
        return Math.min(m.cant, v);
    }

    /** {metas hechas, metas totales} de la mision activa de esa clase. */
    public int[] metas(String clase, Player p) {
        String id = activa(clase);
        if (id == null) return new int[]{0, 0};
        int hechas = 0, total = 0;
        for (Objetivo o : objetivos(id)) {
            for (Objetivo.Meta m : o.metas()) {
                total++;
                if (progreso(clase, p, o, m) >= m.cant) hechas++;
            }
        }
        return new int[]{hechas, total};
    }

    public boolean completa(String clase, Player p) {
        if (activa(clase) == null) return false;
        if (bypass(clase, p)) return true;
        int[] m = metas(clase, p);
        return m[1] > 0 && m[0] >= m[1];
    }

    public Estado estado(String clase, Player p) {
        if (activa(clase) == null) return Estado.NINGUNA;
        if (clase.equals(OP) && bloqueada(p)) return Estado.BLOQUEADA;
        if (reclamada(clase, p)) return Estado.RECLAMADA;
        return completa(clase, p) ? Estado.COMPLETADA : Estado.EN_CURSO;
    }

    /** La opcional se bloquea mientras haya una diaria activa sin reclamar (como antes). */
    public boolean bloqueada(Player p) { return activa(DIA) != null && !reclamada(DIA, p); }

    /** Puede sumar progreso en esa clase. */
    boolean cuenta(String clase, Player p) {
        if (activa(clase) == null || reclamada(clase, p)) return false;
        return !(clase.equals(OP) && bloqueada(p));
    }

    /**
     * Sonidos y avisos (como chequearProgreso del misiones.sk):
     * cada meta nueva suena "meta" y avisa n/total; la ultima suena solo "gg".
     */
    public void verificar(Player p) {
        for (String c : new String[]{DIA, OP}) {
            if (!cuenta(c, p)) continue;
            int[] m = metas(c, p);
            int hechas = bypass(c, p) ? m[1] : m[0], total = m[1];
            if (total == 0) continue;
            String uk = base("ultimo", c, p);
            int ultimo = st.y().getInt(uk, 0);
            if (hechas <= ultimo) continue;
            st.set(uk, hechas);
            boolean dia = c.equals(DIA);
            if (hechas < total) {
                p.playSound(p.getLocation(), pl.getConfig().getString("misiones.sonido_meta", "tod4:meta_mision"), SoundCategory.MASTER, 1f, 1f);
                Style.raw(p, dia ? "&2&lMisión Diaria: &a[" + hechas + "/" + total + " objetivos completados]"
                        : "&b&lMisión Opcional: &f[" + hechas + "/" + total + " objetivos completados]");
                p.sendActionBar(Txt.c(color(c) + "✔ Objetivo completado &7(" + hechas + "/" + total + ")"));
            } else {
                p.playSound(p.getLocation(), pl.getConfig().getString("misiones.sonido_gg", "tod4:gg_mision"), SoundCategory.MASTER, 1f, 1f);
                Style.raw(p, "&e&l¡Completaste todos tus objetivos! (" + total + "/" + total + ")");
                Style.raw(p, "&eUsa &f/misiones &epara reclamar tu recompensa.");
            }
        }
    }

    /** /tod4:completar ... : la mision entera (como el /completar viejo). */
    public void completarTodo(String clase, Player p) {
        st.set(base("bypass", clase, p), true);
        Style.raw(p, "&e&l¡Se completó tu misión! &7Revisa &f/misiones&7.");
        verificar(p);
    }

    /** /tod4:completar ... evento : solo los objetivos de tipo Evento. */
    public boolean completarEvento(String clase, Player p) {
        String id = activa(clase);
        if (id == null) return false;
        boolean hay = false;
        for (Objetivo o : objetivos(id)) {
            if (o.tipo != Objetivo.Tipo.EVENTO) continue;
            hay = true;
            for (Objetivo.Meta m : o.metas()) sumar(clase, p, o, m, 1);
        }
        if (hay) verificar(p);
        return hay;
    }

    /** Reclamar: entrega lo que hay que entregar, da la recompensa y lo anuncia. */
    public void reclamar(String clase, Player p) {
        String id = activa(clase);
        if (id == null) return;
        if (estado(clase, p) != Estado.COMPLETADA) { Style.err(p, "Todavía no completaste la misión."); return; }
        boolean bypass = bypass(clase, p);
        if (!bypass) {
            // primero se fija que tenga todo lo que se entrega (Fabricar puede pedir entregarlo tambien)
            for (Objetivo o : objetivos(id)) {
                if (!o.tipo.consumible() || !o.consumir) continue;
                for (Objetivo.Meta m : o.metas()) {
                    if (Obj.contar(p.getInventory(), m.objs) < m.cant) {
                        Style.err(p, "Para reclamar tienes que tener en el inventario lo que se entrega.");
                        return;
                    }
                }
            }
            for (Objetivo o : objetivos(id)) {
                if (!o.tipo.consumible() || !o.consumir) continue;
                for (Objetivo.Meta m : o.metas()) Obj.sacar(p.getInventory(), m.objs, m.cant);
            }
        }
        st.set(base("reclamo", clase, p), true);
        for (ItemStack it : recompensas(id)) {
            Map<Integer, ItemStack> sobra = p.getInventory().addItem(it.clone());
            for (ItemStack s : sobra.values()) p.getWorld().dropItemNaturally(p.getLocation(), s);
        }
        boolean dia = clase.equals(DIA);
        p.playSound(p.getLocation(), pl.getConfig().getString(dia ? "misiones.sonido_completar_dia" : "misiones.sonido_completar_op",
                dia ? "tod4:completar_diaria" : "tod4:completar_opcional"), SoundCategory.MASTER, 1f, 1f);
        p.showTitle(Title.title(Txt.c(color(clase) + "&l¡MISIÓN COMPLETADA!"), Txt.c("&fRecompensa entregada en tu inventario."),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1900), Duration.ofMillis(500))));
        Style.broadcast(dia ? "&a✔ &a&l" + p.getName() + " &7ha completado la &amisión diaria&7."
                : "&b✔ &b&l" + p.getName() + " &7ha completado la &bmisión opcional&7.");
    }

    // ================================================================= tab / sidebar

    /** Simbolo del tab, igual que el tabulador.sk viejo. */
    public String simboloTab(Player p) {
        // diaria sin reclamar: ✖ rojo | opcional reclamada: ✔ azul (haya o no diaria) | diaria reclamada: ✔ verde | nada: - gris
        boolean opRecl = activa(OP) != null && reclamada(OP, p);
        if (activa(DIA) != null && !reclamada(DIA, p)) return "&c✖ ";
        if (opRecl) return "&b✔ ";
        if (activa(DIA) != null) return "&a✔ ";
        return "&7- ";
    }

    private static final String[] PUNTOS = {"", ".", "..", "..."};

    /** Estado con el simbolo a la derecha. anim = cuadro de los puntitos (0-3). */
    public String textoEstado(Estado e, int anim) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("misiones.estados");
        switch (e) {
            case BLOQUEADA: return c != null ? c.getString("bloqueada", "&cBloqueada 🔒") : "&cBloqueada 🔒";
            case EN_CURSO: return (c != null ? c.getString("en_curso", "&7En curso") : "&7En curso") + PUNTOS[Math.floorMod(anim, 4)];
            case COMPLETADA: return c != null ? c.getString("completada", "&6¡Completada!") : "&6¡Completada!";
            case RECLAMADA: return c != null ? c.getString("reclamada", "&eReclamada ✔") : "&eReclamada ✔";
            default: return "";
        }
    }

    /** Para PlaceholderAPI. */
    public String textoEstado(Estado e) { return textoEstado(e, 3); }

    /** Misiones activas con sus objetivos (para el seguimiento). */
    Map<String, List<Objetivo>> activas() {
        Map<String, List<Objetivo>> m = new LinkedHashMap<>();
        for (String c : new String[]{DIA, OP}) {
            String id = activa(c);
            if (id != null) m.put(c, objetivos(id));
        }
        return m;
    }

    /** Busca la clase de una ID (las IDs son unicas entre diarias y opcionales). */
    public String claseDe(String id) { return existe(id) ? clase(id) : null; }
}
