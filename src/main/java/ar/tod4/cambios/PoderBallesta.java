package ar.tod4.cambios;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Pillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

import ar.tod4.TOD4;
import net.kyori.adventure.text.Component;

/**
 * Dia 2: el encantamiento Poder tambien vale en la ballesta.
 *  - Mesa mejorada: lo decide Mesa (ver Mesa.setPoderBallesta). Mesa vanilla (o un boton que la mesa mejorada no decide): aca, con una chance.
 *  - Yunque: ballesta + libro con Poder.
 *  - Danio: la flecha de una ballesta con Poder pega como la del arco (+0,5 por nivel, mas 0,5), sea quien sea que dispare.
 *  - Mobs: algunos pillagers y piglins nacen con Poder en la ballesta.
 */
final class PoderBallesta implements Listener {
    private final TOD4 pl;
    private final Cambios cb;

    PoderBallesta(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private boolean on() { return cb.on("d2.poder"); }

    private static Enchantment poder() { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("power")); }

    // ------------------------------------------------------------------ mesa vanilla

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mesa(EnchantItemEvent e) {
        if (e.getItem().getType() != Material.CROSSBOW) return;
        boolean laMesaMejoradaLoDecidio = pl.mesa().tomarManejado(e.getEnchanter());
        Enchantment po = poder();
        if (!on() || laMesaMejoradaLoDecidio || po == null) return;
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        if (add.containsKey(po)) return;
        if (ThreadLocalRandom.current().nextDouble() >= pl.getConfig().getDouble("cambios.poder_ballesta_mesa", 0.4)) return;
        // como en el juego: Poder I desde nivel 1, II desde 11, III desde 21, IV desde 31, V desde 41
        int lvl = Math.max(1, Math.min(po.getMaxLevel(), (e.getExpLevelCost() - 1) / 10 + 1));
        add.put(po, lvl);
    }

    // ------------------------------------------------------------------ yunque

    @EventHandler(priority = EventPriority.HIGH)
    public void yunque(PrepareAnvilEvent e) {
        if (!on()) return;
        AnvilInventory inv = e.getInventory();
        ItemStack izq = inv.getItem(0), der = inv.getItem(1);
        if (izq == null || izq.getType() != Material.CROSSBOW || der == null || der.getType() != Material.ENCHANTED_BOOK) return;
        if (!(der.getItemMeta() instanceof EnchantmentStorageMeta libro)) return;
        Enchantment po = poder();
        if (po == null) return;
        int delLibro = libro.getStoredEnchantLevel(po);
        if (delLibro <= 0) return;

        ItemStack res = e.getResult();
        boolean habia = res != null && !res.getType().isAir();
        ItemStack out = habia ? res.clone() : izq.clone();
        out.setAmount(1);
        if (!habia) {
            // el juego no arma nada (el libro solo trae Poder): se agregan tambien los demas encantamientos compatibles del libro
            for (Map.Entry<Enchantment, Integer> en : libro.getStoredEnchants().entrySet()) {
                Enchantment k = en.getKey();
                if (k.equals(po) || !k.canEnchantItem(izq)) continue;
                boolean choca = false;
                for (Enchantment ya : out.getEnchantments().keySet()) if (!ya.equals(k) && (ya.conflictsWith(k) || k.conflictsWith(ya))) choca = true;
                if (choca) continue;
                int actual = out.getEnchantmentLevel(k), nuevo = en.getValue();
                int lvl = actual == nuevo ? Math.min(k.getMaxLevel(), nuevo + 1) : Math.max(actual, nuevo);
                out.addUnsafeEnchantment(k, lvl);
            }
        }
        int actual = out.getEnchantmentLevel(po);
        int lvl = actual == delLibro ? Math.min(po.getMaxLevel(), delLibro + 1) : Math.max(actual, delLibro);
        out.addUnsafeEnchantment(po, lvl);

        int penalidad = 0;
        if (izq.getItemMeta() instanceof Repairable r) penalidad = r.getRepairCost();
        if (!habia) {
            ItemMeta m = out.getItemMeta();
            String nombre = e.getView().getRenameText();
            if (nombre != null && !nombre.isBlank()) m.displayName(Component.text(nombre));
            if (m instanceof Repairable r) r.setRepairCost(penalidad * 2 + 1);
            out.setItemMeta(m);
        }
        e.setResult(out);
        int costo = habia ? safeCost(e) + Math.max(1, lvl) : Math.max(1, lvl) + penalidad;
        Bukkit.getScheduler().runTask(pl, () -> fijar(e, costo));
        fijar(e, costo);
    }

    private static int safeCost(PrepareAnvilEvent e) {
        try { return e.getView().getRepairCost(); } catch (Throwable t) { return 0; }
    }

    private static void fijar(PrepareAnvilEvent e, int costo) {
        try { e.getView().setRepairCost(costo); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ danio

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void dispara(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow flecha) || !on()) return;
        if (!(flecha.getShooter() instanceof LivingEntity tirador)) return;
        Enchantment po = poder();
        EntityEquipment eq = tirador.getEquipment();
        if (po == null || eq == null) return;
        ItemStack arma = eq.getItemInMainHand().getType() == Material.CROSSBOW ? eq.getItemInMainHand()
                : eq.getItemInOffHand().getType() == Material.CROSSBOW ? eq.getItemInOffHand() : null;
        if (arma == null) return;
        int lvl = arma.getEnchantmentLevel(po);
        if (lvl <= 0) return;
        flecha.setDamage(flecha.getDamage() + 0.5 * lvl + 0.5);
    }

    // ------------------------------------------------------------------ mobs

    @EventHandler(ignoreCancelled = true)
    public void nace(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Pillager || e.getEntity() instanceof Piglin) || !on()) return;
        Enchantment po = poder();
        EntityEquipment eq = e.getEntity().getEquipment();
        if (po == null || eq == null) return;
        ItemStack mano = eq.getItemInMainHand();
        if (mano.getType() != Material.CROSSBOW || mano.containsEnchantment(po)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= pl.getConfig().getDouble("cambios.poder_ballesta_mobs", 0.1)) return;
        mano = mano.clone();
        mano.addUnsafeEnchantment(po, 1 + r.nextInt(4));
        eq.setItemInMainHand(mano);
    }
}
