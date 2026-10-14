package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;
import ar.tod4.util.Obj;

/**
 * Detecta el progreso de las misiones.
 *  Conseguir: agarrar del piso (no lo que tiro otro jugador), sacar de la mesa de crafteo / herreria / cortapiedras /
 *             telar / cartografia / afiladora / yunque / aldeano / hornos, sacar de un cofre que ningun jugador lleno,
 *             llenar baldes. Se mide la diferencia real del inventario, asi el shift-click y las cantidades cuentan bien.
 *  Picar: romper el bloque (los bloques puestos por jugadores no cuentan). Funciona con bloques de Oraxen.
 *  Matar: el jugador mata al mob (vanilla o MythicMobs).
 *  Logro / Dimension: se miran en vivo y al cambiar de mundo.
 */
final class Seguimiento implements Listener {

    private final TOD4 pl;
    private final Misiones ms;
    private final NamespacedKey TIRADO, TOCADO, PUESTOS;

    private static final Set<InventoryType> MESAS = EnumSet.of(InventoryType.WORKBENCH, InventoryType.CRAFTING, InventoryType.SMITHING,
            InventoryType.STONECUTTER, InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.GRINDSTONE, InventoryType.ANVIL,
            InventoryType.MERCHANT, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER);

    private static final Set<InventoryType> COFRES = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.DISPENSER, InventoryType.DROPPER, InventoryType.HOPPER);

    Seguimiento(TOD4 pl, Misiones ms) {
        this.pl = pl;
        this.ms = ms;
        this.TIRADO = new NamespacedKey(pl, "tirado");
        this.TOCADO = new NamespacedKey(pl, "cofre_tocado");
        this.PUESTOS = new NamespacedKey(pl, "puestos");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    // ================================================================= Conseguir

    /** Suma lo conseguido a los requisitos "Conseguir" (que no se entregan) que coincidan. */
    private void conseguido(Player p, ItemStack it, int cant) {
        if (cant <= 0 || it == null) return;
        Map<String, List<Req>> act = ms.activas();
        boolean algo = false;
        for (Map.Entry<String, List<Req>> e : act.entrySet()) {
            if (!ms.cuenta(e.getKey(), p)) continue;
            List<Req> rs = e.getValue();
            for (int i = 0; i < rs.size(); i++) {
                Req r = rs.get(i);
                if (r.tipo == Req.Tipo.CONSEGUIR && !r.consumir && Obj.item(r.objs, it)) {
                    ms.sumarContador(e.getKey(), p, i, cant, r.cant);
                    algo = true;
                }
            }
        }
        if (algo) ms.verificar(p);
    }

    /** Objetivos "Conseguir" activos para este jugador (para medir el inventario antes/despues). */
    private List<List<String>> objetivosConseguir(Player p) {
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, List<Req>> e : ms.activas().entrySet()) {
            if (!ms.cuenta(e.getKey(), p)) continue;
            for (Req r : e.getValue()) if (r.tipo == Req.Tipo.CONSEGUIR && !r.consumir) out.add(r.objs);
        }
        return out;
    }

    private int cuantos(Player p, List<String> objs) {
        int n = Obj.contar(p.getInventory(), objs);
        ItemStack cur = p.getItemOnCursor();
        if (Obj.item(objs, cur)) n += cur.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void agarra(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        if (item.getPersistentDataContainer().has(TIRADO, PersistentDataType.BYTE)) return;
        ItemStack it = item.getItemStack();
        conseguido(p, it, it.getAmount() - e.getRemaining());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tira(PlayerDropItemEvent e) {
        e.getItemDrop().getPersistentDataContainer().set(TIRADO, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void balde(PlayerBucketFillEvent e) {
        if (e.getItemStack() != null) conseguido(e.getPlayer(), e.getItemStack(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void baldeMob(PlayerBucketEntityEvent e) {
        conseguido(e.getPlayer(), e.getEntityBucket(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        boolean enTop = clicked.equals(top);
        // poner cosas en un cofre lo marca como "tocado" (sacar de ahi ya no cuenta)
        if (COFRES.contains(top.getType())) {
            boolean pone = (enTop && !e.getCursor().getType().isAir()
                    && e.getAction() != InventoryAction.PICKUP_ALL && e.getAction() != InventoryAction.PICKUP_HALF)
                    || (!enTop && e.isShiftClick())
                    || (enTop && e.getAction() == InventoryAction.HOTBAR_SWAP);
            if (pone) marcarTocado(top);
        }
        if (!enTop) return;
        boolean fuente = (e.getSlotType() == InventoryType.SlotType.RESULT && MESAS.contains(top.getType()))
                || (COFRES.contains(top.getType()) && !tocado(top));
        if (!fuente) return;
        List<List<String>> objs = objetivosConseguir(p);
        if (objs.isEmpty()) return;
        int[] antes = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) antes[i] = cuantos(p, objs.get(i));
        Bukkit.getScheduler().runTask(pl, () -> {
            for (int i = 0; i < objs.size(); i++) {
                int d = cuantos(p, objs.get(i)) - antes[i];
                if (d <= 0) continue;
                // el primer item del inventario que coincide sirve para identificar los requisitos
                ItemStack ref = null;
                for (ItemStack it : p.getInventory().getContents()) if (Obj.item(objs.get(i), it)) { ref = it; break; }
                if (ref == null && Obj.item(objs.get(i), p.getItemOnCursor())) ref = p.getItemOnCursor();
                if (ref != null) sumarLista(p, objs.get(i), d);
            }
        });
    }

    /** Suma a los requisitos cuya lista de objetivos es exactamente esta. */
    private void sumarLista(Player p, List<String> lista, int cant) {
        boolean algo = false;
        for (Map.Entry<String, List<Req>> e : ms.activas().entrySet()) {
            if (!ms.cuenta(e.getKey(), p)) continue;
            List<Req> rs = e.getValue();
            for (int i = 0; i < rs.size(); i++) {
                Req r = rs.get(i);
                if (r.tipo == Req.Tipo.CONSEGUIR && !r.consumir && r.objs.equals(lista)) {
                    ms.sumarContador(e.getKey(), p, i, cant, r.cant);
                    algo = true;
                }
            }
        }
        if (algo) ms.verificar(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void arrastra(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!COFRES.contains(top.getType())) return;
        for (int raw : e.getRawSlots()) if (raw < top.getSize()) { marcarTocado(top); return; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tolva(InventoryMoveItemEvent e) {
        if (COFRES.contains(e.getDestination().getType())) marcarTocado(e.getDestination());
    }

    private PersistentDataContainer[] pdcs(Inventory inv, boolean[] esBloque, List<BlockState> estados) {
        InventoryHolder h = inv.getHolder();
        List<PersistentDataContainer> out = new ArrayList<>();
        if (h instanceof DoubleChest dc) {
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()})
                if (side instanceof TileState ts) { out.add(ts.getPersistentDataContainer()); estados.add(ts); }
            esBloque[0] = true;
        } else if (h instanceof TileState ts) {
            out.add(ts.getPersistentDataContainer());
            estados.add(ts);
            esBloque[0] = true;
        } else if (h instanceof Entity en) {
            out.add(en.getPersistentDataContainer());
        }
        return out.toArray(new PersistentDataContainer[0]);
    }

    private boolean tocado(Inventory inv) {
        List<BlockState> est = new ArrayList<>();
        for (PersistentDataContainer c : pdcs(inv, new boolean[1], est)) if (c.has(TOCADO, PersistentDataType.BYTE)) return true;
        return false;
    }

    private void marcarTocado(Inventory inv) {
        List<BlockState> est = new ArrayList<>();
        for (PersistentDataContainer c : pdcs(inv, new boolean[1], est)) c.set(TOCADO, PersistentDataType.BYTE, (byte) 1);
        for (BlockState s : est) s.update(false, false);
    }

    // ================================================================= Picar

    private int clave(Block b) { return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8); }

    private boolean puesto(Block b) {
        int[] a = b.getChunk().getPersistentDataContainer().get(PUESTOS, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return false;
        int k = clave(b);
        for (int x : a) if (x == k) return true;
        return false;
    }

    private void setPuesto(Block b, boolean v) {
        Chunk ch = b.getChunk();
        int[] a = ch.getPersistentDataContainer().get(PUESTOS, PersistentDataType.INTEGER_ARRAY);
        List<Integer> l = new ArrayList<>();
        if (a != null) for (int x : a) l.add(x);
        Integer k = clave(b);
        if (v) { if (!l.contains(k)) l.add(k); } else l.remove(k);
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        if (out.length == 0) ch.getPersistentDataContainer().remove(PUESTOS);
        else ch.getPersistentDataContainer().set(PUESTOS, PersistentDataType.INTEGER_ARRAY, out);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pone(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        String n = b.getType().name();
        // se marcan los bloques que suelen pedirse (menas, troncos) y los que pide alguna mision activa
        boolean vigilado = n.contains("_ORE") || n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM")
                || n.equals("ANCIENT_DEBRIS") || b.getType() == Material.NOTE_BLOCK;
        if (!vigilado) {
            for (List<Req> rs : ms.activas().values()) for (Req r : rs)
                if (r.tipo == Req.Tipo.PICAR) for (String o : r.objs) if (Obj.bloque(o, b.getType(), null)) vigilado = true;
        }
        if (vigilado) setPuesto(b, true);
    }

    private boolean hayPicar() {
        for (List<Req> rs : ms.activas().values()) for (Req r : rs) if (r.tipo == Req.Tipo.PICAR) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void rompe(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (!hayPicar()) return;
        boolean fuePuesto = puesto(b);
        if (fuePuesto) setPuesto(b, false);
        if (fuePuesto) return;
        Material m = b.getType();
        String ox = Ext.oraxenBlock(b);
        // un tick despues: si el bloque de verdad se rompio, cuenta
        Bukkit.getScheduler().runTask(pl, () -> {
            if (b.getType() == m && ox == null) return;
            if (ox != null && ox.equals(Ext.oraxenBlock(b))) return;
            boolean algo = false;
            for (Map.Entry<String, List<Req>> en : ms.activas().entrySet()) {
                if (!ms.cuenta(en.getKey(), p)) continue;
                List<Req> rs = en.getValue();
                for (int i = 0; i < rs.size(); i++) {
                    Req r = rs.get(i);
                    if (r.tipo != Req.Tipo.PICAR) continue;
                    for (String o : r.objs) if (Obj.bloque(o, m, ox)) { ms.sumarContador(en.getKey(), p, i, 1, r.cant); algo = true; break; }
                }
            }
            if (algo) ms.verificar(p);
        });
    }

    // ================================================================= Matar

    @EventHandler(priority = EventPriority.MONITOR)
    public void mata(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || e.getEntity() instanceof Player) return;
        String mm = Ext.mythic(e.getEntity());
        boolean algo = false;
        for (Map.Entry<String, List<Req>> en : ms.activas().entrySet()) {
            if (!ms.cuenta(en.getKey(), p)) continue;
            List<Req> rs = en.getValue();
            for (int i = 0; i < rs.size(); i++) {
                Req r = rs.get(i);
                if (r.tipo != Req.Tipo.MATAR) continue;
                for (String o : r.objs) if (Obj.mob(o, e.getEntity(), mm)) { ms.sumarContador(en.getKey(), p, i, 1, r.cant); algo = true; break; }
            }
        }
        if (algo) ms.verificar(p);
    }

    // ================================================================= Dimension / Logro

    private void mundo(Player p) {
        for (Map.Entry<String, List<Req>> en : ms.activas().entrySet()) {
            if (!ms.cuenta(en.getKey(), p)) continue;
            List<Req> rs = en.getValue();
            for (int i = 0; i < rs.size(); i++) {
                Req r = rs.get(i);
                if (r.tipo != Req.Tipo.DIMENSION) continue;
                for (String o : r.objs) if (Obj.mundo(o, p.getWorld())) { ms.sumarContador(en.getKey(), p, i, 1, 1); break; }
            }
        }
        ms.verificar(p);
    }

    @EventHandler
    public void cambiaMundo(PlayerChangedWorldEvent e) { mundo(e.getPlayer()); }

    @EventHandler
    public void entra(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(pl, () -> mundo(e.getPlayer()), 20L); }

    @EventHandler
    public void logro(PlayerAdvancementDoneEvent e) { ms.verificar(e.getPlayer()); }

    @SuppressWarnings("unused")
    private static Map<String, Integer> vacio() { return new HashMap<>(); }
}
