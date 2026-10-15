package ar.tod4.fiesta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;

/**
 * Casilleros: click derecho y te escondes. Adentro estas 100% a salvo (aunque te hayan visto entrar):
 * los partygoers no te pueden apuntar. Ves por las rendijas (overlay). Para salir: shift.
 * Cada casillero es un display con el modelo de Oraxen (tod4:casillero) + una "interaccion" para el click + barreras.
 */
final class Casilleros implements Listener {

    private final TOD4 pl;
    private final NamespacedKey CASILLERO;
    /** casillero (clave de bloque) -> jugador adentro */
    private final Map<String, UUID> ocupado = new HashMap<>();
    /** jugador -> casillero donde esta */
    private final Map<UUID, String> adentro = new HashMap<>();
    private final Map<UUID, ItemStack> casco = new HashMap<>();

    Casilleros(TOD4 pl) {
        this.pl = pl;
        this.CASILLERO = new NamespacedKey(pl, "casillero");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    static String clave(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    boolean escondido(Player p) { return adentro.containsKey(p.getUniqueId()); }

    private static float yaw(BlockFace f) {
        switch (f) {
            case WEST: return 90;
            case NORTH: return 180;
            case EAST: return -90;
            default: return 0;
        }
    }

    /** Pone un casillero en ese bloque (el de abajo), mirando hacia "frente". */
    void poner(Block piso, BlockFace frente) {
        Location c = piso.getLocation().add(0.5, 0, 0.5);
        for (Entity e : c.getWorld().getNearbyEntities(c, 0.6, 1.5, 0.6))
            if (e.getPersistentDataContainer().has(CASILLERO, PersistentDataType.STRING)) e.remove();
        String k = clave(piso);
        ItemStack modelo = new ItemStack(Material.PAPER);
        ItemMeta m = modelo.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(pl.getConfig().getString("fiesta.modelo_casillero", "tod4:casillero")));
        modelo.setItemMeta(m);
        Location dl = c.clone().add(0, 0.5, 0);
        dl.setYaw(yaw(frente));
        piso.getWorld().spawn(dl, ItemDisplay.class, d -> {
            d.setItemStack(modelo);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(CASILLERO, PersistentDataType.STRING, k);
        });
        piso.getWorld().spawn(c, Interaction.class, i -> {
            i.setInteractionWidth(1.02f);
            i.setInteractionHeight(2.02f);
            i.setResponsive(true);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(CASILLERO, PersistentDataType.STRING, k + "|" + frente.name());
        });
        piso.setType(Material.BARRIER, false);
        piso.getRelative(BlockFace.UP).setType(Material.BARRIER, false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction i)) return;
        String dato = i.getPersistentDataContainer().get(CASILLERO, PersistentDataType.STRING);
        if (dato == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (escondido(p)) return;
        String[] pt = dato.split("\\|");
        String k = pt[0];
        BlockFace frente;
        try { frente = BlockFace.valueOf(pt[1]); } catch (Exception ex) { frente = BlockFace.SOUTH; }
        if (ocupado.containsKey(k)) {
            p.sendActionBar(Txt.c("&cEste casillero está ocupado."));
            return;
        }
        entrar(p, i.getLocation().getBlock(), k, frente);
    }

    private void entrar(Player p, Block piso, String k, BlockFace frente) {
        ocupado.put(k, p.getUniqueId());
        adentro.put(p.getUniqueId(), k);
        piso.setType(Material.AIR, false);
        piso.getRelative(BlockFace.UP).setType(Material.AIR, false);
        Location l = piso.getLocation().add(0.5, 0, 0.5);
        l.setYaw(yaw(frente));
        l.setPitch(0);
        p.teleport(l);
        // la vista por las rendijas: un "casco" con overlay de pantalla (como la calabaza)
        casco.put(p.getUniqueId(), p.getInventory().getHelmet() == null ? null : p.getInventory().getHelmet().clone());
        ItemStack ov = new ItemStack(Material.PAPER);
        ItemMeta m = ov.getItemMeta();
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setCameraOverlay(NamespacedKey.fromString(pl.getConfig().getString("fiesta.overlay_casillero", "tod4:fiesta/casillero_overlay")));
        m.setEquippable(eq);
        m.setItemModel(NamespacedKey.fromString("tod4:casillero_vacio"));
        ov.setItemMeta(m);
        p.getInventory().setHelmet(ov);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        p.playSound(l, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 0.8f, 1.3f);
        p.sendActionBar(Txt.c("&7Estás escondido. &fShift &7para salir."));
    }

    void salir(Player p) {
        String k = adentro.remove(p.getUniqueId());
        if (k == null) return;
        ocupado.remove(k);
        String[] pt = k.split(":");
        org.bukkit.World w = Bukkit.getWorld(pt[0]);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        ItemStack c = casco.remove(p.getUniqueId());
        p.getInventory().setHelmet(c);
        if (w == null) return;
        Block piso = w.getBlockAt(Integer.parseInt(pt[1]), Integer.parseInt(pt[2]), Integer.parseInt(pt[3]));
        // sale por adelante
        BlockFace frente = BlockFace.SOUTH;
        for (Entity e : w.getNearbyEntities(piso.getLocation().add(0.5, 0, 0.5), 0.6, 1.5, 0.6)) {
            String d = e.getPersistentDataContainer().get(CASILLERO, PersistentDataType.STRING);
            if (d != null && d.contains("|")) { try { frente = BlockFace.valueOf(d.split("\\|")[1]); } catch (Exception ignored) { } }
        }
        Location fuera = piso.getRelative(frente).getLocation().add(0.5, 0, 0.5);
        fuera.setYaw(p.getLocation().getYaw());
        fuera.setPitch(p.getLocation().getPitch());
        if (p.isOnline()) p.teleport(fuera);
        piso.setType(Material.BARRIER, false);
        piso.getRelative(BlockFace.UP).setType(Material.BARRIER, false);
        p.playSound(fuera, Sound.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 0.8f, 1.3f);
    }

    void salirTodos() {
        for (UUID u : new java.util.ArrayList<>(adentro.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) salir(p);
        }
    }

    @EventHandler
    public void shift(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && escondido(e.getPlayer())) salir(e.getPlayer());
    }

    /** Adentro no te podes mover (si mirar). */
    @EventHandler(ignoreCancelled = true)
    public void mueve(PlayerMoveEvent e) {
        if (!escondido(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location n = f.clone();
            n.setYaw(t.getYaw());
            n.setPitch(t.getPitch());
            e.setTo(n);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void dano(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && escondido(p)) e.setCancelled(true);
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) { salir(e.getPlayer()); }
}
