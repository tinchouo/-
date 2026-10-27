package ar.tod4.aether;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import ar.tod4.aether.util.Style;
import ar.tod4.aether.util.Txt;

/**
 * Cambio del dia 7 "Aire del Aether" (d7.aire en /tod4:cambios). Opciones: plugins/TOD4Aether/aire.yml
 *  - En el Aether el aire se acaba: la barra de burbujas de Minecraft baja en ~5 minutos; sin aire, nauseas, lentitud y dano.
 *    Al salir del Aether se recupera.
 *  - Casco de Oxigeno (casco de hierro por dentro, se puede encantar, nunca se rompe): mientras tiene oxigeno respiras
 *    (tambien bajo el agua) y no salen las burbujas. La barra de durabilidad es el oxigeno (30 min). Solo se gasta en el Aether.
 *    Se recarga con un bloque de amatista (en la mesa o en el 2x2, sin orden) y conserva los encantamientos.
 *  - El bloque de amatista se craftea con 9 fragmentos (no con 4).
 */
final class Aire implements Listener {

    private static final NamespacedKey CASCO = new NamespacedKey("tod4", "casco_oxigeno");
    private static final NamespacedKey OXI = new NamespacedKey("tod4", "oxigeno");

    private final TOD4Aether pl;
    private final Aether ae;
    private final NamespacedKey rCasco, rRecarga, rBloque;
    private YamlConfiguration y;
    /** Aire que le queda a cada jugador en el Aether (ticks). */
    private final Map<UUID, Integer> aire = new HashMap<>();
    private int tick;

