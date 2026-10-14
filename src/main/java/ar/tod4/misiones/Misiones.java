package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
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
 * Misiones TOD4: una diaria y una opcional activas a la vez.
 * Estados por jugador: En curso -> Completada (objetivos listos) -> Reclamada (recompensa entregada).
 * La opcional esta bloqueada mientras haya diaria activa sin reclamar.
 */
public final class Misiones {

    public enum Estado { NINGUNA, BLOQUEADA, EN_CURSO, COMPLETADA, RECLAMADA }

    public static final String DIA = "dia", OP = "op";

    final TOD4 pl;
    final Store st;

    public Misiones(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "misiones");
        new Seguimiento(pl, this);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { for (Player p : Bukkit.getOnlinePlayers()) verificar(p); }, 40L, 40L);
    }

    public Store store() { return st; }

    // ================================================================= misiones guardadas

    public List<String> ids(String clase) {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = st.y().getConfigurationSection("misiones");
        if (s != null) for (String k : s.getKeys(false)) if (clase.equals(s.getString(k + ".clase", DIA))) out.add(k);
        return out;
    }

    public boolean existe(String id) { return st.y().isConfigurationSection("misiones." + id); }

    public String nombre(String id) { return st.y().getString("misiones." + id + ".nombre", id); }

    public String clase(String id) { return st.y().getString("misiones." + id + ".clase", DIA); }

    public List<Req> reqs(String id) {
        List<Req> out = new ArrayList<>();
        for (Map<?, ?> m : st.y().getMapList("misiones." + id + ".reqs")) {
            try { out.add(Req.of(m)); } catch (Exception ignored) { }
        }
        return out;
    }

    public void guardarReqs(String id, List<Req> l) {
        List<Map<String, Object>> m = new ArrayList<>();
        for (Req r : l) m.add(r.toMap());
        st.set("misiones." + id + ".reqs", m);
    }

    @SuppressWarnings("unchecked")
    public List<ItemStack> recompensas(String id) {
        List<ItemStack> out = new ArrayList<>();
        List<?> l = st.y().getList("misiones." + id + ".recompensas");
        if (l != null) for (Object o : l) if (o instanceof ItemStack it) out.add(it);
        return out;
    }

    public void guardarRecompensas(String id, List<ItemStack> l) { st.set("misiones." + id + ".recompensas", l); }

    public void crear(String id, String clase) {
        st.set("misiones." + id + ".nombre", id.replace('_', ' '));
        st.set("misiones." + id + ".clase", clase);
        st.set("misiones." + id + ".reqs", new ArrayList<>());
    }

    public void borrar(String id) {
        for (String c : new String[]{DIA, OP}) if (id.equals(activa(c))) desactivar(c, false);
        st.set("misiones." + id, null);
    }

    // ================================================================= activa

    public String activa(String clase) {
        String id = st.y().getString("activa." + clase);
        return id != null && existe(id) ? id : null;
    }

    public void activar(String clase, String id) {
        st.set("activa." + clase, id);
        st.set("prog." + clase, null);
        st.set("completada." + clase, null);
        st.set("reclamo." + clase, null);
        st.set("bypass." + clase, null);
        if (clase.equals(DIA)) {
            pl.avisos().ruletaGlobal(Paleta.VERDE, () -> {
                caja("&a", "&a&l¡NUEVA MISIÓN DIARIA!", "&fCompleta la misión para recibir la", "&frecompensa del día.", "&a");
                for (Player p : Bukkit.getOnlinePlayers())
                    p.playSound(p.getLocation(), pl.getConfig().getString("misiones.sonido_activar_dia", "block.end_portal.spawn"), SoundCategory.MASTER, 1f, 1f);
            });
        } else {
            caja("&b", "&b&l¡NUEVA MISIÓN OPCIONAL!", "&fCompleta la misión para recibir", "&frecompensas extra.", "&b");
            for (Player p : Bukkit.getOnlinePlayers())
                p.playSound(p.getLocation(), pl.getConfig().getString("misiones.sonido_activar_op", "block.note_block.chime"), SoundCategory.MASTER, 1f, 1f);
        }
    }

    private void caja(String c, String titulo, String l1, String l2, String cmd) {
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
        if (avisar) Style.broadcast("&c✖ La misión " + (clase.equals(DIA) ? "diaria" : "opcional") + " se ha desactivado.");
    }

    // ================================================================= progreso

    private String pk(String clase, Player p, int i) { return "prog." + clase + "." + p.getUniqueId() + "." + i; }

    public int contador(String clase, Player p, int i) { return st.y().getInt(pk(clase, p, i), 0); }

    void sumarContador(String clase, Player p, int i, int n, int tope) {
        int v = Math.min(tope, contador(clase, p, i) + n);
        st.set(pk(clase, p, i), v);
    }

    private boolean en(String lista, String clase, Player p) {
        return st.y().getStringList(lista + "." + clase).contains(p.getUniqueId().toString());
    }

    private void agregar(String lista, String clase, Player p) {
        List<String> l = st.y().getStringList(lista + "." + clase);
        if (!l.contains(p.getUniqueId().toString())) { l.add(p.getUniqueId().toString()); st.set(lista + "." + clase, l); }
    }

    public boolean reclamada(String clase, Player p) { return en("reclamo", clase, p); }

    /** Progreso actual de un requisito (algunos se miran en vivo). */
    public int progreso(String clase, Player p, int i, Req r) {
        switch (r.tipo) {
            case CONSEGUIR:
                return r.consumir ? Math.min(r.cant, Obj.contar(p.getInventory(), r.objs)) : contador(clase, p, i);
            case LOGRO: {
                int n = 0;
                for (String o : r.objs) {
                    NamespacedKey k = NamespacedKey.fromString(o.substring(4));
                    Advancement a = k == null ? null : Bukkit.getAdvancement(k);
                    if (a != null && p.getAdvancementProgress(a).isDone()) n++;
                }
                return Math.min(r.cant, n);
            }
            case DIMENSION: {
                if (contador(clase, p, i) >= 1) return 1;
                for (String o : r.objs) if (Obj.mundo(o, p.getWorld())) return 1;
                return 0;
            }
            default:
                return contador(clase, p, i);
        }
    }

    public static int meta(Req r) { return r.tipo == Req.Tipo.DIMENSION ? 1 : r.cant; }

    public boolean completa(String clase, Player p) {
        String id = activa(clase);
        if (id == null) return false;
        if (st.y().getBoolean("bypass." + clase + "." + p.getUniqueId(), false)) return true;
        List<Req> rs = reqs(id);
        if (rs.isEmpty()) return false;
        for (int i = 0; i < rs.size(); i++) if (progreso(clase, p, i, rs.get(i)) < meta(rs.get(i))) return false;
        return true;
    }

    public Estado estado(String clase, Player p) {
        if (activa(clase) == null) return Estado.NINGUNA;
        if (clase.equals(OP) && bloqueada(p)) return Estado.BLOQUEADA;
        if (reclamada(clase, p)) return Estado.RECLAMADA;
        return completa(clase, p) ? Estado.COMPLETADA : Estado.EN_CURSO;
    }

    public boolean bloqueada(Player p) { return activa(DIA) != null && !reclamada(DIA, p); }

    /** Puede sumar progreso en esa clase. */
    boolean cuenta(String clase, Player p) {
        if (activa(clase) == null || reclamada(clase, p)) return false;
        return !(clase.equals(OP) && bloqueada(p));
    }

    /** Mira si completo los objetivos (avisa una sola vez). */
    public void verificar(Player p) {
        for (String c : new String[]{DIA, OP}) {
            if (!cuenta(c, p) || en("completada", c, p)) continue;
            if (completa(c, p)) {
                agregar("completada", c, p);
                p.playSound(p.getLocation(), pl.getConfig().getString("misiones.sonido_meta", "tod4:meta_mision"), SoundCategory.MASTER, 1f, 1f);
                Style.ok(p, "¡Completaste los objetivos de la misión " + (c.equals(DIA) ? "diaria" : "opcional") + "! &7Usá &e/misiones &7para reclamar.");
            }
        }
    }

    public void completar(String clase, Player p) {
        st.set("bypass." + clase + "." + p.getUniqueId(), true);
        verificar(p);
    }

    /** Reclamar: entrega lo que hay que entregar, da la recompensa y lo anuncia. */
    public void reclamar(String clase, Player p) {
        String id = activa(clase);
        if (id == null) return;
        if (estado(clase, p) != Estado.COMPLETADA) { Style.err(p, "Todavía no completaste la misión."); return; }
        boolean bypass = st.y().getBoolean("bypass." + clase + "." + p.getUniqueId(), false);
        if (!bypass) for (Req r : reqs(id)) if (r.tipo == Req.Tipo.CONSEGUIR && r.consumir) Obj.sacar(p.getInventory(), r.objs, r.cant);
        agregar("reclamo", clase, p);
        for (ItemStack it : recompensas(id)) {
            Map<Integer, ItemStack> sobra = p.getInventory().addItem(it.clone());
            for (ItemStack s : sobra.values()) p.getWorld().dropItemNaturally(p.getLocation(), s);
        }
        boolean dia = clase.equals(DIA);
        p.playSound(p.getLocation(), pl.getConfig().getString(dia ? "misiones.sonido_completar_dia" : "misiones.sonido_completar_op",
                dia ? "tod4:completar_diaria" : "tod4:completar_opcional"), SoundCategory.MASTER, 1f, 1f);
        p.showTitle(Title.title(Txt.c(dia ? "&a&l¡MISIÓN COMPLETADA!" : "&b&l¡MISIÓN COMPLETADA!"), Txt.c("&fRecompensa entregada en tu inventario."),
                Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1900), java.time.Duration.ofMillis(500))));
        Style.broadcast(dia ? "&a✔ &a&l" + p.getName() + " &7ha completado la &amisión diaria&7."
                : "&b✔ &b&l" + p.getName() + " &7ha completado la &bmisión opcional&7.");
    }

    // ================================================================= para el tab / sidebar

    /** Simbolo del tab: ✔ azul (opcional reclamada) > ✔ verde (diaria reclamada) > ✖ rojo (diaria sin hacer) > - gris. */
    public String simboloTab(Player p) {
        if (activa(OP) != null && reclamada(OP, p)) return "&b✔";
        if (activa(DIA) != null) return reclamada(DIA, p) ? "&a✔" : "&c✖";
        return "&7-";
    }

    public static String texto(Estado e) {
        switch (e) {
            case BLOQUEADA: return "&c🔒 Bloqueada";
            case EN_CURSO: return "&7En curso";
            case COMPLETADA: return "&6Completada";
            case RECLAMADA: return "&e✔ Reclamada ✔";
            default: return "";
        }
    }

    // usado por el seguimiento
    Map<String, List<Req>> activas() {
        Map<String, List<Req>> m = new HashMap<>();
        for (String c : new String[]{DIA, OP}) {
            String id = activa(c);
            if (id != null) m.put(c, reqs(id));
        }
        return m;
    }

    UUID u(Player p) { return p.getUniqueId(); }
}
