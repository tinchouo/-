package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import ar.tod4.util.ChatInput;
import ar.tod4.util.Items;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Modo Mobs (como en misiones.sk): se guarda tu inventario, te da una vara y con click derecho a un mob lo agregas.
 *  Slot 7: Mob ultra especifico (NO = cualquier panda / SI = panda marron, gato negro...).
 *  Slot 8: volver y guardar.
 * Mientras estas en el modo no podes tirar items, mover el inventario, pegar ni usar comandos.
 */
final class ModoMobs implements Listener {

    private final TOD4 pl;
    private final Misiones ms;
    private final MisionesMenu menu;
    private final NamespacedKey HERRAMIENTA;

    private record Sesion(String id, String uid) {}

    private final Map<UUID, Sesion> sesiones = new HashMap<>();
    private final Map<UUID, Boolean> especifico = new HashMap<>();

    ModoMobs(TOD4 pl, Misiones ms, MisionesMenu menu) {
        this.pl = pl;
        this.ms = ms;
        this.menu = menu;
        this.HERRAMIENTA = new NamespacedKey(pl, "modo_mobs");
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

    private ItemStack botonEspecifico(boolean si) {
        return herramienta(si ? Material.LIME_DYE : Material.RED_DYE, (si ? "&a" : "&c") + "&lMob Ultra Específico: &f&l" + (si ? "SÍ" : "NO"), "especifico",
                "&7Si es NO, agarra pandas, gatos, vacas base.", "&7Si es SÍ, detecta pandas marrones, lazy, etc.", "", "&e▶ Click derecho para cambiar");
    }

    void entrar(Player p, String id, String uid) {
        if (sesiones.containsKey(p.getUniqueId())) return;
        // el inventario se guarda tambien en el archivo, por si se cae el server
        List<ItemStack> inv = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) inv.add(it == null ? null : it.clone());
        ms.store().set("modo_mobs." + p.getUniqueId(), inv);
        ms.store().save();
        sesiones.put(p.getUniqueId(), new Sesion(id, uid));
        especifico.put(p.getUniqueId(), false);
        p.getInventory().clear();
        p.getInventory().setItem(0, herramienta(Material.BLAZE_ROD, "&c&lSelector de Mobs", "vara",
                "&7Click derecho a un mob para agregarlo.", "&7Sirve con MythicMobs."));
        p.getInventory().setItem(7, botonEspecifico(false));
        p.getInventory().setItem(8, herramienta(Material.ARROW, "&a&lVolver y Guardar", "salir", "&e▶ Click derecho para salir"));
        p.getInventory().setHeldItemSlot(0);
        Style.ok(p, "Modo Mobs: &fclick derecho a un mob &acon la vara. Para salir, click derecho con la flecha.");
    }

    private void salir(Player p, boolean reabrir) {
        Sesion s = sesiones.remove(p.getUniqueId());
        especifico.remove(p.getUniqueId());
        ChatInput.cancel(p);
        restaurar(p);
        if (reabrir && s != null) Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) menu.cofre(p, s.id, s.uid); });
    }

    private void restaurar(Player p) {
        List<?> l = ms.store().y().getList("modo_mobs." + p.getUniqueId());
        if (l == null) return;
        ItemStack[] c = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < l.size() && i < c.length; i++) if (l.get(i) instanceof ItemStack it) c[i] = it;
        p.getInventory().setContents(c);
        ms.store().set("modo_mobs." + p.getUniqueId(), null);
        ms.store().save();
    }

    /** Al apagar el server: todos recuperan su inventario. */
    void cerrarTodo() {
        for (UUID u : new ArrayList<>(sesiones.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) salir(p, false);
        }
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler(priority = EventPriority.LOWEST)
    public void clickMob(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Sesion s = sesiones.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || !"vara".equals(tipo(p.getInventory().getItemInMainHand()))) return;
        if (ChatInput.waiting(p)) { Style.err(p, "Primero escribí la cantidad en el chat (o &ccancel&c)."); return; }
        Entity en = e.getRightClicked();
        if (en instanceof Player) { Style.err(p, "Eso es un jugador."); return; }
        String obj = Obj.deMob(en, especifico.getOrDefault(p.getUniqueId(), false));
        String nom = Txt.plain(Obj.nombre(obj));
        Objetivo o = menu.objetivo(s.id, s.uid);
        if (o == null) { salir(p, false); return; }
        if (o.conjunto) {
            menu.editar(s.id, s.uid, x -> x.entradas.put(obj, 1));
            Style.ok(p, "Agregado: &f" + nom + " &a(en conjunto, la cantidad la pone el reloj).");
            return;
        }
        Style.info(p, "Escribí en el chat la cantidad a matar de: &e" + nom + " &7(&ccancel &7para cancelar)");
        ChatInput.ask(p, txt -> {
            Integer leido = numero(txt);
            if (leido == null) { Style.err(p, "Tiene que ser un número (1 o más)."); return; }
            final int n = leido;
            ChatInput.cancel(p);
            menu.editar(s.id, s.uid, x -> x.entradas.put(obj, n));
            Style.ok(p, "Agregado: &f" + n + "x " + nom + "&a. Seguís en el modo Mobs.");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void usa(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!sesiones.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String t = tipo(p.getInventory().getItemInMainHand());
        if ("salir".equals(t)) {
            Style.ok(p, "Mobs guardados.");
            salir(p, true);
        } else if ("especifico".equals(t)) {
            boolean v = !especifico.getOrDefault(p.getUniqueId(), false);
            especifico.put(p.getUniqueId(), v);
            p.getInventory().setItem(7, botonEspecifico(v));
            Style.click(p);
        }
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
        Style.err(e.getPlayer(), "Estás en el modo Mobs: salí con la flecha (click derecho).");
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        if (sesiones.containsKey(e.getPlayer().getUniqueId())) salir(e.getPlayer(), false);
    }

    /** Si el server se cayo en medio del modo, al volver recupera el inventario. */
    @EventHandler
    public void entra(PlayerJoinEvent e) {
        if (ms.store().y().isList("modo_mobs." + e.getPlayer().getUniqueId())) restaurar(e.getPlayer());
    }

    private static Integer numero(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n >= 1 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