    Aire(TOD4Aether pl, Aether ae) {
        this.pl = pl;
        this.ae = ae;
        rCasco = new NamespacedKey(pl, "casco_oxigeno");
        rRecarga = new NamespacedKey(pl, "casco_recarga");
        rBloque = new NamespacedKey(pl, "bloque_amatista");
        recargar();
        recetas();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaTick, 20L, 1L);
    }

    void recargar() {
        File f = new File(pl.getDataFolder(), "aire.yml");
        if (!f.exists()) pl.saveResource("aire.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
    }

    boolean activo() { return y.getBoolean("siempre", false) || pl.cambio(y.getString("cambio", "d7.aire")); }

    private int aireMax() { return Math.max(20, (int) (y.getDouble("aire_seg", 300) * 20)); }

    private int oxiMax() { return Math.max(1, (int) (y.getDouble("casco.oxigeno_min", 30) * 60)); }

    // ================================================================= cada tick

    private void cadaTick() {
        tick++;
        boolean on = activo();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!on || !ae.enAether(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) {
                // fuera del Aether el aire vuelve de una
                if (aire.remove(u) != null && p.getRemainingAir() < p.getMaximumAir() && !p.isInWater()) p.setRemainingAir(p.getMaximumAir());
                continue;
            }
            int max = aireMax();
            ItemStack casco = p.getInventory().getHelmet();
            boolean conCasco = esCasco(casco) && oxigeno(casco) > 0;
            if (conCasco) {
                aire.put(u, max);
                if (p.getRemainingAir() < p.getMaximumAir()) p.setRemainingAir(p.getMaximumAir());
                if (tick % 20 == 0) gastar(p, casco);
                continue;
            }
            // bajo el agua manda Minecraft (te ahogas como siempre)
            if (p.isUnderWater()) continue;
            int a = Math.max(0, aire.getOrDefault(u, max) - 1);
            aire.put(u, a);
            p.setRemainingAir((int) Math.round(p.getMaximumAir() * (a / (double) max)));
            if (a <= 0) asfixia(p);
        }
    }

    private void asfixia(Player p) {
        if (tick % 20 == 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 200, 0, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, Math.max(0, y.getInt("lentitud_nivel", 2) - 1), false, false, true));
        }
        int cada = Math.max(1, (int) (y.getDouble("dano_cada_seg", 1) * 20));
        if (tick % cada == 0) p.damage(y.getDouble("dano", 2), DamageSource.builder(DamageType.DROWN).build());
    }

    @EventHandler
    public void muere(PlayerDeathEvent e) { aire.remove(e.getEntity().getUniqueId()); }

    // ================================================================= el casco

    static boolean esCasco(ItemStack it) {
        return it != null && it.getType() == Material.IRON_HELMET && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(CASCO, PersistentDataType.BYTE);
    }

    private int oxigeno(ItemStack it) {
        Integer o = it.getItemMeta().getPersistentDataContainer().get(OXI, PersistentDataType.INTEGER);
        return o == null ? 0 : Math.max(0, Math.min(oxiMax(), o));
    }

    /** Un segundo de oxigeno (el item del slot se cambia ahi mismo: no suena como si te lo pusieras de nuevo). */
    private void gastar(Player p, ItemStack casco) {
        int o = oxigeno(casco) - 1;
        ponerOxigeno(casco, o);
        if (o == 60) p.sendActionBar(Txt.c("&cTe queda 1 minuto de oxígeno."));
        if (o <= 0) {
            p.sendActionBar(Txt.c("&cTu casco se quedó sin oxígeno."));
            p.playSound(p, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f);
        }
    }

    /** Pone el oxigeno: la barra de durabilidad y el "Oxígeno: X%" del texto. */
    private void ponerOxigeno(ItemStack it, int o) {
        int max = oxiMax();
        o = Math.max(0, Math.min(max, o));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(OXI, PersistentDataType.INTEGER, o);
        if (m instanceof Damageable d) {
            d.setMaxDamage(max);
            d.setDamage(max - o);
        }
        int pct = (int) Math.ceil(o * 100.0 / max);
        m.lore(lore(pct));
        it.setItemMeta(m);
    }

    private List<net.kyori.adventure.text.Component> lore(int pct) {
        String c = pct > 50 ? "&b" : pct > 20 ? "&e" : "&c";
        List<String> l = new ArrayList<>();
        l.add("&7Oxígeno: " + c + pct + "%");
        for (String x : y.getStringList("casco.lore")) l.add(x);
        return Txt.lines(l);
    }

    ItemStack nuevoCasco() {
        ItemStack it = new ItemStack(Material.IRON_HELMET);
        ItemMeta m = it.getItemMeta();
        m.itemName(Txt.c(y.getString("casco.nombre", "&bCasco de Oxígeno")));
        String modelo = y.getString("casco.modelo", "tod4:casco_oxigeno");
        if (modelo != null && !modelo.isEmpty()) m.setItemModel(NamespacedKey.fromString(modelo));
        // sin la capa de armadura de hierro: en la cabeza se ve el modelo del casco (el domo de vidrio)
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setModel(null);
        eq.setEquipSound(Sound.ITEM_ARMOR_EQUIP_IRON);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(CASCO, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        ponerOxigeno(it, oxiMax());
        return it;
    }

    /** Nunca se rompe ni se gasta peleando (lo unico que baja es el oxigeno). */
    @EventHandler(ignoreCancelled = true)
    public void dano(PlayerItemDamageEvent e) { if (esCasco(e.getItem())) e.setCancelled(true); }

    /** Reparacion (experiencia) no carga oxigeno. */
    @EventHandler(ignoreCancelled = true)
    public void mending(PlayerItemMendEvent e) { if (esCasco(e.getItem())) e.setCancelled(true); }

    /** Yunque y afiladora: se puede encantar, pero el oxigeno queda como estaba (no se "repara"). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void yunque(PrepareAnvilEvent e) {
        ItemStack a = e.getInventory().getFirstItem(), r = e.getResult();
        if (!esCasco(a)) {
            if (esCasco(e.getInventory().getSecondItem())) e.setResult(null);
            return;
        }
        if (r == null || !esCasco(r)) return;
        ItemStack b = e.getInventory().getSecondItem();
        if (b != null && b.getType() != Material.ENCHANTED_BOOK && !esCasco(b)) { e.setResult(null); return; }   // hierro: no se repara
        ponerOxigeno(r, oxigeno(a));
        e.setResult(r);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void afiladora(PrepareGrindstoneEvent e) {
        ItemStack a = e.getInventory().getUpperItem(), b = e.getInventory().getLowerItem();
        if (!esCasco(a) && !esCasco(b)) return;
        if (a != null && b != null) { e.setResult(null); return; }          // juntar dos: no
        ItemStack r = e.getResult();
        if (r != null && esCasco(r)) { ponerOxigeno(r, oxigeno(esCasco(a) ? a : b)); e.setResult(r); }
    }

    // ================================================================= recetas

    private void recetas() {
        Bukkit.removeRecipe(rCasco);
        Bukkit.removeRecipe(rRecarga);
        Bukkit.removeRecipe(rBloque);
        ShapedRecipe c = new ShapedRecipe(rCasco, nuevoCasco());
        c.shape("VVV", "VYV", "FFF");
        c.setIngredient('V', Material.GLASS);
        c.setIngredient('Y', Material.IRON_HELMET);
        c.setIngredient('F', Material.AMETHYST_SHARD);
        Bukkit.addRecipe(c);
        ShapelessRecipe r = new ShapelessRecipe(rRecarga, nuevoCasco());
        r.addIngredient(new RecipeChoice.MaterialChoice(Material.IRON_HELMET));
        r.addIngredient(Material.AMETHYST_BLOCK);
        Bukkit.addRecipe(r);
        ShapedRecipe b = new ShapedRecipe(rBloque, new ItemStack(Material.AMETHYST_BLOCK));
        b.shape("FFF", "FFF", "FFF");
        b.setIngredient('F', Material.AMETHYST_SHARD);
        Bukkit.addRecipe(b);
    }

    private static String clave(Recipe r) { return r instanceof org.bukkit.Keyed k ? k.getKey().toString() : ""; }

    @EventHandler(priority = EventPriority.HIGH)
    public void craftea(PrepareItemCraftEvent e) {
        String k = clave(e.getRecipe());
        if (k.isEmpty()) return;
        boolean on = activo();
        if (k.equals(rCasco.toString())) {
            if (!on) e.getInventory().setResult(null);
            else for (ItemStack it : e.getInventory().getMatrix()) if (esCasco(it)) e.getInventory().setResult(null);   // un casco de oxigeno no es "un casco de hierro"
        } else if (k.equals(rRecarga.toString())) {
            ItemStack casco = null;
            for (ItemStack it : e.getInventory().getMatrix()) if (esCasco(it)) casco = it;
            if (!on || casco == null) { e.getInventory().setResult(null); return; }
            ItemStack lleno = casco.clone();
            lleno.setAmount(1);
            ponerOxigeno(lleno, oxiMax());
            e.getInventory().setResult(lleno);
        } else if (k.equals(rBloque.toString())) {
            if (!on) e.getInventory().setResult(null);
        } else if (k.equals("minecraft:amethyst_block")) {
            if (on) e.getInventory().setResult(null);        // con el cambio: 9 fragmentos, no 4
        }
    }

    // ================================================================= comandos

    /** /tod4:aether_casco [jugador] | /tod4:aether_aire [estado|vaciar|llenar] */
    void comando(CommandSender s, String c, String[] a) {
        if (c.equals("aether_casco")) {
            Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player pp ? pp : null;
            if (t == null) { Style.err(s, "Uso: &e/tod4:aether_casco [jugador]"); return; }
            for (ItemStack sob : t.getInventory().addItem(nuevoCasco()).values()) t.getWorld().dropItem(t.getLocation(), sob);
            Style.ok(s, "Casco de Oxígeno entregado a &f" + t.getName() + "&a.");
            return;
        }
        String m = a.length > 0 ? a[0].toLowerCase() : "estado";
        Player p = s instanceof Player pp ? pp : null;
        switch (m) {
            case "vaciar", "llenar" -> {
                if (p == null) return;
                ItemStack h = p.getInventory().getHelmet();
                if (esCasco(h)) {
                    ponerOxigeno(h, m.equals("llenar") ? oxiMax() : 0);
                    Style.ok(s, "Casco " + (m.equals("llenar") ? "lleno" : "vacío") + ".");
                } else {
                    aire.put(p.getUniqueId(), m.equals("llenar") ? aireMax() : 1);
                    Style.ok(s, "Tu aire: " + (m.equals("llenar") ? "lleno" : "vacío") + ".");
                }
            }
            default -> {
                Style.info(s, "Aire del Aether: " + (activo() ? "&aprendido" : "&capagado &7(se prende con el cambio &fd7.aire &7en /tod4:cambios)"));
                Style.info(s, "&e/tod4:aether_casco [jugador] &7| &e/tod4:aether_aire vaciar|llenar &7(prueba, con o sin casco puesto)");
            }
        }
    }
}
