package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;

/**
 * Cambios de dificultad por dia. Cada cambio se prende/apaga desde el menu (/cambios) y tiene efecto al instante.
 * Un cambio puede tener un aviso vinculado: al activarlo se manda solo.
 */
public final class Cambios {

    public static final class Cambio {
        public final String id, nombre;
        public final int dia;
        public final String[] desc;
        public final boolean nota; // solo recordatorio (no se activa)
        Consumer<Boolean> alCambiar;

        Cambio(String id, int dia, String nombre, boolean nota, String... desc) {
            this.id = id; this.dia = dia; this.nombre = nombre; this.nota = nota; this.desc = desc;
        }
    }

    private final TOD4 pl;
    private final Store st;
    private final Map<String, Cambio> todos = new LinkedHashMap<>();

    public Cambios(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "cambios");
        // ---- Dia 1
        add(new Cambio("d1.hornos", 1, "Explosión de Hornos", false, "&7Los hornos prendidos pueden", "&7explotar de la nada."));
        add(new Cambio("d1.manzanas", 1, "Bloqueo de Manzanas", false, "&7Las hojas no sueltan manzanas."));
        add(new Cambio("d1.aldeanos", 1, "Bloqueo de Aldeanos", false, "&7Los aldeanos naturales desaparecen.", "&7Solo quedan los de huevo/comando."));
        add(new Cambio("d1.totems", 1, "Bloqueo de Tótems", false, "&7Los invocadores no sueltan tótem."));
        add(new Cambio("d1.nether", 1, "Calor del Nether", false, "&7En el Nether te quemas sin", "&7Resistencia al fuego y no hay agua."));
        // ---- Dia 2
        add(new Cambio("d2.reproduccion", 2, "Bloqueo de Reproducción", false, "&7Los aldeanos no se reproducen", "&7(no pueden juntar comida)."));
        add(new Cambio("d2.mending", 2, "Recordatorio: Mending", true, "&7Esto lo hace el datapack", "&7de ítems prohibidos."));
        add(new Cambio("d2.saplings", 2, "Eliminar Saplings", false, "&7Las hojas no sueltan brotes."));
        add(new Cambio("d2.hornos", 2, "Hornos Especiales", false, "&7Alto horno: arena, piedra, basalto,", "&7arenisca, terracotas -> esmaltadas.", "&7Ahumador: cactus, pepino de mar,", "&7chorus, troncos (carbón), esponja."));
        // ---- Dia 3
        add(new Cambio("d3.hornos", 3, "Hornos iguales al horno", false, "&7El alto horno y el ahumador cocinan", "&7todo lo que cocina el horno normal."));
        add(new Cambio("d3.leche", 3, "Sin leche", false, "&7No se puede sacar ni tomar leche", "&7(no se pueden borrar efectos)."));
        add(new Cambio("d3.lava", 3, "Lava + Res. al fuego", false, "&7La resistencia al fuego no te salva", "&7de la lava: quema como siempre."));
        // ---- Dia 4
        add(new Cambio("d4.mesa", 4, "Mesa de encantamientos", false, "&7Con 30 estanterías: botones 15/30/40", "&7y encantamientos mejores."));
        add(new Cambio("d4.agua", 4, "Ahogo rápido", false, "&7Te ahogas 5 veces más rápido."));
        add(new Cambio("d4.puertas", 4, "Botones y puertas", false, "&7Usarlos te mata (el tótem te salva)."));
        add(new Cambio("d4.crianza", 4, "Sin crianza", false, "&7Los animales no se reproducen."));
        add(new Cambio("d4.perla", 4, "Perlas con más daño", false, "&7Cada perla te saca 4 corazones."));

