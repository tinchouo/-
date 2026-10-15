package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;
import ar.tod4.util.Obj;

/**
 * Detecta el progreso de las misiones.
 *  Conseguir (tenerlo): se mira el inventario en vivo.
 *  Fabricar / Obtener: solo para quien saca el resultado de mesa de crafteo, hornos, aldeanos, herreria, cortapiedras,
 *                      telar, cartografia, afiladora o yunque; o lo pesca / llena un balde. Se mide lo que de verdad
 *                      llego al inventario, asi el shift-click y las cantidades cuentan bien.
 *  Picar: cuando el jugador rompe el bloque (opcional: los puestos por jugadores no cuentan). Funciona con Oraxen.
 *  Matar: cuando el jugador mata al mob (vanilla, variante o MythicMobs).
 *  Logro / Ir a: se miran en vivo y al cambiar de mundo.
 */
final class Seguimiento implements Listener {

    private final TOD4 pl;
    private final Misiones ms;
    private final NamespacedKey PUESTOS;

    private static final Set<InventoryType> MESAS = EnumSet.of(InventoryType.WORKBENCH, InventoryType.CRAFTING, InventoryType.SMITHING,
            InventoryType.STONECUTTER, InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.GRINDSTONE, InventoryType.ANVIL,
            InventoryType.MERCHANT, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER);

    Seguimiento(TOD4 pl, Misiones ms) {
        this.pl = pl;
        this.ms = ms;
        this.PUESTOS = new NamespacedKey(pl, "puestos");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    /** Una meta de un objetivo de una mision activa, para un jugador que puede sumar. */
    private record Ref(String clase, Objetivo o, Objetivo.Meta m) {}

    private List<Ref> metas(Player p, Objetivo.Tipo tipo) {
        List<Ref> out = new ArrayList<>();
        for (Map.Entry<String, List<Objetivo>> e : ms.activas().entrySet()) {
            if (!ms.cuenta(e.getKey(), p)) continue;
            for (Objetivo o : e.getValue()) {
                if (o.tipo != tipo) continue;
                for (Objetivo.Meta m : o.metas()) out.add(new Ref(e.getKey(), o, m));
            }
        }
        return out;
    }

    // ================================================================= Fabricar / Obtener

    private void obtenido(Player p, ItemStack it, int cant) {
        if (cant <= 0 || it == null) return;
        boolean algo = false;
        for (Ref r : metas(p, Objetivo.Tipo.FABRICAR)) {
            if (Obj.item(r.m.objs, it)) { ms.sumar(r.clase, p, r.o, r.m, cant); algo = true; }
        }
        if (algo) ms.verificar(p);
    }

    private int cuantos(Player p, List<String> objs) {
        int n = Obj.contar(p.getInventory(), objs);
        ItemStack cur = p.getItemOnCursor();
        if (Obj.item(objs, cur)) n += cur.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void resultado(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (e.getSlotType() != InventoryType.SlotType.RESULT || !MESAS.contains(top.getType())) return;
        List<Ref> refs = metas(p, Objetivo.Tipo.FABRICAR);
        if (refs.isEmpty()) return;
        int[] antes = new int[refs.size()];
        for (int i = 0; i < refs.size(); i++) antes[i] = cuantos(p, refs.get(i).m.objs);
        // un tick despues: lo que de verdad llego al inventario / cursor
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!p.isOnline()) return;
            boolean algo = false;
            for (int i = 0; i < refs.size(); i++) {
                int d = cuantos(p, refs.get(i).m.objs) - antes[i];
                if (d > 0) { Ref r = refs.get(i); ms.sumar(r.clase, p, r.o, r.m, d); algo = true; }
            }
            if (algo) ms.verificar(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pesca(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item it)
            obtenido(e.getPlayer(), it.getItemStack(), it.getItemStack().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void balde(PlayerBucketFillEvent e) {
        if (e.getItemStack() != null) obtenido(e.getPlayer(), e.getItemStack(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void baldeMob(PlayerBucketEntityEvent e) {
        obtenido(e.getPlayer(), e.getEntityBucket(), 1);
    }

    // ================================================================= Conseguir (tenerlo): en vivo, se revisa enseguida

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void agarra(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) ms.verificar(p); });
    }

    @EventHandler
    public void cierra(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) ms.verificar(p); });
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
            for (List<Objetivo> os : ms.activas().values()) for (Objetivo o : os)
                if (o.tipo == Objetivo.Tipo.PICAR) for (String x : o.entradas.keySet()) if (Obj.bloque(x, b.getType(), null)) vigilado = true;
        }
        if (vigilado) setPuesto(b, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void rompe(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        List<Ref> refs = metas(p, Objetivo.Tipo.PICAR);
        boolean fuePuesto = puesto(b);
        if (fuePuesto) setPuesto(b, false);
        if (refs.isEmpty()) return;
        Material m = b.getType();
        String ox = Ext.oraxenBlock(b);
        // un tick despues: si el bloque de verdad se rompio, cuenta
        Bukkit.getScheduler().runTask(pl, () -> {
            if (ox == null && b.getType() == m) return;
            if (ox != null && ox.equals(Ext.oraxenBlock(b))) return;
            boolean algo = false;
            for (Ref r : refs) {
                if (fuePuesto && r.o.sinPuestos) continue;
                for (String x : r.m.objs) {
                    if (Obj.bloque(x, m, ox)) { ms.sumar(r.clase, p, r.o, r.m, 1); algo = true; break; }
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
        List<Ref> refs = metas(p, Objetivo.Tipo.MATAR);
        if (refs.isEmpty()) return;
        String mm = Ext.mythic(e.getEntity());
        boolean algo = false;
        for (Ref r : refs) {
            for (String x : r.m.objs) {
                if (Obj.mob(x, e.getEntity(), mm)) { ms.sumar(r.clase, p, r.o, r.m, 1); algo = true; break; }
            }
        }
        if (algo) ms.verificar(p);
    }

    // ================================================================= Ir a / Logro

    private void mundo(Player p) {
        for (Ref r : metas(p, Objetivo.Tipo.DIMENSION))
            for (String x : r.m.objs) if (Obj.mundo(x, p.getWorld())) ms.visitar(r.clase, p, r.o, x);
        ms.verificar(p);
    }

    @EventHandler
    public void cambiaMundo(PlayerChangedWorldEvent e) { mundo(e.getPlayer()); }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) mundo(p); }, 20L);
    }

    @EventHandler
    public void logro(PlayerAdvancementDoneEvent e) { ms.verificar(e.getPlayer()); }
}
