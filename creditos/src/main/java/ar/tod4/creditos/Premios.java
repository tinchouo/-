package ar.tod4.creditos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/**
 * /premios                       menu: cada premio -> sus nominados -> click en el ganador (se guarda solo)
 * /premios entregar <premio> [probar]   la entrega en pantalla (titulo, nominados, redoble, ganador). "probar" = solo vos
 * /premios ver                   los ganadores elegidos
 */
final class Premios implements Listener {

    private static final TextColor ORO = TextColor.color(0xFFBE3C);
    private final TOD4Creditos pl;

    Premios(TOD4Creditos pl) {
        this.pl = pl;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    /** id -> {titulo, nominados} en el orden del archivo. */
    Map<String, Object[]> lista() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        ConfigurationSection s = pl.premios().getConfigurationSection("premios");
        if (s != null) for (String k : s.getKeys(false))
            m.put(k, new Object[]{s.getString(k + ".titulo", k), s.getStringList(k + ".nominados")});
        return m;
    }

    String ganador(String id) { return pl.estado().getString("ganadores." + id); }

    Map<String, String> ganadores() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : lista().keySet()) { String g = ganador(k); if (g != null) m.put(k, g); }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final String premio;   // null = la lista de premios
        Inventory inv;
        Menu(String premio) { this.premio = premio; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack item(Material mat, Component nombre, List<Component> lore, boolean brilla) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(nombre.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        if (brilla) m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    void abrir(Player p) {
        Map<String, Object[]> l = lista();
        Menu h = new Menu(null);
        Inventory inv = Bukkit.createInventory(h, Math.max(9, Math.min(54, ((l.size() + 8) / 9) * 9)), Component.text("Premios: elegí el ganador"));
        h.inv = inv;
        int i = 0;
        for (Map.Entry<String, Object[]> e : l.entrySet()) {
            String g = ganador(e.getKey());
            inv.setItem(i++, item(g != null ? Material.GOLD_INGOT : Material.PAPER,
                    Component.text((String) e.getValue()[0], ORO),
                    List.of(g != null ? Component.text("Ganador: ", NamedTextColor.GRAY).append(Component.text(g, NamedTextColor.WHITE))
                                    : Component.text("Sin elegir", NamedTextColor.DARK_GRAY),
                            Component.text("Click: elegir el ganador", NamedTextColor.YELLOW),
                            Component.text("Click derecho: borrar", NamedTextColor.DARK_GRAY)), g != null));
        }
        p.openInventory(inv);
    }

    @SuppressWarnings("unchecked")
    void abrirPremio(Player p, String id) {
        Object[] d = lista().get(id);
        if (d == null) return;
        List<String> noms = (List<String>) d[1];
        Menu h = new Menu(id);
        Inventory inv = Bukkit.createInventory(h, Math.max(18, Math.min(54, ((noms.size() + 1 + 8) / 9) * 9)), Component.text((String) d[0]));
        h.inv = inv;
        String g = ganador(id);
        for (int i = 0; i < noms.size() && i < inv.getSize() - 1; i++) {
            boolean es = noms.get(i).equals(g);
            inv.setItem(i, item(es ? Material.GOLD_INGOT : Material.NAME_TAG, Component.text(noms.get(i), es ? ORO : TextColor.color(0xFFFFFF)),
                    List.of(es ? Component.text("★ Ganador", ORO) : Component.text("Click: es el ganador", NamedTextColor.YELLOW)), es));
        }
        inv.setItem(inv.getSize() - 1, item(Material.ARROW, Component.text("Volver", NamedTextColor.GRAY), List.of(), false));
        p.openInventory(inv);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (h.premio == null) {
            List<String> ids = new ArrayList<>(lista().keySet());
            if (s < 0 || s >= ids.size()) return;
            if (e.isRightClick()) {
                pl.estado().set("ganadores." + ids.get(s), null);
                pl.guardarEstado();
                p.playSound(p, Sound.UI_BUTTON_CLICK, 1f, 0.8f);
                abrir(p);
            } else {
                p.playSound(p, Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                abrirPremio(p, ids.get(s));
            }
            return;
        }
        if (s == e.getInventory().getSize() - 1) { abrir(p); return; }
        Object[] d = lista().get(h.premio);
        if (d == null) return;
        @SuppressWarnings("unchecked") List<String> noms = (List<String>) d[1];
        if (s < 0 || s >= noms.size()) return;
        pl.estado().set("ganadores." + h.premio, noms.get(s));
        pl.guardarEstado();
        p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        p.sendMessage(Component.text("✔ " + d[0] + ": ", NamedTextColor.GREEN).append(Component.text(noms.get(s), NamedTextColor.WHITE)));
        abrir(p);
    }

    @EventHandler
    public void arrastra(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ------------------------------------------------------------------------------------------------ entrega

    private static final TextColor NARANJA = TextColor.color(0xFFAA00), AMARILLO = TextColor.color(0xFFFF55);
    private org.bukkit.NamespacedKey kFuego;

    /**
     * La entrega: titulo grande con el premio (naranja) y abajo el ganador (amarillo). Si el ganador esta conectado,
     * le salen fuegos artificiales de donde este (en "probar", de donde estas vos). Los fuegos no lastiman.
     */
    void entregar(CommandSender s, String id, boolean prueba) {
        Object[] d = lista().get(id);
        if (d == null) { s.sendMessage(Component.text("No existe el premio \"" + id + "\". Mirá /premios ver", NamedTextColor.RED)); return; }
        String g = ganador(id);
        if (g == null) { s.sendMessage(Component.text("Todavía no elegiste el ganador de " + d[0] + " (/premios).", NamedTextColor.RED)); return; }
        List<Player> ven = new ArrayList<>();
        if (prueba) { if (s instanceof Player p) ven.add(p); else return; }
        else ven.addAll(Bukkit.getOnlinePlayers());
        String titulo = (String) d[0];
        Title ti = Title.title(Component.text(titulo, NARANJA).decoration(TextDecoration.BOLD, true), Component.text(g, AMARILLO),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(6000), Duration.ofMillis(1200)));
        for (Player p : ven) {
            p.showTitle(ti);
            p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
            p.sendMessage(Component.text("🏆 " + titulo + ": ", NARANJA).append(Component.text(g, AMARILLO).decoration(TextDecoration.BOLD, true)));
        }
        Player donde = prueba ? (s instanceof Player p ? p : null) : buscar(g);
        if (donde != null) {
            final Player q = donde;
            for (int i = 0; i < 6; i++) later(i * 8, () -> { if (q.isOnline()) fuego(q.getLocation()); });
        }
        if (s instanceof Player p && !ven.contains(p)) s.sendMessage(Component.text("Entregado.", NamedTextColor.GREEN));
    }

    /** El jugador conectado que mas se parece al nombre del ganador ("Misovia" -> Misovia_, "iGab" -> iGab_203y). */
    private static Player buscar(String nombre) {
        String n = limpio(nombre);
        if (n.isEmpty()) return null;
        Player exacto = Bukkit.getPlayerExact(nombre);
        if (exacto != null) return exacto;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String q = limpio(p.getName());
            if (q.equals(n) || q.startsWith(n) || (n.length() >= 4 && q.contains(n))) return p;
        }
        return null;
    }

    private static String limpio(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]", ""); }

    private void fuego(org.bukkit.Location l) {
        if (kFuego == null) kFuego = new org.bukkit.NamespacedKey(pl, "fuego_premio");
        java.util.Random r = new java.util.Random();
        org.bukkit.Location at = l.clone().add(r.nextDouble() * 4 - 2, 0.5, r.nextDouble() * 4 - 2);
        l.getWorld().spawn(at, org.bukkit.entity.Firework.class, f -> {
            org.bukkit.inventory.meta.FireworkMeta m = f.getFireworkMeta();
            m.addEffect(org.bukkit.FireworkEffect.builder()
                    .with(r.nextBoolean() ? org.bukkit.FireworkEffect.Type.BALL_LARGE : org.bukkit.FireworkEffect.Type.STAR)
                    .withColor(org.bukkit.Color.ORANGE, org.bukkit.Color.YELLOW, org.bukkit.Color.fromRGB(0xFFD700))
                    .withFade(org.bukkit.Color.WHITE).trail(true).flicker(true).build());
            m.setPower(1);
            f.setFireworkMeta(m);
            f.getPersistentDataContainer().set(kFuego, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Los fuegos de los premios no lastiman. */
    @EventHandler
    public void noLastima(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (kFuego != null && e.getDamager() instanceof org.bukkit.entity.Firework f
                && f.getPersistentDataContainer().has(kFuego, org.bukkit.persistence.PersistentDataType.BYTE)) e.setCancelled(true);
    }

    private void later(int ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(pl, r, Math.max(1, ticks)); }

    void ver(CommandSender s) {
        s.sendMessage(Component.text("Premios:", ORO));
        for (Map.Entry<String, Object[]> e : lista().entrySet()) {
            String g = ganador(e.getKey());
            s.sendMessage(Component.text(" " + e.getKey(), NamedTextColor.GRAY)
                    .append(Component.text(" (" + e.getValue()[0] + "): ", NamedTextColor.DARK_GRAY))
                    .append(g != null ? Component.text(g, NamedTextColor.WHITE) : Component.text("sin elegir", NamedTextColor.DARK_GRAY)));
        }
    }
}
