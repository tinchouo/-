package ar.tod4.aether;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.aether.util.Ext;
import ar.tod4.aether.util.Style;
import ar.tod4.aether.util.Txt;

/**
 * Mesa de crafteo 6x6 (plugins/TOD4Aether/mesa6x6.yml). Es una mesa de crafteo marcada: al ponerla queda guardada y
 * con clic derecho abre una grilla de 6x6. Las recetas van en mesa6x6.yml (forma de 6 renglones y que item es cada letra).
 * Se consigue con /tod4:aether_mesa dar [jugador]. Al romperla suelta la misma mesa.
 */
final class Mesa6 implements Listener {

    private static final NamespacedKey MARCA = new NamespacedKey("tod4", "mesa6x6");
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");
    private static final int RES = 25;           // casillero del resultado
    private static final int FLECHA = 24;

    private final TOD4Aether pl;
    private YamlConfiguration y, datos;
    private File f, fd;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        String receta;
        @Override public Inventory getInventory() { return inv; }
    }

    Mesa6(TOD4Aether pl) {
        this.pl = pl;
        recargar();
        fd = new File(pl.getDataFolder(), "mesas6x6.yml");
        datos = YamlConfiguration.loadConfiguration(fd);
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    void recargar() {
        f = new File(pl.getDataFolder(), "mesa6x6.yml");
        if (!f.exists()) pl.saveResource("mesa6x6.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
    }

    // ================================================================= la mesa (bloque)

    ItemStack item() {
        ItemStack it = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta m = it.getItemMeta();
        m.itemName(Txt.c(y.getString("mesa.nombre", "&dMesa de crafteo 6x6")));
        m.lore(Txt.lines(y.getStringList("mesa.lore")));
        String modelo = y.getString("mesa.modelo", "");
        if (!modelo.isEmpty()) m.setItemModel(NamespacedKey.fromString(modelo));
        m.getPersistentDataContainer().set(MARCA, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static boolean esItemMesa(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(MARCA, PersistentDataType.BYTE);
    }

    private static String clave(Location l) { return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }

    private boolean esMesa(Block b) {
        if (b.getType() != Material.CRAFTING_TABLE) return false;
        return datos.getStringList("mesas").contains(clave(b.getLocation()));
    }

    private void marcar(Block b, boolean si) {
        List<String> l = new ArrayList<>(datos.getStringList("mesas"));
        String k = clave(b.getLocation());
        if (si) { if (!l.contains(k)) l.add(k); } else l.remove(k);
        datos.set("mesas", l);
        try { datos.save(fd); } catch (IOException ex) { pl.getLogger().warning("No pude guardar mesas6x6.yml: " + ex.getMessage()); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pone(BlockPlaceEvent e) { if (esItemMesa(e.getItemInHand())) marcar(e.getBlockPlaced(), true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rompe(BlockBreakEvent e) {
        if (!esMesa(e.getBlock())) return;
        marcar(e.getBlock(), false);
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE)
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), item());
    }

    @EventHandler(ignoreCancelled = true)
    public void explota(EntityExplodeEvent e) { e.blockList().removeIf(this::esMesa); }

    @EventHandler(ignoreCancelled = true)
    public void explota2(BlockExplodeEvent e) { e.blockList().removeIf(this::esMesa); }

    @EventHandler(ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (esMesa(b)) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void piston2(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (esMesa(b)) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH)
    public void abre(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!esMesa(e.getClickedBlock()) || e.getPlayer().isSneaking() && e.getItem() != null) return;
        e.setCancelled(true);
        abrir(e.getPlayer());
    }

    // ================================================================= la grilla

    private static int slot(int fila, int col) { return fila * 9 + col; }

    void abrir(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, Txt.c(y.getString("mesa.titulo", "&8Mesa de crafteo 6x6")));
        ItemStack vidrio = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = vidrio.getItemMeta();
        m.setHideTooltip(true);
        vidrio.setItemMeta(m);
        for (int i = 0; i < 54; i++) if (i % 9 >= 6 && i != RES) h.inv.setItem(i, vidrio);
        ItemStack flecha = new ItemStack(Material.ARROW);
        ItemMeta fm = flecha.getItemMeta();
        fm.setHideTooltip(true);
        flecha.setItemMeta(fm);
        h.inv.setItem(FLECHA, flecha);
        p.openInventory(h.inv);
        p.playSound(p, Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.6f, 0.8f);
    }

    private static boolean esGrilla(int s) { return s >= 0 && s < 54 && s % 9 < 6; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        Player p = (Player) e.getWhoClicked();
        int raw = e.getRawSlot();
        boolean arriba = raw < 54;
        if (arriba && raw == RES) {
            e.setCancelled(true);
            sacar(p, h, e.isShiftClick());
            return;
        }
        if (arriba && !esGrilla(raw)) { e.setCancelled(true); return; }
        // shift desde tu inventario: va a la grilla (la hace el juego, pero no al resultado ni a los vidrios)
        if (!arriba && e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            for (int i = 0; i < 54 && it.getAmount() > 0; i++) {
                if (!esGrilla(i)) continue;
                ItemStack g = h.inv.getItem(i);
                if (g == null || g.getType().isAir()) { h.inv.setItem(i, it.clone()); it.setAmount(0); }
                else if (g.isSimilar(it) && g.getAmount() < g.getMaxStackSize()) {
                    int n = Math.min(it.getAmount(), g.getMaxStackSize() - g.getAmount());
                    g.setAmount(g.getAmount() + n);
                    it.setAmount(it.getAmount() - n);
                }
            }
            e.setCurrentItem(it.getAmount() <= 0 ? null : it);
        }
        if (e.getAction().name().contains("COLLECT")) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(pl, () -> actualizar(h));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void arrastra(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        for (int raw : e.getRawSlots()) if (raw < 54 && !esGrilla(raw)) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(pl, () -> actualizar(h));
    }

    @EventHandler
    public void cierra(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        Player p = (Player) e.getPlayer();
        for (int i = 0; i < 54; i++) {
            if (!esGrilla(i)) continue;
            ItemStack it = h.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            h.inv.setItem(i, null);
            for (ItemStack sob : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), sob);
        }
        h.inv.setItem(RES, null);
    }

    /** Se fija si la grilla arma alguna receta y muestra el resultado. */
    private void actualizar(Holder h) {
        h.receta = null;
        ItemStack res = null;
        ConfigurationSection rs = y.getConfigurationSection("recetas");
        if (rs != null) for (String id : rs.getKeys(false)) {
            if (coincide(h, rs.getConfigurationSection(id))) { h.receta = id; res = resultado(rs.getConfigurationSection(id)); break; }
        }
        h.inv.setItem(RES, res);
    }

    /** La forma tiene que estar igual (se puede correr dentro de la grilla) y cada casilla con su item. */
    private boolean coincide(Holder h, ConfigurationSection r) {
        if (r == null) return false;
        List<String> forma = r.getStringList("forma");
        if (forma.isEmpty()) return false;
        // caja de la forma
        int f0 = 99, f1 = -1, c0 = 99, c1 = -1;
        for (int i = 0; i < forma.size(); i++) for (int j = 0; j < forma.get(i).length(); j++)
            if (forma.get(i).charAt(j) != '.' && forma.get(i).charAt(j) != ' ') { f0 = Math.min(f0, i); f1 = Math.max(f1, i); c0 = Math.min(c0, j); c1 = Math.max(c1, j); }
        if (f1 < 0) return false;
        // caja de lo que hay en la grilla
        int g0 = 99, g1 = -1, k0 = 99, k1 = -1;
        for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) {
            ItemStack it = h.inv.getItem(slot(i, j));
            if (it != null && !it.getType().isAir()) { g0 = Math.min(g0, i); g1 = Math.max(g1, i); k0 = Math.min(k0, j); k1 = Math.max(k1, j); }
        }
        if (g1 < 0 || g1 - g0 != f1 - f0 || k1 - k0 != c1 - c0) return false;
        for (int i = f0; i <= f1; i++) for (int j = c0; j <= c1; j++) {
            char ch = j < forma.get(i).length() ? forma.get(i).charAt(j) : '.';
            ItemStack it = h.inv.getItem(slot(g0 + i - f0, k0 + j - c0));
            boolean vacio = it == null || it.getType().isAir();
            if (ch == '.' || ch == ' ') { if (!vacio) return false; continue; }
            if (vacio || !es(it, r.getString("ingredientes." + ch, ""))) return false;
        }
        return true;
    }

    /** minecraft:id (un item normal, no uno de Oraxen) u oraxen:id. */
    private static boolean es(ItemStack it, String id) {
        String ox = it.hasItemMeta() ? it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING) : null;
        if (id.startsWith("oraxen:")) return id.substring(7).equals(ox);
        if (ox != null) return false;
        Material m = Material.matchMaterial(id);
        return m != null && it.getType() == m;
    }

    private ItemStack resultado(ConfigurationSection r) {
        String b64 = r.getString("resultado_guardado");
        ItemStack it = null;
        if (b64 != null) it = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        else {
            String id = r.getString("resultado", "");
            if (id.startsWith("oraxen:")) it = Ext.oraxenItem(id.substring(7));
            else { Material m = Material.matchMaterial(id); if (m != null) it = new ItemStack(m); }
        }
        if (it != null) it.setAmount(Math.max(1, r.getInt("cantidad", 1)));
        return it;
    }

    /** Sacar el resultado: gasta uno de cada casilla. Con shift, todos los que alcancen. */
    private void sacar(Player p, Holder h, boolean todos) {
        actualizar(h);
        if (h.receta == null) return;
        int veces = 0;
        do {
            ItemStack res = h.inv.getItem(RES);
            if (res == null) break;
            if (todos) {
                if (!p.getInventory().addItem(res.clone()).isEmpty()) break;
            } else {
                ItemStack cur = p.getItemOnCursor();
                if (cur.getType().isAir()) p.setItemOnCursor(res.clone());
                else if (cur.isSimilar(res) && cur.getAmount() + res.getAmount() <= cur.getMaxStackSize()) cur.setAmount(cur.getAmount() + res.getAmount());
                else break;
            }
            for (int i = 0; i < 54; i++) {
                if (!esGrilla(i)) continue;
                ItemStack it = h.inv.getItem(i);
                if (it == null || it.getType().isAir()) continue;
                it.setAmount(it.getAmount() - 1);
                h.inv.setItem(i, it.getAmount() <= 0 ? null : it);
            }
            veces++;
            actualizar(h);
        } while (todos && h.receta != null && veces < 64);
        if (veces > 0) p.playSound(p, Sound.BLOCK_ANVIL_USE, 0.5f, 1.4f);
    }

    // ================================================================= comando

    /** /tod4:aether_mesa dar [jugador] | abrir | resultado <receta> */
    void comando(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "";
        switch (m) {
            case "dar" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player pp ? pp : null;
                if (t == null) { Style.err(s, "Jugador no encontrado."); return; }
                for (ItemStack sob : t.getInventory().addItem(item()).values()) t.getWorld().dropItem(t.getLocation(), sob);
                Style.ok(s, "Mesa de crafteo 6x6 entregada a &f" + t.getName() + "&a.");
            }
            case "abrir" -> { if (s instanceof Player p) abrir(p); }
            case "resultado" -> {
                if (!(s instanceof Player p) || a.length < 2) { Style.info(s, "Uso: &e/tod4:aether_mesa resultado <receta> &7con el item en la mano"); return; }
                if (!y.isConfigurationSection("recetas." + a[1])) { Style.err(s, "No existe la receta &f" + a[1] + "&c en mesa6x6.yml."); return; }
                ItemStack mano = p.getInventory().getItemInMainHand();
                if (mano.getType().isAir()) { Style.err(s, "Ponete el item en la mano."); return; }
                ItemStack uno = mano.clone();
                uno.setAmount(1);
                y.set("recetas." + a[1] + ".resultado_guardado", Base64.getEncoder().encodeToString(uno.serializeAsBytes()));
                try { y.save(f); } catch (IOException ex) { Style.err(s, "No pude guardar mesa6x6.yml."); return; }
                Style.ok(s, "El resultado de &f" + a[1] + "&a ahora es el item de tu mano.");
            }
            default -> Style.info(s, "Uso: &e/tod4:aether_mesa dar [jugador] &7| &eabrir &7| &eresultado <receta>&7. Recetas en &fmesa6x6.yml&7.");
        }
    }
}