        new Mecanicas(pl, this);
        // aplica lo que ya estaba prendido (recetas, mesa)
        for (Cambio c : todos.values()) if (c.alCambiar != null && on(c.id)) c.alCambiar.accept(true);
    }

    private void add(Cambio c) { todos.put(c.id, c); }

    void hook(String id, Consumer<Boolean> f) { Cambio c = todos.get(id); if (c != null) c.alCambiar = f; }

    public boolean on(String id) { return st.y().getBoolean("activos." + id, false); }

    public void set(String id, boolean v) {
        Cambio c = todos.get(id);
        if (c == null || c.nota) return;
        st.set("activos." + id, v);
        if (c.alCambiar != null) c.alCambiar.accept(v);
        if (v) {
            String av = st.y().getString("avisos." + id);
            if (av != null && pl.avisos().existe(av)) pl.avisos().enviarATodos(av);
        }
    }

    /** Para el importador: cambia el estado sin mandar el aviso vinculado. */
    public void importar(String id, boolean v) {
        Cambio c = todos.get(id);
        if (c == null || c.nota) return;
        st.set("activos." + id, v);
        if (c.alCambiar != null) c.alCambiar.accept(v);
    }

    // ------------------------------------------------------------------ menus

    private static final int DIAS = 16;

    public void abrirDias(Player p) {
        Menu m = new Menu(3, "&8Cambios de Dificultad");
        for (int d = 1; d <= DIAS; d++) {
            final int dia = d;
            List<Cambio> lista = delDia(d);
            int activos = 0;
            for (Cambio c : lista) if (!c.nota && on(c.id)) activos++;
            int total = (int) lista.stream().filter(c -> !c.nota).count();
            if (lista.isEmpty()) {
                m.set(d - 1, Items.of(Material.GRAY_DYE, "&8Día " + d, "&8Próximamente..."));
            } else {
                Material mat = activos == 0 ? Material.RED_DYE : (activos == total ? Material.LIME_DYE : Material.ORANGE_DYE);
                m.set(d - 1, Items.of(mat, "&6&lDía " + d, "", "&7Cambios activos: &f" + activos + "&7/&f" + total, "", "&e▶ Click para abrir"),
                        e -> { Style.click(p); abrirDia(p, dia); });
            }
        }
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    public void abrirDia(Player p, int dia) {
        List<Cambio> lista = delDia(dia);
        Menu m = new Menu(3, "&8Menú de Cambios - Día " + dia);
        int[] slots = slotsCentrados(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            Cambio c = lista.get(i);
            m.set(slots[i], icono(c), e -> {
                if (c.nota) return;
                if (e.isShiftClick()) { elegirAviso(p, c, dia); return; }
                set(c.id, !on(c.id));
                p.playSound(p.getLocation(), on(c.id) ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_STONE_BUTTON_CLICK_OFF, 0.6f, 1.4f);
                abrirDia(p, dia);
            });
        }
        m.set(18, Items.of(Material.ARROW, "&7◀ Volver"), e -> { Style.click(p); abrirDias(p); });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private org.bukkit.inventory.ItemStack icono(Cambio c) {
        List<String> lore = new ArrayList<>();
        for (String s : c.desc) lore.add(s);
        lore.add("");
        if (c.nota) return Items.of(Material.PAPER, "&e&l" + c.nombre, lore);
        boolean on = on(c.id);
        lore.add("&7Estado: " + (on ? "&aACTIVADO" : "&cDESACTIVADO"));
        String av = st.y().getString("avisos." + c.id);
        lore.add("&7Aviso al activar: " + (av != null ? "&f" + av : "&8ninguno"));
        lore.add("");
        lore.add(on ? "&eClick para desactivar" : "&eClick para activar");
        lore.add("&8Shift+click: vincular aviso");
        return Items.of(on ? Material.LIME_DYE : Material.RED_DYE, (on ? "&a" : "&c") + c.nombre, lore);
    }

    private void elegirAviso(Player p, Cambio c, int dia) {
        pl.avisos().elegir(p, "&8Aviso para: " + c.nombre, id -> {
            st.set("avisos." + c.id, id);
            if (id == null) Style.ok(p, "Sin aviso para &f" + c.nombre + "&a.");
            else Style.ok(p, "Aviso &f" + id + " &avinculado a &f" + c.nombre + "&a.");
            abrirDia(p, dia);
        });
    }

    private List<Cambio> delDia(int d) {
        List<Cambio> out = new ArrayList<>();
        for (Cambio c : todos.values()) if (c.dia == d) out.add(c);
        return out;
    }

    private static int[] slotsCentrados(int n) {
        int[][] t = {{}, {13}, {12, 14}, {11, 13, 15}, {10, 12, 14, 16}, {9, 11, 13, 15, 17}, {10, 11, 12, 14, 15, 16},
                {10, 11, 12, 13, 14, 15, 16}};
        if (n < t.length) return t[n];
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = 9 + i;
        return s;
    }
}
