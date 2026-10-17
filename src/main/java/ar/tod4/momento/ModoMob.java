package ar.tod4.momento;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Items;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Selector de mobs para los Momentos Truco (como el de las misiones): se guarda tu inventario, te da una vara y con click
 * derecho a un mob se elige el mob exacto (su tipo, o su MythicMob; nunca una variante: un Enderman con un bloque es solo "Enderman").
 * Para salir: click derecho con la flecha (o al elegir, si se pide un solo mob).
 */
final class ModoMob implements Listener {

    private record Sesion(Consumer<String> alElegir, boolean varios, Runnable alSalir) {}

    private final TOD4 pl;
    private final Momentos mo;
    private final NamespacedKey HERRAMIENTA;
    private final Map<UUID, Sesion> sesiones = new HashMap<>();

    ModoMob(TOD4 pl, Momentos mo) {
        this.pl = pl;
        this.mo = mo;
        this.HERRAMIENTA = new NamespacedKey(pl, "momento_modo_mob");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private ItemStack herramienta(Material m, String nombre, String tipo, String... lore) {
        ItemStack it = Items.of(m, nombre, lore);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(HERRAMIENTA, PersistentDataType.STRING, tipo);
        it.setItemMeta(meta);
        return it;
    }

    private String tipo(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(HERRAMIENTA, PersistentDataType.STRING);
    }

    /** varios = true: se pueden elegir varios mobs y se sale con la flecha; false: sale solo al elegir uno. */
    void entrar(Player p, boolean varios, Consumer<String> alElegir, Runnable alSalir) {
        if (sesiones.containsKey(p.getUniqueId())) return;
        List<ItemStack> inv = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) inv.add(it == null ? null : it.clone());
        mo.store().set("modo_mob." + p.getUniqueId(), inv);   // tambien en el archivo, por si se cae el server
        mo.store().save();
        sesiones.put(p.getUniqueId(), new Sesion(alElegir, varios, alSalir));
        p.getInventory().clear();
        p.getInventory().setItem(0, herramienta(Material.BLAZE_ROD, "&c&lSelector de Mobs", "vara",
                "&7Click derecho a un mob para elegirlo.", "&7Sirve con MythicMobs."));
        p.getInventory().setItem(8, herramienta(Material.ARROW, "&a&lVolver", "salir", "&e▶ Click derecho para salir"));
        p.getInventory().setHeldItemSlot(0);
        Style.ok(p, "Selector de mobs: &fclick derecho a un mob &acon la vara." + (varios ? " Para terminar, click derecho con la flecha." : ""));
    }

    private void salir(Player p, boolean avisar) {
        Sesion s = sesiones.remove(p.getUniqueId());
        restaurar(p);
        if (avisar && s != null) Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) s.alSalir.run(); });
    }

    private void restaurar(Player p) {
        List<?> l = mo.store().y().getList("modo_mob." + p.getUniqueId());
        if (l == null) return;
        ItemStack[] c = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < l.size() && i < c.length; i++) if (l.get(i) instanceof ItemStack it) c[i] = it;
        p.getInventory().setContents(c);
        mo.store().set("modo_mob." + p.getUniqueId(), null);
        mo.store().save();
    }

    void cerrarTodo() {
        for (UUID u : new ArrayList<>(sesiones.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) salir(p, false);
        }
    }

    /** "mm:Id" -> "mythic:Id"; "ent:TIPO" -> "tipo" (para el spawn de un paso). */
    static String paraSpawn(String obj) {
        if (obj.startsWith("mm:")) return "mythic:" + obj.substring(3);
        if (obj.startsWith("ent:")) return obj.substring(4).split(":")[0].toLowerCase();
        return obj;
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler(priority = EventPriority.LOWEST)
    public void clickMob(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Sesion s = sesiones.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || !"vara".equals(tipo(p.getInventory().getItemInMainHand()))) return;
        Entity en = e.getRightClicked();
        if (en instanceof Player) { Style.err(p, "Eso es un jugador."); return; }
        String obj = Obj.deMob(en, false);   // el mob, sin variantes
        s.alElegir.accept(obj);
        Style.ok(p, "Elegido: &f" + Txt.plain(Obj.nombre(obj)));
        if (!s.varios) salir(p, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void usa(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!sesiones.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if ("salir".equals(tipo(p.getInventory().getItemInMainHand()))) salir(p, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void pega(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && sesiones.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void tira(PlayerDropItemEvent e) {
        if (sesiones.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void mueve(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && sesiones.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void comando(PlayerCommandPreprocessEvent e) {
        if (!sesiones.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        Style.err(e.getPlayer(), "Estás en el selector de mobs: sal con la flecha (click derecho).");
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        if (sesiones.remove(e.getPlayer().getUniqueId()) != null) restaurar(e.getPlayer());
    }

    /** Si el server se cayo con el selector abierto, al entrar recupera su inventario. */
    @EventHandler
    public void entra(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!sesiones.containsKey(p.getUniqueId())) restaurar(p);
    }
}
